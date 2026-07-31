package com.jhuanglululu.billboard.stats;

import com.jhuanglululu.billboard.message.MessageFormats;
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
 * <p><b>Layout.</b> An instance's numbers are a block, not a sentence: its name, then one line per
 * field. A comma then only ever means thousands, which is the whole reason for the shape — a
 * comma-chained line of comma-grouped numbers cannot be read at a glance, and glancing is what
 * this command is for. Nothing is indented: the chat box wraps at arbitrary widths, so leading
 * space only makes a wrapped line ragged.
 *
 * <p><b>Colour.</b> Yellow labels the fields, white carries the numbers, gray is the connective
 * text between them. The {@code [Billboard]} prefix keeps whatever {@link MessageFormats} says it
 * is.
 */
public final class StatsFormats {
    
    private StatsFormats() {
    }
    
    /**
     * Ticks per second, for turning capture windows back into the seconds the user asked for.
     */
    public static final int TICKS_PER_SECOND = 20;
    
    private static final double MIB = 1024.0 * 1024.0;
    
    /**
     * A visible line and its hover detail.
     *
     * @param visible the line — or multi-line block — shown in chat
     * @param hover   the detail behind it
     */
    public record Line(String visible, String hover) {
    }
    
    // --- /billboard stats (no arguments) ---
    
    /**
     * The instant plugin-wide summary: threads, instances, placements.
     */
    public static Line pluginSummary(PluginStats stats) {
        String visible = MessageFormats.PREFIX + "<white>" + stats.activeInstances()
                + "</white> <gray>" + plural(stats.activeInstances(), "instance")
                + " on</gray> <white>" + stats.poolThreads()
                + "</white><gray>/</gray><white>" + stats.maxThreads()
                + "</white> <gray>worker " + plural(stats.maxThreads(), "thread") + "</gray>";
        StringBuilder hover = new StringBuilder();
        hover.append("<yellow>placements</yellow> <white>").append(stats.placements()).append("</white>");
        if (stats.instancesByAnimation().isEmpty()) {
            hover.append("\n<gray>no animation is running right now</gray>");
        } else {
            for (Map.Entry<String, Integer> e : sorted(stats.instancesByAnimation())) {
                hover.append("\n<yellow>").append(MessageFormats.escape(e.getKey()))
                        .append("</yellow> <white>").append(e.getValue()).append("</white>");
            }
        }
        return new Line(visible, hover.toString());
    }
    
    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>(counts.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return out;
    }
    
    // --- starting and stopping a capture ---
    
    /**
     * The acknowledgement, carrying the click that ends the window early. It shows no numbers:
     * nothing is measured outside a capture, so anything here would be a figure from before the
     * one the user just asked for.
     *
     * <p>The click argument is the target word Brigadier already parsed. Its grammar has no quote
     * character, so it cannot break out of the MiniMessage tag it is embedded in.
     */
    public static String captureStarted(String target, int seconds, int armed) {
        String instanceStr = armed > 1 ? "instances" : "instance";
        
        return MessageFormats.PREFIX + "<gray>capturing</gray> <white>"
                + MessageFormats.escape(target) + "</white> <gray>for</gray> <white>" + seconds
                + "s</white> <gray>(</gray><white>" + armed + "</white> "
                + "<gray>" + instanceStr + ")</gray> "
                + "<click:run_command:'/billboard stats stop " + target + "'>"
                + "<red>[stop]</red></click>";
    }
    
    /**
     * The refusal when a capture on the same target is already running: one capture per target, so
     * the running window keeps its samples instead of being restarted under whoever asked for it.
     */
    public static String captureAlreadyRunning(String target, long remainingTicks) {
        return MessageFormats.PREFIX + "<gray>already capturing</gray> <white>"
                + MessageFormats.escape(target) + "</white><gray>,</gray> <white>"
                + seconds(remainingTicks) + "s</white> <gray>left</gray>";
    }
    
    /**
     * The gray notice {@code stats stop} gives when nothing is running on the target.
     */
    public static String noCaptureRunning(String target) {
        return MessageFormats.PREFIX + "<gray>no capture is running on</gray> <white>"
                + MessageFormats.escape(target) + "</white>";
    }
    
    /**
     * The loud warning that the target resolved but nothing is running it. The window is armed
     * anyway — an instance that starts inside it is captured too — so this warns rather than
     * refuses.
     */
    public static String noInstances(String target) {
        return MessageFormats.PREFIX + "<red>nothing is running <white>"
                + MessageFormats.escape(target) + "</white></red>";
    }
    
    // --- the report ---
    
