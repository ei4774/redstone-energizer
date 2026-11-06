package dev.redstoneenergizer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Keeps track of the blocks that can receive Forge Energy and feeds the powered ones.
 *
 * <p>Iterating every loaded block entity every tick would be far too expensive, so the mod keeps
 * two per-level position sets:
 * <ul>
 *   <li><b>candidates</b> - every block entity that exposes an {@link IEnergyStorage};</li>
 *   <li><b>active</b> - the subset of candidates that currently has a redstone signal.</li>
 * </ul>
 *
 * Candidates are discovered when a chunk loads and when a block is placed; the powered/not-powered
 * state is refreshed on neighbour notifications, which is what a redstone change emits. A slow
 * sweep re-checks the power state of all candidates so a missed notification cannot silently stop
 * the supply.
 *
 * <p>Energy is inserted through the side-less capability first and then through each of the six
 * faces. Some mods (Mekanism for example) expose a side-less handler that refuses external
 * insertion, while the per-side handlers accept it, so every context has to be tried.
 */
public final class EnergyFeed {

    private static final Direction[] SIDES = Direction.values();

    /** How often (in ticks) the whole candidate set is re-checked against the world. */
    private static final int SWEEP_INTERVAL = 100;

    /** Returned by {@link #feed} when the block exposes no energy storage at all. */
    private static final int NO_STORAGE = -1;

    private static final Map<ResourceKey<Level>, LongOpenHashSet> CANDIDATES = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongOpenHashSet> ACTIVE = new HashMap<>();

    /**
     * Candidates discovered while a chunk was loading, waiting for the next server tick.
     *
     * <p>{@link ChunkEvent.Load} is fired on a chunk worker thread. Reading the block state of a
     * neighbour - which the redstone query does - can need another chunk, and asking for one from a
     * worker blocks on the main thread, which is itself waiting for the chunk: a deadlock that trips
     * the server watchdog. So the power state is resolved one tick later, on the main thread.
     */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PENDING_REFRESH = new HashMap<>();

    /**
     * Per-position FE that could not be delivered yet.
     *
     * <p>Some receivers convert FE to their internal unit with integer truncation (Mekanism uses
     * 2.5 J per FE), so asking for 15 FE can end up delivering only 14. The shortfall is carried to
     * the next tick, which keeps the long-run average exact.
     */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> CARRY = new HashMap<>();

    /** Cap on the carried shortfall, so a full receiver does not accumulate a huge burst. */
    private static final int MAX_CARRY = 1000;

