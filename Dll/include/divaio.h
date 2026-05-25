#pragma once

#include <windows.h>

#include <stdint.h>

enum {
    DIVA_IO_OPBTN_TEST    = 0x01,
    DIVA_IO_OPBTN_SERVICE = 0x02,
};

enum {
    DIVA_IO_GAMEBTN_CIRCLE   = 0x01,
    DIVA_IO_GAMEBTN_CROSS    = 0x02,
    DIVA_IO_GAMEBTN_SQUARE   = 0x04,
    DIVA_IO_GAMEBTN_TRIANGLE = 0x08,
    DIVA_IO_GAMEBTN_START    = 0x10,
};

enum {
    DIVA_IO_TOUCH_DOWN    = 0x01,
    DIVA_IO_TOUCH_STREAM  = 0x02,
    DIVA_IO_TOUCH_LIFTOFF = 0x04,
};

typedef void (*diva_io_slider_callback_t)(const uint8_t *state);

typedef void (*diva_io_touch_callback_t)(
        const uint8_t status,
        const uint16_t x,
        const uint16_t y,
        const uint8_t id);

