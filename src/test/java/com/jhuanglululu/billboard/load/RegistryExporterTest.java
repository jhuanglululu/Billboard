package com.jhuanglululu.billboard.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.load.RegistryExporter.Entry;
import com.jhuanglululu.billboard.load.RegistryExporter.StateEnum;
import com.jhuanglululu.billboard.load.RegistryExporter.Variant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code registry.rs} against the SDK's format contract. The expected text below is
 * written by hand from that contract (and from the shape of the SDK's bundled snapshot), not copied
 * out of the generator — it is the specification this test holds the generator to.
 */
class RegistryExporterTest {

    private static final StateEnum AXIS = new StateEnum("Axis", "axis",
            List.of(new Variant("X", "x"), new Variant("Y", "y"), new Variant("Z", "z")));

    private static final StateEnum HALF = new StateEnum("Half", "half",
            List.of(new Variant("Top", "top"), new Variant("Bottom", "bottom")));

    /** Deliberately unsorted, with ids that prove id-order beats const-name order. */
    private static final List<Entry> BLOCKS = List.of(
            Entry.of("minecraft:redstone_block"),
            Entry.of("minecraft:red_concrete"),
            Entry.of("minecraft:air"));

    private static final List<Entry> ITEMS = List.of(
            Entry.of("minecraft:stick"),
            Entry.of("minecraft:apple"));

    private static final String EXPECTED_BODY = """
            /// The `axis` block-state property.
            #[derive(Clone, Copy, Debug, PartialEq, Eq)]
            pub enum Axis {
                X,
                Y,
                Z,
            }

            impl Axis {
                pub const fn as_str(self) -> &'static str {
                    match self {
                        Axis::X => "x",
                        Axis::Y => "y",
                        Axis::Z => "z",
                    }
                }
            }

            /// The `half` block-state property.
            #[derive(Clone, Copy, Debug, PartialEq, Eq)]
            pub enum Half {
                Top,
                Bottom,
            }

            impl Half {
                pub const fn as_str(self) -> &'static str {
                    match self {
                        Half::Top => "top",
                        Half::Bottom => "bottom",
                    }
                }
            }

            pub mod blocks {
                pub const AIR: crate::registry::BlockId = crate::registry::BlockId::new("minecraft:air");
                pub const RED_CONCRETE: crate::registry::BlockId = crate::registry::BlockId::new("minecraft:red_concrete");
                pub const REDSTONE_BLOCK: crate::registry::BlockId = crate::registry::BlockId::new("minecraft:redstone_block");
            }

            pub mod items {
                pub const APPLE: crate::registry::ItemId = crate::registry::ItemId::new("minecraft:apple");
                pub const STICK: crate::registry::ItemId = crate::registry::ItemId::new("minecraft:stick");
            }
            """;

    /** The generated text with the contract header stripped, so the body can be compared exactly. */
    private static String body(String rendered) {
        int firstItem = rendered.indexOf("/// The `");
        assertTrue(firstItem > 0, "the header must come before the first item");
        return rendered.substring(firstItem);
    }

    @Test
    void rendersTheContractShapeExactly() {
        // Enums passed out of alphabetical order: the generator sorts by type name.
        String rendered = RegistryExporter.render(List.of(HALF, AXIS), BLOCKS, ITEMS);
        assertEquals(EXPECTED_BODY, body(rendered));
    }

    @Test
    void headerRepeatsTheFormatContract() {
        String rendered = RegistryExporter.render(List.of(AXIS), BLOCKS, ITEMS);
        assertTrue(rendered.startsWith("// @generated — the identifier registry for the Billboard SDK.\n"),
                "the file must be self-describing from its first line");
        assertTrue(rendered.contains("// FORMAT CONTRACT."));
        // The four contract points, so a future edit cannot quietly drop one.
        assertTrue(rendered.contains("//  1. Plain Rust items"));
        assertTrue(rendered.contains("//  2. First the common block-state enums"));
        assertTrue(rendered.contains("//  3. Then `pub mod blocks`, then `pub mod items`"));
        assertTrue(rendered.contains("//  4. One entry per line"));
        // Nothing version- or time-stamped, or two exports of one registry would differ.
        assertFalse(rendered.contains("26.2"), "the export must not be version-stamped");
    }

    @Test
    void identicalInputRendersByteIdenticalOutput() {
        assertEquals(RegistryExporter.render(List.of(AXIS, HALF), BLOCKS, ITEMS),
                RegistryExporter.render(List.of(HALF, AXIS), List.of(BLOCKS.get(2),
                        BLOCKS.get(0), BLOCKS.get(1)), ITEMS));
    }

    @Test
    void blocksModulePrecedesItemsModule() {
        String rendered = RegistryExporter.render(List.of(), BLOCKS, ITEMS);
        assertTrue(rendered.indexOf("pub mod blocks {") < rendered.indexOf("pub mod items {"));
        // With no enums the body is just the two modules, still separated by one blank line.
        assertTrue(rendered.contains("}\n\npub mod items {"));
    }

    @Test
    void constNameDropsTheNamespaceAndUpperCases() {
        assertEquals("RED_CONCRETE", Entry.of("minecraft:red_concrete").constName());
        assertEquals("STONE", Entry.of("minecraft:stone").constName());
        // A namespace-less id is still accepted (the id string is emitted verbatim either way).
        assertEquals("STONE", Entry.of("stone").constName());
    }

    @Test
    void pascalCaseBuildsVariantNamesFromVanillaValues() {
        assertEquals("North", RegistryExporter.pascalCase("north"));
        assertEquals("LightBlue", RegistryExporter.pascalCase("light_blue"));
        assertEquals("Top", RegistryExporter.pascalCase("top"));
    }

    @Test
    void moduleBodiesHaveNoBlankLinesOrDocComments() {
        String rendered = RegistryExporter.render(List.of(AXIS), BLOCKS, ITEMS);
        String blocks = rendered.substring(rendered.indexOf("pub mod blocks {"),
                rendered.indexOf("pub mod items {"));
        // Point 4: one entry per line, nothing else inside a module.
        for (String line : blocks.lines().toList()) {
            assertFalse(line.isEmpty() && !line.equals(blocks.lines().toList().getLast()),
                    "no blank lines inside a module: " + blocks);
            assertFalse(line.startsWith("    ///"), "no doc comments inside a module");
        }
        assertEquals(3, blocks.lines().filter(l -> l.startsWith("    pub const ")).count());
    }
}
