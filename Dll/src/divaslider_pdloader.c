#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <process.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include <detours.h>

#include "divaslider_log.h"
#include "divaslider_pdloader.h"
#include "divaslider_shm.h"

#define TLAC_ENGINE_UPDATE_INPUT_ADDRESS 0x000000014018CBB0ULL

enum {
    TLAC_SLIDER_STATE_ADDRESS = 0x4CC5DE40,
    TLAC_BUTTON_TEST = 1 << 0,
    TLAC_BUTTON_SERVICE = 1 << 1,
    TLAC_BUTTON_START = 1 << 2,
    TLAC_BUTTON_TRIANGLE = 1 << 7,
    TLAC_BUTTON_SQUARE = 1 << 8,
    TLAC_BUTTON_CROSS = 1 << 9,
    TLAC_BUTTON_CIRCLE = 1 << 10,
    TLAC_MANAGED_LOW_MASK = TLAC_BUTTON_START |
            TLAC_BUTTON_TRIANGLE |
            TLAC_BUTTON_SQUARE |
            TLAC_BUTTON_CROSS |
            TLAC_BUTTON_CIRCLE,
    TLAC_BUTTON_META = 0x6e,
    TLAC_SLIDER_OK = 3,
    TLAC_SLIDER_SERIAL_PTR_OFFSET = 0x68,
    TLAC_SLIDER_STATE_OFFSET = 0x70,
    TLAC_SLIDER_PRESSURE_OFFSET = 0x94,
    TLAC_SLIDER_SENSOR_TOUCHED_OFFSET = 0xD40,
    TLAC_SLIDER_SENSOR_TOUCHED_STRIDE = 48,
    TLAC_SLIDER_SENSOR_TOUCHED_FLAG_OFFSET = 2,
    TLAC_SERIAL_RESPONSE_COUNT_OFFSET = 0x1960,
    TLAC_SERIAL_SCAN_MODE_OFFSET = 0x1964,
    TLAC_SERIAL_SCAN_COUNT_OFFSET = 0x1968,
    TLAC_SERIAL_SENSOR_HISTORY_OFFSET = 0x196C,
    DIVASLIDER_PDLOADER_THREAD_SLEEP_MS = 1,
};

typedef void (__cdecl *divaslider_engine_update_input_t)(void *input_state);

struct divaslider_tlac_button_state {
    uint32_t state[4];
};

struct divaslider_tlac_input_state {
    struct divaslider_tlac_button_state tapped;
    struct divaslider_tlac_button_state released;
    struct divaslider_tlac_button_state down;
    uint32_t padding_30[4];
    struct divaslider_tlac_button_state double_tapped;
    uint32_t padding_50[4];
    struct divaslider_tlac_button_state interval_tapped;
};

static unsigned int __stdcall divaslider_pdloader_thread_proc(void *ctx);
static void __cdecl divaslider_pdloader_hooked_engine_update_input(void *input_state);
static bool divaslider_pdloader_install_hook(void);
static void divaslider_pdloader_uninstall_hook(void);
static bool divaslider_pdloader_start_sampler(void);
static void divaslider_pdloader_ensure_lock(void);
static BOOL CALLBACK divaslider_pdloader_init_lock(
        PINIT_ONCE init_once,
        PVOID parameter,
        PVOID *context);
static void divaslider_pdloader_cache_snapshot(
        const struct divaslider_snapshot *snapshot,
        ULONGLONG now);
static bool divaslider_pdloader_apply_cached(
        struct divaslider_tlac_input_state *input);
static uint32_t divaslider_pdloader_map_buttons(uint16_t buttons);
static void divaslider_pdloader_update_button_edges(uint32_t buttons);
static void divaslider_pdloader_write_buttons(
        struct divaslider_tlac_input_state *input,
        uint32_t buttons,
        uint32_t tapped,
        uint32_t released);
