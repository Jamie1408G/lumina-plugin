package nl.jamie1408g.lumina.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * LumiCoins are stored as whole cents (long) so amounts never drift.
 * 1 LC = 100 cents.
 */
public final class Money {

    private static String symbol = "LC";

    private Money() {
    }

    public static void setSymbol(String newSymbol) {
        symbol = newSymbol == null || newSymbol.isBlank() ? "LC" : newSymbol;
    }

    public static String symbol() {
        return symbol;
    }

    /** Converts a config value like 2.5 to 250 cents. */
    public static long fromDouble(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /**
     * Parses user input like "12", "12.5" or "12,50" into cents.
     * Returns -1 when the input is not a valid positive amount.
     */
    public static long parse(String input) {
        if (input == null) {
            return -1;
        }
        String clean = input.trim().replace(',', '.');
        if (clean.isEmpty() || !clean.matches("\\d{1,9}(\\.\\d{1,2})?")) {
            return -1;
        }
        return new BigDecimal(clean).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /** 1250 -> "12.50" */
    public static String plain(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    /** 1250 -> "LC 12.50" */
    public static String format(long cents) {
        return symbol + " " + plain(cents);
    }
}
