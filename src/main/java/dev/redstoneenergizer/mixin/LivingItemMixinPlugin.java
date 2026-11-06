package dev.redstoneenergizer.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the living_item mixin only when that mod is installed.
 *
 * <p>Detected by class resource instead of the mod list: mixin configs are handled before mods are
 * constructed, so the mod list is not reliable at this point.
 */
public final class LivingItemMixinPlugin implements IMixinConfigPlugin {

    private static final String LIVING_ITEM_CLASS =
            "com/qiqi/li/living/domain/redstone/RedstonePropagation.class";

    private static Boolean present;

    private static boolean present() {
        if (present == null) {
            present = LivingItemMixinPlugin.class.getClassLoader().getResource(LIVING_ITEM_CLASS) != null;
        }
        return present;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return present();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
