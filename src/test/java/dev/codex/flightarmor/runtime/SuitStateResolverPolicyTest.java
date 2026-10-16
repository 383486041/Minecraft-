package dev.codex.flightarmor.runtime;

import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.platform.ChargingPlatformController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Offline policy coverage for server-authoritative core action admission. */
public final class SuitStateResolverPolicyTest {
	private SuitStateResolverPolicyTest() {
	}

	public static void main(String[] args) {
		everyIdentityOrLocationConflictFailsClosed();
		platformFailureAloneStillAllowsRepair();
		stableIndependentCarrierCanBecomeEscort();
		stableWornSuitCanBeExplicitlyReleased();
	}

	private static void everyIdentityOrLocationConflictFailsClosed() {
		for (SuitRuntimeSnapshot.Conflict conflict : List.of(
				SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH,
				SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH,
				SuitRuntimeSnapshot.Conflict.PARTIAL_WORN_SET,
				SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION,
				SuitRuntimeSnapshot.Conflict.LEDGER_MISMATCH,
				SuitRuntimeSnapshot.Conflict.PLATFORM_IDENTITY_MISMATCH,
				SuitRuntimeSnapshot.Conflict.TRANSITION_CONFLICT)) {
			require(SuitStateResolver.hasBlockingActionConflict(Set.of(conflict)),
					"dangerous conflict did not fail closed: " + conflict);
			require(actions(SuitRuntimeSnapshot.Location.CARRIER,
					SuitRuntimeSnapshot.CarrierKind.INDEPENDENT, Set.of(conflict)).isEmpty(),
					"dangerous conflict still authorized a carrier command: " + conflict);
		}
	}

	private static void platformFailureAloneStillAllowsRepair() {
		Set<SuitRuntimeSnapshot.AllowedAction> actions = actions(
				SuitRuntimeSnapshot.Location.UNKNOWN, SuitRuntimeSnapshot.CarrierKind.NONE,
				Set.of(SuitRuntimeSnapshot.Conflict.PLATFORM_BROKEN));
		require(actions.equals(Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_REBIND_HOME,
						SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING)),
				"a broken optional home must not masquerade as destroyed armor or block repair");
	}

	private static void stableIndependentCarrierCanBecomeEscort() {
		Set<SuitRuntimeSnapshot.AllowedAction> actions = actions(
				SuitRuntimeSnapshot.Location.CARRIER,
				SuitRuntimeSnapshot.CarrierKind.INDEPENDENT, Set.of());
		require(actions.contains(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR),
				"independent carrier cannot be recalled for wear");
		require(actions.contains(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT),
				"independent carrier cannot be adopted into the escort formation");
	}

	private static void stableWornSuitCanBeExplicitlyReleased() {
		Set<SuitRuntimeSnapshot.AllowedAction> actions = actions(
				SuitRuntimeSnapshot.Location.WORN,
				SuitRuntimeSnapshot.CarrierKind.NONE, Set.of());
		require(actions.contains(SuitRuntimeSnapshot.AllowedAction.CAN_DEPLOY_WORN),
				"the core panel cannot explicitly release the exact worn suit");
		require(!actions.contains(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR),
				"a physically worn suit must not also expose a recall-for-wear command");
	}

	private static Set<SuitRuntimeSnapshot.AllowedAction> actions(
			SuitRuntimeSnapshot.Location location,
			SuitRuntimeSnapshot.CarrierKind carrierKind,
			Set<SuitRuntimeSnapshot.Conflict> conflicts) {
		ArmorCoreController.CoreRuntimeView core = new ArmorCoreController.CoreRuntimeView(
				true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(), null);
		return SuitStateResolver.allowedActions(core, SuitRuntimeSnapshot.Role.ESCORT,
				location, carrierKind, SuitRuntimeSnapshot.Phase.GUARDING,
				SuitRuntimeSnapshot.HomeHealth.UNBOUND,
				new ChargingPlatformController.PlatformRuntimeView(
						ChargingPlatformController.PlatformHealth.UNBOUND,
						false, false, null, null),
				false,
				EnumSet.copyOf(conflicts.isEmpty()
						? EnumSet.noneOf(SuitRuntimeSnapshot.Conflict.class) : conflicts));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
