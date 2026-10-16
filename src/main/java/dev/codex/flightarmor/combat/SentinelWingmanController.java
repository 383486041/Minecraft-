package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.network.SentinelWingmanCommandPayload;
import dev.codex.flightarmor.sound.ModSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server authority for the two physical Sentinel wingmen.
 *
 * <p>There is exactly one transient entity for each (owner, suit, side) key. The stable worn
 * configuration is authoritative; wingmen are derived combat visuals and never own armor items or
 * world-persistent state. The runtime index is reduced deterministically before a missing side is
 * spawned, preventing reconnect, dimension-change and chunk-unload duplication.</p>
 */
public final class SentinelWingmanController {
	public static final int LEFT_SIDE = -1;
	public static final int RIGHT_SIDE = 1;
	private static final long DESTROYED_RESPAWN_DELAY_TICKS = 120L;
	private static final long MINIMUM_COMMAND_GAP_TICKS = 2L;
	private static final Set<SentinelWingmanEntity> LIVE = ConcurrentHashMap.newKeySet();
	private static final Map<WingmanKey, Long> RESPAWN_AFTER = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_CLIENT_SEQUENCE = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_COMMAND_TICK = new ConcurrentHashMap<>();

	private SentinelWingmanController() {
	}

	public static void handleCommand(ServerPlayer owner,
			SentinelWingmanCommandPayload payload) {
		if (owner == null || payload == null || payload.clientSequence() <= 0L) return;
		UUID ownerId = owner.getUUID();
		Long previousSequence = LAST_CLIENT_SEQUENCE.get(ownerId);
		if (previousSequence != null && payload.clientSequence() <= previousSequence) return;
		LAST_CLIENT_SEQUENCE.put(ownerId, payload.clientSequence());
		if (!owner.isAlive() || owner.isSpectator() || owner.isSleeping()
				|| owner.isPassenger() || ArmorAssemblyController.isAnimating(owner)
				|| ArmorCoreController.hasActiveQuickSwitch(owner)) {
			deny(owner, "message.flight_armor.wingmen_busy");
			return;
		}
		long now = owner.serverLevel().getGameTime();
		long lastCommand = LAST_COMMAND_TICK.getOrDefault(ownerId, Long.MIN_VALUE / 2L);
		if (now - lastCommand < MINIMUM_COMMAND_GAP_TICKS) return;
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(owner).orElse(null);
		if (configured == null || !ownerId.equals(configured.ownerId())
				|| payload.expectedSuitId() == null
				|| !payload.expectedSuitId().equals(configured.suitId())) {
			deny(owner, "message.flight_armor.wingmen_requires_sentinel");
			return;
		}
		ArmorConfigurationData.Snapshot snapshot = configured.snapshot();
		if (snapshot.mode() != SentinelArmorMode.OVERDRIVE_FLIGHT
				|| ArmorConfigurationAccess.isTransitioning(snapshot, now)) {
			deny(owner, "message.flight_armor.wingmen_requires_overdrive");
			return;
		}
		if (ArmorConfigurationAccess.isWingmenTransitioning(snapshot, now)) {
			deny(owner, "message.flight_armor.wingmen_busy");
			return;
		}
		boolean deploy = !snapshot.wingmenDeployed();
		ItemStack chest = owner.getItemBySlot(EquipmentSlot.CHEST);
		if (!ArmorConfigurationData.setWingmenDeployed(chest, deploy, now)) {
			deny(owner, "message.flight_armor.wingmen_busy");
			return;
		}
		LAST_COMMAND_TICK.put(ownerId, now);
		ArmorCombatController.cancel(owner, ArmorCombatDenyReason.INVALID_ACTION);
		owner.getInventory().setChanged();
		owner.inventoryMenu.broadcastChanges();
		if (owner.containerMenu != owner.inventoryMenu) {
			owner.containerMenu.broadcastChanges();
		}
		owner.level().playSound(null, owner.getX(), owner.getY(), owner.getZ(),
				deploy ? ModSoundEvents.ASSEMBLY_UNLOCK_TORSO
						: ModSoundEvents.ASSEMBLY_RETRACT_HEAVY,
				SoundSource.PLAYERS, deploy ? 0.72F : 0.64F, deploy ? 1.08F : 0.94F);
		owner.displayClientMessage(Component.translatable(deploy
				? "message.flight_armor.wingmen_deploying"
				: "message.flight_armor.wingmen_recalling"), true);
	}

