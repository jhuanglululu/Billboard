package com.jhuanglululu.billboard.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads config.toml into a {@link BillboardConfig}. Missing keys fall back to the
 * template defaults, so a partially-edited file never breaks startup. No Bukkit API,
 * so it is unit-testable against a plain file.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static BillboardConfig load(Path file) {
        try (CommentedFileConfig config = CommentedFileConfig.builder(file)
                .preserveInsertionOrder().sync().build()) {
            config.load();
            return fromConfig(config);
        }
    }

    static BillboardConfig fromConfig(Config config) {
        BillboardConfig d = BillboardConfig.defaults();
        BillboardConfig.RuntimeSettings runtime = new BillboardConfig.RuntimeSettings(
                config.getIntOrElse("runtime.threads", d.runtime().threads()),
                config.getIntOrElse("runtime.pool-shrink-delay-ticks", d.runtime().poolShrinkDelayTicks()),
                config.getLongOrElse("runtime.instruction-budget", d.runtime().instructionBudget()),
                config.getIntOrElse("runtime.memory-cap-mib", d.runtime().memoryCapMib()));
        BillboardConfig.Proximity proximity = new BillboardConfig.Proximity(
                config.getIntOrElse("proximity.radius", d.proximity().radius()),
                config.getIntOrElse("proximity.check-interval", d.proximity().checkInterval()),
                config.getIntOrElse("proximity.linger-ticks", d.proximity().lingerTicks()));
        List<String> logViewers = config.getOrElse("logging.log-viewers", d.logViewers());
        return new BillboardConfig(runtime, proximity, logViewers);
    }
}
