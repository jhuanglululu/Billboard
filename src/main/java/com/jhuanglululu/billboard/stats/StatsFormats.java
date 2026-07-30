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
 * <p><b>Layout.</b> An instance's numbers are a block, not a sentence: its name, then one indented
 * line per group. A comma then only ever means thousands, which is the whole reason for the shape
 * — a comma-chained line of comma-grouped numbers cannot be read at a glance, and glancing is what
 * this command is for.
 *
 * <p><b>Colour.</b> Yellow labels the fields, white carries the numbers, gray is everything else.
 * The {@code [Billboard]} prefix keeps whatever {@link MessageFormats} says it is.
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
     * The block indents: one space before an instance's name, three before its numbers.
     */
    private static final String NAME_INDENT = " ";
    private static final String DETAIL_INDENT = "   ";
    
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
                + "</white> <gray>instance(s) on</gray> <white>" + stats.poolThreads()
                + "</white><gray>/</gray><white>" + stats.maxThreads()
                + "</white> <gray>worker thread(s)</gray>";
        StringBuilder hover = new StringBuilder();
        hover.append("<yellow>placements</yellow> <white>").append(stats.placements()).append("</white>");
        if (stats.instancesByAnimation().isEmpty()) {
            hover.append("\n<gray>no animation is running right now</gray>");
        } else {
            for (Map.Entry<String, Integer> e : sorted(stats.instancesByAnimation())) {
                hover.append("\n<yellow>").append(MessageFormats.escape(e.getKey()))
                        .append("</yellow> <white>").append(e.getValue())
                        .append("</white> <gray>instance(s)</gray>");
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
                + "<red>[click]</red></click>";
    }
    
    /**
     * The refusal when a capture on the same target is already running.
     */
    public static Line captureAlreadyRunning(String target, long remainingTicks) {
        String visible = MessageFormats.PREFIX + "<gray>already capturing</gray> <yellow>"
                + MessageFormats.escape(target) + "</yellow><gray>,</gray> <white>"
                + seconds(remainingTicks) + "s</white> <gray>left</gray>";
        
        String hover = "<gray>one capture per target: the running window keeps its samples "
                + "instead of being restarted under you</gray>"
                + "\n<gray>the report goes to whoever started it</gray>";
        return new Line(visible, hover);
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
    
    /**
     * The header line: what was captured, over how long, and what it cost per tick.
     */
    public static Line reportHeader(CaptureReport report) {
        String span = report.stopped()
                ? "<gray>over</gray> <white>" + seconds(report.elapsedTicks())
                + "s</white> <gray>of</gray> <white>" + seconds(report.windowTicks())
                + "s</white> <gray>requested</gray>"
                : "<gray>over</gray> <white>" + seconds(report.windowTicks()) + "s</white>";
        String visible = MessageFormats.PREFIX + "<white>" + MessageFormats.escape(report.target())
                + "</white> " + span + "<gray>:</gray> <white>"
                + mean(report.meanInstructionsPerTick()) + "</white> <gray>instr/tick across</gray> "
                + "<white>" + report.sampledInstances() + "</white> <gray>instance(s)</gray>";
        String hover = "<yellow>window instructions</yellow> <white>"
                + count(report.windowInstructions()) + "</white>"
                + "\n<yellow>memory</yellow> <white>" + mib(report.meanMemoryBytes())
                + "</white> <gray>mean, peak</gray> <white>" + mib(report.peakMemoryBytes())
                + "</white>"
                + "\n<yellow>entities</yellow> <white>" + report.liveEntities() + "</white>"
                + (report.partial()
                ? "\n<gray>partial: an instance ended or joined inside the window</gray>"
                : "")
                + "\n<gray>per-tick figures are the sum over instances — what the animation costs "
                + "the server each tick</gray>";
        return new Line(visible, hover);
    }
    
    /**
     * One instance's block: its name, then its numbers on indented lines. The hover holds window
     * facts only — nothing is measured outside a capture, so there is nothing else honest to show.
     *
     * @param requestedTicks the window length that was asked for, which only the report knows
     */
    public static Line instanceLine(CaptureReport.InstanceStats instance, long requestedTicks) {
        CaptureSummary c = instance.capture();
        StatsSnapshot s = instance.snapshot();
        String name = NAME_INDENT + "<white>" + MessageFormats.escape(instance.label()) + "</white>";
        if (!instance.sampled()) {
            return new Line(name + "\n" + DETAIL_INDENT + "<gray>no ticks captured</gray>",
                            "<gray>it ran no tick inside the window: it had already ended, or it never "
                                    + "started</gray>");
        }
        String visible = name
                + "\n" + DETAIL_INDENT + "<white>" + mean(c.meanInstructions())
                + "</white> <gray>instr/tick (</gray><white>" + count(c.instructionsMin())
                + "</white><gray>–</gray><white>" + count(c.instructionsMax()) + "</white><gray>)</gray>"
                + "\n" + DETAIL_INDENT + "<yellow>mem</yellow> <white>" + mib(s.memoryUsedBytes())
                + "</white> <gray>(peak</gray> <white>" + mib(c.memoryPeakBytes())
                + "</white> <gray>of</gray> <white>" + mib(s.memoryCapBytes()) + "</white><gray>)</gray>"
                + "\n" + DETAIL_INDENT + "<white>" + s.liveTasks()
                + "</white> <gray>task(s) ·</gray> <white>" + instance.liveEntities()
                + "</white> <gray>entity(s) ·</gray> <yellow>up</yellow> <white>"
                + instance.uptimeTicks() + "t</white>";
        String hover = "<yellow>window</yellow> <white>" + count(c.ticksCaptured())
                + "</white><gray>/</gray><white>" + count(requestedTicks)
                + "</white> <gray>tick(s)</gray>"
                + (c.complete() ? "" : " <gray>(ended early)</gray>")
                + "\n<yellow>window instructions</yellow> <white>" + count(c.instructionsSum())
                + "</white>"
                + "\n<yellow>window memory peak</yellow> <white>" + mib(c.memoryPeakBytes())
                + "</white>";
        return new Line(visible, hover);
    }
    
    /**
     * The report for a window that ended having sampled nothing at all. Zeroes would read as a
     * measurement; this says there was none.
     */
    public static Line reportWithoutSamples(CaptureReport report) {
        String visible = MessageFormats.PREFIX + "<white>" + MessageFormats.escape(report.target())
                + "</white> <gray>ran nothing in</gray> <white>" + seconds(report.elapsedTicks())
                + "s</white> <gray>(hover)</gray>";
        String hover = "<gray>the window closed without a single captured tick — no instance was "
                + "running and none started</gray>"
                + "\n<gray>an instance exists only while an eligible player is in range</gray>";
        return new Line(visible, hover);
    }
    
    // --- numbers ---
    
    /**
     * Ticks as whole-ish seconds, the unit the command speaks.
     */
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
