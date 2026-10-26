package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The card behind an app with no banner: panel2 with a little of the icon's colour (docs/ui/launcher board 13). */
public class TintedCardTest {
    @Test public void aGreyIconLeavesTheCardAsItIs() {
        assertEquals(0xFF1F2227, AppAdapter.tintedCard(0xFF1F2227));
    }

    @Test public void aColourTintsTheCardWithoutTakingItOver() {
        int card = AppAdapter.tintedCard(0xFFFF0000);
        assertEquals(0xFF, card >>> 24);
        int red = (card >> 16) & 0xFF, green = (card >> 8) & 0xFF;
        assertEquals(94, red);     // 31 * 0.72 + 255 * 0.28
        assertEquals(24, green);   // 34 * 0.72
    }
}
