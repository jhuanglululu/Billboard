package com.jhuanglululu.billboard.scheduler;

import com.jhuanglululu.billboard.message.GuestOutput;
import com.jhuanglululu.billboard.runtime.ExitCode;
import com.jhuanglululu.billboard.runtime.TickResult;
import com.jhuanglululu.billboard.stats.CaptureControl;
import com.jhuanglululu.billboard.stats.CaptureOrchestrator;
import com.jhuanglululu.billboard.stats.CaptureReport;
import com.jhuanglululu.billboard.stats.PluginStats;
import com.jhuanglululu.wasmachine.runtime.WorkerPoolSizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.plugin.Plugin;

/**
 * Runs each live {@link RunningInstance}'s {@code tick} on a worker pool — never the main
 * thread — sized by {@link WorkerPoolSizer} as instances come and go. One instance is never
 * ticked on two workers at once (in-flight tracking). A worker executes the tick (packets go
 * out during it), then the {@link TickResult} and any buffered guest logs are handled back on
 * the main thread: finished/kept/repeat/errored per the exit-code contract, and an error
 * pauses the whole animation.
 */
public final class AnimationScheduler implements CaptureControl {

    private final Plugin plugin;
    private final WorkerPoolSizer sizer;
    private final int maxThreads;
    private final LongSupplier budget;
    private final GuestOutput guestOutput;
    private final ThreadPoolExecutor workers;
    // Capture windows ride this scheduler's existing per-tick pass: no second timer exists just
    // to notice that a measurement has finished.
    private final CaptureOrchestrator captures = new CaptureOrchestrator();

    private final Set<RunningInstance> instances = ConcurrentHashMap.newKeySet();
    private final Set<RunningInstance> inFlight = ConcurrentHashMap.newKeySet();

    private Consumer<RunningInstance> endHandler = instance -> { };
    private BiConsumer<String, String> errorHandler = (animation, message) -> { };

    private long currentTick;
    private int tickTaskId = -1;

    public AnimationScheduler(Plugin plugin, int maxThreads, WorkerPoolSizer sizer,
            LongSupplier budget, GuestOutput guestOutput) {
        this.plugin = plugin;
        this.sizer = sizer;
        this.maxThreads = Math.max(1, maxThreads);
        this.budget = budget;
        this.guestOutput = guestOutput;
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(1, Math.max(1, maxThreads), 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "Billboard-Worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.workers.allowCoreThreadTimeOut(true);
    }

    /** Called when an instance ends (finished/errored) so the proximity controller can drop it. */
    public void setEndHandler(Consumer<RunningInstance> endHandler) {
        this.endHandler = endHandler;
    }

    /** Called on error with (animation, message) so the animation can be paused and reported. */
    public void setErrorHandler(BiConsumer<String, String> errorHandler) {
        this.errorHandler = errorHandler;
    }

    /** Begin the per-tick loop on the main thread. */
    public void start() {
        tickTaskId = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::onServerTick, 1L, 1L).getTaskId();
    }

    public void add(RunningInstance instance) {
        instance.markStarted(currentTick);
        instances.add(instance);
    }

    public void removeAndStop(RunningInstance instance) {
        instances.remove(instance);
        instance.stop();
    }

    /** The count of instances currently ticking (for pool sizing / status). */
    public int activeCount() {
        return instances.size();
    }

    /**
     * Every instance the scheduler is holding, for the main-thread passes that have to reach all of
     * them — today the player-snapshot pass. The set is concurrent, so iterating it is safe while
     * workers finish ticks and hand results back.
     */
    public Iterable<RunningInstance> instances() {
        return instances;
    }

    // --- stats ---

    /**
     * Arms a {@code /billboard stats} capture across every live instance of the target. Runs on the
     * main thread, like the tick pass that will collect it.
     *
     * @param target      the word the user typed (one capture per target)
     * @param animation   the resolved animation
     * @param placementId one placement, or {@code null} for every placement of the animation
     * @param windowTicks how long to capture
     * @param onReport    receives the report on the main thread when the window closes
     */
    @Override
    public CaptureOrchestrator.CaptureStart startCapture(String target, String animation,
            String placementId, int windowTicks, Consumer<CaptureReport> onReport) {
        return captures.start(target, animation, placementId, windowTicks, currentTick,
                instances, onReport);
    }

    /**
     * Closes the capture running on {@code target} early and delivers its report to whoever
     * started it.
     *
     * @return false if no capture was running on that target
     */
    @Override
    public boolean stopCapture(String target) {
        return captures.stop(target, currentTick);
    }

    /** The instant plugin-wide view, minus the placement count only the data store knows. */
    public PluginStats pluginStats(int placements) {
        Map<String, Integer> byAnimation = new TreeMap<>();
        for (RunningInstance instance : instances) {
            byAnimation.merge(instance.placement().animation(), 1, Integer::sum);
        }
        return new PluginStats(sizer.current(), maxThreads, instances.size(), byAnimation, placements);
    }

    /** Stop everything and shut the pool down (plugin disable). */
    public void shutdown() {
        if (tickTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        for (RunningInstance instance : instances) {
            instance.stop();
        }
        instances.clear();
        captures.clear();
        workers.shutdownNow();
    }

    private void onServerTick() {
        currentTick++;
        // Before dispatch: an instance armed now is sampled by the tick this pass is about to run.
        captures.tick(currentTick, instances);
        int size = sizer.update(instances.size(), currentTick);
        workers.setCorePoolSize(Math.max(1, size));
        long b = budget.getAsLong();
        long t = currentTick;
        for (RunningInstance instance : instances) {
            if (!instance.isTickable() || !inFlight.add(instance)) {
                continue; // released (kept) or still running from a previous tick
            }
            workers.execute(() -> runTick(instance, t, b));
        }
    }

    private void runTick(RunningInstance instance, long tick, long b) {
        TickResult result;
        try {
            result = instance.tick(tick, b);
        } catch (RuntimeException e) {
            result = new TickResult.Errored("internal interpreter error: " + e);
        }
        TickResult finished = result;
        plugin.getServer().getScheduler().runTask(plugin, () -> handleResult(instance, finished));
    }

    private void handleResult(RunningInstance instance, TickResult result) {
        inFlight.remove(instance);
        flushLogs(instance);
        switch (result) {
            case TickResult.Running ignored -> { }
            case TickResult.Finished finished -> handleFinish(instance, finished.exitCode());
            case TickResult.Errored errored -> {
                instances.remove(instance);
                instance.stop();
                endHandler.accept(instance);
                errorHandler.accept(instance.placement().animation(), errored.message());
                guestOutput.fail(instance.placement().animation(), instance.ownerLabel(), errored.message());
            }
        }
    }

    private void handleFinish(RunningInstance instance, ExitCode code) {
        switch (code) {
            case END -> {
                instances.remove(instance);
                instance.stop();
                endHandler.accept(instance);
            }
            case KEEP -> {
                // Release the interpreter but leave the entities standing; the proximity
                // controller still owns the handle and stops it when viewers leave.
                instances.remove(instance);
                instance.releaseRuntimeKeepEntities();
            }
            case REPEAT -> {
                instance.restart();
                instance.markStarted(currentTick); // a repeat is a new run, and a new uptime
            }
        }
    }

    private void flushLogs(RunningInstance instance) {
        List<String> logs = instance.drainLogs();
        for (String message : logs) {
            guestOutput.log(instance.placement().animation(), instance.ownerLabel(), message);
        }
    }
}
