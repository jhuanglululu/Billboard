package com.jhuanglululu.billboard.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes a selected player list into the blob {@code players_read} writes into guest memory.
 *
 * <h2>Wire format (little-endian throughout)</h2>
 *
 * <pre>
 * u32 count
 * per player:
 *   u32 name_len
 *   name_len bytes  UTF-8 account name
 *   f64 x, f64 y, f64 z          feet, placement-local
 *   f64 eye_height
 *   f64 yaw, f64 pitch           placement-local, vanilla conventions
 * </pre>
 *
 * <p>Unlike the engine's environ blob, an empty list still writes its {@code count} — the pair is
 * {@code players_len}/{@code players_read} rather than a "zero means nothing" idiom, and a guest
 * that allocated four bytes and reads a zero count needs no special case.
 *
 * <p>Names are variable-length, which is why the count leads and each name carries its own length:
 * the guest walks the blob once and never has to know a maximum name length.
 */
public final class PlayerBlob {

    private PlayerBlob() {}

    /** The six {@code f64}s each player carries after its name. */
    private static final int DOUBLES_PER_PLAYER = 6;

    /** The blob for {@code players}, in the order given (the query already decided it). */
    public static byte[] pack(List<PlayerView> players) {
        byte[][] names = new byte[players.size()][];
        int size = 4;
        for (int i = 0; i < players.size(); i++) {
            names[i] = players.get(i).name().getBytes(StandardCharsets.UTF_8);
            size += 4 + names[i].length + DOUBLES_PER_PLAYER * 8;
        }
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(players.size());
        for (int i = 0; i < players.size(); i++) {
            b.putInt(names[i].length);
            b.put(names[i]);
            for (double v : players.get(i).values()) {
                b.putDouble(v);
            }
        }
        return b.array();
    }
}
