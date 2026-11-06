package dev.redstoneenergizer;

import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * The one routine that pushes energy into the items of an inventory, shared by
 * {@link ContainerCharger} and {@link PlayerCharger}.
 */
final class ItemCharging {

    private ItemCharging() {
    }

    /**
     * Charges every chargeable stack in the inventory.
     *
     * <p>Stacks of more than one item are always skipped: an energy capability on a stack is a
     * per-item capability, so charging "the stack" would either charge every item for free or
     * have to split the stack. Neither is what this mod does - only single items are charged.
     *
     * @param charged     stacks that already received energy during this event; a container that is
     *                    reached through two block entities (a double chest) would otherwise be
     *                    charged twice
     * @param chargedOne  one-element flag for sequential mode, so a double chest charges only one
     *                    stack per event even though it is visited twice
     */
    static void charge(IItemHandler inventory, int amount, boolean simultaneous,
            Set<ItemStack> charged, boolean[] chargedOne) {
        if (!simultaneous && chargedOne[0]) {
            return;
        }
        int slots = inventory.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() > 1 || charged.contains(stack)) {
                continue;
            }
            IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || !storage.canReceive()) {
                continue;
            }
            // No simulation pass: an item that cannot take anything simply returns 0, and the
            // stack is only written back when something was actually accepted.
            if (storage.receiveEnergy(amount, false) > 0) {
                charged.add(stack);
                writeBack(inventory, slot, stack);
                if (!simultaneous) {
                    // One slot at a time: the first item that could take energy got this event.
                    chargedOne[0] = true;
                    return;
                }
            }
        }
    }

    /**
     * Write the mutated stack back so the inventory marks itself dirty and the new energy is
     * persisted. Plain {@link IItemHandler} is read-only, so a modifiable view is used when present.
     */
    static void writeBack(IItemHandler inventory, int slot, ItemStack stack) {
        if (inventory instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(slot, stack);
        }
    }
}
