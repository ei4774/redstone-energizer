package dev.redstoneenergizer;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Optional integration with the living_item mod: a "living redstone" item charges the items around
 * it inside the same container.
 *
 * <p>No compile dependency. The mod is only touched when it is present, and its state is read
 * through the item component it publishes ({@code living_item:living_redstone_data}), which is a
 * record with a {@code signalStrength()} accessor. The accessor is called reflectively and the
 * result cached; if the id or the accessor is ever missing, the whole feature quietly turns itself
 * off instead of crashing.
 *
 * <p>That component is the right source: living_item recalculates the container-level redstone
 * network every tick and mirrors the result onto the stack, so reading it gives the current signal
 * level - including values above 15, which is exactly what its stack-count based cap allows.
 *
 * <p>Only the four slots around a redstone item are charged, following the container grid, and the
 * direction matters per component: dust, torch, lever, button and block feed all four neighbours,
 * while a repeater or a comparator feeds only the slot it points at - the same slot living_item
 * itself propagates its signal to. Several sources reaching one slot do not stack, the strongest
 * signal wins, exactly like redstone feeding.
 */
public final class LivingCompat {

    private static final String MOD_ID = "living_item";
    private static final String LIVING_MARKER = "is_living";

    /**
     * Every living redstone component that emits a signal. The accessor is read reflectively: an
     * {@code int} is used as-is (redstone dust can exceed 15 thanks to its stack-count cap), a
     * {@code boolean} means "this component is on" and contributes {@code onTrue}.
     *
     * <p>{@code dataClass} is the record behind the component: living_item removes the component
     * when it equals the default ({@code LivingItemManager.setData}), so an absent component means
     * "the default value", not "nothing". A freshly made, lit torch has no component at all - read
     * naively it would emit nothing.
     *
     * <p>The arity says where the output goes, mirroring what living_item itself propagates: a
     * repeater and a comparator only feed their {@code direction}, a torch feeds everything except
     * the side it is attached to ({@code edgeIndex(opposite(direction))}), and the rest feed all
     * four.
     *
     * <p>The redstone block carries no data component and is handled separately.
     */
    private static final Source[] SOURCES = {
            new Source("living_redstone_data", "LivingRedstoneData", "signalStrength", 15, Arity.ALL),
            new Source("living_redstone_torch_data", "LivingRedstoneTorchData", "isLit", 15, Arity.NOT_BEHIND),
            new Source("living_lever_data", "LivingLeverData", "powered", 15, Arity.ALL),
            new Source("living_button_data", "LivingButtonData", "pressed", 15, Arity.ALL),
            new Source("living_repeater_data", "LivingRepeaterData", "powered", 15, Arity.ONE),
            new Source("living_comparator_data", "LivingComparatorData", "powered", 15, Arity.ONE),
    };

    /** Where a source sends its output. */
    enum Arity {
        /** All four grid neighbours. */
        ALL,
        /** Only the slot it points at. */
        ONE,
        /** Every neighbour except the side it is attached to. */
        NOT_BEHIND
    }

    /** Direction bits, in the same order as the grid directions used by {@link #route}. */
    static final int ALL_DIRECTIONS = 0b1111;

    /** A living_item direction vector ({@code Pos2D}); {@code (0, 0)} means "not configured". */
    record Vec(int dx, int dy) {
    }

    /** What one living redstone item emits, where it points, and which slots it feeds. */
    private record Emission(int signal, Vec vec, int mask) {
    }

    private static final Emission NOTHING = new Emission(0, null, 0);

    private static boolean resolved;
    private static DataComponentType<?> livingMarker;
    private static boolean capResolved;
    private static Method capMethod;

    /** One data component of the living_item mod and the accessor that reads it. */
    private static final class Source {
        private final String path;
        private final String dataClass;
        private final String accessor;
        private final int onTrue;
        private final Arity arity;
        private DataComponentType<?> type;
        private Method method;
        private Method defaultMethod;
        private Method directionMethod;
        private Method posX;
        private Method posY;

        Source(String path, String dataClass, String accessor, int onTrue, Arity arity) {
            this.path = path;
            this.dataClass = dataClass;
            this.accessor = accessor;
            this.onTrue = onTrue;
            this.arity = arity;
        }

