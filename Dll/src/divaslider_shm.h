#pragma once

#include <stdbool.h>
#include <stdint.h>

enum {
    DIVASLIDER_BUTTON_CIRCLE   = 0x0001,
    DIVASLIDER_BUTTON_CROSS    = 0x0002,
    DIVASLIDER_BUTTON_SQUARE   = 0x0004,
    DIVASLIDER_BUTTON_TRIANGLE = 0x0008,
    DIVASLIDER_BUTTON_START    = 0x0010,
    DIVASLIDER_BUTTON_TEST     = 0x0020,
    DIVASLIDER_BUTTON_SERVICE  = 0x0040,
};

struct divaslider_snapshot {
    uint32_t sequence;
    uint64_t updated_millis;
    uint16_t buttons;
    uint16_t coins;
    bool connected;
    uint8_t slider[32];
};

bool divaslider_shm_read(struct divaslider_snapshot *out);
void divaslider_shm_write_slider_leds(const uint8_t *rgb);
void divaslider_shm_write_button_leds(const uint8_t *rgb);
void divaslider_shm_close(void);