	public static void tickServer(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		Map<WingmanKey, ServerPlayer> authorized = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ArmorConfigurationAccess.ConfiguredSentinel configured =
					ArmorConfigurationAccess.configuredSentinel(player).orElse(null);
			if (!shouldSpawnWingmen(player, configured, now)) continue;
			authorized.put(new WingmanKey(player.getUUID(), configured.suitId(), LEFT_SIDE), player);
			authorized.put(new WingmanKey(player.getUUID(), configured.suitId(), RIGHT_SIDE), player);
		}

		// Snapshot because discarding mutates LIVE through Entity.remove.
		Map<WingmanKey, List<SentinelWingmanEntity>> byKey = new HashMap<>();
		for (SentinelWingmanEntity wingman : List.copyOf(LIVE)) {
			if (wingman == null || wingman.isRemoved()) {
				LIVE.remove(wingman);
				continue;
			}
			WingmanKey key = wingman.key();
			ServerPlayer owner = key == null ? null : authorized.get(key);
			if (key == null) {
				wingman.recombine();
				continue;
			}
			if (owner == null) {
				ServerPlayer physicalOwner =
						server.getPlayerList().getPlayer(key.ownerId());
				if (physicalOwner != null && physicalOwner.level() == wingman.level()
						&& physicalOwner.isAlive()) {
					wingman.beginRecombine(physicalOwner, now);
				} else {
					wingman.recombine();
				}
				continue;
			}
			if (owner.level() != wingman.level()) {
				wingman.recombine();
				continue;
			}
			byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(wingman);
		}

		for (Map.Entry<WingmanKey, ServerPlayer> entry : authorized.entrySet()) {
			WingmanKey key = entry.getKey();
			ServerPlayer owner = entry.getValue();
			List<SentinelWingmanEntity> matches =
					byKey.getOrDefault(key, List.of()).stream()
							.filter(entity -> !entity.isRemoved() && entity.level() == owner.level())
							.sorted(Comparator.comparing(Entity::getUUID))
							.toList();
			if (!matches.isEmpty()) {
				for (int index = 1; index < matches.size(); index++) matches.get(index).recombine();
				continue;
			}
			long allowedAt = RESPAWN_AFTER.getOrDefault(key, Long.MIN_VALUE);
			if (now >= allowedAt && owner.level() instanceof ServerLevel level) {
				SentinelWingmanEntity.spawn(level, owner, key.suitId(), key.side());
			}
		}

