package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The island's Wi-Fi card: the band under the signal, and the network's name when Android gives it. */
public class IslandNetTest {
    @Test public void channelsMapToTheirBand() {
        assertEquals("2.4", Island.Net.band(2412));
        assertEquals("2.4", Island.Net.band(2484));
        assertEquals("5", Island.Net.band(5180));
        assertEquals("5", Island.Net.band(5885));
        assertEquals("6", Island.Net.band(5955));
    }

    @Test public void anUnknownFrequencyHasNoBand() {
        assertNull(Island.Net.band(0));
        assertNull(Island.Net.band(-1));
    }

    @Test public void theNameLosesItsQuotes() {
        assertEquals("Home 5G", Island.Net.networkName("\"Home 5G\""));
        assertEquals("Cafe", Island.Net.networkName(" Cafe "));
    }

    @Test public void aHiddenNameIsNoName() {
        assertNull(Island.Net.networkName("<unknown ssid>"));
        assertNull(Island.Net.networkName("0x"));
        assertNull(Island.Net.networkName("\"\""));
        assertNull(Island.Net.networkName(null));
    }
}
