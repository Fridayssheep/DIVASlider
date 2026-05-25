#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <process.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "divaio.h"
#include "divaslider_log.h"
#include "divaslider_pdloader.h"
#include "divaslider_shm.h"

static unsigned int __stdcall divaslider_slider_thread_proc(void *ctx);
static unsigned int __stdcall divaslider_touch_thread_proc(void *ctx);
static void divaslider_stop_slider_thread(void);
static void divaslider_stop_touch_thread(void);
static void divaslider_log_jvs_state(const struct divaslider_snapshot *snapshot);
static void divaslider_log_slider_state(const uint8_t *pressure);
static void divaslider_slider_summary(
        const uint8_t *pressure,
        int *count_out,
        int *first_out,
        int *last_out);
static uint16_t divaslider_update_coin_counter(uint16_t server_coins);

static CRITICAL_SECTION divaslider_thread_lock;
static CRITICAL_SECTION divaslider_state_lock;
static bool divaslider_thread_lock_ready;
static bool divaslider_state_lock_ready;

static HANDLE divaslider_slider_thread;
static volatile LONG divaslider_slider_stop;

static HANDLE divaslider_touch_thread;
static volatile LONG divaslider_touch_stop;

static uint16_t divaslider_last_server_coins;
static uint16_t divaslider_coin_counter;
static bool divaslider_coin_state_ready;
static uint16_t divaslider_logged_buttons;
static uint16_t divaslider_logged_coins;
static bool divaslider_logged_connected;
static bool divaslider_logged_jvs_state;
static uint8_t divaslider_logged_slider[32];
static bool divaslider_logged_slider_state;
static uint8_t divaslider_last_opbtn;
static uint8_t divaslider_last_gamebtn;

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, void *reserved)
{
    (void) inst;
    (void) reserved;

    if (reason == DLL_PROCESS_ATTACH) {
        InitializeCriticalSection(&divaslider_thread_lock);
        InitializeCriticalSection(&divaslider_state_lock);
        divaslider_thread_lock_ready = true;
        divaslider_state_lock_ready = true;
        DisableThreadLibraryCalls(inst);
    } else if (reason == DLL_PROCESS_DETACH) {
        divaslider_pdloader_stop();
        divaslider_stop_slider_thread();
        divaslider_stop_touch_thread();
        divaslider_shm_close();

        if (divaslider_thread_lock_ready) {
            DeleteCriticalSection(&divaslider_thread_lock);
            divaslider_thread_lock_ready = false;
        }

        if (divaslider_state_lock_ready) {
            DeleteCriticalSection(&divaslider_state_lock);
            divaslider_state_lock_ready = false;
        }
    }

    return TRUE;
}

__declspec(dllexport) uint16_t diva_io_get_api_version(void)
{
    return 0x0101;
}

__declspec(dllexport) HRESULT diva_io_jvs_init(void)
{
    struct divaslider_snapshot snapshot;

    divaslider_pdloader_start();
    divaslider_pdloader_reprime();

    if (divaslider_shm_read(&snapshot)) {
        EnterCriticalSection(&divaslider_state_lock);
        divaslider_last_server_coins = snapshot.coins;
        divaslider_coin_counter = 0;
        divaslider_coin_state_ready = true;
        LeaveCriticalSection(&divaslider_state_lock);
        divaslider_logf(
                "jvs_init: shared memory ok connected=%u buttons=0x%04x coins=%u seq=%lu",
                snapshot.connected ? 1 : 0,
                snapshot.buttons,
                snapshot.coins,
                (unsigned long) snapshot.sequence);
    } else {
        divaslider_logf("jvs_init: shared memory is not ready");
    }

    return S_OK;
}

__declspec(dllexport) void diva_io_jvs_poll(uint8_t *opbtn_out, uint8_t *gamebtn_out)
{
    struct divaslider_snapshot snapshot;
    uint16_t buttons;
    uint8_t opbtn;
    uint8_t gamebtn;

    opbtn = divaslider_last_opbtn;
    gamebtn = divaslider_last_gamebtn;

    if (divaslider_shm_read(&snapshot)) {
        opbtn = 0;
        gamebtn = 0;
        divaslider_log_jvs_state(&snapshot);
        if (!snapshot.connected) {
            goto done;
        }

        buttons = snapshot.buttons;

        if ((buttons & DIVASLIDER_BUTTON_TEST) != 0) {
            opbtn |= DIVA_IO_OPBTN_TEST;
        }

        if ((buttons & DIVASLIDER_BUTTON_SERVICE) != 0) {
            opbtn |= DIVA_IO_OPBTN_SERVICE;
        }

        if ((buttons & DIVASLIDER_BUTTON_CIRCLE) != 0) {
            gamebtn |= DIVA_IO_GAMEBTN_CIRCLE;
        }

        if ((buttons & DIVASLIDER_BUTTON_CROSS) != 0) {
            gamebtn |= DIVA_IO_GAMEBTN_CROSS;
        }

        if ((buttons & DIVASLIDER_BUTTON_SQUARE) != 0) {
            gamebtn |= DIVA_IO_GAMEBTN_SQUARE;
        }

        if ((buttons & DIVASLIDER_BUTTON_TRIANGLE) != 0) {
            gamebtn |= DIVA_IO_GAMEBTN_TRIANGLE;
        }

        if ((buttons & DIVASLIDER_BUTTON_START) != 0) {
            gamebtn |= DIVA_IO_GAMEBTN_START;
        }
    }

done:
    divaslider_last_opbtn = opbtn;
    divaslider_last_gamebtn = gamebtn;

    if (opbtn_out != NULL) {
        *opbtn_out = opbtn;
    }

    if (gamebtn_out != NULL) {
        *gamebtn_out = gamebtn;
    }
}

