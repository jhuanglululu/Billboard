package com.jhuanglululu.billboard.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Renderer} that records every call in order, for test assertions. Calls are flattened
 * into {@link Event}s so a test can assert on a compact trace instead of a mock's call log.
 */
final class RecordingRenderer implements Renderer {

    @SuppressWarnings("ArrayRecordComponent") // test data holder; not compared by equals
    record Event(String kind, int id, double[] nums, String text, long over) {}

    final List<Event> events = new ArrayList<>();
    int tweenTicks;

    private void add(String kind, int id, double[] nums, String text, long over) {
        events.add(new Event(kind, id, nums, text, over));
    }

    @Override
    public void spawnBlockDisplay(int id, String blockState, double x, double y, double z) {
        add("spawn", id, new double[] {x, y, z}, blockState, 0);
    }

    @Override
    public void spawnItemDisplay(int id, String item, double x, double y, double z) {
        add("spawnItemDisplay", id, new double[] {x, y, z}, item, 0);
    }

    @Override
    public void spawnTextDisplay(int id, String text, double x, double y, double z) {
        add("spawnTextDisplay", id, new double[] {x, y, z}, text, 0);
    }

    @Override
    public void spawnArmorStand(int id, double x, double y, double z) {
        add("spawnArmorStand", id, new double[] {x, y, z}, null, 0);
    }

    @Override
    public void spawnItem(int id, String item, double x, double y, double z) {
        add("spawnItem", id, new double[] {x, y, z}, item, 0);
    }

    @Override
    public void setPosition(int id, double x, double y, double z, long overTicks) {
        add("setPosition", id, new double[] {x, y, z}, null, overTicks);
    }

    @Override
    public void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks) {
        add("setRotation", id, new double[] {qx, qy, qz, qw}, null, overTicks);
    }

    @Override
    public void setScale(int id, double sx, double sy, double sz, long overTicks) {
        add("setScale", id, new double[] {sx, sy, sz}, null, overTicks);
    }

    @Override
    public void setBlock(int id, String blockState) {
        add("setBlock", id, null, blockState, 0);
    }

    @Override
    public void setItem(int id, String item) {
        add("setItem", id, null, item, 0);
    }

    @Override
    public void setDisplayContext(int id, int context) {
        add("setDisplayContext", id, new double[] {context}, null, 0);
    }

    @Override
    public void setBillboardMode(int id, int mode) {
        add("setBillboardMode", id, new double[] {mode}, null, 0);
    }

    @Override
    public void setText(int id, String text) {
        add("setText", id, null, text, 0);
    }

    @Override
    public void setTextBackground(int id, long argb) {
        add("setTextBackground", id, null, null, argb);
    }

    @Override
    public void setTextOpacity(int id, long opacity) {
        add("setTextOpacity", id, null, null, opacity);
    }

    @Override
    public void setLineWidth(int id, long width) {
        add("setLineWidth", id, null, null, width);
    }

    @Override
    public void setTextFlags(int id, int flags) {
        add("setTextFlags", id, new double[] {flags}, null, 0);
    }

    @Override
    public void setPose(int id, int part, double xDeg, double yDeg, double zDeg, long overTicks) {
        add("setPose", id, new double[] {part, xDeg, yDeg, zDeg}, null, overTicks);
    }

    @Override
    public void setEquipment(int id, int slot, String item) {
        add("setEquipment", id, new double[] {slot}, item, 0);
    }

    @Override
    public void setStandFlags(int id, int flags) {
        add("setStandFlags", id, new double[] {flags}, null, 0);
    }

    @Override
    public void setYaw(int id, double yawDegrees, long overTicks) {
        add("setYaw", id, new double[] {yawDegrees}, null, overTicks);
    }

    @Override
    public void despawn(int id) {
        add("despawn", id, null, null, 0);
    }

    @Override
    public void playSound(String name, double x, double y, double z, int category, double volume,
            double pitch) {
        add("playSound", 0, new double[] {x, y, z, category, volume, pitch}, name, 0);
    }

    @Override
    public void emitParticle(ParticleSpec.Emission e) {
        add("emitParticle", e.count(), new double[] {e.x(), e.y(), e.z(),
                e.offsetX(), e.offsetY(), e.offsetZ(), e.speed()}, describe(e.particle()), 0);
    }

    @Override
    public void tickTweens() {
        tweenTicks++;
    }

    /** A particle rendered as {@code kind(a,b,…)} so a test can assert on one string. */
    private static String describe(ParticleSpec spec) {
        return switch (spec) {
            case ParticleSpec.Named n -> "named(" + n.name() + ")";
            case ParticleSpec.Dust d -> format("dust", d.red(), d.green(), d.blue(), d.size());
            case ParticleSpec.DustTransition d -> format("dustTransition", d.fromRed(),
                    d.fromGreen(), d.fromBlue(), d.toRed(), d.toGreen(), d.toBlue(), d.size());
            case ParticleSpec.Block b -> "block(" + b.blockState() + ")";
            case ParticleSpec.Item i -> "item(" + i.item() + ")";
        };
    }

    /** Full precision on purpose: the determinism assertions compare these strings. */
    private static String format(String kind, double... values) {
        StringBuilder sb = new StringBuilder(kind).append('(');
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append(values[i]);
        }
        return sb.append(')').toString();
    }

    /** A full-precision signature of one call, for comparing whole traces. */
    String signature(Event e) {
        return e.kind() + " id=" + e.id() + " over=" + e.over() + " text=" + e.text()
                + " nums=" + java.util.Arrays.toString(e.nums());
    }

    /** Every recorded call as a signature, in order. */
    List<String> trace() {
        return events.stream().map(this::signature).toList();
    }

    long count(String kind) {
        return events.stream().filter(e -> e.kind().equals(kind)).count();
    }

    /** The events of one kind, in order. */
    List<Event> of(String kind) {
        return events.stream().filter(e -> e.kind().equals(kind)).toList();
    }

    /** The first event of one kind, for single-call assertions. */
    Event first(String kind) {
        return of(kind).getFirst();
    }
}
