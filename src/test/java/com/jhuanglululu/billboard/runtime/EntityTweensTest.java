package com.jhuanglululu.billboard.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The host tween subsystem: step values, landing exactly on the target, replace-not-stack, and the
 * two cancellation paths. All expectations are hand-computed from the linear rule
 * {@code from + (to - from) * i / n}.
 */
class EntityTweensTest {

    private static final double EPS = 1e-9;

    private static EntityTweens.Update only(List<EntityTweens.Update> updates) {
        assertEquals(1, updates.size(), "expected exactly one update but got " + updates.size());
        return updates.getFirst();
    }

    @Test
    void stepsLinearlyAndLandsExactlyOnTheTarget() {
        EntityTweens tweens = new EntityTweens();
        // 0 -> 10 over 4 ticks: 2.5, 5.0, 7.5, 10.0.
        tweens.start(7, EntityTweens.Attribute.POSITION, 0,
                new double[] {0, 0, 0}, new double[] {10, 20, -4}, 4);

        assertArrayEquals(new double[] {2.5, 5.0, -1.0}, only(tweens.advance()).values(), EPS);
        assertArrayEquals(new double[] {5.0, 10.0, -2.0}, only(tweens.advance()).values(), EPS);
        assertArrayEquals(new double[] {7.5, 15.0, -3.0}, only(tweens.advance()).values(), EPS);

        EntityTweens.Update last = only(tweens.advance());
        assertArrayEquals(new double[] {10.0, 20.0, -4.0}, last.values(), EPS);
        assertTrue(last.finished(), "the update that reaches the target must be marked finished");
        // Finished tweens are dropped, so the target is emitted exactly once.
        assertEquals(List.of(), tweens.advance());
        assertEquals(0, tweens.size());
    }

    @Test
    void oneTickTweenLandsImmediately() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {90}, 1);

        EntityTweens.Update update = only(tweens.advance());
        assertArrayEquals(new double[] {90.0}, update.values(), EPS);
        assertTrue(update.finished());
        assertEquals(0, tweens.size());
    }

    @Test
    void aNewSetReplacesTheRunningTweenOnTheSameAttribute() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {100}, 10);
        assertArrayEquals(new double[] {10.0}, only(tweens.advance()).values(), EPS);

        // Replaced mid-flight, starting from where it had got to: 10 -> 20 over 2 ticks.
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {10}, new double[] {20}, 2);
        assertEquals(1, tweens.size(), "the replaced tween must not still be running");
        assertArrayEquals(new double[] {15.0}, only(tweens.advance()).values(), EPS);
        assertArrayEquals(new double[] {20.0}, only(tweens.advance()).values(), EPS);
        assertEquals(0, tweens.size());
    }

    @Test
    void poseTweensAreIndependentPerPart() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(3, EntityTweens.Attribute.POSE, 0,
                new double[] {0, 0, 0}, new double[] {2, 0, 0}, 2);
        tweens.start(3, EntityTweens.Attribute.POSE, 3,
                new double[] {0, 0, 0}, new double[] {0, 0, 8}, 2);
        assertEquals(2, tweens.size());

        List<EntityTweens.Update> first = tweens.advance();
        assertEquals(2, first.size());
        // Insertion order: part 0 then part 3.
        assertEquals(0, first.get(0).part());
        assertArrayEquals(new double[] {1.0, 0.0, 0.0}, first.get(0).values(), EPS);
        assertEquals(3, first.get(1).part());
        assertArrayEquals(new double[] {0.0, 0.0, 4.0}, first.get(1).values(), EPS);
    }

    @Test
    void positionAndYawOfOneEntityCoexist() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(5, EntityTweens.Attribute.POSITION, 0,
                new double[] {0, 0, 0}, new double[] {4, 0, 0}, 2);
        tweens.start(5, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {180}, 2);

        assertTrue(tweens.isTweening(5, EntityTweens.Attribute.POSITION, 0));
        assertTrue(tweens.isTweening(5, EntityTweens.Attribute.YAW, 0));
        assertEquals(2, tweens.advance().size());
    }

    @Test
    void cancelDropsOnlyThatEntitysTweens() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(1, EntityTweens.Attribute.POSITION, 0,
                new double[] {0, 0, 0}, new double[] {9, 9, 9}, 3);
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {90}, 3);
        tweens.start(2, EntityTweens.Attribute.POSITION, 0,
                new double[] {0, 0, 0}, new double[] {1, 1, 1}, 3);

        tweens.cancel(1);

        assertEquals(1, tweens.size());
        assertFalse(tweens.isTweening(1, EntityTweens.Attribute.POSITION, 0));
        assertEquals(2, only(tweens.advance()).entityId());
    }

    @Test
    void clearDropsEverything() {
        EntityTweens tweens = new EntityTweens();
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {90}, 5);
        tweens.start(2, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {90}, 5);

        tweens.clear();

        assertEquals(0, tweens.size());
        assertEquals(List.of(), tweens.advance());
    }

    @Test
    void survivesAWorkerAndTheMainThreadTouchingItAtOnce() throws Exception {
        // The real boundary: a worker starts and advances tweens while ticking its instance, and the
        // main thread cancels/clears them from RunningInstance.stop()/restart() — which the
        // scheduler can call while that tick is still in flight. An unsynchronized LinkedHashMap
        // corrupts (or throws) under exactly this pattern.
        EntityTweens tweens = new EntityTweens();
        int rounds = 4000;
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    tweens.start(i % 8, EntityTweens.Attribute.POSITION, 0,
                            new double[] {0, 0, 0}, new double[] {i, i, i}, 5);
                    tweens.start(i % 8, EntityTweens.Attribute.YAW, 0,
                            new double[] {0}, new double[] {i}, 5);
                    for (EntityTweens.Update u : tweens.advance()) {
                        // Touch the payload the way the renderer does.
                        assertEquals(u.values().length, u.values().length);
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread main = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    tweens.cancel(i % 8);
                    if (i % 50 == 0) {
                        tweens.clear();
                    }
                    tweens.size();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        worker.start();
        main.start();
        worker.join(30_000);
        main.join(30_000);
        assertFalse(worker.isAlive() || main.isAlive(), "threads should have finished");
        if (failure.get() != null) {
            throw new AssertionError("concurrent access failed", failure.get());
        }
        // And it is still usable afterwards.
        tweens.clear();
        tweens.start(1, EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {90}, 1);
        assertEquals(1, tweens.advance().size());
    }

    @Test
    void nonPositiveDurationIsAProgrammingError() {
        EntityTweens tweens = new EntityTweens();
        // The renderer must send instantly instead of registering a zero-tick tween.
        assertThrows(IllegalArgumentException.class, () -> tweens.start(1,
                EntityTweens.Attribute.YAW, 0, new double[] {0}, new double[] {1}, 0));
    }
}
