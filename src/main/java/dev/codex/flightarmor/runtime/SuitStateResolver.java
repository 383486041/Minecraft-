package dev.codex.flightarmor.runtime;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 把现有控制器的只读证据解析成单一服务端快照。 */
public final class SuitStateResolver {
	private static final AtomicLong NEXT_REVISION = new AtomicLong();

	private SuitStateResolver() {
	}

	public static SuitRuntimeSnapshot resolve(ServerPlayer player) {
		ArmorCoreController.CoreRuntimeView core = ArmorCoreController.runtimeView(player);
		List<ChargingPlatformController.WornPartRuntimeView> worn =
				ChargingPlatformController.wornRuntimeView(player);
		Map<UUID, List<ChargingPlatformController.WornPartRuntimeView>> wornBySuit = new HashMap<>();
		for (ChargingPlatformController.WornPartRuntimeView part : worn) {
			wornBySuit.computeIfAbsent(part.suitId(), ignored -> new ArrayList<>()).add(part);
		}
		Optional<ArmorCruiseController.CruiseRuntimeView> cruise =
				ArmorCruiseController.runtimeView(player);
		Optional<ArmorAssemblyController.AssemblyRuntimeView> assembly =
				ArmorAssemblyController.runtimeView(player).filter(
						ArmorAssemblyController.AssemblyRuntimeView::animating);

		List<SuitRuntimeSnapshot.SuitState> states = new ArrayList<>(core.bindings().size());
		EnumSet<SuitRuntimeSnapshot.Conflict> ownerConflicts =
				EnumSet.noneOf(SuitRuntimeSnapshot.Conflict.class);
		for (ArmorCoreController.CoreBindingRuntimeView binding : core.bindings()) {
			SuitRuntimeSnapshot.SuitState state = resolveSuit(
					player, core, binding, wornBySuit.getOrDefault(binding.suitId(), List.of()),
					cruise.orElse(null), assembly.orElse(null));
			states.add(state);
			ownerConflicts.addAll(state.conflicts());
		}
		return new SuitRuntimeSnapshot(NEXT_REVISION.incrementAndGet(), player.getUUID(),
				core.active(), core.coreId(), core.primarySuitId(), states, ownerConflicts);
	}