static void divaslider_pdloader_reprime_buttons(uint32_t buttons);
static void divaslider_pdloader_fill_button_state(
        struct divaslider_tlac_button_state *state,
        uint32_t buttons,
        bool meta);
static void divaslider_pdloader_set_button_bit(
        struct divaslider_tlac_button_state *state,
        uint32_t bit);
static void divaslider_pdloader_write_slider(const uint8_t *slider);
static bool divaslider_pdloader_is_executable(const void *ptr);
static bool divaslider_pdloader_is_writeable(const void *ptr, size_t len);

static HANDLE divaslider_pdloader_thread;
static volatile LONG divaslider_pdloader_stop_flag;
static volatile LONG divaslider_pdloader_reprime_flag;
static volatile LONG divaslider_pdloader_hook_active;
static volatile LONG divaslider_pdloader_hook_installing;
static INIT_ONCE divaslider_pdloader_lock_once = INIT_ONCE_STATIC_INIT;
static CRITICAL_SECTION divaslider_pdloader_lock;
static divaslider_engine_update_input_t divaslider_pdloader_original_engine_update_input =
        (divaslider_engine_update_input_t) TLAC_ENGINE_UPDATE_INPUT_ADDRESS;
static struct divaslider_snapshot divaslider_pdloader_cached_snapshot;
static bool divaslider_pdloader_cached_snapshot_valid;
static uint32_t divaslider_pdloader_cached_buttons;
static uint32_t divaslider_pdloader_last_buttons;
static uint32_t divaslider_pdloader_pending_tapped;
static uint32_t divaslider_pdloader_pending_released;
static uint32_t divaslider_pdloader_owned_momentary_tapped;
static struct divaslider_tlac_input_state *divaslider_pdloader_last_input;

void divaslider_pdloader_start(void)
{
    divaslider_pdloader_install_hook();
    divaslider_pdloader_start_sampler();
}

static bool divaslider_pdloader_start_sampler(void)
{
    HANDLE thread;

    if (InterlockedCompareExchangePointer(
            (PVOID volatile *) &divaslider_pdloader_thread,
            NULL,
            NULL) != NULL) {
        return true;
    }

    InterlockedExchange(&divaslider_pdloader_stop_flag, 0);
    thread = (HANDLE) _beginthreadex(
            NULL,
            0,
            divaslider_pdloader_thread_proc,
            NULL,
            0,
            NULL);

    if (thread == NULL) {
        divaslider_logf("pdloader_bridge: _beginthreadex failed");
        return false;
    }
    SetThreadPriority(thread, THREAD_PRIORITY_HIGHEST);

    if (InterlockedCompareExchangePointer(
            (PVOID volatile *) &divaslider_pdloader_thread,
            thread,
            NULL) != NULL) {
        InterlockedExchange(&divaslider_pdloader_stop_flag, 1);
        WaitForSingleObject(thread, INFINITE);
        CloseHandle(thread);
        return true;
    }

    return true;
}

void divaslider_pdloader_stop(void)
{
    HANDLE thread;

    thread = (HANDLE) InterlockedExchangePointer(
            (PVOID volatile *) &divaslider_pdloader_thread,
            NULL);

    if (thread == NULL) {
        divaslider_pdloader_uninstall_hook();
        return;
    }

    InterlockedExchange(&divaslider_pdloader_stop_flag, 1);
    WaitForSingleObject(thread, INFINITE);
    CloseHandle(thread);
    divaslider_logf("pdloader_bridge: stopped");

    divaslider_pdloader_uninstall_hook();
}

void divaslider_pdloader_reprime(void)
{
    InterlockedExchange(&divaslider_pdloader_reprime_flag, 1);
}