		Set<WingmanKey> activeKeys = new HashSet<>(authorized.keySet());
		RESPAWN_AFTER.entrySet().removeIf(entry ->
				!activeKeys.contains(entry.getKey()) || now >= entry.getValue() + 20L);
	}

	static boolean isDeploymentAuthorized(ServerPlayer owner, UUID suitId, long gameTime) {
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(owner).orElse(null);
		return configured != null && suitId != null && suitId.equals(configured.suitId())
				&& hasValidOverdriveIdentity(owner, configured)
				&& !ArmorConfigurationAccess.isTransitioning(configured.snapshot(), gameTime)
				&& configured.snapshot().wingmenDeployed();
	}

	static boolean isCombatAuthorized(ServerPlayer owner, UUID suitId, long gameTime) {
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(owner).orElse(null);
		return configured != null && suitId != null && suitId.equals(configured.suitId())
				&& configured.snapshot().wingmenDeployed()
				&& !ArmorConfigurationAccess.isWingmenTransitioning(
						configured.snapshot(), gameTime)
				&& isStableOverdrive(owner, configured, gameTime);
	}

	static long initialDeploymentElapsed(ServerPlayer owner, UUID suitId, long gameTime) {
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(owner).orElse(null);
		if (configured != null && suitId != null && suitId.equals(configured.suitId())
				&& configured.snapshot().mode() == SentinelArmorMode.OVERDRIVE_FLIGHT
				&& configured.snapshot().wingmenDeployed()) {
			return Math.max(0L, Math.min(SentinelWingmanEntity.DEPLOY_DURATION_TICKS,
					gameTime - configured.snapshot().wingmenTransitionStartTick()));
		}
		return 0L;
	}

	private static boolean shouldSpawnWingmen(ServerPlayer player,
			ArmorConfigurationAccess.ConfiguredSentinel configured, long gameTime) {
		if (!hasValidOverdriveIdentity(player, configured)) return false;
		if (ArmorConfigurationAccess.isTransitioning(configured.snapshot(), gameTime)
				|| !configured.snapshot().wingmenDeployed()) return false;
		/*
		 * Spawn at command start and keep the entity renderer hidden until the discrete hand-off.
		 * This gives normal client tracking several ticks to receive both identities and prevents
		 * the worn module from disappearing before its replacement exists locally.
		 */
		return true;
	}

	private static boolean isStableOverdrive(ServerPlayer player,
			ArmorConfigurationAccess.ConfiguredSentinel configured, long gameTime) {
		return hasValidOverdriveIdentity(player, configured)
				&& !ArmorConfigurationAccess.isTransitioning(configured.snapshot(), gameTime);
	}

	private static boolean hasValidOverdriveIdentity(ServerPlayer player,
			ArmorConfigurationAccess.ConfiguredSentinel configured) {
		return configured != null
				&& player.isAlive() && !player.isSpectator()
				&& player.getUUID().equals(configured.ownerId())
				&& configured.snapshot().mode() == SentinelArmorMode.OVERDRIVE_FLIGHT;
	}

	static void track(SentinelWingmanEntity wingman) {
		if (wingman != null && !wingman.level().isClientSide) LIVE.add(wingman);
	}

	static void untrack(SentinelWingmanEntity wingman) {
		LIVE.remove(wingman);
	}

	static void markDestroyed(SentinelWingmanEntity wingman, long now) {
		WingmanKey key = wingman == null ? null : wingman.key();
		if (key != null) RESPAWN_AFTER.put(key, now + DESTROYED_RESPAWN_DELAY_TICKS);
	}

	public static void removeOwner(UUID ownerId) {
		if (ownerId == null) return;
		for (SentinelWingmanEntity wingman : List.copyOf(LIVE)) {
			if (ownerId.equals(wingman.getOwnerId())) wingman.recombine();
		}
		RESPAWN_AFTER.keySet().removeIf(key -> ownerId.equals(key.ownerId()));
		LAST_CLIENT_SEQUENCE.remove(ownerId);
		LAST_COMMAND_TICK.remove(ownerId);
	}

	public static void handleEntityLoad(Entity entity) {
		if (entity instanceof SentinelWingmanEntity wingman) track(wingman);
	}

	public static void handleEntityUnload(Entity entity) {
		if (entity instanceof SentinelWingmanEntity wingman) untrack(wingman);
	}

	public static void clearRuntimeIndex() {
		LIVE.clear();
		RESPAWN_AFTER.clear();
		LAST_CLIENT_SEQUENCE.clear();
		LAST_COMMAND_TICK.clear();
	}

	private static void deny(ServerPlayer owner, String translationKey) {
		owner.displayClientMessage(Component.translatable(translationKey), true);
	}

	record WingmanKey(UUID ownerId, UUID suitId, int side) {
		WingmanKey {
			if (ownerId == null || suitId == null
					|| side != LEFT_SIDE && side != RIGHT_SIDE) {
				throw new IllegalArgumentException("Invalid Sentinel wingman identity");
			}
		}
	}
}
