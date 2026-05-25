#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "divaslider_log.h"
#include "divaslider_shm.h"

enum {
    DIVASLIDER_SHM_SIZE = 256,
    DIVASLIDER_SHM_VERSION = 1,
    DIVASLIDER_SHM_OFFSET_SEQUENCE = 8,
    DIVASLIDER_SHM_OFFSET_UPDATED_MILLIS = 12,
    DIVASLIDER_SHM_OFFSET_BUTTONS = 20,
    DIVASLIDER_SHM_OFFSET_CONNECTED = 22,
    DIVASLIDER_SHM_OFFSET_SLIDER = 24,
    DIVASLIDER_SHM_OFFSET_COINS = 56,
    DIVASLIDER_SHM_OFFSET_LED_SEQUENCE = 60,
    DIVASLIDER_SHM_OFFSET_SLIDER_LEDS = 64,
    DIVASLIDER_SHM_SLIDER_LED_SIZE = 32 * 3,
    DIVASLIDER_SHM_OFFSET_BUTTON_LEDS = DIVASLIDER_SHM_OFFSET_SLIDER_LEDS +
            DIVASLIDER_SHM_SLIDER_LED_SIZE,
    DIVASLIDER_SHM_BUTTON_LED_SIZE = 10,
};

static const wchar_t divaslider_shm_name[] = L"Local\\DIVASLIDER_SHARED_BUFFER";

static CRITICAL_SECTION divaslider_shm_lock;
static INIT_ONCE divaslider_shm_lock_once = INIT_ONCE_STATIC_INIT;
static HANDLE divaslider_shm_mapping;
static uint8_t *divaslider_shm_view;
static DWORD divaslider_shm_last_open_error;
static bool divaslider_shm_open_logged;
static bool divaslider_shm_header_error_logged;

static void divaslider_shm_ensure_lock(void);
static BOOL CALLBACK divaslider_shm_init_lock(
        PINIT_ONCE init_once,
        PVOID parameter,
        PVOID *context);
static bool divaslider_shm_ensure_open(void);
static bool divaslider_shm_validate_header(const uint8_t *data);
static uint16_t divaslider_read_le16(const uint8_t *data);
static uint32_t divaslider_read_le32(const uint8_t *data);
static uint64_t divaslider_read_le64(const uint8_t *data);
static void divaslider_write_le32(uint8_t *data, uint32_t value);
static void divaslider_shm_write_led_bytes(
        size_t offset,
        const uint8_t *rgb,
        size_t len,
        const char *label);

bool divaslider_shm_read(struct divaslider_snapshot *out)
{
    const uint8_t *data;
    uint32_t seq_before;
    uint32_t seq_after;
    int attempt;

    if (out == NULL) {
        return false;
    }

    memset(out, 0, sizeof(*out));
    divaslider_shm_ensure_lock();

    EnterCriticalSection(&divaslider_shm_lock);

    if (!divaslider_shm_ensure_open()) {
        LeaveCriticalSection(&divaslider_shm_lock);
        return false;
    }

    data = divaslider_shm_view;

    if (!divaslider_shm_validate_header(data)) {
        LeaveCriticalSection(&divaslider_shm_lock);
        return false;
    }

    for (attempt = 0; attempt < 3; attempt++) {
        seq_before = divaslider_read_le32(data + DIVASLIDER_SHM_OFFSET_SEQUENCE);

        if ((seq_before & 1) != 0) {
            Sleep(0);
            continue;
        }

        out->sequence = seq_before >> 1;
        out->updated_millis = divaslider_read_le64(
                data + DIVASLIDER_SHM_OFFSET_UPDATED_MILLIS);
        out->buttons = divaslider_read_le16(data + DIVASLIDER_SHM_OFFSET_BUTTONS);
        out->connected = data[DIVASLIDER_SHM_OFFSET_CONNECTED] != 0;
        memcpy(out->slider, data + DIVASLIDER_SHM_OFFSET_SLIDER, sizeof(out->slider));
        out->coins = divaslider_read_le16(data + DIVASLIDER_SHM_OFFSET_COINS);

        seq_after = divaslider_read_le32(data + DIVASLIDER_SHM_OFFSET_SEQUENCE);

        if (seq_before == seq_after && (seq_after & 1) == 0) {
            LeaveCriticalSection(&divaslider_shm_lock);
            return true;
        }

        Sleep(0);
    }

    LeaveCriticalSection(&divaslider_shm_lock);
    return false;
}

void divaslider_shm_close(void)
{
    divaslider_shm_ensure_lock();
    EnterCriticalSection(&divaslider_shm_lock);

    if (divaslider_shm_view != NULL) {
        UnmapViewOfFile(divaslider_shm_view);
        divaslider_shm_view = NULL;
    }

    if (divaslider_shm_mapping != NULL) {
        CloseHandle(divaslider_shm_mapping);
        divaslider_shm_mapping = NULL;
    }

    LeaveCriticalSection(&divaslider_shm_lock);
}

void divaslider_shm_write_slider_leds(const uint8_t *rgb)
{
    divaslider_shm_write_led_bytes(
            DIVASLIDER_SHM_OFFSET_SLIDER_LEDS,
            rgb,
            DIVASLIDER_SHM_SLIDER_LED_SIZE,
            "slider_leds");
}

