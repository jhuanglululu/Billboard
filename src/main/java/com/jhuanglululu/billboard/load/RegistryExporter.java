package com.jhuanglululu.billboard.load;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates {@code registry.rs} — the Rust source the SDK compiles the animation's identifiers
 * from. Pure string assembly, so the format contract is unit-testable; the Bukkit registry walk
 * lives in {@link BukkitRegistrySource}.
 *
 * <p><b>The format contract is the SDK's, not ours.</b> {@code billboard-rs} ships a hand-written
 * snapshot in this exact shape and its {@code build.rs} only <em>copies</em> the file into
 * {@code OUT_DIR} for an {@code include!}, so rustc is the only validator. The emitted header
 * repeats the contract verbatim, which is what makes an exported file self-describing:
 *
 * <ol>
 *   <li>Plain Rust items, absolute {@code crate::registry::…} paths (the file is included
 *       <em>inside</em> that module, so relative paths would break).</li>
 *   <li>Block-state enums first, sorted by type name, fieldless, deriving
 *       {@code Clone, Copy, Debug, PartialEq, Eq}, with {@code pub const fn as_str}.</li>
 *   <li>Then {@code pub mod blocks}, then {@code pub mod items}, one const per entry, sorted by
 *       the <em>identifier string</em> in byte order — not by const name, so {@code red_concrete}
 *       precedes {@code redstone_block} exactly as the server iterates.</li>
 *   <li>One entry per line, no blank lines or doc comments inside a module, so two exports diff
 *       as pure additions and removals.</li>
 * </ol>
 *
 * <p>Nothing version- or time-stamped is emitted: the same registry must always produce a
 * byte-identical file, or the diffs the contract promises are worthless.
 */
public final class RegistryExporter {

    private RegistryExporter() {}

    /** One variant of a block-state enum: its Rust name and the vanilla property value. */
    public record Variant(String rustName, String value) {}

    /**
     * A common block-state property as a Rust enum.
     *
     * @param typeName the Rust type name ({@code Facing})
     * @param property the vanilla property name ({@code facing}), for the doc comment
     * @param variants in vanilla order — the order the enum declares and matches in
     */
    public record StateEnum(String typeName, String property, List<Variant> variants) {

        public StateEnum {
            variants = List.copyOf(variants);
        }
    }

    /** An id with the const name it becomes: {@code minecraft:red_concrete} → {@code RED_CONCRETE}. */
    public record Entry(String id, String constName) {

        /** Derives the const name from the id path (namespace stripped, upper-cased). */
        public static Entry of(String id) {
            int colon = id.indexOf(':');
            String path = colon < 0 ? id : id.substring(colon + 1);
            return new Entry(id, path.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static final String HEADER = """
            // @generated — the identifier registry for the Billboard SDK.
            //
            // FORMAT CONTRACT. The plugin writes this file with `/billboard export
            // registry`; the SDK's build.rs only *copies* it into OUT_DIR, and
            // `billboard::registry` `include!`s the copy. rustc is therefore the only
            // validator, so the generator must emit exactly this shape:
            //
            //  1. Plain Rust items, no `use`, no inner attributes, no `mod` nesting other
            //     than the two modules below. The file is included *inside* the
            //     `billboard::registry` module, so every path is written **absolute**
            //     (`crate::registry::BlockId`) and stays correct wherever it lands.
            //  2. First the common block-state enums, sorted by type name: a fieldless
            //     `pub enum` deriving `Clone, Copy, Debug, PartialEq, Eq` plus an
            //     `as_str(self) -> &'static str` returning the vanilla property value,
            //     variants in vanilla order. `BlockStateBuilder`'s typed setters name
            //     these types by hand, so the set may grow but must not shrink.
            //  3. Then `pub mod blocks`, then `pub mod items`: one
            //     `pub const NAME: crate::registry::BlockId =
            //     crate::registry::BlockId::new("minecraft:name");` per entry (`ItemId`
            //     for items), NAME being the id path in SCREAMING_SNAKE_CASE, **sorted by
            //     the identifier string** in byte order (so `red_concrete` precedes
            //     `redstone_block`, matching the server's registry iteration order).
            //  4. One entry per line, no blank lines inside a module, no doc comments —
            //     diffs between two exports should be pure additions and removals.
            //
            // This is a full export of one server's registries. Nothing here is
            // version-stamped: the same registry always produces a byte-identical file, so
            // re-exporting after a Minecraft upgrade is the whole upgrade story.
            """;

    /**
     * Renders the whole file.
     *
     * @param enums  the block-state enums; sorted by type name here, so callers need not
     * @param blocks block ids; sorted by id here
     * @param items  item ids; sorted by id here
     */
    public static String render(List<StateEnum> enums, List<Entry> blocks, List<Entry> items) {
        StringBuilder out = new StringBuilder(HEADER);
        List<StateEnum> sortedEnums = new ArrayList<>(enums);
        sortedEnums.sort(Comparator.comparing(StateEnum::typeName));
        for (StateEnum e : sortedEnums) {
            out.append('\n').append(renderEnum(e));
        }
        out.append('\n').append(renderModule("blocks", "BlockId", blocks));
        out.append('\n').append(renderModule("items", "ItemId", items));
        return out.toString();
    }

    private static String renderEnum(StateEnum e) {
        StringBuilder out = new StringBuilder();
        out.append("/// The `").append(e.property()).append("` block-state property.\n");
        out.append("#[derive(Clone, Copy, Debug, PartialEq, Eq)]\n");
        out.append("pub enum ").append(e.typeName()).append(" {\n");
        for (Variant v : e.variants()) {
            out.append("    ").append(v.rustName()).append(",\n");
        }
        out.append("}\n\nimpl ").append(e.typeName()).append(" {\n");
        out.append("    pub const fn as_str(self) -> &'static str {\n");
        out.append("        match self {\n");
        for (Variant v : e.variants()) {
            out.append("            ").append(e.typeName()).append("::").append(v.rustName())
                    .append(" => \"").append(v.value()).append("\",\n");
        }
        out.append("        }\n    }\n}\n");
        return out.toString();
    }

    private static String renderModule(String module, String type, List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        // Byte order over the identifier: vanilla ids are ASCII, so natural String order is
        // byte order, and sorting by id (not const name) is what point 3 requires.
        sorted.sort(Comparator.comparing(Entry::id));
        StringBuilder out = new StringBuilder("pub mod ").append(module).append(" {\n");
        for (Entry entry : sorted) {
            out.append("    pub const ").append(entry.constName())
                    .append(": crate::registry::").append(type)
                    .append(" = crate::registry::").append(type)
                    .append("::new(\"").append(entry.id()).append("\");\n");
        }
        return out.append("}\n").toString();
    }

    /** {@code light_blue} → {@code LightBlue}, for enum variant names built from vanilla values. */
    public static String pascalCase(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_' || c == '-') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