    /**
     * The header line: what was captured, over how long, and what it cost in total. The hover
     * carries the three derived figures — per-tick instructions, memory and entities — that the
     * one visible line has no room for.
     */
    public static Line reportHeader(CaptureReport report) {
        String span = report.stopped()
                ? "<gray>over</gray> <white>" + seconds(report.elapsedTicks())
                + "s</white> <gray>of</gray> <white>" + seconds(report.windowTicks())
                + "s</white> <gray>requested</gray>"
                : "<gray>over</gray> <white>" + seconds(report.windowTicks()) + "s</white>";
        String visible = MessageFormats.PREFIX + "<white>" + MessageFormats.escape(report.target())
                + "</white> " + span + "<gray>:</gray> <white>"
                + count(report.windowInstructions()) + "</white> <gray>instructions across</gray> "
                + "<white>" + report.sampledInstances() + "</white> <gray>"
                + plural(report.sampledInstances(), "instance") + "</gray>";
        String hover = "<yellow>instr/tick</yellow> <white>"
                + mean(report.meanInstructionsPerTick()) + "</white> <gray>across</gray> <white>"
                + report.sampledInstances() + " "
                + plural(report.sampledInstances(), "instance") + "</white><gray>,</gray> <white>total of "
                + report.placements() + " " + plural(report.placements(), "placement") + "</white>"
                + "\n<yellow>memory</yellow> <white>" + mib(report.meanMemoryBytes())
                + "</white> <gray>mean,</gray> <white>" + mib(report.peakMemoryBytes())
                + "</white> <gray>peak</gray>"
                + "\n<yellow>entities</yellow> <white>" + mean(report.entities().mean())
                + "</white> <gray>mean,</gray> <white>" + count(report.entities().peak())
                + "</white> <gray>peak</gray>";
        return new Line(visible, hover);
    }
    
    /**
     * One placement-owner's block: its name, then one line per field, every run of the window
     * merged (a restart mid-window leaves one engine window per run — printed apart they read as
     * duplicates). Live gauges (tasks, entities, cap) are the newest run's. The hover, present
     * only when the runs do not cover the window, explains the missing ticks: time no instance
     * was alive, between a death and its restart or before the first spawn.
     *
     * @param elapsedTicks how long the window actually ran, the merged runs' ticks are held against
     * @return the block, with a {@code null} hover when the runs cover the whole window
     */
    public static Line instanceLine(CaptureReport.MergedInstance instance, long elapsedTicks) {
        String name = "<white>" + MessageFormats.escape(instance.label()) + "</white>";
        long gap = Math.max(0, elapsedTicks - instance.capturedTicks());
        String hover = gap == 0 ? null
                : "<gray>no instance was alive for <white>" + count(gap)
                + "</white> of the window's <white>" + count(elapsedTicks) + "</white> "
                + plural(elapsedTicks, "tick") + "</gray>";
        if (!instance.sampled()) {
            // It had already ended, or it never started: one line, because there are no numbers.
            return new Line(name + " <gray>ran no tick in the window</gray>", hover);
        }
        StatsSnapshot s = instance.newest().snapshot();
        String visible = name
                + "\n<yellow>instance</yellow> <white>" + instance.instanceCount()
                + "</white> <gray>—</gray> <yellow>uptime</yellow> <white>" + count(instance.activeTicks())
                + "</white><gray>/</gray><white>" + count(instance.capturedTicks())
                + "</white> <gray>" + plural(instance.capturedTicks(), "tick") + "</gray>"
                + "\n<yellow>instr/tick</yellow> <white>" + mean(instance.meanInstructions())
                + "</white> <gray>(</gray><white>" + count(instance.instructionsMin())
                + "</white><gray>–</gray><white>" + count(instance.instructionsMax()) + "</white><gray>)</gray>"
                + "\n<yellow>memory</yellow> <white>" + mib(instance.memoryPeakBytes())
                + "</white> <gray>peak of</gray> <white>" + mib(s.memoryCapBytes()) + "</white>"
                + "\n<yellow>tasks</yellow> <white>" + s.liveTasks()
                + "</white> <gray>—</gray> <yellow>entities</yellow> <white>"
                + instance.newest().liveEntities() + "</white>";
        return new Line(visible, hover);
    }
    
    /**
     * The report for a window that ended having sampled nothing at all: no instance was running
     * and none started. Zeroes would read as a measurement; this says there was none.
     */
    public static String reportWithoutSamples(CaptureReport report) {
        return MessageFormats.PREFIX + "<white>" + MessageFormats.escape(report.target())
                + "</white> <gray>ran nothing in</gray> <white>" + seconds(report.elapsedTicks())
                + "s</white>";
    }
    
    // --- numbers ---
    
    /**
     * Ticks as whole-ish seconds, the unit the command speaks.
     */
    public static String seconds(long ticks) {
        return trim((double) ticks / TICKS_PER_SECOND);
    }
    
    /**
     * The noun to go with a count. Written out rather than left as {@code (s)}, which reads like a
     * form field.
     */
    private static String plural(long count, String noun) {
        return count == 1 ? noun : noun + "s";
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
    
    /**
     * Byte counts arrive as longs far below the precision limit; this keeps call sites clean.
     */
    private static String mib(long bytes) {
        return mib((double) bytes);
    }
    
    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
