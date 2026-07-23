package com.jhuanglululu.billboard.runtime;

import java.util.ArrayList;
import java.util.List;

/** A {@link Renderer} that records every call in order, for test assertions. */
final class RecordingRenderer implements Renderer {

    @SuppressWarnings("ArrayRecordComponent") // test data holder; not compared by equals
    record Event(String kind, int id, double[] nums, String block, long over) {}

    final List<Event> events = new ArrayList<>();

    @Override
    public void spawnBlockDisplay(int id, String blockState, double x, double y, double z) {
        events.add(new Event("spawn", id, new double[] {x, y, z}, blockState, 0));
    }

    @Override
    public void setPosition(int id, double x, double y, double z, long overTicks) {
        events.add(new Event("setPosition", id, new double[] {x, y, z}, null, overTicks));
    }

    @Override
    public void setRotation(int id, double qx, double qy, double qz, double qw, long overTicks) {
        events.add(new Event("setRotation", id, new double[] {qx, qy, qz, qw}, null, overTicks));
    }

    @Override
    public void setScale(int id, double sx, double sy, double sz, long overTicks) {
        events.add(new Event("setScale", id, new double[] {sx, sy, sz}, null, overTicks));
    }

    @Override
    public void setBlock(int id, String blockState) {
        events.add(new Event("setBlock", id, null, blockState, 0));
    }

    @Override
    public void despawn(int id) {
        events.add(new Event("despawn", id, null, null, 0));
    }

    long count(String kind) {
        return events.stream().filter(e -> e.kind().equals(kind)).count();
    }
}
