package dev.redstoneenergizer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point.
 *
 * <p>Registers NeoForge's generic configuration screen, which is what adds the "Config" button to
 * this mod's entry in the mods list. The screen is created from the {@link EnergizerConfig} spec, so
 * it always shows every option without any extra UI code. Everything the mod does reads the config
 * on every tick, so a change made while the game is running takes effect immediately.
 *
 * <p>Kept in its own class, and loaded only on the client, so a dedicated server never touches the
 * client-only GUI classes.
 */
@Mod(value = RedstoneEnergizer.MOD_ID, dist = Dist.CLIENT)
public final class RedstoneEnergizerClient {

    public RedstoneEnergizerClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