    private EnergyFeed() {
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** A freshly loaded chunk may contain machines that are already powered. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            // Only touch this chunk here; the neighbouring chunks may not be ready yet.
            considerDeferred(level, pos);
        }
    }

    /** Redstone changes arrive here; the notified sides are exactly the neighbours that changed. */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        // A block update also fires for /setblock and for blocks other mods place, which have no
        // placement event; pick up machines that were not discovered yet. Known candidates cost
        // only a hash lookup.
        if (!isCandidate(level, pos)) {
            consider(level, pos);
        }
        refresh(level, pos);
        // The neighbours only need their power state re-checked: a machine is discovered from its
        // own block update, its placement event or the chunk load, never from a neighbour's update.
        // Skipping those lookups keeps a redstone clock next to a chest from costing seven
        // capability queries every tick.
        for (Direction side : event.getNotifiedSides()) {
            refresh(level, pos.relative(side));
        }
    }

    /** A block placed next to an already powered redstone source (or a machine placed on one). */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        consider(level, pos);
        for (Direction side : SIDES) {
            consider(level, pos.relative(side));
        }
    }

    /**
     * Only the broken block itself is forgotten: its neighbours may well be machines that are still
     * powered by another redstone source, so they must be refreshed rather than dropped.
     */
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        forget(level, pos);
        for (Direction side : SIDES) {
            refresh(level, pos.relative(side));
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            CANDIDATES.remove(level.dimension());
            ACTIVE.remove(level.dimension());
            CARRY.remove(level.dimension());
            PENDING_REFRESH.remove(level.dimension());
        }
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        resolvePendingRefreshes(level);
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (cfg.fePerTick <= 0) {
            return;
        }

        LongOpenHashSet active = ACTIVE.get(level.dimension());
        if (active != null && !active.isEmpty()) {
            // Snapshot: capability lookups can load a chunk, and ChunkEvent.Load adds to these sets.
            long[] snapshot = active.toLongArray();
            LongArrayList stale = null;
            for (long packed : snapshot) {
                BlockPos pos = BlockPos.of(packed);

                int signal = level.isLoaded(pos) ? level.getBestNeighborSignal(pos) : 0;
                if (signal <= 0) {
                    // No more redstone on any face.
                    if (stale == null) {
                        stale = new LongArrayList();
                    }
                    stale.add(packed);
                    continue;
                }

                long raw = cfg.energyPerTick(signal);
                Long2IntOpenHashMap carry = carry(level);
                long want = raw + (carry.isEmpty() ? 0 : carry.get(packed));
                int accepted = feed(level, pos, clampToInt(want));
                if (accepted == NO_STORAGE) {
                    // The block or its capability is gone; pick it up again later.
                    if (stale == null) {
                        stale = new LongArrayList();
                    }
                    stale.add(packed);
                    carry.remove(packed);
                } else {
                    int leftover = (int) Math.min(want - accepted, MAX_CARRY);
                    if (leftover > 0) {
                        carry.put(packed, leftover);
                    } else {
                        // Most receivers take exactly what they are given; no entry needed.
                        carry.remove(packed);
                    }
                }
            }
            if (stale != null) {
                for (int i = 0; i < stale.size(); i++) {
                    active.remove(stale.getLong(i));
                }
            }
        }

        if (level.getGameTime() % SWEEP_INTERVAL == 0) {
            sweep(level);
        }
    }

    /** Re-derive the active set from the candidate set, dropping candidates that unloaded. */
    private static void sweep(ServerLevel level) {
        LongOpenHashSet candidates = CANDIDATES.get(level.dimension());
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        LongOpenHashSet active = active(level);
        long[] snapshot = candidates.toLongArray();
        LongArrayList stale = null;
        for (long packed : snapshot) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                if (stale == null) {
                    stale = new LongArrayList();
                }
                stale.add(packed);
                active.remove(packed);
            } else if (level.getBestNeighborSignal(pos) > 0) {
                active.add(packed);
            } else {
                active.remove(packed);
            }
        }
        if (stale != null) {
            for (int i = 0; i < stale.size(); i++) {
                candidates.remove(stale.getLong(i));
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void consider(ServerLevel level, BlockPos pos) {
        // No isLoaded() guard on purpose: this also runs from ChunkEvent.Load, where the chunk may
        // not be visible to Level#isLoaded yet. Lookups on an unloaded position simply return null.
        if (!hasStorage(level, pos)) {
            return;
        }
        candidates(level).add(pos.asLong());
        refresh(level, pos);
    }

    /**
     * Chunk-load variant of {@link #consider}: register the candidate, but leave the redstone query
     * for the next server tick, where all neighbouring chunks are safe to read.
     */
    private static void considerDeferred(ServerLevel level, BlockPos pos) {
        if (!hasStorage(level, pos)) {
            return;
        }
        if (candidates(level).add(pos.asLong())) {
            pendingRefresh(level).add(pos.asLong());
        }
    }

    /** Resolve the power state of candidates found while a chunk was loading. */
    private static void resolvePendingRefreshes(ServerLevel level) {
        LongOpenHashSet pending = PENDING_REFRESH.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        // Snapshot: a refresh may load a chunk, which fires ChunkEvent.Load and adds to this set.
        long[] snapshot = pending.toLongArray();
        pending.clear();
        for (long packed : snapshot) {
            BlockPos pos = BlockPos.of(packed);
            if (level.isLoaded(pos)) {
                refresh(level, pos);
            }
        }
    }

    private static void forget(ServerLevel level, BlockPos pos) {
        long packed = pos.asLong();
        LongOpenHashSet candidates = CANDIDATES.get(level.dimension());
        if (candidates != null) {
            candidates.remove(packed);
        }
        LongOpenHashSet active = ACTIVE.get(level.dimension());
        if (active != null) {
            active.remove(packed);
        }
        Long2IntOpenHashMap carry = CARRY.get(level.dimension());
        if (carry != null) {
            carry.remove(packed);
        }
    }

    /** Update the powered state of a position, but only for blocks already known to be candidates. */
    private static void refresh(ServerLevel level, BlockPos pos) {
        LongOpenHashSet candidates = CANDIDATES.get(level.dimension());
        if (candidates == null) {
            return;
        }
        long packed = pos.asLong();
        if (!candidates.contains(packed)) {
            return;
        }
        if (level.getBestNeighborSignal(pos) > 0) {
            active(level).add(packed);
        } else {
            LongOpenHashSet active = ACTIVE.get(level.dimension());
            if (active != null) {
                active.remove(packed);
            }
        }
    }

    /** Whether any capability context of the block exposes energy storage. */
    private static boolean hasStorage(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) == null) {
            return false;
        }
        if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null) {
            return true;
        }
        for (Direction side : SIDES) {
            if (level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Push {@code amount} FE into the block.
     *
     * @return the amount accepted, {@code 0} when the block has storage that is full or refuses the
     *         insertion, or {@link #NO_STORAGE} when no capability context exposes any storage
     */
    private static int feed(ServerLevel level, BlockPos pos, int amount) {
        boolean anyStorage = false;

        IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
        if (storage != null) {
            anyStorage = true;
            int accepted = storage.receiveEnergy(amount, false);
            if (accepted > 0) {
                return accepted;
            }
        }
        for (Direction side : SIDES) {
            storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
            if (storage == null) {
                continue;
            }
            anyStorage = true;
            int accepted = storage.receiveEnergy(amount, false);
            if (accepted > 0) {
                return accepted;
            }
        }
        return anyStorage ? 0 : NO_STORAGE;
    }

    private static LongOpenHashSet candidates(ServerLevel level) {
        return CANDIDATES.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
    }

    /** Diagnostics: is this position tracked as an energy receiver? */
    public static boolean isCandidate(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = CANDIDATES.get(level.dimension());
        return set != null && set.contains(pos.asLong());
    }

    /** Diagnostics: is this position currently receiving energy? */
    public static boolean isActive(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = ACTIVE.get(level.dimension());
        return set != null && set.contains(pos.asLong());
    }

    private static LongOpenHashSet active(ServerLevel level) {
        return ACTIVE.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
    }

    private static LongOpenHashSet pendingRefresh(ServerLevel level) {
        return PENDING_REFRESH.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
    }

    private static Long2IntOpenHashMap carry(ServerLevel level) {
        return CARRY.computeIfAbsent(level.dimension(), key -> new Long2IntOpenHashMap());
    }

    /**
     * Clamp a {@code long} energy amount into the range an {@link IEnergyStorage} can accept, so
     * large configured rates never overflow into a negative value.
     */
    public static int clampToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
