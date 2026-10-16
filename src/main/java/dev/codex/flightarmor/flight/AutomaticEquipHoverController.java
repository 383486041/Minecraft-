package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Detects every ordinary equipment-slot path on the authoritative server.
 *
 * <p>Inventory dragging, shift-clicking, commands and other mods all converge on the four
 * vanilla equipment slots. Sampling those slots once per server tick therefore covers them
 * without trusting a client packet or patching every possible inventory menu.</p>
 */
public final class AutomaticEquipHoverController {
	private static final int EDGE_RETRY_TICKS = 40;
	private static final Map<UUID, EquipTracker> TRACKERS = new HashMap<>();
	private static long nextEdgeId;

	private AutomaticEquipHoverController() {
	}

	public static void tickServer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickPlayer(player);
		}
	}

	private static void tickPlayer(ServerPlayer player) {
		boolean eligible = FlightController.hasAuthorizedOrdinaryFlightGear(player);
		EquipTracker tracker = TRACKERS.get(player.getUUID());
		if (tracker == null || !tracker.dimension.equals(player.level().dimension())) {
			TRACKERS.put(player.getUUID(), EquipTracker.baseline(player, eligible));
			return;
		}

		boolean transactionBusy = transactionBusy(player);
		boolean flightSessionActive = FlightController.hasActiveFlightSession(player);
		AutomaticEquipHoverTransition.Action action = AutomaticEquipHoverTransition.decide(
				true, tracker.previouslyEligible, eligible, transactionBusy,
				flightSessionActive, tracker.pending != null);

		switch (action) {
			case BASELINE -> {
				tracker.previouslyEligible = eligible;
				tracker.pending = null;
			}
			case IDLE -> tracker.previouslyEligible = eligible;
			case CANCEL_PENDING -> {
				tracker.previouslyEligible = false;
				tracker.pending = null;
			}
			case SUPPRESS -> {
				// Assembly/docking owns its own sequence-bound hover event. An already active
				// session also consumes this equipment edge so stopping it later cannot relaunch.
				tracker.previouslyEligible = eligible;
				tracker.pending = null;
			}
			case START_EDGE -> {
				long edgeId = nextEdgeId();
				tracker.previouslyEligible = true;
				tracker.pending = new PendingEdge(edgeId, player.serverLevel().getGameTime(),
						player.tickCount + EDGE_RETRY_TICKS, false);
				attemptEdge(player, tracker);
			}
			case RETRY_EDGE -> {
				tracker.previouslyEligible = true;
				attemptEdge(player, tracker);
			}
		}
	}

	private static void attemptEdge(ServerPlayer player, EquipTracker tracker) {
		PendingEdge edge = tracker.pending;
		if (edge == null) return;
		if (player.tickCount > edge.expiresAtTick) {
			tracker.pending = null;
			return;
		}
		FlightController.OrdinaryEquipHoverResult result =
				FlightController.tryEngageHoverAfterOrdinaryEquip(
						player, edge.edgeId, edge.attemptId, !edge.groundLiftPrepared);
		switch (result) {
			case ENGAGED -> tracker.pending = null;
			case GROUND_LIFT_PREPARED -> tracker.pending = edge.withGroundLiftPrepared();
			case WAITING_FOR_LIFTOFF, DENIED -> {
				// Keep this exact edge pending for a bounded period. This is not a second
				// false-to-true transition and therefore cannot repeatedly restart flight.
			}
		}
	}

	private static boolean transactionBusy(ServerPlayer player) {
		if (ArmorAssemblyController.isAnimating(player.getUUID())) return true;
		return ArmorCruiseController.runtimeView(player)
				.map(view -> switch (view.phase()) {
					case RECALLING, PHASING, DOCKING, DEPLOYING -> true;
					case IDLE, CRUISING, OWNER_DOWN -> false;
				})
				.orElse(false);
	}

	public static void handleJoin(ServerPlayer player) {
		TRACKERS.put(player.getUUID(), EquipTracker.baseline(player,
				FlightController.hasAuthorizedOrdinaryFlightGear(player)));
	}

	public static void handleRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		TRACKERS.remove(oldPlayer.getUUID());
		handleJoin(newPlayer);
	}

	public static void handleDimensionChange(ServerPlayer player) {
		TRACKERS.put(player.getUUID(), EquipTracker.baseline(player,
				FlightController.hasAuthorizedOrdinaryFlightGear(player)));
	}

	public static void remove(ServerPlayer player) {
		TRACKERS.remove(player.getUUID());
	}

	public static void clear() {
		TRACKERS.clear();
	}

	private static long nextEdgeId() {
		nextEdgeId = nextEdgeId == Long.MAX_VALUE ? 1L : nextEdgeId + 1L;
		return nextEdgeId;
	}

	private static final class EquipTracker {
		private final ResourceKey<Level> dimension;
		private boolean previouslyEligible;
		private PendingEdge pending;

		private EquipTracker(ResourceKey<Level> dimension, boolean previouslyEligible) {
			this.dimension = dimension;
			this.previouslyEligible = previouslyEligible;
		}

		private static EquipTracker baseline(ServerPlayer player, boolean eligible) {
			return new EquipTracker(player.level().dimension(), eligible);
		}
	}

	private record PendingEdge(long edgeId, long attemptId, int expiresAtTick,
			boolean groundLiftPrepared) {
		private PendingEdge withGroundLiftPrepared() {
			return groundLiftPrepared ? this
					: new PendingEdge(edgeId, attemptId, expiresAtTick, true);
		}
	}
}
