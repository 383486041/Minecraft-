package dev.codex.flightarmor.core;

import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Offline regression coverage for idempotent B-key desired-formation planning. */
public final class ArmorFormationPlannerTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID CORE = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID PRIMARY = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final UUID SECONDARY = UUID.fromString("00000000-0000-0000-0000-000000000004");

	private ArmorFormationPlannerTest() {}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		inactiveCoreFallsBackToStandaloneB();
		activeCoreWithoutBindingsDoesNotFallBack();
		alignedFormationIsIdempotent();
		platformFormationStartsOnce();
		independentSecondaryIsAdoptedFirst();
		existingEscortIsUntouched();
		missingSecondaryDoesNotBlockHealthyFormation();
		carrierPrimariesUseTheirExactSource();
		onlyPlatformCommandsRequirePlatformHome();
		wornSecondaryStartsOneQuickSwitch();
		transactionsAndTransitionsWait();
		hardConflictRejects();
	}

	private static void onlyPlatformCommandsRequirePlatformHome() {
		require(ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM),
				"platform recall stopped requiring its physical home");
		require(ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.LAUNCH_ESCORT_PLATFORM),
				"platform escort launch stopped requiring its physical home");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.ADOPT_INDEPENDENT_ESCORT),
				"online independent carrier was coupled to an optional return home");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_INDEPENDENT),
				"online independent recall was coupled to an optional platform home");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_ESCORT),
				"online escort recall was coupled to an optional platform home");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.QUICK_SWITCH_PRIMARY),
				"quick-switch preflight was globally coupled to a target platform home");
	}

	private static void inactiveCoreFallsBackToStandaloneB() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(false,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE, Set.of(), Set.of(), null)));
		require(plan.status() == ArmorFormationPlanner.Status.INACTIVE && plan.commands().isEmpty(),
				"inactive core must leave B to standalone cruise");
	}

	private static void activeCoreWithoutBindingsDoesNotFallBack() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true));
		require(plan.status() == ArmorFormationPlanner.Status.REJECTED
				&& plan.reason() == ArmorFormationPlanner.Reason.NO_PRIMARY
				&& plan.commands().isEmpty(), "active empty core must be handled instead of falling back");
	}
	private static void alignedFormationIsIdempotent() {
		SuitRuntimeSnapshot aligned = snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE, Set.of(), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.GUARDING, Set.of(), Set.of(), null));
		ArmorFormationPlanner.Plan first = ArmorFormationPlanner.plan(aligned);
		ArmorFormationPlanner.Plan repeated = ArmorFormationPlanner.plan(aligned);
		require(first.status() == ArmorFormationPlanner.Status.SATISFIED && first.commands().isEmpty(),
				"aligned formation unexpectedly emitted a command");
		require(repeated.equals(first), "repeated B changed an aligned formation plan");
		require(ArmorFormationPlanner.shouldDeployAlignedWornPrimary(first, aligned, true),
				"physical B must deploy an aligned primary that is already fully worn");
		require(!ArmorFormationPlanner.shouldDeployAlignedWornPrimary(first, aligned, false),
				"physical B must not deploy a partial worn set");
		require(!ArmorFormationPlanner.shouldDeployAlignedWornPrimary(
				ArmorFormationPlanner.Plan.inactive(), aligned, true), "inactive core policy leaked into standalone B");
	}

	private static void platformFormationStartsOnce() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT), Set.of(), null)));
		requireCommands(plan,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.LAUNCH_ESCORT_PLATFORM, SECONDARY),
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM, PRIMARY));
	}

	private static void independentSecondaryIsAdoptedFirst() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.INDEPENDENT,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.GUARDING, Set.of(), Set.of(), null)));
		requireCommands(plan,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.ADOPT_INDEPENDENT_ESCORT, SECONDARY),
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM, PRIMARY));
	}

	private static void existingEscortIsUntouched() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.GUARDING, Set.of(), Set.of(), null)));
		requireCommands(plan,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM, PRIMARY));
	}

	private static void missingSecondaryDoesNotBlockHealthyFormation() {
		UUID healthySecondary = UUID.fromString("00000000-0000-0000-0000-000000000005");
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.UNKNOWN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING), Set.of(), null),
				suit(healthySecondary, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT), Set.of(), null)));
		requireCommands(plan,
				new ArmorFormationPlanner.Command(
						ArmorFormationPlanner.CommandType.LAUNCH_ESCORT_PLATFORM, healthySecondary),
				new ArmorFormationPlanner.Command(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM, PRIMARY));
	}

	private static void carrierPrimariesUseTheirExactSource() {
		ArmorFormationPlanner.Plan independent = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.INDEPENDENT,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.GUARDING, Set.of(), Set.of(), null)));
		requireCommands(independent,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.RECALL_PRIMARY_INDEPENDENT, PRIMARY));
		ArmorFormationPlanner.Plan escort = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.GUARDING,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null)));
		requireCommands(escort,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.RECALL_PRIMARY_ESCORT, PRIMARY));
	}

	private static void wornSecondaryStartsOneQuickSwitch() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.IDLE, Set.of(), Set.of(), null)));
		requireCommands(plan,
				new ArmorFormationPlanner.Command(ArmorFormationPlanner.CommandType.QUICK_SWITCH_PRIMARY, PRIMARY));
	}

	private static void transactionsAndTransitionsWait() {
		ArmorFormationPlanner.Plan transaction = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.RETRACTING,
						Set.of(), Set.of(), PRIMARY)));
		require(transaction.status() == ArmorFormationPlanner.Status.WAITING
				&& transaction.reason() == ArmorFormationPlanner.Reason.TRANSACTION_ACTIVE
				&& transaction.commands().isEmpty(), "quick-switch emitted a second command");
		ArmorFormationPlanner.Plan transition = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.INDEPENDENT,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.RECALLING_OWNER,
						Set.of(), Set.of(), null)));
		require(transition.status() == ArmorFormationPlanner.Status.WAITING
				&& transition.reason() == ArmorFormationPlanner.Reason.TRANSITION_ACTIVE
				&& transition.commands().isEmpty(), "transition emitted a duplicate command");
		ArmorFormationPlanner.Plan repeatedLaunch = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.WORN, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE, Set.of(), Set.of(), null),
				suit(SECONDARY, SuitRuntimeSnapshot.Location.CARRIER, SuitRuntimeSnapshot.CarrierKind.ESCORT,
						SuitRuntimeSnapshot.Role.ESCORT, SuitRuntimeSnapshot.Phase.LAUNCHING, Set.of(), Set.of(), null)));
		require(repeatedLaunch.status() == ArmorFormationPlanner.Status.WAITING
				&& repeatedLaunch.commands().isEmpty(), "repeated B relaunched an escort already leaving its platform");
	}

	private static void hardConflictRejects() {
		ArmorFormationPlanner.Plan plan = ArmorFormationPlanner.plan(snapshot(true,
				suit(PRIMARY, SuitRuntimeSnapshot.Location.PLATFORM, SuitRuntimeSnapshot.CarrierKind.NONE,
						SuitRuntimeSnapshot.Role.PRIMARY, SuitRuntimeSnapshot.Phase.IDLE,
						Set.of(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR),
						Set.of(SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION), null)));
		require(plan.status() == ArmorFormationPlanner.Status.REJECTED
				&& plan.reason() == ArmorFormationPlanner.Reason.IDENTITY_OR_LOCATION_CONFLICT
				&& plan.commands().isEmpty(), "location conflict was not rejected");
	}

	private static SuitRuntimeSnapshot snapshot(boolean active, SuitRuntimeSnapshot.SuitState... suits) {
		return new SuitRuntimeSnapshot(1L, OWNER, active, active ? CORE : null, PRIMARY, List.of(suits), Set.of());
	}

	private static SuitRuntimeSnapshot.SuitState suit(UUID suitId,
			SuitRuntimeSnapshot.Location location, SuitRuntimeSnapshot.CarrierKind carrierKind,
			SuitRuntimeSnapshot.Role role, SuitRuntimeSnapshot.Phase phase,
			Set<SuitRuntimeSnapshot.AllowedAction> allowed,
			Set<SuitRuntimeSnapshot.Conflict> conflicts, UUID transactionTarget) {
		return new SuitRuntimeSnapshot.SuitState(suitId, OWNER, CORE, UUID.randomUUID(),
				"SUIT-" + suitId.toString().substring(0, 8), ArmorVariant.DEFAULT,
				location, carrierKind, role, phase, SuitRuntimeSnapshot.HomeHealth.VALID_EMPTY_RESERVED,
				Level.OVERWORLD, new BlockPos(0, 64, 0), Level.OVERWORLD, new BlockPos(4, 70, 4),
				ArmorEscortProfile.defaults(), allowed, conflicts, transactionTarget,
				transactionTarget == null ? 0L : 10L, transactionTarget == null ? 0L : 20L,
				transactionTarget == null ? 0L : 1200L);
	}

	private static void requireCommands(ArmorFormationPlanner.Plan plan,
			ArmorFormationPlanner.Command... expected) {
		require(plan.status() == ArmorFormationPlanner.Status.EXECUTE,
				"expected executable plan, got " + plan.status() + "/" + plan.reason());
		require(plan.commands().equals(List.of(expected)), "unexpected command sequence: " + plan.commands());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