__declspec(dllexport) void diva_io_jvs_read_coin_counter(uint16_t *out)
{
    struct divaslider_snapshot snapshot;

    if (out == NULL) {
        return;
    }

    if (divaslider_shm_read(&snapshot)) {
        *out = divaslider_update_coin_counter(snapshot.coins);
    } else {
        EnterCriticalSection(&divaslider_state_lock);
        *out = divaslider_coin_counter;
        LeaveCriticalSection(&divaslider_state_lock);
    }
}

__declspec(dllexport) HRESULT diva_io_slider_init(void)
{
    divaslider_pdloader_start();
    divaslider_pdloader_reprime();
    divaslider_logf("slider_init");
    return S_OK;
}

__declspec(dllexport) void diva_io_slider_start(diva_io_slider_callback_t callback)
{
    HANDLE thread;

    if (callback == NULL) {
        divaslider_logf("slider_start ignored: callback is null");
        return;
    }

    EnterCriticalSection(&divaslider_thread_lock);
    divaslider_pdloader_reprime();

    if (divaslider_slider_thread != NULL) {
        divaslider_logf("slider_start ignored: thread already running");
        LeaveCriticalSection(&divaslider_thread_lock);
        return;
    }

    InterlockedExchange(&divaslider_slider_stop, 0);
    thread = (HANDLE) _beginthreadex(
            NULL,
            0,
            divaslider_slider_thread_proc,
            callback,
            0,
            NULL);

    if (thread != NULL) {
        divaslider_slider_thread = thread;
        divaslider_logf("slider_start: thread started");
    } else {
        divaslider_logf("slider_start: _beginthreadex failed");
    }

    LeaveCriticalSection(&divaslider_thread_lock);
}

__declspec(dllexport) void diva_io_slider_stop(void)
{
    divaslider_stop_slider_thread();
}

__declspec(dllexport) void diva_io_slider_set_leds(const uint8_t *rgb)
{
    divaslider_shm_write_slider_leds(rgb);
}

__declspec(dllexport) HRESULT diva_io_led_init(void)
{
    divaslider_logf("led_init");
    return S_OK;
}

__declspec(dllexport) void diva_io_led_set_leds(uint8_t board, const uint8_t *rgb)
{
    (void) board;
    divaslider_shm_write_button_leds(rgb);
}

__declspec(dllexport) HRESULT diva_io_touch_init(void)
{
    divaslider_logf("touch_init");
    return S_OK;
}

__declspec(dllexport) void diva_io_touch_start(diva_io_touch_callback_t callback)
{
    HANDLE thread;

    if (callback == NULL) {
        divaslider_logf("touch_start ignored: callback is null");
        return;
    }

    EnterCriticalSection(&divaslider_thread_lock);

    if (divaslider_touch_thread != NULL) {
        divaslider_logf("touch_start ignored: thread already running");
        LeaveCriticalSection(&divaslider_thread_lock);
        return;
    }

    InterlockedExchange(&divaslider_touch_stop, 0);
    thread = (HANDLE) _beginthreadex(
            NULL,
            0,
            divaslider_touch_thread_proc,
            callback,
            0,
            NULL);

    if (thread != NULL) {
        divaslider_touch_thread = thread;
        divaslider_logf("touch_start: thread started");
    } else {
        divaslider_logf("touch_start: _beginthreadex failed");
    }

    LeaveCriticalSection(&divaslider_thread_lock);
}

__declspec(dllexport) void diva_io_touch_stop(void)
{
    divaslider_stop_touch_thread();
}

static void divaslider_stop_slider_thread(void)
{
    HANDLE thread;

    EnterCriticalSection(&divaslider_thread_lock);
    thread = divaslider_slider_thread;

    if (thread != NULL) {
        divaslider_slider_thread = NULL;
        InterlockedExchange(&divaslider_slider_stop, 1);
    }

    LeaveCriticalSection(&divaslider_thread_lock);

    if (thread != NULL) {
        WaitForSingleObject(thread, INFINITE);
        CloseHandle(thread);
        divaslider_logf("slider_stop: thread stopped");
    }
}