static unsigned int __stdcall divaslider_pdloader_thread_proc(void *ctx)
{
    struct divaslider_snapshot snapshot;
    bool active_logged;

    (void) ctx;
    active_logged = false;
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);

    while (InterlockedCompareExchange(&divaslider_pdloader_stop_flag, 0, 0) == 0) {
        if (!divaslider_shm_read(&snapshot)) {
            Sleep(DIVASLIDER_PDLOADER_THREAD_SLEEP_MS);
            continue;
        }

        divaslider_pdloader_cache_snapshot(&snapshot, GetTickCount64());
        if (!active_logged) {
            divaslider_logf("pdloader_bridge: sampler active");
            active_logged = true;
        }

        Sleep(DIVASLIDER_PDLOADER_THREAD_SLEEP_MS);
    }

    return 0;
}

static void __cdecl divaslider_pdloader_hooked_engine_update_input(void *input_state)
{
    static bool active_logged;
    static bool bad_input_logged;

    divaslider_pdloader_original_engine_update_input(input_state);

    if (!divaslider_pdloader_is_writeable(
            input_state,
            sizeof(struct divaslider_tlac_input_state))) {
        if (!bad_input_logged) {
            divaslider_logf("pdloader_bridge: hook got invalid TLAC input state");
            bad_input_logged = true;
        }
        return;
    }

    if (divaslider_pdloader_apply_cached(
            (struct divaslider_tlac_input_state *) input_state) &&
            !active_logged) {
        divaslider_logf("pdloader_bridge: engine input hook active");
        active_logged = true;
    }
}

static bool divaslider_pdloader_install_hook(void)
{
    LONG error;

    if (InterlockedCompareExchange(&divaslider_pdloader_hook_active, 0, 0) != 0) {
        return true;
    }

    if (InterlockedCompareExchange(&divaslider_pdloader_hook_installing, 1, 0) != 0) {
        return InterlockedCompareExchange(&divaslider_pdloader_hook_active, 0, 0) != 0;
    }

    if (!divaslider_pdloader_is_executable(
            (const void *) TLAC_ENGINE_UPDATE_INPUT_ADDRESS)) {
        divaslider_logf(
                "pdloader_bridge: engine input hook target is not executable: 0x%llx",
                (unsigned long long) TLAC_ENGINE_UPDATE_INPUT_ADDRESS);
        InterlockedExchange(&divaslider_pdloader_hook_installing, 0);
        return false;
    }

    error = DetourTransactionBegin();
    if (error == NO_ERROR) {
        error = DetourUpdateThread(GetCurrentThread());
    }
    if (error == NO_ERROR) {
        error = DetourAttach(
                (PVOID *) &divaslider_pdloader_original_engine_update_input,
                divaslider_pdloader_hooked_engine_update_input);
    }
    if (error == NO_ERROR) {
        error = DetourTransactionCommit();
    } else {
        DetourTransactionAbort();
    }

    if (error != NO_ERROR) {
        divaslider_logf("pdloader_bridge: engine input hook failed: %ld", error);
        InterlockedExchange(&divaslider_pdloader_hook_installing, 0);
        return false;
    }

    InterlockedExchange(&divaslider_pdloader_hook_active, 1);
    InterlockedExchange(&divaslider_pdloader_hook_installing, 0);
    divaslider_logf("pdloader_bridge: engine input hook installed");
    return true;
}

static void divaslider_pdloader_uninstall_hook(void)
{
    LONG error;

    if (InterlockedExchange(&divaslider_pdloader_hook_active, 0) == 0) {
        return;
    }

    error = DetourTransactionBegin();
    if (error == NO_ERROR) {
        error = DetourUpdateThread(GetCurrentThread());
    }
    if (error == NO_ERROR) {
        error = DetourDetach(
                (PVOID *) &divaslider_pdloader_original_engine_update_input,
                divaslider_pdloader_hooked_engine_update_input);
    }
    if (error == NO_ERROR) {
        error = DetourTransactionCommit();
    } else {
        DetourTransactionAbort();
    }

    if (error == NO_ERROR) {
        divaslider_logf("pdloader_bridge: engine input hook removed");
    } else {
        divaslider_logf("pdloader_bridge: engine input hook remove failed: %ld", error);
    }
}

