package dev.redstoneenergizer;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Charges the items stored inside redstone-powered containers.
 *
 * <p>There is no whitelist: any block that exposes an item handler capability counts, so modded
 * containers, machines with internal slots, and anything else that holds items are supported
 * without configuration. A {@code blacklist} option and a slot count cap keep pathological cases
 * out of the loop.
 *
 * <p>Containers are discovered when a chunk loads, when a block entity is placed, and when a block
 * update is announced for a position (which is what {@code /setblock} produces). Every
 * {@code intervalTicks} ticks, each tracked container that currently has a redstone signal is
 * visited once: its item handler is queried, and every chargeable single item receives energy.
 * Stacks with more than one item are skipped - an energy capability is per item, so a stack is
 * never split and never charged as a whole.
 *
 * <p>A double chest is two block entities sharing one inventory, so both halves would visit the
 * same stacks. Stacks charged during one event are remembered by identity, which keeps the rate
 * correct without having to understand the container layout.
 */
public final class ContainerCharger {

    private static final Direction[] SIDES = Direction.values();

    private static final Map<ResourceKey<Level>, LongOpenHashSet> CONTAINERS = new HashMap<>();

    /**
     * Positions found while a chunk was loading. They are only scanned on the next tick: asking for
     * an item handler capability makes some containers call {@code setChanged()}, which walks to
     * neighbouring blocks and can force a chunk load. Doing that from inside the chunk status task
     * deadlocks the server thread (the chunk it waits for can only finish on that same thread), which
     * is exactly what happened with Sophisticated Storage in 1.7.0.
     */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PENDING = new HashMap<>();

    /** Capability probes per level per tick, so a huge base cannot stall a single tick. */
    private static final int DISCOVERY_BUDGET = 64;

