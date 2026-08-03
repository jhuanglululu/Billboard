package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 40-byte query struct and the selection it drives.
 *
 * <p>The struct is decoded from hand-written bytes (same IEEE-754 patterns as
 * {@link PlayerBlobTest}), and the selection is asserted against a fixed, hand-placed set whose
 * distances are all whole numbers along one axis — so every expected order below was worked out on
 * paper, not by running the comparator.
 */
class PlayerQueryTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void theStructIsDecodedFieldByField() {
        PlayerQuery q = PlayerQuery.parse(bytes(
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F,     // origin_x =  1.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40,     // origin_y =  2.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0xC0,     // origin_z = -4.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE0, 0x3F,     // range    =  0.5
                0x03, 0x00, 0x00, 0x00,                             // limit    =  3
                0x01, 0x00, 0x00, 0x00));                           // sort     =  name

        assertEquals(1.0, q.originX());
        assertEquals(2.0, q.originY());
        assertEquals(-4.0, q.originZ());
        assertEquals(0.5, q.range());
        assertEquals(3, q.limit());
        assertEquals(PlayerQuery.SORT_NAME, q.sort());
    }

    @Test
    void negativeNumbersDecodeAsTheUnlimitedSentinels() {
        PlayerQuery q = PlayerQuery.parse(bytes(
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0xBF,     // range = -1.0
                0xFF, 0xFF, 0xFF, 0xFF,                             // limit = -1
                0x00, 0x00, 0x00, 0x00));

        assertEquals(-1.0, q.range());
        assertEquals(-1, q.limit());
        // and they really do mean "everything": the fixed set below comes back whole.
        assertEquals(List.of("alice", "bob", "carol", "dave"), names(q.apply(SET)));
    }

    // Four players strung out along +Z from the origin, so every distance is its z:
    // alice 1, bob 1 (tied with alice), carol 3, dave 10. Names deliberately sort the same way
    // distance does, except for the tie — which is the case the tiebreak exists for.
    private static final List<PlayerView> SET = List.of(
            new PlayerView("carol", 0, 0, 3, 1.62, 0, 0),
            new PlayerView("bob", 0, 0, 1, 1.62, 0, 0),
            new PlayerView("alice", 0, 0, 1, 1.62, 0, 0),
            new PlayerView("dave", 0, 0, 10, 1.62, 0, 0));

    private static List<String> names(List<PlayerView> players) {
        return players.stream().map(PlayerView::name).toList();
    }

    @Test
    void rangeFiltersAndDistanceSortsWithANameTiebreak() {
        // range 5 drops dave (10); alice and bob are both at 1, so the name decides.
        PlayerQuery q = new PlayerQuery(0, 0, 0, 5, 0, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("alice", "bob", "carol"), names(q.apply(SET)));
    }

    @Test
    void rangeIsInclusive() {
        PlayerQuery exactly = new PlayerQuery(0, 0, 0, 3, 0, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("alice", "bob", "carol"), names(exactly.apply(SET)));
        PlayerQuery justUnder = new PlayerQuery(0, 0, 0, 2.99, 0, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("alice", "bob"), names(justUnder.apply(SET)));
    }

    @Test
    void limitKeepsTheNearestNotTheFirstSeen() {
        // The input order starts with carol, so a limit applied before the sort would keep her.
        PlayerQuery q = new PlayerQuery(0, 0, 0, -1, 2, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("alice", "bob"), names(q.apply(SET)));
    }

    @Test
    void nameSortIgnoresDistanceEntirely() {
        PlayerQuery q = new PlayerQuery(0, 0, 0, -1, 0, PlayerQuery.SORT_NAME);
        assertEquals(List.of("alice", "bob", "carol", "dave"), names(q.apply(SET)));

        PlayerQuery limited = new PlayerQuery(0, 0, 0, -1, 2, PlayerQuery.SORT_NAME);
        assertEquals(List.of("alice", "bob"), names(limited.apply(SET)));
    }

    @Test
    void theOriginMovesWhatCountsAsNear() {
        // Measured from (0,0,10) the order reverses: dave 0, carol 7, alice/bob 9.
        PlayerQuery q = new PlayerQuery(0, 0, 10, -1, 0, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("dave", "carol", "alice", "bob"), names(q.apply(SET)));

        PlayerQuery near = new PlayerQuery(0, 0, 10, 1, 0, PlayerQuery.SORT_DISTANCE);
        assertEquals(List.of("dave"), names(near.apply(SET)));
    }

    @Test
    void aDefaultQuerySelectsEverythingNearestFirst() {
        assertEquals(List.of("alice", "bob", "carol", "dave"), names(PlayerQuery.all().apply(SET)));
        assertEquals(List.of(), names(PlayerQuery.all().apply(List.of())));
    }
}
