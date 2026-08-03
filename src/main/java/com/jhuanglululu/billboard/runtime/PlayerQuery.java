package com.jhuanglululu.billboard.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The filter/sort/limit the guest asks {@code players_len}/{@code players_read} for, and the
 * host-side application of it.
 *
 * <p><b>Why host-side.</b> A busy server's viewer list can be long, and copying all of it into
 * guest memory just so the guest can throw most of it away costs the instance's memory cap and its
 * instruction budget. Narrowing before the blob is written means "the four nearest players" copies
 * four players.
 *
 * <h2>Wire format (packed, 40 bytes, little-endian)</h2>
 *
 * <pre>
 * offset 0   f64 origin_x   measure distance from here — placement-local, like everything else
 * offset 8   f64 origin_y
 * offset 16  f64 origin_z
 * offset 24  f64 range      max distance from the origin; negative = unlimited
 * offset 32  i32 limit      max results; &lt;= 0 = unlimited
 * offset 36  i32 sort       0 = distance ascending, 1 = name ascending
 * </pre>
 *
 * <p>The same pointer rides both calls of the len/fill pair. There is no blocking point between
 * them, so no other task can run and no snapshot can be swapped in between — the length the guest
 * allocated for is the length it gets.
 *
 * @param originX the distance origin's x, placement-local
 * @param originY the distance origin's y
 * @param originZ the distance origin's z
 * @param range   the inclusive maximum distance from that origin; negative means unlimited
 * @param limit   the maximum number of results; zero or negative means unlimited
 * @param sort    {@link #SORT_DISTANCE} or {@link #SORT_NAME}; anything else sorts by distance
 */
public record PlayerQuery(double originX, double originY, double originZ, double range,
        int limit, int sort) {

    /** The packed size of the struct in guest memory. */
    public static final int BYTES = 40;

    /** Nearest first, ties broken by name so the order is total and deterministic. */
    public static final int SORT_DISTANCE = 0;

    /** Account name ascending, by plain string order. */
    public static final int SORT_NAME = 1;

    /** Everything, nearest to the placement origin first — what a bare {@code players()} sends. */
    public static PlayerQuery all() {
        return new PlayerQuery(0, 0, 0, -1, 0, SORT_DISTANCE);
    }

    /**
     * Reads the struct out of {@code packed}, which must be the {@value #BYTES} bytes at the
     * guest's query pointer.
     */
    public static PlayerQuery parse(byte[] packed) {
        if (packed.length < BYTES) {
            throw new IllegalArgumentException("a player query is " + BYTES + " bytes, got "
                    + packed.length);
        }
        ByteBuffer b = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        return new PlayerQuery(b.getDouble(0), b.getDouble(8), b.getDouble(16), b.getDouble(24),
                b.getInt(32), b.getInt(36));
    }

    /**
     * The players this query selects, in its order: range filter, then sort, then limit. Sorting
     * before limiting is what makes {@code limit} mean "the nearest four" rather than "whichever
     * four came first".
     */
    public List<PlayerView> apply(List<PlayerView> players) {
        List<PlayerView> out = new ArrayList<>(players.size());
        for (PlayerView p : players) {
            if (range < 0 || distanceSquared(p) <= range * range) {
                out.add(p);
            }
        }
        out.sort(comparator());
        if (limit > 0 && out.size() > limit) {
            return List.copyOf(out.subList(0, limit));
        }
        return List.copyOf(out);
    }

    private Comparator<PlayerView> comparator() {
        if (sort == SORT_NAME) {
            return Comparator.comparing(PlayerView::name);
        }
        return Comparator.comparingDouble(this::distanceSquared).thenComparing(PlayerView::name);
    }

    /** Squared distance from the query origin — squared, because only the ordering is used. */
    private double distanceSquared(PlayerView p) {
        double dx = p.x() - originX;
        double dy = p.y() - originY;
        double dz = p.z() - originZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
