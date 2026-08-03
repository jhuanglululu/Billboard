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

        Map<String, String> effective =
                Env.effective(animationLayer, placement(placementLayer), "alice");

        assertEquals("winter", effective.get("theme"), "an animation-only key survives");
        assertEquals("2", effective.get("speed"), "the placement layer wins over the animation");
        assertEquals("hello", effective.get("caption"), "a placement-only key survives");
    }

    @Test
    void builtInsAreWrittenLastAndCannotBeShadowed() {
        // The command rejects bb.* keys, but a hand-edited data file could still carry one; the
        // merge has to make that harmless rather than trusting the command to be the only writer.
        Map<String, String> animationLayer = Map.of("bb.animation", "not-this");
        Map<String, String> placementLayer = Map.of("bb.x", "-999", "bb.player", "mallory");

        Map<String, String> effective =
                Env.effective(animationLayer, placement(placementLayer), "alice");

        assertEquals("demo", effective.get("bb.animation"));
        assertEquals("10.5", effective.get("bb.x"));
        assertEquals("alice", effective.get("bb.player"));
    }

    @Test
    void everyBuiltInIsPresentWithThePlacementsOwnGeometry() {
        Map<String, String> effective = Env.effective(Map.of(),
                placement(Map.of(Env.TYPE, "per_player")), "alice");

        assertEquals("demo", effective.get("bb.animation"));
        assertEquals("lobby", effective.get("bb.id"));
        assertEquals("per_player", effective.get("bb.type"));
        assertEquals("10.5", effective.get("bb.x"));
        assertEquals("64.0", effective.get("bb.y"));
        assertEquals("-20.0", effective.get("bb.z"));
        assertEquals("90.0", effective.get("bb.yaw"));
        assertEquals("-22.5", effective.get("bb.pitch"));
        assertEquals("180.0", effective.get("bb.roll"));
        assertEquals("alice", effective.get("bb.player"));
    }

    @Test
    void aSharedInstanceGetsNoPlayerKeyAtAll() {
        // Not an empty string: "absent" is a state the guest can test, and inventing a name for
        // an instance that has no owner would be a lie every viewer could read.
        Map<String, String> effective = Env.effective(Map.of(), placement(Map.of()), null);
        assertFalse(effective.containsKey("bb.player"), effective.toString());
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
    void reservedKeysAreRejectedWhateverTheirCase() {
        for (String reserved : List.of("bb.player", "bb.", "BB.x", "Bb.anything")) {
            assertTrue(Env.rejectKey(reserved).isPresent(), reserved + " must be rejected");
        }
        assertTrue(Env.rejectKey("").isPresent(), "an empty key must be rejected");
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
