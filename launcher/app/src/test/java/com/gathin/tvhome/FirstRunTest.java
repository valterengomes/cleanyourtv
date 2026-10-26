package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** First run's favourites, read back as the dock: in the order picked, without blanks or repeats. */
public class FirstRunTest {
    @Test public void favouritesKeepTheirOrder() {
        assertEquals(Arrays.asList("com.b", "com.a", "com.c"), FirstRunActivity.splitFavourites("com.b,com.a,com.c"));
    }

    @Test public void blanksAndRepeatsAreDropped() {
        assertEquals(Arrays.asList("com.a", "com.b"), FirstRunActivity.splitFavourites(" com.a,,com.b,com.a, "));
    }

    @Test public void nothingSavedIsNoFavourites() {
        assertEquals(Collections.emptyList(), FirstRunActivity.splitFavourites(null));
        assertEquals(Collections.emptyList(), FirstRunActivity.splitFavourites(""));
    }
}