    private ContainerCharger() {
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Chunk load only records positions. No capability is queried here: this runs inside the chunk
     * status task, where any (even indirect) chunk lookup can block forever.
     */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (chunk.getBlockEntities().isEmpty()) {
            return;
        }
        LongOpenHashSet pending = pending(level);
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            pending.add(pos.asLong());
        }
    }

    /** Block updates also fire for {@code /setblock} and for blocks placed by other mods. */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            considerDiscoverable(level, event.getPos());
        }
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            considerDiscoverable(level, event.getPos());
        }
    }

    /**
     * These events can also fire while a chunk is generated (structure blocks, world generation), so
     * they only look at positions whose chunk already exists.
     */
    private static void considerDiscoverable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return;
        }
        consider(level, pos);
    }

    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LongOpenHashSet set = CONTAINERS.get(level.dimension());
        if (set != null) {
            set.remove(event.getPos().asLong());
        }
        LongOpenHashSet pending = PENDING.get(level.dimension());
        if (pending != null) {
            pending.remove(event.getPos().asLong());
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            CONTAINERS.remove(level.dimension());
            PENDING.remove(level.dimension());
        }
    }

    // ------------------------------------------------------------------
    // Charging
    // ------------------------------------------------------------------

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Runs on the tick thread, outside chunk tasks: capability lookups are safe here.
        drainDiscovered(level);

        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (!cfg.containersEnabled) {
            return;
        }
        int interval = cfg.containerInterval;
        if (level.getGameTime() % interval != 0) {
            return;
        }

        LongOpenHashSet containers = CONTAINERS.get(level.dimension());
        if (containers == null || containers.isEmpty()) {
            return;
        }

        if (cfg.fePerTick <= 0) {
            return;
        }
        boolean applyMultiplier = cfg.containerApplyMultiplier;
        boolean simultaneous = cfg.containerSimultaneous;
        boolean checkBlacklist = !cfg.blacklistIds.isEmpty() || !cfg.blacklistNamespaces.isEmpty();
        int maxSlots = cfg.containerMaxSlots;

        // Stacks charged by this event; shared by both halves of a double chest.
        Set<ItemStack> charged = Collections.newSetFromMap(new IdentityHashMap<>());
        // Sequential mode charges at most one item per event, even across a double chest.
        boolean[] chargedOne = new boolean[1];

        // Snapshot the positions: querying the world can load a chunk, which fires ChunkEvent.Load
        // and adds to this very set, and modifying a fastutil set while iterating it is unsafe.
        long[] snapshot = containers.toLongArray();
        LongArrayList stale = null;

        for (long packed : snapshot) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                // Drop it: ChunkEvent.Load adds it back when the chunk returns, and this keeps the
                // set from growing with every chunk the player ever visits.
                if (stale == null) {
                    stale = new LongArrayList();
                }
                stale.add(packed);
                continue;
            }
            if (checkBlacklist && isBlacklisted(level.getBlockState(pos).getBlock())) {
                if (stale == null) {
                    stale = new LongArrayList();
                }
                stale.add(packed);
                continue;
            }
            int signal = level.getBestNeighborSignal(pos);
            if (signal <= 0) {
                continue;
            }

            IItemHandler inventory = findInventory(level, pos);
            if (inventory == null) {
                if (stale == null) {
                    stale = new LongArrayList();
                }
                stale.add(packed);
                continue;
            }
            if (maxSlots > 0 && inventory.getSlots() > maxSlots) {
                // Still tracked: the option may be raised later, and one capability lookup per
                // interval is cheap.
                continue;
            }

            long raw = cfg.energyPerTick(signal);
            if (applyMultiplier) {
                raw *= interval;
            }
            int amount = EnergyFeed.clampToInt(raw);
            if (amount <= 0) {
                continue;
            }
            ItemCharging.charge(inventory, amount, simultaneous, charged, chargedOne);
        }

        if (stale != null) {
            for (int i = 0; i < stale.size(); i++) {
                containers.remove(stale.getLong(i));
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Track the position if it is a block entity that exposes an item handler and is allowed. */
    private static void consider(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = containers(level);
        if (set.contains(pos.asLong())) {
            return;
        }
        // No isLoaded() guard on purpose: this also runs from ChunkEvent.Load, where the chunk may
        // not be visible to Level#isLoaded yet. Lookups on an unloaded position simply return null.
        if (level.getBlockEntity(pos) == null) {
            return;
        }
        if (isBlacklisted(level.getBlockState(pos).getBlock())) {
            return;
        }
        if (findInventory(level, pos) == null) {
            return;
        }
        set.add(pos.asLong());
    }

    static IItemHandler findInventory(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : SIDES) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /**
     * Entries are block ids, plus {@code namespace:*} wildcards, pre-parsed into two sets when the
     * config is read so the check is a couple of hash lookups.
     */
    static boolean isBlacklisted(Block block) {
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (cfg.blacklistIds.isEmpty() && cfg.blacklistNamespaces.isEmpty()) {
            return false;
        }
        return EnergizerConfig.isBlacklisted(cfg, BuiltInRegistries.BLOCK.getKey(block));
    }

    private static LongOpenHashSet containers(ServerLevel level) {
        return CONTAINERS.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
    }

    private static LongOpenHashSet pending(ServerLevel level) {
        return PENDING.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
    }

    /**
     * Scans the positions found by {@link #onChunkLoad}, a bounded number per tick, from the tick
     * thread. Anything that is no longer in a loaded chunk is dropped instead of asked about.
     */
    private static void drainDiscovered(ServerLevel level) {
        LongOpenHashSet pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        int budget = DISCOVERY_BUDGET;
        for (long packed : pending.toLongArray()) {
            if (budget-- <= 0) {
                break;
            }
            pending.remove(packed);
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            consider(level, pos);
        }
    }

    /** Snapshot of the tracked container positions, or {@code null} when none are tracked. */
    static long[] trackedSnapshot(ServerLevel level) {
        LongOpenHashSet set = CONTAINERS.get(level.dimension());
        return set == null || set.isEmpty() ? null : set.toLongArray();
    }

    /** Diagnostics: is this position tracked as a chargeable container? */
    public static boolean isTracked(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = CONTAINERS.get(level.dimension());
        return set != null && set.contains(pos.asLong());
    }

    /** Diagnostics: how many positions are still waiting for their deferred scan. */
    static int pendingCount(ServerLevel level) {
        LongOpenHashSet pending = PENDING.get(level.dimension());
        return pending == null ? 0 : pending.size();
    }

    /** Diagnostics: a short description of the tracked container positions. */
    public static String describeTracked(ServerLevel level) {
        LongOpenHashSet set = CONTAINERS.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return "no tracked containers";
        }
        StringBuilder builder = new StringBuilder("tracked=" + set.size() + ":");
        int shown = 0;
        for (long packed : set.toLongArray()) {
            if (shown++ == 12) {
                builder.append(" ...");
                break;
            }
            builder.append(' ').append(BlockPos.of(packed).toShortString());
        }
        return builder.toString();
    }
}