	private static SuitRuntimeSnapshot.SuitState resolveSuit(ServerPlayer player,
			ArmorCoreController.CoreRuntimeView core,
			ArmorCoreController.CoreBindingRuntimeView binding,
			List<ChargingPlatformController.WornPartRuntimeView> wornParts,
			ArmorCruiseController.CruiseRuntimeView cruise,
			ArmorAssemblyController.AssemblyRuntimeView assembly) {
		UUID suitId = binding.suitId();
		EnumSet<SuitRuntimeSnapshot.Conflict> conflicts =
				EnumSet.noneOf(SuitRuntimeSnapshot.Conflict.class);
		if (!core.active()) conflicts.add(SuitRuntimeSnapshot.Conflict.CORE_INACTIVE);

		ChargingPlatformController.OwnershipRuntimeView ownership =
				ChargingPlatformController.ownershipRuntimeView(player, suitId).orElse(null);
		ChargingPlatformController.PlatformRuntimeView platform =
				ChargingPlatformController.platformRuntimeView(player, suitId, binding.platformId(),
						binding.dimension(), binding.pos());

		boolean worn = !wornParts.isEmpty();
		boolean completeWorn = isCompleteWornSet(player, binding, wornParts);
		if (worn && !completeWorn) conflicts.add(SuitRuntimeSnapshot.Conflict.PARTIAL_WORN_SET);
		for (ChargingPlatformController.WornPartRuntimeView part : wornParts) {
			if (!player.getUUID().equals(part.ownerId())) {
				conflicts.add(SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH);
			}
			if (!part.expectedItem() || part.equippedSlot() != part.identitySlot()) {
				conflicts.add(SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH);
			}
		}

		boolean escort = ArmorEscortController.isLoadedActive(suitId);
		boolean escortRecoverable =
				ArmorEscortController.hasRecoverableSuit(player.getServer(), suitId);
		boolean cruiseLocatorMatches = cruise != null && suitId.equals(cruise.suitId());
		// A persisted CRUISE ledger/locator is recovery evidence, not proof that a usable carrier
		// currently exists. Only the exact, loaded carrier may authorize carrier commands.
		boolean cruiseMatches = cruiseLocatorMatches && cruise.loaded();
		boolean carrier = escort || cruiseMatches;
		boolean platformOccupied = platform.health() == ChargingPlatformController.PlatformHealth.VALID_OCCUPIED
				|| platform.health() == ChargingPlatformController.PlatformHealth.INCOMPLETE;
		int physicalLocations = (worn ? 1 : 0) + (carrier ? 1 : 0) + (platformOccupied ? 1 : 0);
		if (physicalLocations > 1) conflicts.add(SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION);

		if (ownership != null) {
			if (!player.getUUID().equals(ownership.ownerId())) {
				conflicts.add(SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH);
			}
			if (worn && ownership.location() != ChargingPlatformController.OwnershipLocation.CARRIED
					|| carrier && ownership.location() != ChargingPlatformController.OwnershipLocation.CRUISE
					|| platformOccupied && ownership.location() != ChargingPlatformController.OwnershipLocation.PLATFORM) {
				conflicts.add(SuitRuntimeSnapshot.Conflict.LEDGER_MISMATCH);
			}
		}
		if (platform.health() == ChargingPlatformController.PlatformHealth.IDENTITY_CONFLICT) {
			conflicts.add(SuitRuntimeSnapshot.Conflict.PLATFORM_IDENTITY_MISMATCH);
		}
		if (platform.health() == ChargingPlatformController.PlatformHealth.BROKEN
				|| platform.health() == ChargingPlatformController.PlatformHealth.DIMENSION_UNAVAILABLE) {
			conflicts.add(SuitRuntimeSnapshot.Conflict.PLATFORM_BROKEN);
		}

		SuitRuntimeSnapshot.Location location = resolveLocation(
				worn, carrier, platformOccupied, ownership);
		SuitRuntimeSnapshot.HomeHealth homeHealth = mapHomeHealth(platform.health());
		SuitRuntimeSnapshot.Role role = suitId.equals(core.primarySuitId())
				? SuitRuntimeSnapshot.Role.PRIMARY : SuitRuntimeSnapshot.Role.ESCORT;
		SuitRuntimeSnapshot.Phase phase = resolvePhase(
				player, suitId, location, core.quickSwitch(), escort,
				cruiseLocatorMatches, cruise, assembly);

		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> currentDimension = null;
		BlockPos currentPosition = null;
		if (location == SuitRuntimeSnapshot.Location.WORN) {
			currentDimension = player.level().dimension();
			currentPosition = player.blockPosition();
		} else if (escort) {
			ArmorEscortController.EscortWorldAnchor anchor =
					ArmorEscortController.currentAnchor(suitId).orElse(null);
			if (anchor != null) {
				currentDimension = anchor.dimension();
				currentPosition = anchor.position();
			}
		} else if (cruiseLocatorMatches && cruise != null) {
			currentDimension = cruise.dimension();
			currentPosition = BlockPos.containing(cruise.position());
		} else if (location == SuitRuntimeSnapshot.Location.LOOSE && ownership != null) {
			currentDimension = ownership.dimension();
			currentPosition = ownership.pos();
		} else if (location == SuitRuntimeSnapshot.Location.PLATFORM) {
			currentDimension = binding.dimension();
			currentPosition = binding.pos();
		}

		TransactionMetadata transaction = transactionMetadata(
				suitId, core.quickSwitch(), cruiseLocatorMatches ? cruise : null,
				worn ? assembly : null);
		SuitRuntimeSnapshot.CarrierKind carrierKind = location == SuitRuntimeSnapshot.Location.CARRIER
				? (escort ? SuitRuntimeSnapshot.CarrierKind.ESCORT : SuitRuntimeSnapshot.CarrierKind.INDEPENDENT)
				: SuitRuntimeSnapshot.CarrierKind.NONE;
		Set<SuitRuntimeSnapshot.AllowedAction> allowed = allowedActions(
				core, role, location, carrierKind, phase, homeHealth, platform,
				escortRecoverable || cruiseLocatorMatches, conflicts);
		return new SuitRuntimeSnapshot.SuitState(suitId, player.getUUID(), core.coreId(),
				binding.platformId(), binding.displayName(), binding.variant(), location, carrierKind, role, phase,
				homeHealth, binding.dimension(), binding.pos(), currentDimension, currentPosition,
				binding.escortProfile(), allowed, conflicts, transaction.targetSuitId(),
				transaction.sequence(), transaction.startTick(), transaction.timeoutTick());
	}

