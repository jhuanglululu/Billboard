# Billboard

A Paper plugin that plays animations authored in Rust and compiled to WASM:
client-side display entities, sounds and particles rendered per-viewer via
packets — think Times Square billboards, driven by imperative guest code
with real `sleep(ticks)`.

Runs on the [WASMachine](https://github.com/jhuanglululu/WASMachine)
interpreter (consumed as a Gradle source dependency tracking `main`) and is
its reference embedder. Animations are written against the
[`billboard-rs`](https://github.com/jhuanglululu/billboard-rs) SDK and
dropped into `plugins/Billboard/animations/` as `.wasm` files.

Personal-use plugin: versioned by git, no publishing pipeline.
