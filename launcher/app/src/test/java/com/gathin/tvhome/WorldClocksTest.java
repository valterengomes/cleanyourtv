package com.gathin.tvhome;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class WorldClocksTest {
    private static final ZonedDateTime LISBON_EVENING = ZonedDateTime.of(2026, 10, 26, 21, 30, 0, 0, ZoneId.of("Europe/Lisbon"));

    @Test public void keepsKnownZonesOnceAndAtMostThree() {
        assertEquals(Arrays.asList("Europe/London", "Asia/Tokyo", "America/New_York"),
                WorldClocks.valid(Arrays.asList("Europe/London", "Mars/Olympus", "Asia/Tokyo", "Europe/London", "America/New_York",
                        "Australia/Sydney"), ZoneId.getAvailableZoneIds()));
        assertEquals(Collections.emptyList(), WorldClocks.valid(Collections.singletonList(null), ZoneId.getAvailableZoneIds()));
    }

    @Test public void namesTheCityFromTheZone() {
        assertEquals("London", WorldClocks.cityName("Europe/London"));
        assertEquals("Buenos Aires", WorldClocks.cityName("America/Argentina/Buenos_Aires"));
        assertEquals("UTC", WorldClocks.cityName("UTC"));
    }

    @Test public void showsEachCityWithADayAheadOrBehind() {
        assertEquals("London 21:30  ·  Tokyo 06:30 +1  ·  New York 17:30",
                WorldClocks.line(Arrays.asList("Europe/London", "Asia/Tokyo", "America/New_York"), LISBON_EVENING, true, Locale.UK));
        ZonedDateTime lisbonMorning = LISBON_EVENING.withHour(2);
        assertEquals("Los Angeles 7:30 PM -1",
                WorldClocks.line(Collections.singletonList("America/Los_Angeles"), lisbonMorning, false, Locale.US));
    }

    @Test public void noZonesIsAnEmptyLine() {
        assertEquals("", WorldClocks.line(Collections.emptyList(), LISBON_EVENING, true, Locale.UK));
    }
}
