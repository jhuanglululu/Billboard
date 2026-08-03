package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code players_read} blob, byte for byte.
 *
 * <p>The expected arrays below are written out by hand, not produced by a {@link java.nio.ByteBuffer}
 * — a test that packed the bytes the same way the packer does would agree with it about a wrong
 * endianness or a wrong field order. Every {@code f64} here is a value whose IEEE-754 bit pattern
 * is short enough to write down and check by eye:
 *
 * <pre>
 *  1.0 = 0x3FF0000000000000    2.0 = 0x4000000000000000
 * -4.0 = 0xC010000000000000    0.5 = 0x3FE0000000000000
 * 90.0 = 0x4056800000000000
 * </pre>
 */
class PlayerBlobTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void onePlayerPacksExactlyAsTheAbiSpecifies() {
        PlayerView bob = new PlayerView("Bob", 1.0, 2.0, -4.0, 0.5, 90.0, -4.0);

        assertArrayEquals(bytes(
                0x01, 0x00, 0x00, 0x00,                                 // u32 count = 1
                0x03, 0x00, 0x00, 0x00,                                 // u32 name_len = 3
                0x42, 0x6F, 0x62,                                       // "Bob"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F,         // x =  1.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40,         // y =  2.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0xC0,         // z = -4.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE0, 0x3F,         // eye_height = 0.5
                0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x56, 0x40,         // yaw = 90.0
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0xC0),        // pitch = -4.0
                PlayerBlob.pack(List.of(bob)));
    }

    @Test
    void anEmptyListStillWritesItsCount() {
        // Deliberately not the engine's "zero length means nothing" idiom: a guest that allocated
        // four bytes and reads a zero count needs no special case for "nobody is here".
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x00), PlayerBlob.pack(List.of()));
    }

    @Test
    void playersFollowOneAnotherInTheOrderGiven() {
        byte[] blob = PlayerBlob.pack(List.of(
                new PlayerView("A", 1.0, 1.0, 1.0, 0.5, 0.0, 0.0),
                new PlayerView("B", 2.0, 2.0, 2.0, 0.5, 0.0, 0.0)));

        assertEquals(4 + (4 + 1 + 48) * 2, blob.length);
        assertArrayEquals(bytes(0x02, 0x00, 0x00, 0x00), java.util.Arrays.copyOfRange(blob, 0, 4));
        assertEquals('A', blob[8]);
        // 4 count + 4 len + 1 name + 48 doubles = 57, then the second record's length prefix.
        assertEquals('B', blob[57 + 4]);
    }

    @Test
    void nameLengthIsBytesNotCharacters() {
        // A three-character name whose UTF-8 is nine bytes: a guest reading a character count
        // would walk straight off the end of the record.
        byte[] blob = PlayerBlob.pack(List.of(
                new PlayerView("日本語", 0, 0, 0, 0, 0, 0)));
        assertArrayEquals(bytes(0x09, 0x00, 0x00, 0x00), java.util.Arrays.copyOfRange(blob, 4, 8));
        assertEquals(4 + 4 + 9 + 48, blob.length);
    }
}