static void divaslider_pdloader_ensure_lock(void)
{
    InitOnceExecuteOnce(
            &divaslider_pdloader_lock_once,
            divaslider_pdloader_init_lock,
            NULL,
            NULL);
}

static BOOL CALLBACK divaslider_pdloader_init_lock(
        PINIT_ONCE init_once,
        PVOID parameter,
        PVOID *context)
{
    (void) init_once;
    (void) parameter;
    (void) context;

    InitializeCriticalSection(&divaslider_pdloader_lock);
    return TRUE;
}

static void divaslider_pdloader_cache_snapshot(
        const struct divaslider_snapshot *snapshot,
        ULONGLONG now)
{
    uint32_t buttons;

    if (snapshot == NULL) {
        return;
    }

    if (snapshot->connected) {
        buttons = divaslider_pdloader_map_buttons(snapshot->buttons);
    } else {
        buttons = 0;
    }

    divaslider_pdloader_ensure_lock();
    EnterCriticalSection(&divaslider_pdloader_lock);
    (void) now;
    if (!divaslider_pdloader_cached_snapshot_valid ||
            InterlockedExchange(&divaslider_pdloader_reprime_flag, 0) != 0) {
        divaslider_pdloader_reprime_buttons(buttons);
    } else {
        divaslider_pdloader_update_button_edges(buttons);
    }

    divaslider_pdloader_cached_snapshot = *snapshot;
    divaslider_pdloader_cached_buttons = buttons;
    divaslider_pdloader_cached_snapshot_valid = true;
    LeaveCriticalSection(&divaslider_pdloader_lock);
}

static bool divaslider_pdloader_apply_cached(
        struct divaslider_tlac_input_state *input)
{
    struct divaslider_snapshot snapshot;
    uint32_t buttons;
    uint32_t tapped;
    uint32_t released;
    ULONGLONG now;
    bool valid;
    uint8_t empty_slider[32];

    if (input == NULL) {
        return false;
    }

    memset(&snapshot, 0, sizeof(snapshot));
    now = GetTickCount64();
    divaslider_pdloader_ensure_lock();
    EnterCriticalSection(&divaslider_pdloader_lock);

    valid = divaslider_pdloader_cached_snapshot_valid;
    if (valid) {
        snapshot = divaslider_pdloader_cached_snapshot;
        buttons = divaslider_pdloader_cached_buttons;

        if (input != divaslider_pdloader_last_input) {
            divaslider_logf(
                    "pdloader_bridge: TLAC input state changed: %p",
                    (void *) input);
            (void) now;
            divaslider_pdloader_reprime_buttons(buttons);
            divaslider_pdloader_last_input = input;
        }

        tapped = divaslider_pdloader_pending_tapped;
        released = divaslider_pdloader_pending_released;
        divaslider_pdloader_pending_tapped = 0;
        divaslider_pdloader_pending_released = 0;
    } else {
        buttons = 0;
        tapped = 0;
        released = 0;
    }

    LeaveCriticalSection(&divaslider_pdloader_lock);

    if (!valid) {
        return false;
    }

    if (snapshot.connected) {
        divaslider_pdloader_write_slider(snapshot.slider);
    } else {
        memset(empty_slider, 0, sizeof(empty_slider));
        divaslider_pdloader_write_slider(empty_slider);
    }

    divaslider_pdloader_write_buttons(input, buttons, tapped, released);
    return true;
}