	private static boolean isCompleteWornSet(ServerPlayer player,
			ArmorCoreController.CoreBindingRuntimeView binding,
			List<ChargingPlatformController.WornPartRuntimeView> parts) {
		if (parts.size() != 4) return false;
		EnumSet<EquipmentSlot> slots = EnumSet.noneOf(EquipmentSlot.class);
		for (ChargingPlatformController.WornPartRuntimeView part : parts) {
			if (!player.getUUID().equals(part.ownerId()) || !part.suitId().equals(binding.suitId())
					|| part.identitySlot() != part.equippedSlot() || !part.expectedItem()
					|| part.variant() != binding.variant()) return false;
			slots.add(part.equippedSlot());
		}
		return slots.containsAll(List.of(EquipmentSlot.FEET, EquipmentSlot.LEGS,
				EquipmentSlot.CHEST, EquipmentSlot.HEAD));
	}

	private static SuitRuntimeSnapshot.Location resolveLocation(boolean worn, boolean carrier,
			boolean platformOccupied, ChargingPlatformController.OwnershipRuntimeView ownership) {
		if (worn) return SuitRuntimeSnapshot.Location.WORN;
		if (carrier) return SuitRuntimeSnapshot.Location.CARRIER;
		if (platformOccupied) return SuitRuntimeSnapshot.Location.PLATFORM;
		if (ownership == null) return SuitRuntimeSnapshot.Location.UNKNOWN;
		return switch (ownership.location()) {
			// PLATFORM is likewise only a locator state. An occupied/incomplete loaded platform
			// was already accepted above; an empty, broken, replaced, or unloaded home is not a
			// physical recall source.
			case PLATFORM -> SuitRuntimeSnapshot.Location.UNKNOWN;
			// A ledger row cannot stand in for the physical carrier. The cruise controller keeps
			// its locator for recovery, while the panel exposes this as missing/offline until the
			// exact entity and its four identities are restored.
			case CRUISE -> SuitRuntimeSnapshot.Location.UNKNOWN;
			case LOOSE -> SuitRuntimeSnapshot.Location.LOOSE;
			case CARRIED -> SuitRuntimeSnapshot.Location.UNKNOWN;
		};
	}

	private static SuitRuntimeSnapshot.HomeHealth mapHomeHealth(
			ChargingPlatformController.PlatformHealth health) {
		return switch (health) {
			case VALID_OCCUPIED -> SuitRuntimeSnapshot.HomeHealth.VALID_OCCUPIED;
			case VALID_EMPTY_RESERVED -> SuitRuntimeSnapshot.HomeHealth.VALID_EMPTY_RESERVED;
			case UNLOADED -> SuitRuntimeSnapshot.HomeHealth.UNLOADED;
			case UNBOUND -> SuitRuntimeSnapshot.HomeHealth.UNBOUND;
			case INCOMPLETE -> SuitRuntimeSnapshot.HomeHealth.INCOMPLETE;
			case BROKEN, DIMENSION_UNAVAILABLE, IDENTITY_CONFLICT ->
					SuitRuntimeSnapshot.HomeHealth.BROKEN;
		};
	}

