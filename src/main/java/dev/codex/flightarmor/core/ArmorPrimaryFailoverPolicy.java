package dev.codex.flightarmor.core;

import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure server-side policy for promoting a healthy linked suit when the primary is confirmed missing. */
final class ArmorPrimaryFailoverPolicy {
	private static final Set<SuitRuntimeSnapshot.Conflict> UNSAFE_CONFLICTS = EnumSet.of(
			SuitRuntimeSnapshot.Conflict.OWNER_MISMATCH,
			SuitRuntimeSnapshot.Conflict.IDENTITY_MISMATCH,
			SuitRuntimeSnapshot.Conflict.PARTIAL_WORN_SET,
			SuitRuntimeSnapshot.Conflict.LOCATION_COLLISION,
			SuitRuntimeSnapshot.Conflict.LEDGER_MISMATCH,
			SuitRuntimeSnapshot.Conflict.PLATFORM_IDENTITY_MISMATCH,
			SuitRuntimeSnapshot.Conflict.TRANSITION_CONFLICT);

	private ArmorPrimaryFailoverPolicy() {
	}

	static Optional<UUID> selectReplacement(SuitRuntimeSnapshot snapshot) {
		if (snapshot == null || !snapshot.coreActive() || snapshot.suits().isEmpty()) {
			return Optional.empty();
		}
		// Never change role ownership while any physical transfer still references a suit.
		if (snapshot.suits().stream().anyMatch(suit -> suit.transactionTargetSuitId() != null)) {
			return Optional.empty();
		}

		UUID primarySuitId = snapshot.primarySuitId();
		SuitRuntimeSnapshot.SuitState primary = snapshot.suits().stream()
				.filter(suit -> suit.suitId().equals(primarySuitId))
				.findFirst().orElse(null);
		if (primary != null && !isConfirmedMissing(primary)) return Optional.empty();

		return snapshot.suits().stream()
				.filter(suit -> primarySuitId == null || !suit.suitId().equals(primarySuitId))
				.filter(ArmorPrimaryFailoverPolicy::isHealthyCandidate)
				.min(Comparator.comparingInt(ArmorPrimaryFailoverPolicy::candidateRank))
				.map(SuitRuntimeSnapshot.SuitState::suitId);
	}

	/**
	 * B-key fallback is deliberately broader than automatic failover: the current primary does
	 * not need to be confirmed destroyed, it only needs to be unavailable for this recall attempt.
	 */
	static Optional<UUID> selectRecallablePrimaryForB(SuitRuntimeSnapshot snapshot) {
		if (snapshot == null || !snapshot.coreActive() || snapshot.suits().isEmpty()) {
			return Optional.empty();
		}
		if (snapshot.suits().stream().anyMatch(suit -> suit.transactionTargetSuitId() != null)) {
			return Optional.empty();
		}
		UUID primarySuitId = snapshot.primarySuitId();
		boolean primaryRecallable = snapshot.suits().stream()
				.filter(suit -> suit.suitId().equals(primarySuitId))
				.anyMatch(ArmorPrimaryFailoverPolicy::isRecallableNow);
		if (primaryRecallable) return Optional.empty();
		return snapshot.suits().stream()
				.filter(suit -> primarySuitId == null || !suit.suitId().equals(primarySuitId))
				.filter(ArmorPrimaryFailoverPolicy::isRecallableNow)
				.min(Comparator.comparingInt(ArmorPrimaryFailoverPolicy::candidateRank))
				.map(SuitRuntimeSnapshot.SuitState::suitId);
	}
	private static boolean isConfirmedMissing(SuitRuntimeSnapshot.SuitState suit) {
		return suit.location() == SuitRuntimeSnapshot.Location.UNKNOWN
				&& suit.currentDimension() == null
				&& suit.currentPosition() == null
				&& suit.phase() == SuitRuntimeSnapshot.Phase.IDLE
				&& suit.allowedActions().contains(
						SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING);
	}

	private static boolean isHealthyCandidate(SuitRuntimeSnapshot.SuitState suit) {
		if (suit.conflicts().stream().anyMatch(UNSAFE_CONFLICTS::contains)) return false;
		return switch (suit.location()) {
			case WORN -> suit.phase() == SuitRuntimeSnapshot.Phase.IDLE;
			case CARRIER -> suit.phase() == SuitRuntimeSnapshot.Phase.GUARDING
					|| suit.phase() == SuitRuntimeSnapshot.Phase.OWNER_DOWN;
			case PLATFORM -> suit.phase() == SuitRuntimeSnapshot.Phase.IDLE
					&& suit.homeHealth() == SuitRuntimeSnapshot.HomeHealth.VALID_OCCUPIED;
			case LOOSE, UNKNOWN -> false;
		};
	}

	private static boolean isRecallableNow(SuitRuntimeSnapshot.SuitState suit) {
		if (suit.conflicts().stream().anyMatch(UNSAFE_CONFLICTS::contains)) return false;
		return switch (suit.location()) {
			case WORN -> suit.phase() == SuitRuntimeSnapshot.Phase.IDLE;
			case CARRIER -> (suit.phase() == SuitRuntimeSnapshot.Phase.GUARDING
					|| suit.phase() == SuitRuntimeSnapshot.Phase.OWNER_DOWN)
					&& (suit.carrierKind() == SuitRuntimeSnapshot.CarrierKind.INDEPENDENT
					|| suit.allowedActions().contains(
							SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR));
			case PLATFORM -> suit.phase() == SuitRuntimeSnapshot.Phase.IDLE
					&& suit.homeHealth() == SuitRuntimeSnapshot.HomeHealth.VALID_OCCUPIED
					&& suit.allowedActions().contains(
							SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR);
			case LOOSE, UNKNOWN -> false;
		};
	}
	private static int candidateRank(SuitRuntimeSnapshot.SuitState suit) {
		return switch (suit.location()) {
			case WORN -> 0;
			case CARRIER -> 1;
			case PLATFORM -> 2;
			case LOOSE, UNKNOWN -> Integer.MAX_VALUE;
		};
	}
}