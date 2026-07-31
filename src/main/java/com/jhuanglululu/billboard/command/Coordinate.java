package com.jhuanglululu.billboard.command;

import java.util.regex.Pattern;

/**
 * One coordinate token of {@code /billboard spawn}, in vanilla's notation: an absolute number, or
 * a {@code ~} form measured from the executing player's own coordinate on that axis. Pure (no
 * Bukkit), so both the grammar and the arithmetic are unit-testable.
 *
 * <pre>
 * 10.5     absolute            -> 10.5
 * ~        the player's own    -> base
 * ~3       base + 3
 * ~-0.5    base - 0.5
 * </pre>
 *
 * <p>The number grammar is deliberately narrower than {@link Double#parseDouble}: only an optional
 * sign, digits and one dot. {@code parseDouble} would otherwise accept {@code 1d}, {@code NaN},
 * {@code Infinity} and hex floats, none of which is a coordinate anyone meant to type, and two of
 * which would poison every position the placement ever emits.
 */
public record Coordinate(boolean relative, double value) {

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    /**
     * Parses one token.
     *
     * @throws IllegalArgumentException if it is neither a plain number nor a {@code ~} form
     */
    public static Coordinate parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("a coordinate cannot be empty");
        }
        if (token.charAt(0) != '~') {
            return new Coordinate(false, number(token));
        }
        String offset = token.substring(1);
        // A bare "~" is the player's coordinate exactly — an offset of zero, not a missing number.
        return new Coordinate(true, offset.isEmpty() ? 0 : number(offset));
    }

    /**
     * The world coordinate this token names.
     *
     * @param base the executing player's coordinate on this axis; unused by an absolute token, so
     *     a caller with no player may pass anything once it knows {@link #relative()} is false
     */
    public double resolve(double base) {
        return relative ? base + value : value;
    }

    private static double number(String text) {
        if (!NUMBER.matcher(text).matches()) {
            throw new IllegalArgumentException("not a number: " + text);
        }
        return Double.parseDouble(text);
    }
}
