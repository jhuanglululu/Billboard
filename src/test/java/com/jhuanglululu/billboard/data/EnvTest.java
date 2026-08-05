package com.jhuanglululu.billboard.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three env layers and the two rules the command enforces on them. This is where the
 * precedence order is actually pinned: everything else (the command, the lifecycle) just calls
 * {@link Env#effective}.
 */
class EnvTest {

    private static Placement placement(Map<String, String> env) {
        return new Placement("demo", "lobby", "world", 10.5, 64.0, -20.0,
                90.0, -22.5, 180.0, env, VisibilityMode.EVERYONE);
    }

    @Test
    void placementOverridesAnimationAndBuiltInsOverrideBoth() {
        Map<String, String> animationLayer = new LinkedHashMap<>();
        animationLayer.put("theme", "winter");     // only the animation sets it
        animationLayer.put("speed", "1");          // the placement overrides it
        Map<String, String> placementLayer = new LinkedHashMap<>();
        placementLayer.put("speed", "2");
        placementLayer.put("caption", "hello");    // only the placement sets it

        Map<String, String> effective = Env.effective(animationLayer, placement(placementLayer));

        assertEquals("winter", effective.get("theme"), "an animation-only key survives");
        assertEquals("2", effective.get("speed"), "the placement layer wins over the animation");
        assertEquals("hello", effective.get("caption"), "a placement-only key survives");
    }

    @Test
    void builtInsAreWrittenLastAndCannotBeShadowed() {
        // The command rejects bb.* keys, but a hand-edited data file could still carry one; the
        // merge has to make that harmless rather than trusting the command to be the only writer.
        Map<String, String> animationLayer = Map.of("bb.z", "-999");
        Map<String, String> placementLayer = Map.of("bb.x", "-999", "bb.id", "elsewhere");

        Map<String, String> effective = Env.effective(animationLayer, placement(placementLayer));

        assertEquals("10.5", effective.get("bb.x"));
        assertEquals("-20.0", effective.get("bb.z"));
        assertEquals("lobby", effective.get("bb.id"));
    }

    @Test
    void everyBuiltInIsPresentWithThePlacementsOwnGeometry() {
        Map<String, String> effective =
                Env.effective(Map.of(), placement(Map.of(Env.TYPE, "per_player")));

        assertEquals("lobby", effective.get("bb.id"));
        assertEquals("10.5", effective.get("bb.x"));
        assertEquals("64.0", effective.get("bb.y"));
        assertEquals("-20.0", effective.get("bb.z"));
        assertEquals("90.0", effective.get("bb.yaw"));
        assertEquals("-22.5", effective.get("bb.pitch"));
        assertEquals("180.0", effective.get("bb.roll"));
        assertEquals("per_player", effective.get("bb.type"));
        assertEquals(Map.of("bb.id", "lobby", "bb.x", "10.5", "bb.y", "64.0", "bb.z", "-20.0",
                "bb.yaw", "90.0", "bb.pitch", "-22.5", "bb.roll", "180.0",
                "bb.type", "per_player"), effective,
                "the built-ins are exactly these eight — nothing else is published");
    }

    @Test
    void noInstanceGetsAPlayerIdentityNotEvenAPerPlayerOne() {
        // The environ describes the placement, not the audience: there is no bb.player key, so a
        // per-player instance's environ is byte-identical to a shared one's.
        assertFalse(Env.effective(Map.of(), placement(Map.of())).containsKey("bb.player"));
        Map<String, String> perPlayer =
                Env.effective(Map.of(), placement(Map.of(Env.TYPE, "per_player")));
        assertFalse(perPlayer.containsKey("bb.player"), perPlayer.toString());
    }

    @Test
    void theTypeBuiltInIsRewrittenCanonicallyEvenWhenTheUserLayerIsNonsense() {
        // A hand-edited file can say anything; the guest still reads one of the two tokens.
        assertEquals("shared",
                Env.effective(Map.of(), placement(Map.of(Env.TYPE, "sideways"))).get(Env.TYPE));
        assertEquals("per_player",
                Env.effective(Map.of(), placement(Map.of(Env.TYPE, "PER_PLAYER"))).get(Env.TYPE));
    }

    @Test
    void typeDefaultsToSharedWhenAbsentOrUnrecognised() {
        assertEquals(InstanceType.SHARED, Env.typeOf(Map.of()));
        assertEquals(InstanceType.SHARED, Env.typeOf(Map.of(Env.TYPE, "shared")));
        assertEquals(InstanceType.PER_PLAYER, Env.typeOf(Map.of(Env.TYPE, "per_player")));
        assertEquals(InstanceType.PER_PLAYER, Env.typeOf(Map.of(Env.TYPE, "PER_PLAYER")));
        assertEquals(InstanceType.SHARED, Env.typeOf(Map.of(Env.TYPE, "sideways")));
    }

    @Test
    void theTypeIsReadFromEitherUserLayer() {
        // bb.type is an env key like any other, so the animation layer may carry it — and the
        // placement layer still wins where both do.
        assertEquals(InstanceType.PER_PLAYER,
                Env.typeOf(Map.of(Env.TYPE, "per_player"), placement(Map.of())));
        assertEquals(InstanceType.SHARED,
                Env.typeOf(Map.of(Env.TYPE, "per_player"), placement(Map.of(Env.TYPE, "shared"))));
    }

    @Test
    void aPlacementSpawnedWithNoEnvIsShared() {
        // The spawn grammar no longer names a type: what it builds is a placement with an empty
        // env, and that must instantiate exactly as the old explicit "shared" did.
        Placement spawned = new Placement("demo", "lobby", "world", 1, 2, 3,
                0, 0, 0, Map.of(), VisibilityMode.EVERYONE);
        assertEquals(Map.of(), spawned.env());
        assertEquals(InstanceType.SHARED, spawned.type());
        assertEquals("shared", Env.effective(Map.of(), spawned).get(Env.TYPE));
    }

    @Test
    void reservedKeysAreRejectedWhateverTheirCase() {
        for (String reserved : List.of("bb.id", "bb.", "BB.x", "Bb.anything", "BB.TYPE")) {
            assertTrue(Env.rejectKey(reserved).isPresent(), reserved + " must be rejected");
        }
        assertTrue(Env.rejectKey("").isPresent(), "an empty key must be rejected");
        // bb.type is the one built-in the operator is meant to write — it is how a placement
        // becomes per-player now that spawn does not ask.
        assertTrue(Env.rejectKey(Env.TYPE).isEmpty(), "bb.type must be settable");
        for (String fine : List.of("bb", "b.b", "theme", "bbq.sauce", "abb.x")) {
            assertTrue(Env.rejectKey(fine).isEmpty(),
                    fine + " must be accepted but was: " + Env.rejectKey(fine).orElse(""));
        }
    }

    @Test
    void onlyTheTypeKeysValueIsValidated() {
        assertTrue(Env.rejectValue(Env.TYPE, "shared").isEmpty());
        assertTrue(Env.rejectValue(Env.TYPE, "per_player").isEmpty());
        assertTrue(Env.rejectValue(Env.TYPE, "sideways").isPresent(),
                "a bad type must be refused rather than silently reading as shared");
        // Every other key is opaque to the host, so there are no rules to enforce on it.
        assertTrue(Env.rejectValue("theme", "sideways").isEmpty());
        assertTrue(Env.rejectValue("caption", "").isEmpty());
    }

    @Test
    void thePlacementsTypeAccessorReadsTheEnvKey() {
        assertEquals(InstanceType.SHARED, placement(Map.of()).type());
        assertEquals(InstanceType.PER_PLAYER, placement(Map.of(Env.TYPE, "per_player")).type());
        assertEquals(InstanceType.PER_PLAYER,
                placement(Map.of()).withType(InstanceType.PER_PLAYER).type());
    }
}
