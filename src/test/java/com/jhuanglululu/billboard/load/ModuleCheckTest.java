package com.jhuanglululu.billboard.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jhuanglululu.billboard.runtime.AnimationInstance;
import com.jhuanglululu.wasmachine.runtime.MachineInstance;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Load-time animation validation: a module is accepted only if it parses, instantiates, and answers
 * both handshakes with a version this host speaks — the engine's {@code _engine_abi} and
 * Billboard's {@code _billboard_abi}. These are the checks that used to happen the first time a
 * player walked up to a billboard.
 *
 * <p>Modules are hand-assembled here (LEB and section framing written out by hand, as elsewhere in
 * this suite) so the bytes under test are not produced by the parser that reads them.
 */
class ModuleCheckTest {

    // --- minimal hand-rolled module bytes ---

    private static byte[] uleb(int value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int v = value;
        do {
            int b = v & 0x7F;
            v >>>= 7;
            o.write(v != 0 ? b | 0x80 : b);
        } while (v != 0);
        return o.toByteArray();
    }

    private static byte[] section(int id, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id);
        o.writeBytes(uleb(body.length));
        o.writeBytes(body);
        return o.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] name(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(uleb(utf8.length));
        o.writeBytes(utf8);
        return o.toByteArray();
    }

    /** A module reporting the versions this host speaks — the smallest thing that validates. */
    private static byte[] module() {
        return module(AnimationInstance.ABI_VERSION);
    }

    /**
     * A module exporting {@code _engine_main}, both handshakes and {@code __heap_base}. The
     * engine handshake reports the version WASMachine speaks; {@code _billboard_abi} reports
     * {@code abiVersion}, which is what the accept/reject tests vary.
     */
    private static byte[] module(int abiVersion) {
        return module(abiVersion, MachineInstance.ENGINE_ABI_VERSION);
    }

    private static byte[] module(int abiVersion, int engineAbiVersion) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00));
        m.writeBytes(section(1, bytes(0x01, 0x60, 0x00, 0x01, 0x7F)));      // type ()->(i32)
        m.writeBytes(section(3, bytes(0x03, 0x00, 0x00, 0x00)));            // three funcs of type 0
        m.writeBytes(section(6, bytes(0x01, 0x7F, 0x00, 0x41, 0x80, 0x08, 0x0B))); // __heap_base=1024
        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        exports.writeBytes(uleb(4));
        exports.writeBytes(name("_engine_main"));
        exports.writeBytes(bytes(0x00, 0x00));
        exports.writeBytes(name("_billboard_abi"));
        exports.writeBytes(bytes(0x00, 0x01));
        exports.writeBytes(name("_engine_abi"));
        exports.writeBytes(bytes(0x00, 0x02));
        exports.writeBytes(name("__heap_base"));
        exports.writeBytes(bytes(0x03, 0x00));
        m.writeBytes(section(7, exports.toByteArray()));
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(uleb(3));
        code.writeBytes(bytes(0x04, 0x00, 0x41, 0x00, 0x0B));               // main: i32.const 0
        code.writeBytes(bytes(0x04, 0x00, 0x41, abiVersion, 0x0B));         // billboard abi
        code.writeBytes(bytes(0x04, 0x00, 0x41, engineAbiVersion, 0x0B));   // engine abi
        m.writeBytes(section(10, code.toByteArray()));
        return m.toByteArray();
    }

    /**
     * A module whose {@code _engine_main} has the wrong signature, or is missing entirely.
     *
     * @param mainType {@code 0} = {@code ()->(i32)} (correct), {@code 1} = {@code (i32)->(i32)},
     *     {@code 2} = {@code ()->()}
     * @param exportMain whether to export {@code _engine_main} at all
     */
    private static byte[] moduleWithMain(int mainType, boolean exportMain) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00));
        // types: 0 ()->(i32), 1 (i32)->(i32), 2 ()->()
        m.writeBytes(section(1, bytes(0x03,
                0x60, 0x00, 0x01, 0x7F,
                0x60, 0x01, 0x7F, 0x01, 0x7F,
                0x60, 0x00, 0x00)));
        // func0 = main, func1 = billboard abi, func2 = engine abi
        m.writeBytes(section(3, bytes(0x03, mainType, 0x00, 0x00)));
        m.writeBytes(section(6, bytes(0x01, 0x7F, 0x00, 0x41, 0x80, 0x08, 0x0B)));
        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        exports.writeBytes(uleb(exportMain ? 4 : 3));
        if (exportMain) {
            exports.writeBytes(name("_engine_main"));
            exports.writeBytes(bytes(0x00, 0x00));
        }
        exports.writeBytes(name("_billboard_abi"));
        exports.writeBytes(bytes(0x00, 0x01));
        exports.writeBytes(name("_engine_abi"));
        exports.writeBytes(bytes(0x00, 0x02));
        exports.writeBytes(name("__heap_base"));
        exports.writeBytes(bytes(0x03, 0x00));
        m.writeBytes(section(7, exports.toByteArray()));
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(uleb(3));
        // A body valid for whichever signature main has.
        if (mainType == 2) {
            code.writeBytes(bytes(0x02, 0x00, 0x0B));               // ()->() : just end
        } else {
            code.writeBytes(bytes(0x04, 0x00, 0x41, 0x00, 0x0B));   // returns i32.const 0
        }
        code.writeBytes(bytes(0x04, 0x00, 0x41, AnimationInstance.ABI_VERSION, 0x0B));
        code.writeBytes(bytes(0x04, 0x00, 0x41, MachineInstance.ENGINE_ABI_VERSION, 0x0B));
        m.writeBytes(section(10, code.toByteArray()));
        return m.toByteArray();
    }

    /**
     * The same module missing one handshake export.
     *
     * @param keep the handshake it still exports, or {@code null} for neither
     */
    private static byte[] moduleWithoutAbi(String keep) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00));
        m.writeBytes(section(1, bytes(0x01, 0x60, 0x00, 0x01, 0x7F)));
        m.writeBytes(section(3, bytes(0x02, 0x00, 0x00)));
        m.writeBytes(section(6, bytes(0x01, 0x7F, 0x00, 0x41, 0x80, 0x08, 0x0B)));
        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        exports.writeBytes(uleb(keep == null ? 2 : 3));
        exports.writeBytes(name("_engine_main"));
        exports.writeBytes(bytes(0x00, 0x00));
        if (keep != null) {
            exports.writeBytes(name(keep));
            exports.writeBytes(bytes(0x00, 0x01));
        }
        exports.writeBytes(name("__heap_base"));
        exports.writeBytes(bytes(0x03, 0x00));
        m.writeBytes(section(7, exports.toByteArray()));
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(uleb(2));
        code.writeBytes(bytes(0x04, 0x00, 0x41, 0x00, 0x0B));
        code.writeBytes(bytes(0x04, 0x00, 0x41, 0x01, 0x0B));
        m.writeBytes(section(10, code.toByteArray()));
        return m.toByteArray();
    }

    /** A module the handshake refused: it parsed and instantiated, but reported a bad version. */
    private static void assertHandshakeRejected(ModuleCheck.Result result, String needle) {
        assertFalse(result.ok(), "expected the handshake to reject the module");
        String error = result.error().orElseThrow();
        assertTrue(error.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)),
                "expected \"" + needle + "\" in: " + error);
    }

    private static void assertRejected(ModuleCheck.Result result, String needle) {
        assertFalse(result.ok(), "expected the module to be rejected");
        assertNull(result.module(), "a rejected module must not be handed on");
        String error = result.error().orElseThrow();
        assertTrue(error.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)),
                "expected \"" + needle + "\" in: " + error);
    }

    @Test
    void theCurrentAbiVersionIsAccepted() {
        ModuleCheck.Result result = ModuleCheck.check(module());
        assertTrue(result.ok(), () -> "v3 must load: " + result.error().orElse(""));
        assertNotNull(result.module());
    }

    @Test
    void preSplitAbiVersionsAreRejectedAtLoadTime() {
        // ABI 3 moved the engine imports into their own module, so a v1/v2 guest could not link
        // even if the handshake let it through. The break is stated once, here.
        for (int old : new int[] {1, 2}) {
            ModuleCheck.Result result = ModuleCheck.check(module(old));
            assertFalse(result.ok(), "v" + old + " must not load after the namespace split");
            assertTrue(result.error().orElseThrow().contains("returned " + old),
                    result.error().orElseThrow());
        }
    }

    @Test
    void newerAbiVersionIsRejectedAtLoadTime() {
        ModuleCheck.Result result = ModuleCheck.check(module(4));
        assertFalse(result.ok());
        assertTrue(result.error().orElseThrow().contains("returned 4"));
    }

    @Test
    void anEngineHandshakeThisEngineDoesNotSpeakIsRejected() {
        // Both halves are checked, and the message names the export that disagreed.
        ModuleCheck.Result result = ModuleCheck.check(
                module(AnimationInstance.ABI_VERSION, MachineInstance.ENGINE_ABI_VERSION + 1));
        assertFalse(result.ok());
        assertTrue(result.error().orElseThrow().contains("_engine_abi"),
                result.error().orElseThrow());
    }

    @Test
    void nonWasmBytesAreRejected() {
        assertRejected(ModuleCheck.check("this is not wasm".getBytes(StandardCharsets.UTF_8)),
                "not valid WebAssembly");
    }

    @Test
    void truncatedModuleIsRejected() {
        assertRejected(ModuleCheck.check(bytes(0x00, 0x61, 0x73, 0x6D)), "not valid WebAssembly");
    }

    @Test
    void missingEitherAbiExportIsRejected() {
        // A handshake failure is reported by the instance rather than by the export scan, so the
        // module itself is still handed back — only ok() and the message matter here.
        assertHandshakeRejected(ModuleCheck.check(moduleWithoutAbi("_engine_abi")),
                "_billboard_abi");
        assertHandshakeRejected(ModuleCheck.check(moduleWithoutAbi("_billboard_abi")),
                "_engine_abi");
        assertHandshakeRejected(ModuleCheck.check(moduleWithoutAbi(null)), "abi");
    }

    @Test
    void validationRunsNoAnimationCode() {
        // A module whose main traps immediately still validates: only the handshake is invoked.
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00));
        m.writeBytes(section(1, bytes(0x01, 0x60, 0x00, 0x01, 0x7F)));
        m.writeBytes(section(3, bytes(0x03, 0x00, 0x00, 0x00)));
        m.writeBytes(section(6, bytes(0x01, 0x7F, 0x00, 0x41, 0x80, 0x08, 0x0B)));
        ByteArrayOutputStream exports = new ByteArrayOutputStream();
        exports.writeBytes(uleb(4));
        exports.writeBytes(name("_engine_main"));
        exports.writeBytes(bytes(0x00, 0x00));
        exports.writeBytes(name("_billboard_abi"));
        exports.writeBytes(bytes(0x00, 0x01));
        exports.writeBytes(name("_engine_abi"));
        exports.writeBytes(bytes(0x00, 0x02));
        exports.writeBytes(name("__heap_base"));
        exports.writeBytes(bytes(0x03, 0x00));
        m.writeBytes(section(7, exports.toByteArray()));
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.writeBytes(uleb(3));
        code.writeBytes(bytes(0x03, 0x00, 0x00, 0x0B));          // main: unreachable
        code.writeBytes(bytes(0x04, 0x00, 0x41, AnimationInstance.ABI_VERSION, 0x0B));
        code.writeBytes(bytes(0x04, 0x00, 0x41, MachineInstance.ENGINE_ABI_VERSION, 0x0B));
        m.writeBytes(section(10, code.toByteArray()));

        assertTrue(ModuleCheck.check(m.toByteArray()).ok(),
                "construction must not run task 0, so a trapping main still loads");
    }

    @Test
    void missingMainExportIsRejectedAtLoadTime() {
        // Without this check the module loads and only fails when a player walks up to it — the
        // exact class of failure load-time validation exists to eliminate.
        assertRejected(ModuleCheck.check(moduleWithMain(0, false)), "_engine_main");
    }

    @Test
    void mainTakingArgumentsIsRejected() {
        assertRejected(ModuleCheck.check(moduleWithMain(1, true)), "_engine_main");
    }

    @Test
    void mainReturningNothingIsRejected() {
        assertRejected(ModuleCheck.check(moduleWithMain(2, true)), "_engine_main");
    }

    @Test
    void aCorrectMainSignatureIsAccepted() {
        // The control: the same builder with the right signature must still pass, so the three
        // rejections above are about the signature and not about the builder.
        ModuleCheck.Result result = ModuleCheck.check(moduleWithMain(0, true));
        assertTrue(result.ok(), () -> "expected acceptance but got " + result.error().orElse(""));
    }

    // --- folder scanning ---

    @Test
    void oneBrokenFileDoesNotBlockTheFolder(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("good.wasm"), module());
        Files.write(dir.resolve("broken.wasm"), "garbage".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("other.wasm"), moduleWithMain(0, true));
        Files.write(dir.resolve("notes.txt"), "ignored".getBytes(StandardCharsets.UTF_8));

        AnimationLoader.Result result = AnimationLoader.load(dir);

        assertEquals(List.of("good", "other"), result.modules().keySet().stream().sorted().toList());
        assertEquals(2, result.hashes().size());
        assertEquals(1, result.issues().size());
        LoadIssue issue = result.issues().getFirst();
        assertEquals(LoadIssue.Scope.ANIMATION, issue.scope());
        assertEquals("broken", issue.subject());
    }

    @Test
    void anEmptyOrAbsentFolderIsNotAnIssue(@TempDir Path dir) {
        AnimationLoader.Result result = AnimationLoader.load(dir.resolve("animations"));
        assertEquals(List.of(), result.issues());
        assertTrue(result.modules().isEmpty());
    }

    @Test
    void hashesChangeWithContentSoReloadCanDiff(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("a.wasm"), module());
        int first = AnimationLoader.load(dir).hashes().get("a");
        Files.write(dir.resolve("a.wasm"), moduleWithMain(0, true));
        assertFalse(first == AnimationLoader.load(dir).hashes().get("a"),
                "a changed file must produce a different hash");
    }
}