	private static SuitRuntimeSnapshot.Phase resolvePhase(ServerPlayer player, UUID suitId,
			SuitRuntimeSnapshot.Location location,
			ArmorCoreController.QuickSwitchRuntimeView quickSwitch,
			boolean escort, boolean cruiseMatches,
			ArmorCruiseController.CruiseRuntimeView cruise,
			ArmorAssemblyController.AssemblyRuntimeView assembly) {
		if (quickSwitch != null && suitId.equals(quickSwitch.outgoingSuitId())) {
			return SuitRuntimeSnapshot.Phase.RETRACTING;
		}
		if (quickSwitch != null && suitId.equals(quickSwitch.targetSuitId())) {
			return SuitRuntimeSnapshot.Phase.RECALLING_OWNER;
		}
		if (assembly != null && location == SuitRuntimeSnapshot.Location.WORN) {
			return assembly.retracting()
					? SuitRuntimeSnapshot.Phase.RETRACTING : SuitRuntimeSnapshot.Phase.ASSEMBLING;
		}
		if (escort) {
			if (ArmorEscortController.isReturning(suitId)) {
				return SuitRuntimeSnapshot.Phase.RETURNING_PLATFORM;
			}
			if (ArmorEscortController.isRecallForWearInProgress(player, suitId)) {
				return SuitRuntimeSnapshot.Phase.RECALLING_OWNER;
			}
			return SuitRuntimeSnapshot.Phase.GUARDING;
		}
		if (cruiseMatches && cruise != null) {
			return switch (cruise.phase()) {
				case CRUISING -> SuitRuntimeSnapshot.Phase.GUARDING;
				case RECALLING -> SuitRuntimeSnapshot.Phase.RECALLING_OWNER;
				case PHASING -> SuitRuntimeSnapshot.Phase.PHASING;
				case OWNER_DOWN -> SuitRuntimeSnapshot.Phase.OWNER_DOWN;
				case DOCKING -> SuitRuntimeSnapshot.Phase.DOCKING;
				case DEPLOYING -> SuitRuntimeSnapshot.Phase.DEPLOYING;
				case IDLE -> SuitRuntimeSnapshot.Phase.IDLE;
			};
		}
		return SuitRuntimeSnapshot.Phase.IDLE;
	}

