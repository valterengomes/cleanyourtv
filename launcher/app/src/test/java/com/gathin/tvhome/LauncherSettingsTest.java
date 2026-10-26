package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Launcher settings: the value a row shows for a theme's own number, and the choices a theme push clears. */
public class LauncherSettingsTest {
    @Test public void aThemeValueBetweenTheStepsShowsTheClosestOne() {
        int[] dims = LauncherSettingsActivity.DIM_CHOICES;
        assertEquals(0, LauncherSettingsActivity.nearest(dims, 0));
        assertEquals(1, LauncherSettingsActivity.nearest(dims, 12));
        assertEquals(3, LauncherSettingsActivity.nearest(dims, 35));
        assertEquals(dims.length - 1, LauncherSettingsActivity.nearest(dims, 90));
        assertEquals(1, LauncherSettingsActivity.nearest(LauncherSettingsActivity.DOCK_OPACITIES, 80));
    }

    @Test public void everyOnTvChoiceIsListedOnce() {
        List<String> keys = Arrays.asList(Config.ON_TV_KEYS);
        assertEquals(keys.size(), new HashSet<>(keys).size());
        for (String key : new String[]{"columns", "glass_style", "cinematic_home", "reduced_motion", "date_below_clock",
                "island", "recent_row", "home_idle_clock_minutes", "wallpaper_dim"}) {
            assertTrue(key, keys.contains(key));
        }
    }
}
