package co.multiply.tropical.frame;

import clojure.lang.ILookup;
import clojure.lang.Keyword;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * How one island's template changed between two frames, when it changed only
 * by child islands added or removed: a list that grew or shrank.
 * <p>
 * A template's parts are strings and holes, a hole being anything with a
 * {@code :slot}, in document order; strings never meet, and none is empty. Two
 * templates are a splice of one another when, with the added holes left out of
 * the new one and the removed holes out of the old one, they are the same text
 * with the same holes in the same order. The strings either side of a hole left
 * out then meet, so they are compared as one text, across part boundaries,
 * without joining them.
 * <p>
 * An added hole with no text between it and a removed one takes that one's
 * place: a child whose key changed, say. It is anchored there, so that it can
 * go in where the removed one was, even with no kept child beside it.
 * <p>
 * {@link #of} decides it in one pass over both templates, allocating nothing
 * unless they are a splice.
 */
public final class Splice {

    private static final Keyword SLOT = Keyword.intern("slot");

    /** The indexes, in the new template's parts, of the holes added, ascending. */
    public final int[] added;

    /**
     * For each added hole, in {@link #added}'s order, the slot of the last hole
     * removed at its place, with no text or kept hole between them; null for
     * one added where nothing was removed.
     */
    public final Object[] anchors;

    /** The slots of the holes removed, in the old template's order. */
    public final List<Object> removed;

    /** The slots of the holes kept whose child frames differ, in order. */
    public final List<Object> changed;

    private Splice(int[] added, Object[] anchors, List<Object> removed, List<Object> changed) {
        this.added = added;
        this.anchors = anchors;
        this.removed = removed;
        this.changed = changed;
    }

    private static Object slot(Object part) {
        return ((ILookup) part).valAt(SLOT);
    }

    /**
     * The splice from the template {@code parts} to the template
     * {@code nextParts}, whose children are {@code slots} and {@code nextSlots}
     * (slot to child frame); null if they differ otherwise, or not at all.
     */
    public static Splice of(List<?> parts, List<?> nextParts, Map<?, ?> slots, Map<?, ?> nextSlots) {
        int n = parts.size();
        int m = nextParts.size();
        int i = 0;
        int j = 0;
        // Where the comparison stands within the string parts i and j.
        int at = 0;
        int nextAt = 0;
        int[] added = null;
        Object[] anchors = null;
        int addedCount = 0;
        // The last hole removed since text or a kept hole was passed: what an
        // added hole here takes the place of. Removals at a place come before
        // additions there, since the old template's hole is looked at first.
        Object gapRemoved = null;
        List<Object> removed = null;
        List<Object> changed = null;

        while (i < n || j < m) {
            Object part = i < n ? parts.get(i) : null;
            Object next = j < m ? nextParts.get(j) : null;
            Object slot = part == null || part instanceof String ? null : slot(part);
            Object nextSlot = next == null || next instanceof String ? null : slot(next);

            if (slot != null && slot.equals(nextSlot)) {
                // The same child in both, in place: kept, and changed if its frame is another.
                if (slots.get(slot) != nextSlots.get(slot)) {
                    if (changed == null) {
                        changed = new ArrayList<>();
                    }
                    changed.add(slot);
                }
                gapRemoved = null;
                i++;
                j++;
            } else if (slot != null && !nextSlots.containsKey(slot)) {
                if (removed == null) {
                    removed = new ArrayList<>();
                }
                removed.add(slot);
                gapRemoved = slot;
                i++;
            } else if (nextSlot != null && !slots.containsKey(nextSlot)) {
                if (added == null) {
                    added = new int[4];
                    anchors = new Object[4];
                } else if (addedCount == added.length) {
                    added = Arrays.copyOf(added, addedCount * 2);
                    anchors = Arrays.copyOf(anchors, addedCount * 2);
                }
                added[addedCount] = j;
                anchors[addedCount] = gapRemoved;
                addedCount++;
                j++;
            } else if (part instanceof String s && next instanceof String t) {
                int len = Math.min(s.length() - at, t.length() - nextAt);
                if (!s.regionMatches(at, t, nextAt, len)) {
                    return null;
                }
                at += len;
                nextAt += len;
                gapRemoved = null;
                if (at == s.length()) {
                    i++;
                    at = 0;
                }
                if (nextAt == t.length()) {
                    j++;
                    nextAt = 0;
                }
            } else {
                // A string against a hole, a kept child against another, or one
                // template ended before the other.
                return null;
            }
        }

        if (added == null && removed == null) {
            return null;
        }
        return new Splice(
                added == null ? new int[0] : Arrays.copyOf(added, addedCount),
                anchors == null ? new Object[0] : Arrays.copyOf(anchors, addedCount),
                removed == null ? List.of() : removed,
                changed == null ? List.of() : changed);
    }
}
