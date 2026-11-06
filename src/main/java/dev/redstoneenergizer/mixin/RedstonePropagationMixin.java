package dev.redstoneenergizer.mixin;

import dev.redstoneenergizer.LivingDustLinks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Merges the chargeable-item connection bits into living_item's dust connection mask.
 *
 * <p>{@code computeDustConnections} is where that mask is produced, and the caller immediately
 * writes it onto the stack and syncs it, so this is the only place where an extra bit survives
 * more than one tick. {@code require = 0}: if a living_item update renames the method the lines
 * silently stop instead of crashing the game.
 */
@Mixin(targets = "com.qiqi.li.living.domain.redstone.RedstonePropagation", remap = false)
public class RedstonePropagationMixin {

    @Inject(method = "computeDustConnections(I)B", at = @At("RETURN"), cancellable = true,
            remap = false, require = 0)
    private void redstone_energizer$connectChargedItems(int slot, CallbackInfoReturnable<Byte> cir) {
        Byte current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        byte extended = LivingDustLinks.extend(this, slot, current);
        if (extended != current) {
            cir.setReturnValue(extended);
        }
    }
}
