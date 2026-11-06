package dev.redstoneenergizer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Mod entry point.
 *
 * <p>The mod has no blocks and no items: it observes the world and pushes Forge Energy into any
 * block that can receive energy while that block is receiving a redstone signal.
 */
@Mod(RedstoneEnergizer.MOD_ID)
public final class RedstoneEnergizer {

    public static final String MOD_ID = "redstone_energizer";

    public RedstoneEnergizer(ModContainer container, IEventBus modBus) {
        // COMMON config -> config/redstone_energizer-common.toml, shared by client and server.
        container.registerConfig(ModConfig.Type.COMMON, EnergizerConfig.SPEC, "redstone_energizer-common.toml");

        // The tick paths read a cached snapshot instead of the spec; these two events refill it on
        // startup, on a file change and when the in-game config screen is closed.
        modBus.addListener(EnergizerConfig::onConfigLoading);
        modBus.addListener(EnergizerConfig::onConfigReloading);

        // All of these are game-bus events (the world is running, not the mod loading).
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onNeighborNotify);
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(EnergyFeed::onLevelTick);

        NeoForge.EVENT_BUS.addListener(ContainerCharger::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(ContainerCharger::onNeighborNotify);
        NeoForge.EVENT_BUS.addListener(ContainerCharger::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(ContainerCharger::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(ContainerCharger::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ContainerCharger::onLevelTick);

        NeoForge.EVENT_BUS.addListener(PlayerCharger::onLevelTick);

        // Optional living_item integration; a no-op unless that mod is installed and enabled.
        NeoForge.EVENT_BUS.addListener(LivingCompat::onLevelTick);
    }
}
