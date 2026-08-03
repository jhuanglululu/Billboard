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
import java.util.function.IntSupplier;
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
    private final IntSupplier taskStackBytes;

    /**
     * @param animationEnv   the animation-level env layer by animation name; merged with the
     *                       placement's own and the host built-ins at start, and frozen for the
     *                       run
     * @param taskStackBytes the configured per-spawned-task stack size
     */
    public BukkitInstanceLifecycle(Server server, AnimationScheduler scheduler,
            Function<String, Module> moduleLookup, BlockStateValidator validator,
            ContentValidator content, LongSupplier memoryCapBytes,
            Function<String, Map<String, String>> animationEnv, IntSupplier taskStackBytes) {
        this.server = server;
        this.scheduler = scheduler;
        this.moduleLookup = moduleLookup;
        this.validator = validator;
        this.content = content;
        this.memoryCapBytes = memoryCapBytes;
        this.animationEnv = animationEnv;
        this.taskStackBytes = taskStackBytes;
    }

    @Override
    public RunningInstance start(Placement placement, Set<ViewerPosition> viewers) {
        Module module = moduleLookup.apply(placement.animation());
        if (module == null) {
            throw new IllegalStateException("no loaded module for animation " + placement.animation());
        }
        String owner = ownerLabel(placement, viewers);
        // bb.player names a real account, so the shared instance's EVERYONE placeholder is not it.
        String envOwner = placement.type() == InstanceType.PER_PLAYER ? owner : null;
        RunningInstance instance = new RunningInstance(placement, owner,
                module, validator, content, memoryCapBytes.getAsLong(),
                Env.effective(animationEnv.apply(placement.animation()), placement, envOwner),
                taskStackBytes.getAsInt());
        instance.setViewers(resolve(viewers));
        instance.setPlayerSnapshot(viewers);
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

    private static String ownerLabel(Placement placement, Set<ViewerPosition> viewers) {
        if (placement.type() == InstanceType.PER_PLAYER && viewers.size() == 1) {
            return viewers.iterator().next().name();
        }
        return MessageFormats.EVERYONE;
    }
}
