package dev.codex.flightarmor.core;

import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure desired-formation decision layer. It never reads a controller or moves an ItemStack. */
final class ArmorFormationPlanner {
	private static final Set<SuitRuntimeSnapshot.Phase> TRANSITION_PHASES = EnumSet.of(
			SuitRuntimeSnapshot.Phase.LAUNCHING,
			SuitRuntimeSnapshot.Phase.TRANSIT,
			SuitRuntimeSnapshot.Phase.RETURNING_PLATFORM,
			SuitRuntimeSnapshot.Phase.RECALLING_OWNER,
			SuitRuntimeSnapshot.Phase.ASSEMBLING,
			SuitRuntimeSnapshot.Phase.RETRACTING,
			SuitRuntimeSnapshot.Phase.DEPLOYING,
			SuitRuntimeSnapshot.Phase.DOCKING,
			SuitRuntimeSnapshot.Phase.PHASING);
	private static final Set<SuitRuntimeSnapshot.Conflict> HARD_CONFLICTS = EnumSet.of(
			SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH,
			SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH,
			SuitRuntimeSnapshot.Conflict.PARTIAL_WORN_SET,
			SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION,
			SuitRuntimeSnapshot.Conflict.LEDGER_MISMATCH,
			SuitRuntimeSnapshot.Conflict.PLATFORM_IDENTITY_MISMATCH);

	private ArmorFormationPlanner() {
	}

	static Plan plan(SuitRuntimeSnapshot snapshot) {
		if (snapshot == null || !snapshot.coreActive()) return Plan.inactive();
		SuitRuntimeSnapshot.SuitState primary = snapshot.suits().stream()
				.filter(suit -> suit.suitId().equals(snapshot.primarySuitId()))
				.findFirst().orElse(null);
		if (primary == null) return Plan.rejected(Reason.NO_PRIMARY);
		for (SuitRuntimeSnapshot.SuitState suit : snapshot.suits()) {
			if (suit.transactionTargetSuitId() != null) return Plan.waiting(Reason.TRANSACTION_ACTIVE);
			if (suit.conflicts().stream().anyMatch(HARD_CONFLICTS::contains)) {
				return Plan.rejected(Reason.IDENTITY_OR_LOCATION_CONFLICT);
			}
			if (suit.conflicts().contains(SuitRuntimeSnapshot.Conflict.TRANSITION_CONFLICT)
					|| TRANSITION_PHASES.contains(suit.phase())) {
				return Plan.waiting(Reason.TRANSITION_ACTIVE);
			}
		}

		SuitRuntimeSnapshot.SuitState wornSecondary = snapshot.suits().stream()
				.filter(suit -> suit.role() != SuitRuntimeSnapshot.Role.PRIMARY
						&& suit.location() == SuitRuntimeSnapshot.Location.WORN)
				.findFirst().orElse(null);
		if (wornSecondary != null) {
			return Plan.execute(List.of(new Command(CommandType.QUICK_SWITCH_PRIMARY, primary.suitId())));
		}

		List<Command> adopt = new ArrayList<>();
		List<Command> launch = new ArrayList<>();
		for (SuitRuntimeSnapshot.SuitState suit : snapshot.suits()) {
			if (suit.role() == SuitRuntimeSnapshot.Role.PRIMARY) continue;
			Decision secondary = secondaryDecision(suit);
			if (secondary.reason != null) return secondary.waiting
					? Plan.waiting(secondary.reason) : Plan.rejected(secondary.reason);
			if (secondary.command != null) {
				if (secondary.command.type() == CommandType.ADOPT_INDEPENDENT_ESCORT) adopt.add(secondary.command);
				else launch.add(secondary.command);
			}
		}

		Decision primaryDecision = primaryDecision(primary);
		if (primaryDecision.reason != null) return primaryDecision.waiting
				? Plan.waiting(primaryDecision.reason) : Plan.rejected(primaryDecision.reason);
		List<Command> commands = new ArrayList<>(adopt.size() + launch.size() + 1);
		// Adopt independent carriers first, then launch distinct platform escorts before the primary
		// starts its player-wide assembly animation so one batch cannot block its own escorts.
		commands.addAll(adopt);
		commands.addAll(launch);
		if (primaryDecision.command != null) commands.add(primaryDecision.command);
		return commands.isEmpty() ? Plan.satisfied() : Plan.execute(commands);
	}
	static boolean shouldDeployAlignedWornPrimary(
			Plan plan, SuitRuntimeSnapshot snapshot, boolean hasFullWornSet) {
		if (!hasFullWornSet || plan == null || snapshot == null
				|| plan.status() != Status.SATISFIED) return false;
		return snapshot.suits().stream().anyMatch(suit ->
				suit.suitId().equals(snapshot.primarySuitId())
						&& suit.role() == SuitRuntimeSnapshot.Role.PRIMARY
						&& suit.location() == SuitRuntimeSnapshot.Location.WORN);
	}

