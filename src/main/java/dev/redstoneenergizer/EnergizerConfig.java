package dev.redstoneenergizer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration, written to {@code config/redstone_energizer-common.toml} on first launch.
 *
 * <p>Comments are English because the file is English; the in-game config screen has Chinese
 * labels and tooltips in {@code assets/redstone_energizer/lang/zh_cn.json}, so a Chinese client
 * still reads everything in Chinese.
 *
 * <p>The {@link ModConfigSpec} is only used for registration, the file format and the config
 * screen. Everything the mod actually reads at runtime goes through {@link #current()}, which
 * returns an immutable snapshot. The snapshot is read once from the spec when the config is
 * loaded, and re-read whenever NeoForge fires a config event - that covers startup, a file change
 * on disk and closing the in-game config screen. One volatile read per tick is all the hot paths
 * pay, and a whole tick sees a consistent set of values.
 */
public final class EnergizerConfig {

    public static final ModConfigSpec SPEC;

    /** The snapshot every tick path reads. Replaced, never mutated. */
    private static volatile Snapshot SETTINGS;

    // ------------------------------------------------------------------
    // Spec
    // ------------------------------------------------------------------

    /** FE per tick; the flat rate, or the per-level rate when {@link #SCALE_WITH_SIGNAL} is on. */
    public static final ModConfigSpec.IntValue FE_PER_TICK;

    /** Whether the redstone signal level multiplies {@link #FE_PER_TICK}. Off by default. */
    public static final ModConfigSpec.BooleanValue SCALE_WITH_SIGNAL;

    /** Whether powered containers charge the items inside them. */
    public static final ModConfigSpec.BooleanValue CHARGE_CONTAINERS;

    /** Ticks between two container charge events; doubles as the per-event multiplier. */
    public static final ModConfigSpec.IntValue CONTAINER_INTERVAL;

    /** Whether a charge event multiplies the base amount by {@link #CONTAINER_INTERVAL}. */
    public static final ModConfigSpec.BooleanValue CONTAINER_APPLY_MULTIPLIER;

    /** Whether all items are charged at once, or one slot at a time from the first slot. */
    public static final ModConfigSpec.BooleanValue CONTAINER_SIMULTANEOUS;

    /** Block ids that are never treated as chargeable containers; {@code namespace:*} works. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CONTAINER_BLACKLIST;

    /** Containers with more slots than this are skipped; 0 means no limit. */
    public static final ModConfigSpec.IntValue CONTAINER_MAX_SLOTS;

    /** Whether players standing on a powered block get their inventory charged. */
    public static final ModConfigSpec.BooleanValue CHARGE_PLAYERS;

    /** Ticks between two player charge events; doubles as the per-event multiplier. */
    public static final ModConfigSpec.IntValue PLAYER_INTERVAL;

    /** Whether a player charge event multiplies the base amount by {@link #PLAYER_INTERVAL}. */
    public static final ModConfigSpec.BooleanValue PLAYER_APPLY_MULTIPLIER;

    /** Whether all chargeable items in the inventory are charged at once, or one at a time. */
    public static final ModConfigSpec.BooleanValue PLAYER_SIMULTANEOUS;

    /** Whether living redstone items (living_item mod) charge the items around them. */
    public static final ModConfigSpec.BooleanValue LIVING_ENABLED;

    /** Whether living redstone dust draws a connection line towards a chargeable item next to it. */
    public static final ModConfigSpec.BooleanValue LIVING_CONNECT_ITEMS;

    /** The runtime view of the config. Immutable. */
    public static final class Snapshot {
        public final int fePerTick;
        public final boolean signalScaling;

        public final boolean containersEnabled;
        public final int containerInterval;
        public final boolean containerApplyMultiplier;
        public final boolean containerSimultaneous;
        public final int containerMaxSlots;
        public final Set<String> blacklistIds;
        public final Set<String> blacklistNamespaces;

        public final boolean playersEnabled;
        public final int playerInterval;
        public final boolean playerApplyMultiplier;
        public final boolean playerSimultaneous;

        public final boolean livingEnabled;
        public final boolean livingConnectItems;

        Snapshot(int fePerTick, boolean signalScaling, boolean containersEnabled, int containerInterval,
                boolean containerApplyMultiplier, boolean containerSimultaneous, int containerMaxSlots,
                Set<String> blacklistIds, Set<String> blacklistNamespaces, boolean playersEnabled,
                int playerInterval, boolean playerApplyMultiplier, boolean playerSimultaneous,
                boolean livingEnabled, boolean livingConnectItems) {
            this.fePerTick = fePerTick;
            this.signalScaling = signalScaling;
            this.containersEnabled = containersEnabled;
            this.containerInterval = containerInterval;
            this.containerApplyMultiplier = containerApplyMultiplier;
            this.containerSimultaneous = containerSimultaneous;
            this.containerMaxSlots = containerMaxSlots;
            this.blacklistIds = blacklistIds;
            this.blacklistNamespaces = blacklistNamespaces;
            this.playersEnabled = playersEnabled;
            this.playerInterval = playerInterval;
            this.playerApplyMultiplier = playerApplyMultiplier;
            this.playerSimultaneous = playerSimultaneous;
            this.livingEnabled = livingEnabled;
            this.livingConnectItems = livingConnectItems;
        }

        /**
         * FE per tick for a redstone signal of {@code signal}, which is {@code 0} for "no signal".
         *
         * <p>With signal scaling off (the default) the signal level does not matter: any signal
         * above zero charges at the flat base rate. With it on, the signal level is the multiplier:
         * a signal of 1 gives 1x the base rate, 15 gives 15x, and the higher levels living redstone
         * can produce multiply as well.
         */
        public int energyPerTick(int signal) {
            if (signal <= 0 || fePerTick <= 0) {
                return 0;
            }
            if (!signalScaling) {
                return fePerTick;
            }
            long raw = (long) fePerTick * signal;
            return (int) Math.min(raw, Integer.MAX_VALUE);
        }
    }

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.translation("redstone_energizer.configuration.energy")
                .comment(" Redstone feeding: a powered machine or battery gets FE every tick.")
                .push("energy");

        FE_PER_TICK = builder
                .translation("redstone_energizer.configuration.energy.fePerTick")
                .comment(" Base FE per tick. Flat rate when signal scaling is off; multiplied by",
                        " the signal level when it is on. 0 turns redstone feeding off.")
                .defineInRange("fePerTick", 1, 0, Integer.MAX_VALUE);

        SCALE_WITH_SIGNAL = builder
                .translation("redstone_energizer.configuration.energy.signalScaling")
                .comment(" true: the redstone signal level (1-15, higher for living redstone)",
                        " multiplies fePerTick.")
                .define("signalScaling", false);

        builder.pop();

        builder.translation("redstone_energizer.configuration.containers")
                .comment(" Container charging: powered containers charge the items inside them.")
                .push("containers");

        CHARGE_CONTAINERS = builder
                .translation("redstone_energizer.configuration.containers.enabled")
                .comment(" Charge items in any block that exposes an item handler.")
                .define("enabled", true);

        CONTAINER_INTERVAL = builder
                .translation("redstone_energizer.configuration.containers.intervalTicks")
                .comment(" Ticks between two charges; also the per-event multiplier.")
                .defineInRange("intervalTicks", 20, 1, 100);

        CONTAINER_APPLY_MULTIPLIER = builder
                .translation("redstone_energizer.configuration.containers.applyMultiplier")
                .comment(" true: one event delivers intervalTicks worth of energy.")
                .define("applyMultiplier", true);

        CONTAINER_SIMULTANEOUS = builder
                .translation("redstone_energizer.configuration.containers.simultaneous")
                .comment(" true: every item at once; false: one slot at a time.")
                .define("simultaneous", true);

        CONTAINER_BLACKLIST = builder
                .translation("redstone_energizer.configuration.containers.blacklist")
                .comment(" Block ids that are never charged; \"namespace:*\" wildcards work.")
                .defineListAllowEmpty("blacklist", List.<String>of(), () -> "", element -> element instanceof String);

        CONTAINER_MAX_SLOTS = builder
                .translation("redstone_energizer.configuration.containers.maxSlots")
                .comment(" Skip blocks with more slots than this; 0 = no limit.")
                .defineInRange("maxSlots", 512, 0, 65536);

        builder.pop();

        builder.translation("redstone_energizer.configuration.players")
                .comment(" Player charging: standing on a redstone source charges your inventory.")
                .push("players");

        CHARGE_PLAYERS = builder
                .translation("redstone_energizer.configuration.players.enabled")
                .comment(" Charge while standing on a redstone block or a strongly powered block.")
                .define("enabled", false);

        PLAYER_INTERVAL = builder
                .translation("redstone_energizer.configuration.players.intervalTicks")
                .comment(" Ticks between two charges; also the per-event multiplier.")
                .defineInRange("intervalTicks", 20, 1, 100);

        PLAYER_APPLY_MULTIPLIER = builder
                .translation("redstone_energizer.configuration.players.applyMultiplier")
                .comment(" true: one event delivers intervalTicks worth of energy.")
                .define("applyMultiplier", true);

        PLAYER_SIMULTANEOUS = builder
                .translation("redstone_energizer.configuration.players.simultaneous")
                .comment(" true: the whole inventory at once; false: one slot at a time.")
                .define("simultaneous", true);

        builder.pop();

        builder.translation("redstone_energizer.configuration.living")
                .comment(" Living redstone (living_item mod): charges the items around it.")
                .push("living");

        LIVING_ENABLED = builder
                .translation("redstone_energizer.configuration.living.enabled")
                .comment(" Charge items from living redstone. Idle without living_item.")
                .define("enabled", true);

        LIVING_CONNECT_ITEMS = builder
                .translation("redstone_energizer.configuration.living.connectItems")
                .comment(" Draw connection lines from living redstone dust to chargeable items.",
                        " Visual only.")
                .define("connectItems", true);

        builder.pop();

        SPEC = builder.build();
        // Before the config is loaded the defaults are the only sane values.
        SETTINGS = read(true);
    }

    private EnergizerConfig() {
    }

    /** The current settings. Hot paths call this once per tick / event and then read the fields. */
    public static Snapshot current() {
        return SETTINGS;
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        reload(event.getConfig().getSpec());
    }

    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        reload(event.getConfig().getSpec());
    }

    private static void reload(Object changedSpec) {
        // Several mods fire these events; only the ones for this spec matter.
        if (changedSpec != SPEC || !SPEC.isLoaded()) {
            return;
        }
        SETTINGS = read(false);
    }

    /**
     * @param useDefaults read the declared defaults instead of the loaded file, which is the only
     *                    thing that works before the config is loaded
     */
    private static Snapshot read(boolean useDefaults) {
        List<? extends String> blacklist = useDefaults
                ? CONTAINER_BLACKLIST.getDefault()
                : CONTAINER_BLACKLIST.get();
        Set<String> ids = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        for (String entry : blacklist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.trim();
            if (value.endsWith(":*")) {
                namespaces.add(value.substring(0, value.length() - 2));
            } else {
                ids.add(value);
            }
        }
        return new Snapshot(
                useDefaults ? FE_PER_TICK.getDefault() : FE_PER_TICK.get(),
                useDefaults ? SCALE_WITH_SIGNAL.getDefault() : SCALE_WITH_SIGNAL.get(),
                useDefaults ? CHARGE_CONTAINERS.getDefault() : CHARGE_CONTAINERS.get(),
                useDefaults ? CONTAINER_INTERVAL.getDefault() : CONTAINER_INTERVAL.get(),
                useDefaults ? CONTAINER_APPLY_MULTIPLIER.getDefault() : CONTAINER_APPLY_MULTIPLIER.get(),
                useDefaults ? CONTAINER_SIMULTANEOUS.getDefault() : CONTAINER_SIMULTANEOUS.get(),
                useDefaults ? CONTAINER_MAX_SLOTS.getDefault() : CONTAINER_MAX_SLOTS.get(),
                Set.copyOf(ids),
                Set.copyOf(namespaces),
                useDefaults ? CHARGE_PLAYERS.getDefault() : CHARGE_PLAYERS.get(),
                useDefaults ? PLAYER_INTERVAL.getDefault() : PLAYER_INTERVAL.get(),
                useDefaults ? PLAYER_APPLY_MULTIPLIER.getDefault() : PLAYER_APPLY_MULTIPLIER.get(),
                useDefaults ? PLAYER_SIMULTANEOUS.getDefault() : PLAYER_SIMULTANEOUS.get(),
                useDefaults ? LIVING_ENABLED.getDefault() : LIVING_ENABLED.get(),
                useDefaults ? LIVING_CONNECT_ITEMS.getDefault() : LIVING_CONNECT_ITEMS.get());
    }

    /** Diagnostics helper: the block is blacklisted from container charging. */
    static boolean isBlacklisted(Snapshot cfg, ResourceLocation id) {
        return cfg.blacklistIds.contains(id.toString()) || cfg.blacklistNamespaces.contains(id.getNamespace());
    }
}