	static boolean hasBlockingActionConflict(Set<SuitRuntimeSnapshot.Conflict> conflicts) {
		if (conflicts == null || conflicts.isEmpty()) return false;
		return conflicts.contains(SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.PARTIAL_WORN_SET)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.LEDGER_MISMATCH)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.PLATFORM_IDENTITY_MISMATCH)
				|| conflicts.contains(SuitRuntimeSnapshot.Conflict.TRANSITION_CONFLICT);
	}

	static Set<SuitRuntimeSnapshot.AllowedAction> allowedActions(
			ArmorCoreController.CoreRuntimeView core, SuitRuntimeSnapshot.Role role,
			SuitRuntimeSnapshot.Location location, SuitRuntimeSnapshot.CarrierKind carrierKind,
			SuitRuntimeSnapshot.Phase phase, SuitRuntimeSnapshot.HomeHealth homeHealth,
			ChargingPlatformController.PlatformRuntimeView platform,
			boolean recoverableCarrierEvidence,
			Set<SuitRuntimeSnapshot.Conflict> conflicts) {
		EnumSet<SuitRuntimeSnapshot.AllowedAction> allowed =
				EnumSet.noneOf(SuitRuntimeSnapshot.AllowedAction.class);
		if (!core.active() || hasBlockingActionConflict(conflicts)) {
			return allowed;
		}
		if (phase != SuitRuntimeSnapshot.Phase.IDLE
				&& phase != SuitRuntimeSnapshot.Phase.GUARDING) {
			return allowed;
		}
		if (location == SuitRuntimeSnapshot.Location.LOOSE
				|| location == SuitRuntimeSnapshot.Location.UNKNOWN) {
			if (!recoverableCarrierEvidence
					&& (homeHealth == SuitRuntimeSnapshot.HomeHealth.BROKEN
					|| homeHealth == SuitRuntimeSnapshot.HomeHealth.UNBOUND
					|| homeHealth == SuitRuntimeSnapshot.HomeHealth.VALID_EMPTY_RESERVED)) {
				allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING);
			}
			if (homeHealth == SuitRuntimeSnapshot.HomeHealth.BROKEN
					|| homeHealth == SuitRuntimeSnapshot.HomeHealth.UNBOUND) {
				allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_REBIND_HOME);
			}
			return allowed;
		}
		if (role != SuitRuntimeSnapshot.Role.PRIMARY) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_SET_PRIMARY);
		}
		allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_SET_ESCORT);
		if (location == SuitRuntimeSnapshot.Location.PLATFORM
				&& platform.health() != ChargingPlatformController.PlatformHealth.INCOMPLETE
				&& homeHealth != SuitRuntimeSnapshot.HomeHealth.BROKEN) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR);
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT);
		}
		if (location == SuitRuntimeSnapshot.Location.CARRIER) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR);
			if (carrierKind == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT) {
				allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT);
			}
			if (homeHealth == SuitRuntimeSnapshot.HomeHealth.VALID_EMPTY_RESERVED
					|| homeHealth == SuitRuntimeSnapshot.HomeHealth.UNLOADED) {
				allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_RETURN_HOME);
			}
		}
		if (location == SuitRuntimeSnapshot.Location.WORN && core.bindings().size() > 1) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_QUICK_SWITCH);
		}
		if (location == SuitRuntimeSnapshot.Location.WORN) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_DEPLOY_WORN);
		}
		if (homeHealth == SuitRuntimeSnapshot.HomeHealth.BROKEN
				|| homeHealth == SuitRuntimeSnapshot.HomeHealth.UNBOUND) {
			allowed.add(SuitRuntimeSnapshot.AllowedAction.CAN_REBIND_HOME);
		}
		return allowed;
	}

	private static TransactionMetadata transactionMetadata(UUID suitId,
			ArmorCoreController.QuickSwitchRuntimeView quickSwitch,
			ArmorCruiseController.CruiseRuntimeView cruise,
			ArmorAssemblyController.AssemblyRuntimeView assembly) {
		if (quickSwitch != null && (suitId.equals(quickSwitch.outgoingSuitId())
				|| suitId.equals(quickSwitch.targetSuitId()))) {
			return new TransactionMetadata(quickSwitch.targetSuitId(), quickSwitch.startGameTime(),
					quickSwitch.startGameTime(),
					quickSwitch.startGameTime() + quickSwitch.timeoutTicks());
		}
		if (assembly != null) {
			return new TransactionMetadata(null, assembly.sequenceId(), assembly.startGameTime(),
					assembly.startGameTime() + assembly.durationTicks());
		}
		if (cruise != null) {
			long timeout = cruise.phase() == ArmorCruisePhase.RECALLING
					? cruise.phaseStartGameTime() + ArmorCruisePhase.RECALL_TIMEOUT_TICKS : 0L;
			return new TransactionMetadata(null, cruise.sequenceId(),
					cruise.phaseStartGameTime(), timeout);
		}
		return TransactionMetadata.NONE;
	}

	private record TransactionMetadata(UUID targetSuitId, long sequence,
			long startTick, long timeoutTick) {
		private static final TransactionMetadata NONE =
				new TransactionMetadata(null, 0L, 0L, 0L);
	}
}
