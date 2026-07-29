package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.billboard.message.MessageFormats;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.CaptureSummary;
import com.jhuanglululu.wasmachine.runtime.MachineInstance.StatsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the MiniMessage for {@code /billboard stats}. Pure (no Adventure, no Bukkit) like
 * {@link MessageFormats}, so the wording, the number formatting and the escaping of untrusted
 * names are all unit-testable; the caller deserializes and sends.
 *
 * <p>Every message follows the design's detail-in-hover rule: the visible line is one short,
 * identifiable statement and the slow-moving totals live in the hover.
 */
public final class StatsFormats {

    private StatsFormats() {}

    /** Ticks per second, for turning capture windows back into the seconds the user asked for. */
    public static final int TICKS_PER_SECOND = 20;

    private static final double MIB = 1024.0 * 1024.0;

    /**
     * A visible line and its hover detail.
     *
     * @param visible the short line shown in chat
     * @param hover   the detail behind it
     */
    public record Line(String visible, String hover) {}

    // --- /billboard stats (no arguments) ---

    /** The instant plugin-wide summary: threads, instances, placements. */
    public static Line pluginSummary(PluginStats stats) {
        String visible = MessageFormats.PREFIX + "<white>" + stats.activeInstances()
                + "</white> instance(s) on <white>" + stats.poolThreads() + "</white>/"
                + stats.maxThreads() + " worker thread(s) <gray>(hover for the breakdown)</gray>";
        StringBuilder hover = new StringBuilder();
        hover.append("<gray>placements: <white>").append(stats.placements()).append("</white></gray>");
        if (stats.instancesByAnimation().isEmpty()) {
            hover.append("\n<gray>no animation is running right now</gray>");
        } else {
            for (Map.Entry<String, Integer> e : sorted(stats.instancesByAnimation())) {
                hover.append("\n<white>").append(MessageFormats.escape(e.getKey()))
                        .append("</white><gray>: ").append(e.getValue()).append(" instance(s)</gray>");
            }
        }
        hover.append("\n<gray>pool grows with demand and shrinks only after the debounce</gray>");
        return new Line(visible, hover.toString());
    }

    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>(counts.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    // --- starting a capture ---

    /**
     * The acknowledgement, carrying the instant snapshot so the numbers start immediately even
     * though the report is seconds away.
     */
    public static Line captureStarted(String target, int seconds,
            CaptureOrchestrator.CaptureStart start) {
        long lastTick = 0;
        long memory = 0;
        int tasks = 0;
        int entities = 0;
        for (CaptureReport.InstanceStats i : start.instant()) {
            lastTick += i.snapshot().lastTickInstructions();
            memory += i.snapshot().memoryUsedBytes();
            tasks += i.snapshot().liveTasks();
            entities += i.liveEntities();
        }
        String visible = MessageFormats.PREFIX + "<green>capturing <white>"
                + MessageFormats.escape(target) + "</white> for " + seconds + "s</green> <gray>("
                + start.armed() + " instance(s), hover for the current numbers)</gray>";
        StringBuilder hover = new StringBuilder();
        hover.append("<gray>last tick: <white>").append(count(lastTick))
                .append("</white> instruction(s)</gray>")
                .append("\n<gray>memory now: <white>").append(mib(memory)).append("</white></gray>")
                .append("\n<gray>live tasks: <white>").append(tasks)
                .append("</white>, entities: <white>").append(entities).append("</white></gray>")
                .append("\n<gray>the report arrives in ").append(seconds).append("s</gray>");
        for (CaptureReport.InstanceStats i : start.instant()) {
            hover.append("\n<dark_gray>").append(MessageFormats.escape(i.label())).append(": ")
                    .append(count(i.snapshot().lastTickInstructions())).append(" instr, ")
                    .append(mib(i.snapshot().memoryUsedBytes())).append("</dark_gray>");
        }
        return new Line(visible, hover.toString());
    }

    /** The refusal when a capture on the same target is already running. */
    public static Line captureAlreadyRunning(String target, long remainingTicks) {
        String visible = MessageFormats.PREFIX + "<yellow>already capturing <white>"
                + MessageFormats.escape(target) + "</white> — <white>" + seconds(remainingTicks)
                + "s</white> left</yellow>";
        String hover = "<gray>one capture per target: the running window keeps its samples "
                + "instead of being restarted under you</gray>"
                + "\n<gray>the report goes to whoever started it</gray>";
        return new Line(visible, hover);
    }

    /**
     * The loud warning that the target resolved but nothing is running it. The window is armed
     * anyway, so this says how to make it produce something rather than just refusing.
     */
    public static Line noInstances(String target, int seconds) {
        String visible = MessageFormats.PREFIX + "<red>nothing is running <white>"
                + MessageFormats.escape(target) + "</white></red> <gray>(hover)</gray>";
        String hover = "<red>no instance of it exists right now, so there is nothing to measure"
                + "</red>\n<gray>stand in range of a placement, or have an eligible player do it — "
                + "an instance that starts within the next " + seconds
                + "s is captured too</gray>"
                + "\n<gray>a paused animation or placement never starts: check /billboard list</gray>";
        return new Line(visible, hover);
    }

    // --- the report ---

    /** The header line: what was captured, over how long, and what it cost per tick. */
    public static Line reportHeader(CaptureReport report) {
        String visible = MessageFormats.PREFIX + "<white>" + MessageFormats.escape(report.target())
                + "</white> <gray>over " + seconds(report.windowTicks()) + "s:</gray> <white>"
                + mean(report.meanInstructionsPerTick()) + "</white> instr/tick across <white>"
                + report.sampledInstances() + "</white> instance(s)"
                + (report.partial() ? " <yellow>(partial)</yellow>" : "");
        String hover = "<gray>window total: <white>" + count(report.windowInstructions())
                + "</white> instruction(s)</gray>"
                + "\n<gray>memory in use across instances: <white>" + mib(report.meanMemoryBytes())
                + "</white> mean, highest run watermark <white>" + mib(report.peakMemoryBytes())
                + "</white></gray>"
                + "\n<gray>live entities: <white>" + report.liveEntities() + "</white></gray>"
                + (report.partial()
                        ? "\n<yellow>partial: an instance ended or joined inside the window</yellow>"
                        : "")
                + "\n<gray>per-tick figures are the sum over instances — what the animation costs "
                + "the server each tick</gray>";
        return new Line(visible, hover);
    }

    /** One instance's line: the per-tick numbers visible, the run totals in the hover. */
    public static Line instanceLine(CaptureReport.InstanceStats instance) {
        CaptureSummary c = instance.capture();
        StatsSnapshot s = instance.snapshot();
        if (!instance.sampled()) {
            return new Line("  <white>" + MessageFormats.escape(instance.label())
                    + "</white> <gray>— no ticks captured</gray>",
                    "<gray>it ran no tick inside the window: it had already ended, or it never "
                            + "started</gray>");
        }
        String visible = "  <white>" + MessageFormats.escape(instance.label()) + "</white> <gray>"
                + mean(c.meanInstructions()) + " instr/tick (" + count(c.instructionsMin()) + "–"
                + count(c.instructionsMax()) + "), mem " + mib(c.meanMemoryBytes()) + " (peak "
                + mib(s.memoryPeakBytes()) + " of " + mib(s.memoryCapBytes()) + "), "
                + s.liveTasks() + " task(s), " + instance.liveEntities() + " entity(s), up "
                + s.uptimeTicks() + "t</gray>" + (c.complete() ? "" : " <yellow>(partial)</yellow>");
        String hover = "<gray>window: <white>" + c.ticksCaptured() + "</white> tick(s)"
                + (c.complete() ? "" : " <yellow>(ended early)</yellow>") + "</gray>"
                + "\n<gray>window instructions: <white>" + count(c.instructionsSum()) + "</white></gray>"
                + "\n<gray>run instructions: <white>" + count(s.totalInstructions())
                + "</white> over <white>" + s.uptimeTicks() + "</white> tick(s)</gray>"
                + "\n<gray>forks: <white>" + s.totalForks() + "</white>, entity spawns: <white>"
                + instance.totalSpawns() + "</white>, restarts: <white>" + instance.restarts()
                + "</white></gray>"
                + "\n<gray>non-deterministic draws: <white>" + count(s.nonDeterministicDraws())
                + "</white>" + (s.nonDeterministicDraws() == 0 ? " (reproducible)" : "") + "</gray>"
                + "\n<gray>memory: window sampled peak <white>" + mib(c.memoryPeakBytes())
                + "</white>, run watermark <white>" + mib(s.memoryPeakBytes()) + "</white></gray>";
        return new Line(visible, hover);
    }

    /** The report for a window that ended having sampled nothing at all. */
    public static Line reportWithoutSamples(CaptureReport report) {
        String visible = MessageFormats.PREFIX + "<yellow><white>"
                + MessageFormats.escape(report.target()) + "</white> ran nothing in "
                + seconds(report.windowTicks()) + "s</yellow> <gray>(hover)</gray>";
        String hover = "<gray>the window closed without a single captured tick — no instance was "
                + "running and none started</gray>"
                + "\n<gray>an instance exists only while an eligible player is in range</gray>";
        return new Line(visible, hover);
    }

    // --- numbers ---

    /** Ticks as whole-ish seconds, the unit the command speaks. */
    public static String seconds(long ticks) {
        return trim((double) ticks / TICKS_PER_SECOND);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String mean(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String mib(double bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / MIB);
    }

    /** Byte counts arrive as longs far below the precision limit; this keeps call sites clean. */
    private static String mib(long bytes) {
        return mib((double) bytes);
    }

    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
