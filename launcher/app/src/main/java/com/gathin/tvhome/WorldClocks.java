package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Other cities' time under the clock (config.xml or a theme, world_clocks): up to three time zones by their IANA id,
 * e.g. "Europe/London", on one quiet line, "London 21:30  ·  Tokyo 05:30 +1". The big clock is the TV's own time,
 * so with three more that is four clocks. A day ahead or behind the TV's date gets "+1" or "-1". Pure Java: tested on
 * the JVM.
 */
final class WorldClocks {
    static final int MAX = 3;

    private WorldClocks() {
    }

    /** The ids the TV knows, at most MAX, each once; anything else is left out. */
    static List<String> valid(List<String> ids, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (id != null && known.contains(id) && !out.contains(id)) out.add(id);
            if (out.size() == MAX) break;
        }
        return out;
    }

    /** "Europe/London" is "London", "America/Argentina/Buenos_Aires" is "Buenos Aires". */
    static String cityName(String id) {
        return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
    }

    /** The line for this instant, in the TV's 12- or 24-hour format; "" for no zones. */
    static String line(List<String> ids, ZonedDateTime now, boolean is24, Locale locale) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern(is24 ? "HH:mm" : "h:mm a", locale);
        LocalDate today = now.toLocalDate();
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            ZonedDateTime there = now.withZoneSameInstant(ZoneId.of(id));
            int days = there.toLocalDate().compareTo(today);
            if (out.length() > 0) out.append("  ·  ");
            out.append(cityName(id)).append(' ').append(there.format(time));
            if (days != 0) out.append(days > 0 ? " +1" : " -1");
        }
        return out.toString();
    }
}