void divaslider_shm_write_button_leds(const uint8_t *rgb)
{
    divaslider_shm_write_led_bytes(
            DIVASLIDER_SHM_OFFSET_BUTTON_LEDS,
            rgb,
            DIVASLIDER_SHM_BUTTON_LED_SIZE,
            "button_leds");
}

static void divaslider_shm_ensure_lock(void)
{
    InitOnceExecuteOnce(
            &divaslider_shm_lock_once,
            divaslider_shm_init_lock,
            NULL,
            NULL);
}

static BOOL CALLBACK divaslider_shm_init_lock(
        PINIT_ONCE init_once,
        PVOID parameter,
        PVOID *context)
{
    (void) init_once;
    (void) parameter;
    (void) context;
    InitializeCriticalSection(&divaslider_shm_lock);

    return TRUE;
}

static bool divaslider_shm_ensure_open(void)
{
    if (divaslider_shm_view != NULL) {
        return true;
    }

    divaslider_shm_mapping = OpenFileMappingW(
            FILE_MAP_READ | FILE_MAP_WRITE,
            FALSE,
            divaslider_shm_name);

    if (divaslider_shm_mapping == NULL) {
        DWORD err = GetLastError();

        if (err != divaslider_shm_last_open_error) {
            divaslider_shm_last_open_error = err;
            divaslider_logf("OpenFileMappingW(%ls) failed: %lu", divaslider_shm_name, err);
        }

        return false;
    }

    divaslider_shm_view = MapViewOfFile(
            divaslider_shm_mapping,
            FILE_MAP_READ | FILE_MAP_WRITE,
            0,
            0,
            DIVASLIDER_SHM_SIZE);

    if (divaslider_shm_view == NULL) {
        DWORD err = GetLastError();

        divaslider_logf("MapViewOfFile(%ls) failed: %lu", divaslider_shm_name, err);
        CloseHandle(divaslider_shm_mapping);
        divaslider_shm_mapping = NULL;
        return false;
    }

    if (!divaslider_shm_open_logged) {
        divaslider_logf("shared memory opened: %ls", divaslider_shm_name);
        divaslider_shm_open_logged = true;
    }

    return true;
}

static bool divaslider_shm_validate_header(const uint8_t *data)
{
    if (memcmp(data, "DIVA", 4) != 0) {
        if (!divaslider_shm_header_error_logged) {
            divaslider_logf(
                    "bad shared memory magic: %02x %02x %02x %02x",
                    data[0],
                    data[1],
                    data[2],
                    data[3]);
            divaslider_shm_header_error_logged = true;
        }

        return false;
    }

    if (divaslider_read_le16(data + 4) != DIVASLIDER_SHM_VERSION) {
        if (!divaslider_shm_header_error_logged) {
            divaslider_logf(
                    "bad shared memory version: %u",
                    divaslider_read_le16(data + 4));
            divaslider_shm_header_error_logged = true;
        }

        return false;
    }

    if (divaslider_read_le16(data + 6) != DIVASLIDER_SHM_SIZE) {
        if (!divaslider_shm_header_error_logged) {
            divaslider_logf(
                    "bad shared memory size: %u",
                    divaslider_read_le16(data + 6));
            divaslider_shm_header_error_logged = true;
        }

        return false;
    }

    return true;
}

static uint16_t divaslider_read_le16(const uint8_t *data)
{
    return (uint16_t) data[0] | ((uint16_t) data[1] << 8);
}

static uint32_t divaslider_read_le32(const uint8_t *data)
{
    return (uint32_t) data[0]
            | ((uint32_t) data[1] << 8)
            | ((uint32_t) data[2] << 16)
            | ((uint32_t) data[3] << 24);
}

static uint64_t divaslider_read_le64(const uint8_t *data)
{
    uint64_t low;
    uint64_t high;

    low = divaslider_read_le32(data);
    high = divaslider_read_le32(data + 4);

    return low | (high << 32);
}

static void divaslider_write_le32(uint8_t *data, uint32_t value)
{
    data[0] = (uint8_t) value;
    data[1] = (uint8_t) (value >> 8);
    data[2] = (uint8_t) (value >> 16);
    data[3] = (uint8_t) (value >> 24);
}

static void divaslider_shm_write_led_bytes(
        size_t offset,
        const uint8_t *rgb,
        size_t len,
        const char *label)
{
    uint32_t sequence;

    if (rgb == NULL) {
        return;
    }

    divaslider_shm_ensure_lock();
    EnterCriticalSection(&divaslider_shm_lock);

    if (!divaslider_shm_ensure_open()) {
        LeaveCriticalSection(&divaslider_shm_lock);
        return;
    }

    if (!divaslider_shm_validate_header(divaslider_shm_view)) {
        LeaveCriticalSection(&divaslider_shm_lock);
        return;
    }

    if (offset + len > DIVASLIDER_SHM_SIZE) {
        LeaveCriticalSection(&divaslider_shm_lock);
        return;
    }

    memcpy(divaslider_shm_view + offset, rgb, len);
    sequence = divaslider_read_le32(
            divaslider_shm_view + DIVASLIDER_SHM_OFFSET_LED_SEQUENCE);
    divaslider_write_le32(
            divaslider_shm_view + DIVASLIDER_SHM_OFFSET_LED_SEQUENCE,
            sequence + 1);

    LeaveCriticalSection(&divaslider_shm_lock);

    (void) label;
}
