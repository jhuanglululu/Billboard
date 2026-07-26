package com.jhuanglululu.billboard.load;

import com.jhuanglululu.billboard.load.RegistryExporter.Entry;
import com.jhuanglululu.billboard.load.RegistryExporter.StateEnum;
import com.jhuanglululu.billboard.load.RegistryExporter.Variant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Axis;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;

/**
 * The thin Bukkit shell around {@link RegistryExporter}: walks the server's block and item
 * registries and its block-data vocabulary, then writes the generated file. Deliberately does no
 * formatting of its own — everything shaped by the SDK's format contract lives in the pure
 * exporter, which is where the tests are (v1 precedent: Bukkit-touching shells stay untested).
 *
 * <p>Only the {@code minecraft} namespace is exported. A datapack or plugin id would still be a
 * valid identifier, but the SDK's snapshot is the vanilla vocabulary and a non-vanilla path can
 * collide or fail to be a Rust identifier; those ids stay reachable through the string escape
 * hatches instead.
 */
public final class BukkitRegistrySource {

    private BukkitRegistrySource() {}

    /**
     * The three common block-state enums, with variants in <em>vanilla order</em> — which is the
     * declaration order of the Bukkit enums they come from, not alphabetical. The SDK's typed
     * setters name these types by hand, so this set may grow but must never shrink.
     */
    public static List<StateEnum> stateEnums() {
        // BlockFace declares north, east, south, west, up, down first; the diagonals and self
        // that follow are not block-state values, so the cardinal six are taken in order.
        List<Variant> facing = new ArrayList<>();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            facing.add(variant(face.name()));
        }
        List<Variant> axis = new ArrayList<>();
        for (Axis a : Axis.values()) {
            axis.add(variant(a.name()));
        }
        List<Variant> half = new ArrayList<>();
        for (Bisected.Half h : Bisected.Half.values()) {
            half.add(variant(h.name()));
        }
        return List.of(new StateEnum("Axis", "axis", axis),
                new StateEnum("Facing", "facing", facing),
                new StateEnum("Half", "half", half));
    }

    private static Variant variant(String enumConstant) {
        String value = enumConstant.toLowerCase(Locale.ROOT);
        return new Variant(RegistryExporter.pascalCase(value), value);
    }

    /** Every vanilla block id. */
    public static List<Entry> blocks() {
        return entries(Registry.BLOCK);
    }

    /** Every vanilla item id. */
    public static List<Entry> items() {
        return entries(Registry.ITEM);
    }

    private static List<Entry> entries(Iterable<? extends Keyed> registry) {
        List<Entry> out = new ArrayList<>();
        for (Keyed keyed : registry) {
            NamespacedKey key = keyed.getKey();
            if (NamespacedKey.MINECRAFT.equals(key.getNamespace())) {
                out.add(Entry.of(key.toString()));
            }
        }
        return out;
    }

    /**
     * Writes the export to {@code target}, creating parent directories.
     *
     * @return the number of block and item entries written
     * @throws java.io.IOException if the file cannot be written
     */
    public static int[] write(Path target) throws java.io.IOException {
        List<Entry> blocks = blocks();
        List<Entry> items = items();
        String source = RegistryExporter.render(stateEnums(), blocks, items);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
        return new int[] {blocks.size(), items.size()};
    }
}
