package dev.codex.flightarmor.cruise;

/**
 * Pure admission and approach policy for moving one carrier from independent cruise to a
 * platform-return controller. It deliberately carries no ItemStack data: the physical carrier
 * remains the sole item owner for the entire handoff.
 */
public final class IndependentCruiseReturnHandoff {
	private static final double HIGH_SPEED_DISTANCE = 24.0D;
	private static final double OVERHEAD_BRAKE_DISTANCE = 5.0D;

	private IndependentCruiseReturnHandoff() {
	}

	public static Admission decide(Evidence evidence) {
		if (evidence == null) return Admission.CARRIER_UNAVAILABLE;
		if (evidence.returningSameTarget()) return Admission.ALREADY_RETURNING;
		if (!evidence.ownerMatches()) return Admission.OWNER_MISMATCH;
		if (!evidence.suitMatches()) return Admission.SUIT_MISMATCH;
		if (!evidence.carrierUsable()) return Admission.CARRIER_UNAVAILABLE;
		if (!evidence.phaseStable()) return Admission.PHASE_BUSY;
		if (evidence.escortConflict()) return Admission.ESCORT_CONFLICT;
		if (!evidence.homeDimensionAvailable()) return Admission.HOME_DIMENSION_UNAVAILABLE;
		if (!evidence.platformInstanceMatches()) return Admission.PLATFORM_REPLACED;
		if (!evidence.platformEmpty()) return Admission.PLATFORM_OCCUPIED;
		return Admission.START;
	}

	/**
	 * Three-stage flight profile: long range closes at high speed, the carrier then arrests above
	 * the pad, and only the final short segment descends onto the display root.
	 */
	public static Approach approach(double distanceToDock) {
		double distance = Double.isFinite(distanceToDock)
				? Math.max(0.0D, distanceToDock) : Double.MAX_VALUE;
		if (distance > HIGH_SPEED_DISTANCE) {
			return new Approach(ApproachStage.HIGH_SPEED, 4.2D, 8.0D);
		}
		if (distance > OVERHEAD_BRAKE_DISTANCE) {
			double normalized = (distance - OVERHEAD_BRAKE_DISTANCE)
					/ (HIGH_SPEED_DISTANCE - OVERHEAD_BRAKE_DISTANCE);
			double speed = 1.25D + normalized * 2.55D;
			return new Approach(ApproachStage.OVERHEAD_BRAKING, 2.6D, speed);
		}
		double speed = 0.24D + Math.min(0.58D, distance * 0.12D);
		return new Approach(ApproachStage.FINAL_DESCENT, 0.0D, speed);
	}

	public enum Admission {
		START,
		ALREADY_RETURNING,
		OWNER_MISMATCH,
		SUIT_MISMATCH,
		CARRIER_UNAVAILABLE,
		PHASE_BUSY,
		ESCORT_CONFLICT,
		HOME_DIMENSION_UNAVAILABLE,
		PLATFORM_REPLACED,
		PLATFORM_OCCUPIED;

		public boolean accepted() {
			return this == START || this == ALREADY_RETURNING;
		}
	}

	public enum ApproachStage {
		HIGH_SPEED,
		OVERHEAD_BRAKING,
		FINAL_DESCENT
	}

	public record Evidence(
			boolean returningSameTarget,
			boolean ownerMatches,
			boolean suitMatches,
			boolean carrierUsable,
			boolean phaseStable,
			boolean escortConflict,
			boolean homeDimensionAvailable,
			boolean platformInstanceMatches,
			boolean platformEmpty
	) {
		public static Evidence valid() {
			return new Evidence(false, true, true, true, true,
					false, true, true, true);
		}
	}

	public record Approach(ApproachStage stage, double targetHeightAboveDock, double maxSpeed) {
		public Approach {
			if (stage == null || !Double.isFinite(targetHeightAboveDock)
					|| targetHeightAboveDock < 0.0D || !Double.isFinite(maxSpeed)
					|| maxSpeed <= 0.0D) {
				throw new IllegalArgumentException("Invalid platform return approach");
			}
		}
	}
}
