package com.jhuanglululu.billboard.placement;

import com.jhuanglululu.billboard.data.Placement;
import java.util.Set;

/**
 * Creates, re-targets, and stops running animation instances on behalf of
 * {@link ProximityController}. The real implementation builds an {@code AnimationInstance}
 * plus a packet renderer and registers it with the scheduler; tests supply a recorder.
 * {@code H} is an opaque handle to a running instance.
 */
public interface InstanceLifecycle<H> {

    /** Start an instance for {@code placement} with the given initial viewers; returns its handle. */
    H start(Placement placement, Set<ViewerPosition> viewers);

    /** Update the viewer set of a running instance (players who joined/left the audience). */
    void setViewers(H handle, Set<ViewerPosition> viewers);

    /** Stop and clean up a running instance. */
    void stop(H handle);
}
