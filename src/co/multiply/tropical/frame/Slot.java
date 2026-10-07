package co.multiply.tropical.frame;

import java.nio.charset.StandardCharsets;

/**
 * An island's slot, the part of its element id it contributes: its name, plus
 * {@code .key} for a keyed island.
 * <p>
 * A key appears in a slot as it is if it is letters, digits and {@code -}.
 * Any other character is written as {@code _} followed by its UTF-8 bytes in
 * hex, so every key gives a valid id, and distinct keys distinct ids.
 * <p>
 * Every keyed island placed in a render gets its slot here, so a list of a
 * thousand rows asks a thousand times per render of the list. A key that needs
 * no escaping, the usual case (numbers, UUIDs, slugs), is returned as it is,
 * after one scan, allocating nothing.
 */
public final class Slot {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Slot() {
    }

    private static boolean idChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    }

    /** The key {@code s} as it appears in a slot. */
    public static String key(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && idChar(s.charAt(i))) {
            i++;
        }
        if (i == n) {
            return s;
        }
        // The characters before i are ASCII, the same in UTF-8: encode the rest.
        byte[] rest = s.substring(i).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(i + rest.length * 3).append(s, 0, i);
        for (byte b : rest) {
            int c = b & 0xff;
            if (idChar(c)) {
                sb.append((char) c);
            } else {
                sb.append('_').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return sb.toString();
    }
}