static void divaslider_stop_touch_thread(void)
{
    HANDLE thread;

    EnterCriticalSection(&divaslider_thread_lock);
    thread = divaslider_touch_thread;

    if (thread != NULL) {
        divaslider_touch_thread = NULL;
        InterlockedExchange(&divaslider_touch_stop, 1);
    }

    LeaveCriticalSection(&divaslider_thread_lock);

    if (thread != NULL) {
        WaitForSingleObject(thread, INFINITE);
        CloseHandle(thread);
        divaslider_logf("touch_stop: thread stopped");
    }
}

static unsigned int __stdcall divaslider_slider_thread_proc(void *ctx)
{
    diva_io_slider_callback_t callback;
    struct divaslider_snapshot snapshot;
    uint8_t pressure[32];

    callback = (diva_io_slider_callback_t) ctx;
    memset(pressure, 0, sizeof(pressure));

    while (InterlockedCompareExchange(&divaslider_slider_stop, 0, 0) == 0) {
        if (divaslider_shm_read(&snapshot)) {
            if (snapshot.connected) {
                memcpy(pressure, snapshot.slider, sizeof(pressure));
            } else {
                memset(pressure, 0, sizeof(pressure));
            }
        }

        divaslider_log_slider_state(pressure);
        callback(pressure);
        Sleep(1);
    }

    return 0;
}

static unsigned int __stdcall divaslider_touch_thread_proc(void *ctx)
{
    diva_io_touch_callback_t callback;

    callback = (diva_io_touch_callback_t) ctx;

    while (InterlockedCompareExchange(&divaslider_touch_stop, 0, 0) == 0) {
        callback(0, 0, 0, 0);
        Sleep(16);
    }

    return 0;
}

static uint16_t divaslider_update_coin_counter(uint16_t server_coins)
{
    uint16_t diff;
    uint16_t result;

    EnterCriticalSection(&divaslider_state_lock);

    if (!divaslider_coin_state_ready) {
        divaslider_last_server_coins = server_coins;
        divaslider_coin_counter = 0;
        divaslider_coin_state_ready = true;

        result = divaslider_coin_counter;
        LeaveCriticalSection(&divaslider_state_lock);
        return result;
    }

    diff = (uint16_t) (server_coins - divaslider_last_server_coins);

    if (diff != 0 && diff < 0x8000) {
        divaslider_coin_counter = (uint16_t) (divaslider_coin_counter + diff);
        divaslider_logf(
                "coin changed: server=%u diff=%u game_counter=%u",
                server_coins,
                diff,
                divaslider_coin_counter);
    }

    divaslider_last_server_coins = server_coins;

    result = divaslider_coin_counter;
    LeaveCriticalSection(&divaslider_state_lock);

    return result;
}

static void divaslider_log_jvs_state(const struct divaslider_snapshot *snapshot)
{
    if (snapshot == NULL) {
        return;
    }

    if (divaslider_logged_jvs_state &&
            divaslider_logged_connected == snapshot->connected &&
            divaslider_logged_buttons == snapshot->buttons &&
            divaslider_logged_coins == snapshot->coins) {
        return;
    }

    divaslider_logged_jvs_state = true;
    divaslider_logged_connected = snapshot->connected;
    divaslider_logged_buttons = snapshot->buttons;
    divaslider_logged_coins = snapshot->coins;

    divaslider_logf(
            "jvs_state: connected=%u buttons=0x%04x coins=%u seq=%lu",
            snapshot->connected ? 1 : 0,
            snapshot->buttons,
            snapshot->coins,
            (unsigned long) snapshot->sequence);
}

static void divaslider_log_slider_state(const uint8_t *pressure)
{
    int count;
    int first;
    int last;

    if (pressure == NULL) {
        return;
    }

    if (divaslider_logged_slider_state &&
            memcmp(divaslider_logged_slider, pressure, sizeof(divaslider_logged_slider)) == 0) {
        return;
    }

    memcpy(divaslider_logged_slider, pressure, sizeof(divaslider_logged_slider));
    divaslider_logged_slider_state = true;
    divaslider_slider_summary(pressure, &count, &first, &last);

    if (count == 0) {
        divaslider_logf("slider_state: none");
    } else if (first == last) {
        divaslider_logf("slider_state: cell=%d pressure=%u", first + 1, pressure[first]);
    } else {
        divaslider_logf("slider_state: count=%d first=%d last=%d", count, first + 1, last + 1);
    }
}

static void divaslider_slider_summary(
        const uint8_t *pressure,
        int *count_out,
        int *first_out,
        int *last_out)
{
    int count;
    int first;
    int last;
    int i;

    count = 0;
    first = -1;
    last = -1;

    for (i = 0; i < 32; i++) {
        if (pressure[i] == 0) {
            continue;
        }

        count++;
        if (first < 0) {
            first = i;
        }
        last = i;
    }

    if (count_out != NULL) {
        *count_out = count;
    }

    if (first_out != NULL) {
        *first_out = first;
    }

    if (last_out != NULL) {
        *last_out = last;
    }
}