static uint32_t divaslider_pdloader_map_buttons(uint16_t buttons)
{
    uint32_t result;

    result = 0;

    if ((buttons & DIVASLIDER_BUTTON_TEST) != 0) {
        result |= TLAC_BUTTON_TEST;
    }

    if ((buttons & DIVASLIDER_BUTTON_SERVICE) != 0) {
        result |= TLAC_BUTTON_SERVICE;
    }

    if ((buttons & DIVASLIDER_BUTTON_START) != 0) {
        result |= TLAC_BUTTON_START;
    }

    if ((buttons & DIVASLIDER_BUTTON_TRIANGLE) != 0) {
        result |= TLAC_BUTTON_TRIANGLE;
    }

    if ((buttons & DIVASLIDER_BUTTON_SQUARE) != 0) {
        result |= TLAC_BUTTON_SQUARE;
    }

    if ((buttons & DIVASLIDER_BUTTON_CROSS) != 0) {
        result |= TLAC_BUTTON_CROSS;
    }

    if ((buttons & DIVASLIDER_BUTTON_CIRCLE) != 0) {
        result |= TLAC_BUTTON_CIRCLE;
    }

    return result;
}

static void divaslider_pdloader_update_button_edges(uint32_t buttons)
{
    uint32_t pressed;
    uint32_t released;

    pressed = buttons & ~divaslider_pdloader_last_buttons;
    released = divaslider_pdloader_last_buttons & ~buttons;
    divaslider_pdloader_last_buttons = buttons;

    divaslider_pdloader_pending_tapped |= pressed;
    divaslider_pdloader_pending_released |= released;
}

static void divaslider_pdloader_write_buttons(
        struct divaslider_tlac_input_state *input,
        uint32_t buttons,
        uint32_t tapped,
        uint32_t released)
{
    uint32_t momentary_tapped;
    uint32_t momentary_mask;
    uint32_t action_mask;
    bool meta;

    if (input == NULL) {
        return;
    }

    momentary_mask = TLAC_BUTTON_TEST | TLAC_BUTTON_SERVICE;
    momentary_tapped = tapped & momentary_mask;
    action_mask = TLAC_BUTTON_START |
            TLAC_BUTTON_TRIANGLE |
            TLAC_BUTTON_SQUARE |
            TLAC_BUTTON_CROSS |
            TLAC_BUTTON_CIRCLE;

    buttons &= ~momentary_mask;
    tapped &= ~momentary_mask;
    released &= ~momentary_mask;

    __try {
        meta = ((tapped | momentary_tapped) & action_mask) != 0;
        divaslider_pdloader_fill_button_state(&input->tapped, tapped, meta);
        input->tapped.state[0] &= ~divaslider_pdloader_owned_momentary_tapped;
        input->tapped.state[0] |= momentary_tapped;
        divaslider_pdloader_owned_momentary_tapped = momentary_tapped;

        divaslider_pdloader_fill_button_state(&input->double_tapped, tapped, meta);
        divaslider_pdloader_fill_button_state(&input->interval_tapped, tapped, meta);

        meta = (released & action_mask) != 0;
        divaslider_pdloader_fill_button_state(&input->released, released, meta);

        meta = (buttons & action_mask) != 0;
        divaslider_pdloader_fill_button_state(&input->down, buttons, meta);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }
}

static void divaslider_pdloader_reprime_buttons(uint32_t buttons)
{
    divaslider_pdloader_pending_tapped |= buttons;
    divaslider_pdloader_pending_released = 0;
    divaslider_pdloader_last_buttons = buttons;
}

static void divaslider_pdloader_fill_button_state(
        struct divaslider_tlac_button_state *state,
        uint32_t buttons,
        bool meta)
{
    state->state[0] = (state->state[0] & ~TLAC_MANAGED_LOW_MASK) | buttons;

    if (meta) {
        divaslider_pdloader_set_button_bit(state, TLAC_BUTTON_META);
    } else {
        state->state[TLAC_BUTTON_META / 32] &= ~(1u << (TLAC_BUTTON_META % 32));
    }
}

static void divaslider_pdloader_set_button_bit(
        struct divaslider_tlac_button_state *state,
        uint32_t bit)
{
    if (bit >= 128) {
        return;
    }

    state->state[bit / 32] |= 1u << (bit % 32);
}

