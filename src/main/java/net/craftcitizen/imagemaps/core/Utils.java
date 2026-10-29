package net.craftcitizen.imagemaps.core;

import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Small helpers used by the commands and the placement code.
 */
public final class Utils {
    public static final int ELEMENTS_PER_PAGE = 10;

    private static final SemanticVersion MC_VERSION = SemanticVersion.of(Bukkit.getBukkitVersion().split("-")[0]);

    private Utils() {
    }

    public static SemanticVersion getMCVersion() {
        return MC_VERSION;
    }

    public static int parseIntegerOrDefault(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Entries of {@code values} that start with {@code prefix}, ignoring case. */
    public static List<String> getMatches(String prefix, Collection<String> values) {
        return startingWith(prefix, values.stream());
    }

    /** Entries of {@code values} that start with {@code prefix}, ignoring case. */
    public static List<String> getMatches(String prefix, String[] values) {
        return startingWith(prefix, Arrays.stream(values));
    }

    private static List<String> startingWith(String prefix, Stream<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.filter(a -> a.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

    /** True when {@code value} lies in the closed range between the two bounds, in either order. */
    public static boolean isBetween(double value, double bound1, double bound2) {
        if (bound1 == bound2)
            return false;
        return value >= Math.min(bound1, bound2) && value <= Math.max(bound1, bound2);
    }

    /** The given zero-based page of {@code values}, {@link #ELEMENTS_PER_PAGE} entries per page. */
    public static <T> List<T> paginate(T[] values, long page) {
        return Arrays.stream(values).skip(page * ELEMENTS_PER_PAGE).limit(ELEMENTS_PER_PAGE)
                     .collect(Collectors.toList());
    }
}