        void resolve() {
            type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, path));
        }

        /** The stack's data, falling back to living_item's own default when the component is gone. */
        private Object data(ItemStack stack) {
            if (type == null) {
                return null;
            }
            Object present = stack.get(LivingCompat.cast(type));
            if (present != null) {
                return present;
            }
            try {
                Method cached = defaultMethod;
                if (cached == null) {
                    cached = Class.forName("com.qiqi.li.living.domain.redstone." + dataClass)
                            .getMethod("of", ItemStack.class);
                    defaultMethod = cached;
                }
                return cached.invoke(null, stack);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        int signal(ItemStack stack) {
            Object data = data(stack);
            if (data == null) {
                return 0;
            }
            Method cached = method;
            if (cached == null) {
                try {
                    cached = data.getClass().getMethod(accessor);
                } catch (NoSuchMethodException e) {
                    return 0;
                }
                method = cached;
            }
            try {
                Object value = cached.invoke(data);
                if (value instanceof Number number) {
                    return number.intValue();
                }
                if (value instanceof Boolean on) {
                    // On = this stack's full output, and that cap grows with the stack count.
                    return on ? capOf(stack.getCount(), onTrue) : 0;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return 0;
            }
            return 0;
        }

        /** The {@code Pos2D} this component stores, or {@code null} when it has none. */
        Vec direction(ItemStack stack) {
            if (arity == Arity.ALL) {
                return null;
            }
            Object data = data(stack);
            if (data == null) {
                return null;
            }
            try {
                if (directionMethod == null) {
                    directionMethod = data.getClass().getMethod("direction");
                    Class<?> pos = directionMethod.getReturnType();
                    posX = pos.getMethod("x");
                    posY = pos.getMethod("y");
                }
                Object pos = directionMethod.invoke(data);
                return new Vec(((Number) posX.invoke(pos)).intValue(),
                        ((Number) posY.invoke(pos)).intValue());
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Which slots a source feeds, mirroring living_item's own edge mapping: a repeater and a
     * comparator use {@code edgeIndex(direction)}, a torch skips {@code edgeIndex(-direction)} -
     * the side it is attached to. A vector it cannot read feeds all four rather than none.
     */
    static int maskFor(Arity arity, Vec vec) {
        if (arity == Arity.ALL || vec == null) {
            return ALL_DIRECTIONS;
        }
        if (arity == Arity.ONE) {
            return 1 << edgeIndex(vec.dx(), vec.dy());
        }
        return ALL_DIRECTIONS & ~(1 << edgeIndex(-vec.dx(), -vec.dy()));
    }

    /**
     * living_item's {@code edgeIndex}: any vector maps to a cardinal edge, and {@code (0, 0)}
     * counts as downwards ({@code y < 0} decides, so zero falls through to down).
     */
    static int edgeIndex(int dx, int dy) {
        if (dx == 0) {
            return dy < 0 ? 0 : 1;
        }
        return dx < 0 ? 2 : 3;
    }

    private LivingCompat() {
    }

    /** Called once per level tick; cheap when the mod is absent or the feature is off. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (!cfg.livingEnabled || cfg.fePerTick <= 0) {
            return;
        }
        resolve();
        if (livingMarker == null) {
            return;
        }

        long time = level.getGameTime();
        boolean containersDue = time % cfg.containerInterval == 0;
        boolean playersDue = time % cfg.playerInterval == 0;
        if (!containersDue && !playersDue) {
            return;
        }

        // Shared per event: the two halves of a double chest expose the same stacks.
        Set<ItemStack> charged = Collections.newSetFromMap(new IdentityHashMap<>());
        if (containersDue) {
            long[] tracked = ContainerCharger.trackedSnapshot(level);
            if (tracked != null) {
                for (long packed : tracked) {
                    BlockPos pos = BlockPos.of(packed);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    IItemHandler inventory = ContainerCharger.findInventory(level, pos);
                    if (inventory != null) {
                        chargeInRange(inventory, cfg, cfg.containerInterval, cfg.containerApplyMultiplier,
                                charged);
                    }
                }
            }
        }
        if (playersDue) {
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                chargeInRange(new InvWrapper(player.getInventory()), cfg, cfg.playerInterval,
                        cfg.playerApplyMultiplier, charged);
            }
        }
    }

    /** What a living redstone component emits, {@link #NOTHING} for anything else. */
    private static Emission emissionOf(ItemStack stack) {
        if (!resolved) {
            resolve();
        }
        if (livingMarker == null || stack.isEmpty()) {
            return NOTHING;
        }
        // One cheap check first: most items in a container are not living items at all.
        if (!stack.has(cast(livingMarker))) {
            return NOTHING;
        }
        // The living redstone block has no data component; it is always a full-strength source,
        // and "full strength" is the stack-count based cap.
        if (stack.is(Items.REDSTONE_BLOCK)) {
            return new Emission(capOf(stack.getCount(), 15), null, ALL_DIRECTIONS);
        }
        Emission best = NOTHING;
        for (Source source : SOURCES) {
            int signal = source.signal(stack);
            if (signal > best.signal()) {
                // The direction belongs to the strongest source, not to whichever came last.
                Vec vec = source.direction(stack);
                best = new Emission(signal, vec, maskFor(source.arity, vec));
            }
        }
        return best;
    }

    /**
     * The signal cap living_item allows a stack of {@code count} to reach. Its own rule:
     * {@code ContainerRedstoneData.getSignalCap(int)}. Falls back when that class is missing, so a
     * renamed method only costs the stack scaling, never the feature.
     */
    private static int capOf(int count, int fallback) {
        if (!capResolved) {
            capResolved = true;
            try {
                Class<?> type = Class.forName("com.qiqi.li.living.domain.redstone.ContainerRedstoneData");
                capMethod = type.getMethod("getSignalCap", int.class);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                capMethod = null;
            }
        }
        if (capMethod == null) {
            return fallback;
        }
        try {
            Object value = capMethod.invoke(null, count);
            if (value instanceof Number number && number.intValue() > 0) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall back below
        }
        return fallback;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        livingMarker = BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, LIVING_MARKER));
        for (Source source : SOURCES) {
            source.resolve();
        }
    }

    private static void chargeInRange(IItemHandler inventory, EnergizerConfig.Snapshot cfg, int interval,
            boolean applyMultiplier, Set<ItemStack> charged) {
        int slots = inventory.getSlots();
        int columns = columnsFor(slots);
        // Signal reaching each slot, built from the sources first: a source feeds all four of its
        // neighbours or just the one it points at, and the strongest source wins per slot.
        int[] signals = null;
        for (int slot = 0; slot < slots; slot++) {
            Emission emission = emissionOf(inventory.getStackInSlot(slot));
            if (emission.signal() <= 0) {
                continue;
            }
            if (signals == null) {
                signals = new int[slots];
            }
            route(signals, slot, emission.signal(), columns, slots, emission.mask());
        }
        if (signals == null) {
            return;
        }

        for (int slot = 0; slot < slots; slot++) {
            int best = signals[slot];
            if (best <= 0) {
                continue;
            }
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getCount() > 1 || charged.contains(stack)) {
                continue;
            }

            long raw = cfg.energyPerTick(best);
            if (applyMultiplier) {
                raw *= interval;
            }
            int amount = EnergyFeed.clampToInt(raw);
            if (amount <= 0) {
                continue;
            }
            IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || !storage.canReceive()) {
                continue;
            }
            if (storage.receiveEnergy(amount, false) > 0) {
                ItemCharging.writeBack(inventory, slot, stack);
                charged.add(stack);
            }
        }
    }

    /**
     * Records where a source at {@code slot} sends its signal. {@code mask} holds the grid
     * directions to feed, so a repeater marks one slot and a torch marks three.
     */
    static void route(int[] signals, int slot, int signal, int columns, int slots, int mask) {
        for (int d = 0; d < 4; d++) {
            if ((mask & (1 << d)) == 0) {
                continue;
            }
            mark(signals, neighbourSlot(slot, d, columns, slots), signal);
        }
    }

    private static void mark(int[] signals, int slot, int signal) {
        if (slot >= 0 && signal > signals[slot]) {
            signals[slot] = signal;
        }
    }

    /**
     * Neighbour slot in a row-major grid: {@code 0} up, {@code 1} down, {@code 2} left, {@code 3}
     * right; {@code -1} when that direction leaves the grid.
     */
    static int neighbourSlot(int slot, int dir, int columns, int slots) {
        switch (dir) {
            case 0:
                return slot >= columns ? slot - columns : -1;
            case 1:
                return slot + columns < slots ? slot + columns : -1;
            case 2:
                return slot % columns != 0 ? slot - 1 : -1;
            case 3:
                if ((slot + 1) % columns == 0) {
                    return -1;
                }
                return slot + 1 < slots ? slot + 1 : -1;
            default:
                return -1;
        }
    }

    /**
     * Slot columns of the container grid, needed to know what "up" and "down" mean.
     *
     * <p>Inferred from the slot count: 3x3 dispensers/droppers, 5-wide hoppers, 9-wide vanilla
     * chests, barrels, shulkers and most modded storage. Anything else falls back to 9, which keeps
     * left/right correct and just leaves vertical neighbours out of range.
     */
    static int columnsFor(int slots) {
        if (slots == 9) {
            return 3;
        }
        if (slots >= 9 && slots % 9 == 0) {
            return 9;
        }
        if (slots >= 5 && slots % 5 == 0) {
            return 5;
        }
        return 9;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DataComponentType<Object> cast(DataComponentType<?> type) {
        return (DataComponentType) type;
    }
}