static void divaslider_pdloader_write_slider(const uint8_t *slider)
{
    uint8_t *base;
    uint8_t *serial;
    int i;

    if (slider == NULL) {
        return;
    }

    base = (uint8_t *) (0x100000000ULL + TLAC_SLIDER_STATE_ADDRESS);
    if (!divaslider_pdloader_is_writeable(
            base + TLAC_SLIDER_PRESSURE_OFFSET,
            sizeof(int32_t) * 32)) {
        return;
    }

    __try {
        *(int32_t *) (base + TLAC_SLIDER_STATE_OFFSET) = TLAC_SLIDER_OK;

        for (i = 0; i < 32; i++) {
            *(int32_t *) (base + TLAC_SLIDER_PRESSURE_OFFSET + i * sizeof(int32_t)) = slider[i];
            *(bool *) (base +
                    TLAC_SLIDER_SENSOR_TOUCHED_OFFSET +
                    i * TLAC_SLIDER_SENSOR_TOUCHED_STRIDE +
                    TLAC_SLIDER_SENSOR_TOUCHED_FLAG_OFFSET) = slider[i] != 0;
        }

        serial = *(uint8_t **) (base + TLAC_SLIDER_SERIAL_PTR_OFFSET);
        if (serial == NULL) {
            return;
        }

        if (!divaslider_pdloader_is_writeable(
                serial + TLAC_SERIAL_RESPONSE_COUNT_OFFSET,
                TLAC_SERIAL_SENSOR_HISTORY_OFFSET + 32 * 16 - TLAC_SERIAL_RESPONSE_COUNT_OFFSET)) {
            return;
        }

        *(int32_t *) (serial + TLAC_SERIAL_RESPONSE_COUNT_OFFSET) = 1;
        *(int32_t *) (serial + TLAC_SERIAL_SCAN_MODE_OFFSET) = 1;
        *(int32_t *) (serial + TLAC_SERIAL_SCAN_COUNT_OFFSET) = 1;

        for (i = 0; i < 32; i++) {
            uint32_t *history = (uint32_t *) (
                    serial +
                    TLAC_SERIAL_SENSOR_HISTORY_OFFSET +
                    i * 4 * sizeof(uint32_t));

            history[3] = history[2];
            history[2] = history[1];
            history[1] = history[0];
            history[0] = slider[i];
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }
}

static bool divaslider_pdloader_is_executable(const void *ptr)
{
    MEMORY_BASIC_INFORMATION mbi;
    DWORD protect;

    if (ptr == NULL) {
        return false;
    }

    if (VirtualQuery(ptr, &mbi, sizeof(mbi)) == 0) {
        return false;
    }

    if (mbi.State != MEM_COMMIT || (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) != 0) {
        return false;
    }

    protect = mbi.Protect & 0xff;
    return protect == PAGE_EXECUTE ||
            protect == PAGE_EXECUTE_READ ||
            protect == PAGE_EXECUTE_READWRITE ||
            protect == PAGE_EXECUTE_WRITECOPY;
}

static bool divaslider_pdloader_is_writeable(const void *ptr, size_t len)
{
    MEMORY_BASIC_INFORMATION mbi;
    uintptr_t start;
    uintptr_t end;
    DWORD protect;

    if (ptr == NULL || len == 0) {
        return false;
    }

    if (VirtualQuery(ptr, &mbi, sizeof(mbi)) == 0) {
        return false;
    }

    if (mbi.State != MEM_COMMIT || (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) != 0) {
        return false;
    }

    start = (uintptr_t) ptr;
    end = start + len;
    if (end < start || end > (uintptr_t) mbi.BaseAddress + mbi.RegionSize) {
        return false;
    }

    protect = mbi.Protect & 0xff;
    return protect == PAGE_READWRITE ||
            protect == PAGE_WRITECOPY ||
            protect == PAGE_EXECUTE_READWRITE ||
            protect == PAGE_EXECUTE_WRITECOPY;
}
