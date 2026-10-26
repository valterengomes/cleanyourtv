package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Home layout Shelves: the row a category gives, the owner's moves, and the order inside a row. */
public class ShelvesTest {
    @Test public void categoriesGoToTheirRows() {
        assertEquals(Shelves.WATCH, Shelves.shelfFor(ApplicationInfo.CATEGORY_VIDEO));
        assertEquals(Shelves.LISTEN, Shelves.shelfFor(ApplicationInfo.CATEGORY_AUDIO));
        assertEquals(Shelves.PLAY, Shelves.shelfFor(ApplicationInfo.CATEGORY_GAME));
        assertEquals(Shelves.TOOLS, Shelves.shelfFor(ApplicationInfo.CATEGORY_PRODUCTIVITY));
        assertEquals(Shelves.TOOLS, Shelves.shelfFor(ApplicationInfo.CATEGORY_UNDEFINED));
    }

    @Test public void movesSurviveSavingAndReading() {
        Map<String, Integer> moves = Shelves.decodeMoves("com.b=2;com.a=0;");
        assertEquals(2, moves.size());
        assertEquals(Integer.valueOf(Shelves.WATCH), moves.get("com.a"));
        assertEquals("com.a=0;com.b=2;", Shelves.encodeMoves(moves));
    }

    @Test public void aDamagedMoveIsDropped() {
        Map<String, Integer> moves = Shelves.decodeMoves("com.a=9;com.b=x;=1;com.c=3");
        assertEquals(1, moves.size());
        assertEquals(Integer.valueOf(Shelves.TOOLS), moves.get("com.c"));
        assertTrue(Shelves.decodeMoves(null).isEmpty());
    }

    @Test public void theAppOpenedLastComesFirstThenAtoZ() {
        assertTrue(Shelves.compare(200, "Zattoo", 100, "Apple TV") < 0);
        assertTrue(Shelves.compare(0, "Browser", 0, "apple TV") > 0);
        assertTrue(Shelves.compare(0, "Kodi", 50, "Plex") > 0);
    }
}
