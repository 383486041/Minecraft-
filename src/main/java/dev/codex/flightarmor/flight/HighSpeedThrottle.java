package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.variant.VariantFlightProfile;
import net.minecraft.util.Mth;

/**
 * Stateless W/S/Ctrl throttle integrator shared by every high-speed flight profile.
 *
 * <p>Keeping this calculation separate from entity bookkeeping makes it impossible for Ctrl to
 * become a preset-speed switch and gives the Sentinel overdrive ramp a directly testable clock.</p>
 */
public final class HighSpeedThrottle {
	private HighSpeedThrottle() {
	}

	public static double advance(double commandedSpeed,
			boolean movingForward, boolean movingBackward, boolean boosting,
			VariantFlightProfile.Mode tuning) {
		if (tuning == null) return 0.0D;
		double command = Double.isFinite(commandedSpeed)
				? Mth.clamp(commandedSpeed, 0.0D, tuning.targetSpeed()) : 0.0D;
		boolean throttleUp = movingForward && !movingBackward;
		boolean throttleDown = movingBackward && !movingForward;
		double nominalAcceleration = tuning.targetSpeed()
				/ (tuning.accelerationSeconds() * FlightPhysics.TICKS_PER_SECOND);
		if (throttleUp) {
			command += nominalAcceleration * (boosting ? 1.70D : 0.75D);
		} else if (throttleDown) {
			double brakingStep = tuning.targetSpeed()
					/ (tuning.brakingSeconds() * FlightPhysics.TICKS_PER_SECOND);
			command -= brakingStep;
		} else if (boosting) {
			command += nominalAcceleration * 1.10D;
		}
		return Mth.clamp(command, 0.0D, tuning.targetSpeed());
	}
}
