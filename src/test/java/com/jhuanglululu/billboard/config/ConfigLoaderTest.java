package com.jhuanglululu.billboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @Test
    void readsAllValues(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [runtime]
                threads = 6
                pool-shrink-delay-ticks = 300
                instruction-budget = 2000000
                memory-cap-mib = 32

                [proximity]
                radius = 48
                check-interval = 10
                linger-ticks = 40

                [logging]
                log-viewers = ["alice", "bob"]
                """);

        BillboardConfig c = ConfigLoader.load(file);
        assertEquals(6, c.runtime().threads());
        assertEquals(300, c.runtime().poolShrinkDelayTicks());
        assertEquals(2_000_000L, c.runtime().instructionBudget());
        assertEquals(32, c.runtime().memoryCapMib());
        assertEquals(32L * 1024 * 1024, c.runtime().memoryCapBytes());
        assertEquals(48, c.proximity().radius());
        assertEquals(10, c.proximity().checkInterval());
        assertEquals(40, c.proximity().lingerTicks());
        assertEquals(java.util.List.of("alice", "bob"), c.logViewers());
    }

    @Test
    void missingKeysFallBackToDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [runtime]
                threads = 2
                """);

        BillboardConfig c = ConfigLoader.load(file);
        BillboardConfig d = BillboardConfig.defaults();
        assertEquals(2, c.runtime().threads());                                    // overridden
        assertEquals(d.runtime().instructionBudget(), c.runtime().instructionBudget()); // default
        assertEquals(d.proximity().radius(), c.proximity().radius());              // default
        assertEquals(d.logViewers(), c.logViewers());                             // default
    }
}
