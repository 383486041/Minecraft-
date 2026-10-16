package dev.codex.flightarmor.combat;

/**
 * Pure movement policy for Sentinel wingmen.
 *
 * <p>The controller behaves like a critically damped formation spring at short range, but gains
 * enough catch-up authority when the wearer accelerates. Keeping this class free of Minecraft
 * types makes the hard speed and overshoot guarantees testable without launching the game.</p>
 */
public final class SentinelWingmanKinematics {
	/** Maximum correction relative to the wearer; absolute speed follows the wearer without a cap. */
	public static final double MAXIMUM_RELATIVE_CORRECTION_SPEED = 2.35D;
	private static final double POSITION_GAIN = 0.185D;
	private static final double VELOCITY_RETENTION = 0.58D;

	private SentinelWingmanKinematics() {
	}

	public static Vector steer(Vector currentVelocity, Vector anchorError, Vector ownerVelocity) {
		Vector current = currentVelocity == null ? Vector.ZERO : currentVelocity;
		Vector error = anchorError == null ? Vector.ZERO : anchorError;
		Vector owner = ownerVelocity == null ? Vector.ZERO : ownerVelocity;
		// Keep the owner's full translational velocity as feed-forward. Only the relative
		// formation correction is capped, so a 36-42 block/tick wearer never outruns a drone
		// whose absolute velocity was incorrectly limited to ordinary entity speeds.
		Vector correction = current.add(owner.scale(-1.0D))
				.scale(VELOCITY_RETENTION)
				.add(error.scale(POSITION_GAIN));
		double correctionLength = correction.length();
		if (correctionLength > MAXIMUM_RELATIVE_CORRECTION_SPEED) {
			correction = correction.scale(
					MAXIMUM_RELATIVE_CORRECTION_SPEED / correctionLength);
		}
		return owner.add(correction);
	}

	public record Vector(double x, double y, double z) {
		public static final Vector ZERO = new Vector(0.0D, 0.0D, 0.0D);

		public Vector add(Vector other) {
			return new Vector(x + other.x, y + other.y, z + other.z);
		}

		public Vector scale(double scalar) {
			return new Vector(x * scalar, y * scalar, z * scalar);
		}

		public double length() {
			return Math.sqrt(x * x + y * y + z * z);
		}
	}
}
