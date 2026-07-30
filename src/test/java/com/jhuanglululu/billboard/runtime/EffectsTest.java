package com.jhuanglululu.billboard.runtime;

import static com.jhuanglululu.billboard.runtime.BillboardWasm.EMIT_PARTICLE;
import static com.jhuanglululu.billboard.runtime.BillboardWasm.EMIT_PARTICLE_BLOCK;
import static com.jhuanglululu.billboard.runtime.BillboardWasm.EMIT_PARTICLE_DUST;
import static com.jhuanglululu.billboard.runtime.BillboardWasm.EMIT_PARTICLE_DUST_TRANSITION;
import static com.jhuanglululu.billboard.runtime.BillboardWasm.EMIT_PARTICLE_ITEM;
import static com.jhuanglululu.billboard.runtime.BillboardWasm.PLAY_SOUND;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * Sound and the five particle shapes through real WASM. Arguments must survive the ABI in the right
 * order and land on the renderer verbatim — the tail every {@code emit_particle_*} import shares is
 * decoded from a different argument offset per variant, which is exactly where an off-by-one hides.
 */
class EffectsTest {

    /** The shared tail: {@code x, y, z, count, ox, oy, oz, speed}. */
    private static P tail(P p) {
        return p.xyz(1, 2, 3).i32(20).xyz(0.5, 0.25, 0.125).f64(0.1);
    }

    @Test
    void soundPassesEveryFieldAndIsNeverValidated() {
        RecordingRenderer renderer = new RecordingRenderer();
        // "ABC" as the sound id — deliberately not a real sound: sounds are never validated.
        P main = new P().i32(0).i32(3).xyz(1, 2, 3).i32(9).f64(2.0).f64(1.5).call(PLAY_SOUND);
        BillboardRun.rendered(main, renderer).assertFinished();

        RecordingRenderer.Event sound = renderer.first("playSound");
        assertEquals("ABC", sound.text());
        assertArrayEquals(new double[] {1, 2, 3, 9, 2.0, 1.5}, sound.nums());
    }

    @Test
    void soundCategoryOutOfRangeKills() {
        // The id is never checked, but a bad category is a guest bug, not resource-pack territory.
        P main = new P().i32(0).i32(3).xyz(0, 0, 0).i32(10).f64(1).f64(1).call(PLAY_SOUND);
        BillboardRun.run(main).assertKilled("play_sound", "sound category 10 out of range 0..9");
    }

    @Test
    void namedParticleCarriesTheSharedTail() {
        RecordingRenderer renderer = new RecordingRenderer();
        BillboardRun.rendered(tail(new P().i32(0).i32(2)).call(EMIT_PARTICLE), renderer).assertFinished();

        RecordingRenderer.Event p = renderer.first("emitParticle");
        assertEquals("named(AB)", p.text());
        assertEquals(20, p.id()); // the recorder stores count in the id slot
        assertArrayEquals(new double[] {1, 2, 3, 0.5, 0.25, 0.125, 0.1}, p.nums());
    }

    @Test
    void dustCarriesItsColourAndSizeBeforeTheTail() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = tail(new P().f64(0.25).f64(0.5).f64(0.75).f64(1.5)).call(EMIT_PARTICLE_DUST);
        BillboardRun.rendered(main, renderer).assertFinished();

        RecordingRenderer.Event p = renderer.first("emitParticle");
        assertEquals("dust(0.25,0.5,0.75,1.5)", p.text());
        assertArrayEquals(new double[] {1, 2, 3, 0.5, 0.25, 0.125, 0.1}, p.nums());
    }

    @Test
    void dustTransitionCarriesBothColours() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = tail(new P().f64(1).f64(0).f64(0).f64(0).f64(0).f64(1).f64(2.0))
                .call(EMIT_PARTICLE_DUST_TRANSITION);
        BillboardRun.rendered(main, renderer).assertFinished();

        assertEquals("dustTransition(1.0,0.0,0.0,0.0,0.0,1.0,2.0)",
                renderer.first("emitParticle").text());
    }

    @Test
    void blockAndItemParticlesCarryTheirString() {
        RecordingRenderer renderer = new RecordingRenderer();
        P main = tail(new P().i32(0).i32(1)).call(EMIT_PARTICLE_BLOCK)
                .append(tail(new P().i32(1).i32(1))).call(EMIT_PARTICLE_ITEM);
        BillboardRun.rendered(main, renderer).assertFinished();

        assertEquals("block(A)", renderer.of("emitParticle").get(0).text());
        assertEquals("item(B)", renderer.of("emitParticle").get(1).text());
    }

    @Test
    void tweensAreSteppedOncePerTickFromTheRenderPath() {
        // RunningInstance ticks the renderer after the interpreter; the runtime itself never does,
        // so a plain AnimationInstance tick must not advance tweens.
        RecordingRenderer renderer = new RecordingRenderer();
        BillboardRun.rendered(new P().sleep(2), renderer);
        assertEquals(0, renderer.tweenTicks);
    }
}
