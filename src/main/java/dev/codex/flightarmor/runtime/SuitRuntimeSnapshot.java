package dev.codex.flightarmor.runtime;

import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 服务端单次解析得到的不可变战甲运行时视图。它只汇总现有权威状态源，
 * 不保存 ItemStack，也不成为新的可变状态机。
 */
public record SuitRuntimeSnapshot(
		long revision,
		UUID ownerId,
		boolean coreActive,
		UUID coreId,
		UUID primarySuitId,
		List<SuitState> suits,
		Set<Conflict> conflicts
) {
	public SuitRuntimeSnapshot {
		suits = List.copyOf(suits);
		conflicts = Set.copyOf(conflicts);
	}

	public Map<UUID, SuitState> suitsById() {
		return suits.stream().collect(Collectors.toUnmodifiableMap(SuitState::suitId, Function.identity()));
	}

	public record SuitState(
			UUID suitId,
			UUID ownerId,
			UUID coreId,
			UUID platformId,
			String displayName,
			ArmorVariant variant,
			Location location,
			CarrierKind carrierKind,
			Role role,
			Phase phase,
			HomeHealth homeHealth,
			ResourceKey<Level> homeDimension,
			BlockPos homePosition,
			ResourceKey<Level> currentDimension,
			BlockPos currentPosition,
			ArmorEscortProfile escortProfile,
			Set<AllowedAction> allowedActions,
			Set<Conflict> conflicts,
			UUID transactionTargetSuitId,
			long transactionSequence,
			long transitionStartTick,
			long transitionTimeoutTick
	) {
		public SuitState {
			homePosition = homePosition == null ? null : homePosition.immutable();
			currentPosition = currentPosition == null ? null : currentPosition.immutable();
			allowedActions = Set.copyOf(allowedActions);
			conflicts = Set.copyOf(conflicts);
		}

		public boolean hasConflict(Conflict conflict) {
			return conflicts.contains(conflict);
		}
	}

	public enum Location {
		PLATFORM,
		WORN,
		CARRIER,
		LOOSE,
		UNKNOWN
	}

	public enum CarrierKind {
		NONE,
		INDEPENDENT,
		ESCORT
	}

	public enum Role {
		PRIMARY,
		ESCORT,
		NONE
	}

	public enum Phase {
		IDLE,
		LAUNCHING,
		TRANSIT,
		GUARDING,
		RETURNING_PLATFORM,
		RECALLING_OWNER,
		ASSEMBLING,
		RETRACTING,
		OWNER_DOWN,
		DEPLOYING,
		DOCKING,
		PHASING
	}

	public enum HomeHealth {
		VALID_OCCUPIED,
		VALID_EMPTY_RESERVED,
		UNLOADED,
		UNBOUND,
		INCOMPLETE,
		BROKEN
	}

	public enum AllowedAction {
		CAN_RECALL_AND_WEAR,
		CAN_LAUNCH_ESCORT,
		CAN_RETURN_HOME,
		CAN_SET_PRIMARY,
		CAN_SET_ESCORT,
		CAN_REBIND_HOME,
		CAN_QUICK_SWITCH,
		CAN_FORGET_MISSING,
		CAN_DEPLOY_WORN
	}

	public enum Conflict {
		CORE_INACTIVE,
		OWNER_MISMATCH,
		IDENTITY_MISMATCH,
		PARTIAL_WORN_SET,
		LOCATION_COLLISION,
		LEDGER_MISMATCH,
		PLATFORM_IDENTITY_MISMATCH,
		PLATFORM_BROKEN,
		TRANSITION_CONFLICT
	}
}
