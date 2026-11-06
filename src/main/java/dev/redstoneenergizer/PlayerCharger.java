package dev.redstoneenergizer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Charges the inventory of players who stand on a block that is a redstone power source.
 *
 * <p>Two things count, and the block right under the player's feet has to be one of them:
 * <ul>
 *   <li>a redstone block ({@code 15});</li>
 *   <li>a block that itself emits redstone power to its neighbours - a "strongly powered" block,
 *       which is what a solid block becomes when a redstone torch sits under it or a repeater or
 *       comparator points into it. A block that merely receives power from a wire does not count;
 *       that is weak powering and it cannot power machines.</li>
 * </ul>
 *
 * <p>By default the strength only decides whether the block counts as a source: any power above
 * zero charges at the flat {@code fePerTick} rate, exactly like feeding a machine. Turning
 * {@code signalScaling} on makes the strength the multiplier, so a redstone block (15) charges at
 * 15x the base rate.
 *
 * <p>Off by default.
 */
public final class PlayerCharger {

    private static final Direction[] SIDES = Direction.values();

    private PlayerCharger() {
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        if (!cfg.playersEnabled || level.players().isEmpty() || cfg.fePerTick <= 0) {
            return;
        }
        if (level.getGameTime() % cfg.playerInterval != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            chargeIfStanding(level, player, cfg);
        }
    }

    /**
     * Charges one player's inventory when they are standing on a power source.
     *
     * @return the FE pushed into every item this event, or {@code 0} when nothing applies
     */
    static int chargeIfStanding(ServerLevel level, ServerPlayer player, EnergizerConfig.Snapshot cfg) {
        if (player.isSpectator()) {
            return 0;
        }
        // The block the player is standing on.
        int power = powerAt(level, player.blockPosition().below());
        if (power <= 0) {
            return 0;
        }
        long raw = cfg.energyPerTick(power);
        if (cfg.playerApplyMultiplier) {
            raw *= cfg.playerInterval;
        }
        int amount = EnergyFeed.clampToInt(raw);
        if (amount <= 0) {
            return 0;
        }
        // Every player has its own set: the inventory is not shared between players.
        Set<ItemStack> charged = Collections.newSetFromMap(new IdentityHashMap<>());
        ItemCharging.charge(new InvWrapper(player.getInventory()), amount, cfg.playerSimultaneous,
                charged, new boolean[1]);
        return amount;
    }

    /**
     * How much redstone power the block at {@code pos} provides to its neighbours, {@code 0} when it
     * provides none.
     *
     * <p>{@link net.minecraft.world.level.SignalGetter#getSignal} is exactly the vanilla question
     * "what does this block emit to that side", including the weak-power rule that makes a
     * strongly powered solid block report its neighbour's direct signal.
     */
    static int powerAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        int best = 0;
        for (Direction side : SIDES) {
            int signal = level.getSignal(pos, side);
            if (signal > best) {
                best = signal;
                if (best >= 15) {
                    return 15;
                }
            }
        }
        return best;
    }

    /** Diagnostics: what each player in this level would be charged from right now. */
    public static String describePlayers(ServerLevel level) {
        if (level.players().isEmpty()) {
            return "no players in this level";
        }
        EnergizerConfig.Snapshot cfg = EnergizerConfig.current();
        StringBuilder builder = new StringBuilder("players=" + level.players().size()
                + " enabled=" + cfg.playersEnabled);
        for (ServerPlayer player : level.players()) {
            BlockPos standingOn = player.blockPosition().below();
            builder.append(" | ").append(player.getName().getString())
                    .append(" on=").append(standingOn.toShortString())
                    .append(" power=").append(powerAt(level, standingOn));
        }
        return builder.toString();
    }
}
