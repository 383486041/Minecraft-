package dev.codex.flightarmor.cruise;

/**
 * Pure recovery admission rules. The policy never manufactures a carrier: a runtime state can only
 * be restored from the exact indexed entity, or a legacy tagged entity when no index exists yet.
 */
final class EscortRecoveryPolicy {
	private EscortRecoveryPolicy() {
	}

	static Decision decide(Evidence evidence) {
		if (evidence == null || !evidence.candidatePresent()) return Decision.WAIT_FOR_INDEXED_CARRIER;
		if (!evidence.ownerTagMatches()) return Decision.REJECT_OWNER_MISMATCH;
		if (!evidence.suitTagMatches()) return Decision.REJECT_SUIT_MISMATCH;
		if (!evidence.fullArmorSet()) return Decision.REJECT_INCOMPLETE_CARRIER;
		if (evidence.indexPresent() && !evidence.carrierUuidMatches()) {
			return Decision.REJECT_INDEXED_CARRIER_MISMATCH;
		}
		if (evidence.activeStatePresent() && !evidence.activeCarrierMatches()) {
			return Decision.REJECT_DUPLICATE_ACTIVE_CARRIER;
		}
		if (evidence.activeStatePresent()) return Decision.REUSE_ACTIVE_STATE;
		return evidence.indexPresent()
				? Decision.RESTORE_INDEXED_PASSIVE : Decision.ADOPT_LEGACY_PASSIVE;
	}

	static SafePhase safePhase(ArmorEscortSavedData.StoredPhase storedPhase, boolean completeHome) {
		return storedPhase == ArmorEscortSavedData.StoredPhase.RETURNING && completeHome
				? SafePhase.RETURNING : SafePhase.ESCORTING;
	}

	enum Decision {
		RESTORE_INDEXED_PASSIVE,
		ADOPT_LEGACY_PASSIVE,
		REUSE_ACTIVE_STATE,
		WAIT_FOR_INDEXED_CARRIER,
		REJECT_OWNER_MISMATCH,
		REJECT_SUIT_MISMATCH,
		REJECT_INCOMPLETE_CARRIER,
		REJECT_INDEXED_CARRIER_MISMATCH,
		REJECT_DUPLICATE_ACTIVE_CARRIER
	}

	enum SafePhase {
		ESCORTING,
		RETURNING
	}

	record Evidence(
			boolean indexPresent,
			boolean candidatePresent,
			boolean ownerTagMatches,
			boolean suitTagMatches,
			boolean fullArmorSet,
			boolean carrierUuidMatches,
			boolean activeStatePresent,
			boolean activeCarrierMatches
	) {
	}
}