	/**
	 * Core membership belongs to the physical suit id. Only commands that actually read a
	 * charging-platform home may require a platform binding.
	 */
	static boolean requiresPlatformHome(CommandType type) {
		return type == CommandType.RECALL_PRIMARY_PLATFORM
				|| type == CommandType.LAUNCH_ESCORT_PLATFORM;
	}

	private static Decision primaryDecision(SuitRuntimeSnapshot.SuitState suit) {
		return switch (suit.location()) {
			case WORN -> Decision.done();
			case PLATFORM -> suit.allowedActions().contains(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR)
					? Decision.command(CommandType.RECALL_PRIMARY_PLATFORM, suit.suitId())
					: Decision.rejected(Reason.PRIMARY_UNAVAILABLE);
			case CARRIER -> {
				if (suit.carrierKind() == SuitRuntimeSnapshot.CarrierKind.ESCORT
						&& suit.allowedActions().contains(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR)) {
					yield Decision.command(CommandType.RECALL_PRIMARY_ESCORT, suit.suitId());
				}
				if (suit.carrierKind() == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT
						&& (suit.phase() == SuitRuntimeSnapshot.Phase.GUARDING
						|| suit.phase() == SuitRuntimeSnapshot.Phase.OWNER_DOWN)) {
					yield Decision.command(CommandType.RECALL_PRIMARY_INDEPENDENT, suit.suitId());
				}
				yield Decision.rejected(Reason.PRIMARY_UNAVAILABLE);
			}
			case LOOSE, UNKNOWN -> Decision.rejected(Reason.PRIMARY_UNAVAILABLE);
		};
	}

	private static Decision secondaryDecision(SuitRuntimeSnapshot.SuitState suit) {
		return switch (suit.location()) {
			case CARRIER -> {
				if (suit.carrierKind() == SuitRuntimeSnapshot.CarrierKind.ESCORT
						&& suit.phase() == SuitRuntimeSnapshot.Phase.GUARDING) yield Decision.done();
				if (suit.carrierKind() == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT
						&& suit.phase() == SuitRuntimeSnapshot.Phase.GUARDING) {
					yield Decision.command(CommandType.ADOPT_INDEPENDENT_ESCORT, suit.suitId());
				}
				yield Decision.waiting(Reason.TRANSITION_ACTIVE);
			}
			case PLATFORM -> suit.allowedActions().contains(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT)
					? Decision.command(CommandType.LAUNCH_ESCORT_PLATFORM, suit.suitId())
					: Decision.rejected(Reason.ESCORT_UNAVAILABLE);
			case WORN -> Decision.rejected(Reason.IDENTITY_OR_LOCATION_CONFLICT);
			// A stale/missing secondary must remain visible for recovery or explicit cleanup, but
			// it must not prevent every other healthy secondary from joining the requested
			// formation. Primaries still fail closed in primaryDecision.
			case LOOSE, UNKNOWN -> Decision.done();
		};
	}

	enum CommandType {
		QUICK_SWITCH_PRIMARY,
		RECALL_PRIMARY_PLATFORM,
		RECALL_PRIMARY_ESCORT,
		RECALL_PRIMARY_INDEPENDENT,
		LAUNCH_ESCORT_PLATFORM,
		ADOPT_INDEPENDENT_ESCORT
	}

	enum Status { INACTIVE, SATISFIED, EXECUTE, WAITING, REJECTED }
	enum Reason {
		NONE,
		NO_PRIMARY,
		TRANSACTION_ACTIVE,
		TRANSITION_ACTIVE,
		IDENTITY_OR_LOCATION_CONFLICT,
		PRIMARY_UNAVAILABLE,
		ESCORT_UNAVAILABLE
	}

	record Command(CommandType type, UUID suitId) {
		Command {
			if (type == null || suitId == null) throw new IllegalArgumentException("Incomplete formation command");
		}
	}

	record Plan(Status status, Reason reason, List<Command> commands) {
		Plan {
			commands = List.copyOf(commands);
		}
		static Plan inactive() { return new Plan(Status.INACTIVE, Reason.NONE, List.of()); }
		static Plan satisfied() { return new Plan(Status.SATISFIED, Reason.NONE, List.of()); }
		static Plan execute(List<Command> commands) { return new Plan(Status.EXECUTE, Reason.NONE, commands); }
		static Plan waiting(Reason reason) { return new Plan(Status.WAITING, reason, List.of()); }
		static Plan rejected(Reason reason) { return new Plan(Status.REJECTED, reason, List.of()); }
	}

	private record Decision(Command command, Reason reason, boolean waiting) {
		static Decision done() { return new Decision(null, null, false); }
		static Decision command(CommandType type, UUID suitId) {
			return new Decision(new Command(type, suitId), null, false);
		}
		static Decision waiting(Reason reason) { return new Decision(null, reason, true); }
		static Decision rejected(Reason reason) { return new Decision(null, reason, false); }
	}
}
