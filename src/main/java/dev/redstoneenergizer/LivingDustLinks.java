package dev.redstoneenergizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The extra connection bits that make living redstone dust draw a line towards a chargeable item.
 *
 * <p>living_item recomputes the dust connection mask on every container tick and writes it back
 * onto the stack, so the bits cannot be added from the outside - the next tick would overwrite
 * them, and there would be no packet to show them. The mixin calls into here from inside that
 * computation, right before the caller compares and syncs the result.
 *
 * <p>Everything living_item-specific is reflective: the mod is optional and is not a compile
 * dependency. Missing classes or renamed fields turn the lines off, never crash.
 */
public final class LivingDustLinks {

    private static final Logger LOGGER = LoggerFactory.getLogger("SomeIdeas/DustLinks");

    /** Connection bit per direction, the same order and values living_item uses. */
    private static final int[] BITS = {1, 2, 4, 8};

    private static boolean resolved;
    private static boolean usable;
    private static Field contextField;
    private static Field sizeField;
    private static Field widthField;
    private static Method getItemMethod;
    private static boolean logged;

    private LivingDustLinks() {
    }

    /**
     * Adds one bit per neighbouring slot that holds an item able to store FE.
     *
     * @param propagation the living_item propagation object the mixin is injected into
     * @param slot        logical slot of the dust in its container
     * @param conn        the connection mask living_item just computed
     * @return {@code conn} with the extra bits, or {@code conn} itself when nothing applies
     */
    public static byte extend(Object propagation, int slot, byte conn) {
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (!cfg.livingEnabled || !cfg.livingConnectItems) {
            return conn;
        }
        if (!resolved) {
            resolve(propagation);
        }
        if (!usable) {
            return conn;
        }
        try {
            Object context = contextField.get(propagation);
            int size = sizeField.getInt(propagation);
            int width = widthField.getInt(propagation);
            int extra = 0;
            for (int dir = 0; dir < 4; dir++) {
                int neighbor = neighborOf(slot, dir, size, width);
                if (neighbor < 0) {
                    continue;
                }
                ItemStack stack = (ItemStack) getItemMethod.invoke(context, neighbor);
                // Any item with an energy storage is chargeable, full or not: the line is wiring,
                // not a level indicator.
                if (!stack.isEmpty() && stack.getCapability(Capabilities.EnergyStorage.ITEM) != null) {
                    extra |= BITS[dir];
                }
            }
            if (!logged) {
                logged = true;
                LOGGER.info("living_item dust links active");
            }
            return (byte) (conn | extra);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            usable = false;
            LOGGER.warn("living_item dust links disabled: {}", e.toString());
            return conn;
        }
    }

    private static void resolve(Object propagation) {
        resolved = true;
        try {
            Class<?> type = propagation.getClass();
            contextField = accessible(type.getDeclaredField("context"));
            sizeField = accessible(type.getDeclaredField("size"));
            widthField = accessible(type.getDeclaredField("width"));
            getItemMethod = contextField.getType().getMethod("getItem", int.class);
            usable = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            usable = false;
            LOGGER.warn("living_item dust links unavailable, the mod's propagation class changed: {}", e.toString());
        }
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    /**
     * The neighbour slot in a row-major grid, or {@code -1} at the border. Mirrors living_item's
     * own {@code ContainerContext.resolveNeighbor}, which is package-private.
     */
    private static int neighborOf(int slot, int dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        switch (dir) {
            case 0 -> row--;
            case 1 -> row++;
            case 2 -> col--;
            case 3 -> col++;
            default -> {
                return -1;
            }
        }
        if (col < 0 || col >= width || row < 0) {
            return -1;
        }
        int result = row * width + col;
        return result < size ? result : -1;
    }
}
