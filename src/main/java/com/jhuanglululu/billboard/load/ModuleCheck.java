package com.jhuanglululu.billboard.load;

import com.jhuanglululu.billboard.runtime.AnimationInstance;
import com.jhuanglululu.billboard.runtime.ContentValidator;
import com.jhuanglululu.wasm.Export;
import com.jhuanglululu.wasm.ExternalKind;
import com.jhuanglululu.wasm.FuncType;
import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasm.ValType;
import com.jhuanglululu.wasm.WasmParseException;
import java.util.List;
import java.util.Optional;

/**
 * Load-time validation of one animation module: it parses, it exports an {@code _engine_main} the
 * scheduler can actually call, it instantiates far enough to have its exports resolved, and both
 * handshakes answer — the engine's {@code _engine_abi()} and Billboard's {@code _billboard_abi()},
 * which since the ABI 3 namespace split must report exactly
 * {@link AnimationInstance#ABI_VERSION}.
 *
 * <p>This runs at server start and on {@code /billboard reload}, <em>before</em> any player can be
 * near a placement, which is the whole point: a broken {@code .wasm} used to surface as a failed
 * start the first time somebody walked up to it. The check builds a real
 * {@link AnimationInstance} against a {@link NoOpRenderer} rather than re-implementing the
 * handshake, so what passes here is exactly what will run later — no second code path to drift.
 * Constructing an instance runs no animation code (task 0's {@code main} is only invoked by
 * {@code tick}), so validation has no side effects.
 *
 * <p>Pure apart from the runtime it exercises: no Bukkit, fully testable from module bytes.
 */
public final class ModuleCheck {

    /** Memory cap for the throwaway validation instance; nothing allocates during the handshake. */
    private static final long VALIDATION_MEMORY_CAP = 1 << 16;

    /**
     * The entry point the scheduler invokes every tick: {@code _engine_main() -> i32}. The name
     * is the engine's since ABI 3 — the engine is what invokes it — but the signature check
     * stays here, because the {@code i32} it returns is a Billboard exit code.
     */
    private static final String MAIN = "_engine_main";

    private ModuleCheck() {}

    /** A module that passed, or the reason it did not. */
    public record Result(Module module, Optional<String> error) {

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * Parses and validates {@code bytes}.
     *
     * @return the parsed module when it is fit to run, else the failure reason
     */
    public static Result check(byte[] bytes) {
        Module module;
        try {
            module = Module.parse(bytes);
        } catch (WasmParseException e) {
            return new Result(null, Optional.of("not valid WebAssembly: " + e.getMessage()));
        }
        Optional<String> badMain = checkMainExport(module);
        if (badMain.isPresent()) {
            return new Result(null, badMain);
        }
        try {
            AnimationInstance probe = new AnimationInstance("validation", module,
                    new NoOpRenderer(), blockState -> true, ContentValidator.PERMISSIVE,
                    (animation, message) -> { }, VALIDATION_MEMORY_CAP, 0L);
            return new Result(module, probe.loadError());
        } catch (RuntimeException e) {
            // A missing import, a missing __heap_base, or an unresolvable export: the module can
            // never be instantiated, so it can never run.
            return new Result(null, Optional.of("cannot be instantiated: " + e.getMessage()));
        }
    }

    /**
     * Checks the entry point exists with the signature the scheduler calls it by. Without this a
     * module missing {@code _engine_main} — or exporting one that takes arguments or returns no
     * exit code — loads happily and only fails when the first player walks up to it, which is the
     * whole failure class load-time validation exists to remove.
     */
    private static Optional<String> checkMainExport(Module module) {
        Export main = null;
        for (Export e : module.exports()) {
            if (MAIN.equals(e.name())) {
                main = e;
                break;
            }
        }
        if (main == null) {
            return Optional.of("does not export " + MAIN + "()");
        }
        if (main.kind() != ExternalKind.FUNCTION) {
            return Optional.of(MAIN + " is exported but is not a function");
        }
        if (main.index() < module.importedFunctionCount()) {
            return Optional.of(MAIN + " is exported but resolves to an imported function");
        }
        FuncType type = module.functionType(main.index());
        if (!type.params().isEmpty()) {
            return Optional.of(MAIN + " must take no arguments but takes " + type.params().size());
        }
        if (!List.of(ValType.I32).equals(type.results())) {
            return Optional.of(MAIN + " must return one i32 exit code but returns "
                    + type.results());
        }
        return Optional.empty();
    }
}
