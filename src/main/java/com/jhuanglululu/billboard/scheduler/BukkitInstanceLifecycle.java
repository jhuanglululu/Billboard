package com.jhuanglululu.billboard.scheduler;

import com.jhuanglululu.billboard.data.Env;
import com.jhuanglululu.billboard.data.InstanceType;
import com.jhuanglululu.billboard.data.Placement;
import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.billboard.placement.InstanceLifecycle;
import com.jhuanglululu.billboard.placement.ViewerPosition;
import com.jhuanglululu.billboard.runtime.BlockStateValidator;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.wasm.Module;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Bridges {@link com.jhuanglululu.billboard.placement.ProximityController} to real instances:
 * builds a {@link RunningInstance} (interpreter + packet renderer) on {@code start}, registers
 * it with the {@link AnimationScheduler}, and re-resolves viewer UUIDs to online players as the
 * audience changes.
 */
public final class BukkitInstanceLifecycle implements InstanceLifecycle<RunningInstance> {

    private final Server server;
    private final AnimationScheduler scheduler;
    private final Function<String, Module> moduleLookup;
    private final BlockStateValidator validator;
    private final ContentValidator content;
    private final LongSupplier memoryCapBytes;
    private final Function<String, Map<String, String>> animationEnv;

    /**
     * @param animationEnv the animation-level env layer by animation name; merged with the
     *                     placement's own and the host built-ins at start, and frozen for the run
     */
    public BukkitInstanceLifecycle(Server server, AnimationScheduler scheduler,
            Function<String, Module> moduleLookup, BlockStateValidator validator,
            ContentValidator content, LongSupplier memoryCapBytes,
            Function<String, Map<String, String>> animationEnv) {
        this.server = server;
        this.scheduler = scheduler;
        this.moduleLookup = moduleLookup;
        this.validator = validator;
        this.content = content;
        this.memoryCapBytes = memoryCapBytes;
        this.animationEnv = animationEnv;
    }

    @Override
    public RunningInstance start(Placement placement, Set<ViewerPosition> viewers) {
        Module module = moduleLookup.apply(placement.animation());
        if (module == null) {
            throw new IllegalStateException("no loaded module for animation " + placement.animation());
        }
        Map<String, String> layer = animationEnv.apply(placement.animation());
        InstanceType type = Env.typeOf(layer, placement);
        // The environ describes the placement, not the audience: a per-player instance's is
        // identical to a shared one's. Only the owner label (seed, stats, log routing) differs.
        RunningInstance instance = new RunningInstance(placement, ownerLabel(type, viewers),
                module, validator, content, memoryCapBytes.getAsLong(),
                Env.effective(layer, placement));
        instance.setViewers(resolve(viewers));
        scheduler.add(instance);
        return instance;
    }

    @Override
    public void setViewers(RunningInstance instance, Set<ViewerPosition> viewers) {
        instance.setViewers(resolve(viewers));
    }

    @Override
    public void stop(RunningInstance instance) {
        scheduler.removeAndStop(instance);
    }

    private Set<Player> resolve(Set<ViewerPosition> viewers) {
        Set<Player> out = new HashSet<>();
        for (ViewerPosition v : viewers) {
            Player p = server.getPlayer(v.uuid());
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static String ownerLabel(InstanceType type, Set<ViewerPosition> viewers) {
        if (type == InstanceType.PER_PLAYER && viewers.size() == 1) {
            return viewers.iterator().next().name();
        }
        return MessageFormats.EVERYONE;
    }
}
