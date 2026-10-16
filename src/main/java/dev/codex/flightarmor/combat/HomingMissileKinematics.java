package dev.codex.flightarmor.combat;

/**
 * Pure homing-missile math shared by the server entity and offline regression tests.
 * Units are blocks and ticks; no Minecraft runtime classes are referenced here.
 */
public final class HomingMissileKinematics {
	private static final double EPSILON = 1.0E-9D;

	private HomingMissileKinematics() {
	}

	/** Accelerates and turns toward the aim vector without exceeding the per-tick turn limit. */
	public static Vector steer(Vector velocity, Vector aim, double acceleration,
			double maximumSpeed, double maximumTurnRadians) {
		Vector desired = aim.normalized();
		double oldSpeed = velocity.length();
		if (desired.lengthSquared() < EPSILON) return velocity;
		double nextSpeed = Math.min(Math.max(0.0D, maximumSpeed),
				Math.max(0.0D, oldSpeed) + Math.max(0.0D, acceleration));
		if (nextSpeed < EPSILON) return Vector.ZERO;

		Vector current = oldSpeed < EPSILON ? desired : velocity.scale(1.0D / oldSpeed);
		double dot = clamp(current.dot(desired), -1.0D, 1.0D);
		double angle = Math.acos(dot);
		if (angle <= maximumTurnRadians || angle < EPSILON) return desired.scale(nextSpeed);

		double fraction = clamp(maximumTurnRadians / angle, 0.0D, 1.0D);
		double sinAngle = Math.sin(angle);
		Vector direction;
		if (Math.abs(sinAngle) < 1.0E-5D) {
			// Opposite directions need an explicit, deterministic rotation plane.
			Vector reference = Math.abs(current.y()) < 0.9D ? new Vector(0.0D, 1.0D, 0.0D)
					: new Vector(1.0D, 0.0D, 0.0D);
			Vector axis = current.cross(reference).normalized();
			double applied = Math.min(angle, Math.max(0.0D, maximumTurnRadians));
			direction = current.scale(Math.cos(applied))
					.add(axis.cross(current).scale(Math.sin(applied))).normalized();
		} else {
			double a = Math.sin((1.0D - fraction) * angle) / sinAngle;
			double b = Math.sin(fraction * angle) / sinAngle;
			direction = current.scale(a).add(desired.scale(b)).normalized();
		}
		return direction.scale(nextSpeed);
	}

	/**
	 * Returns the first segment fraction in [0,1] entering an axis-aligned box, or
	 * {@link Double#POSITIVE_INFINITY} when the swept point misses it.
	 */
	public static double segmentAabbEntry(Vector start, Vector end, Box box) {
		double[] interval = {0.0D, 1.0D};
		if (!clipAxis(start.x(), end.x() - start.x(), box.minX(), box.maxX(), interval)
				|| !clipAxis(start.y(), end.y() - start.y(), box.minY(), box.maxY(), interval)
				|| !clipAxis(start.z(), end.z() - start.z(), box.minZ(), box.maxZ(), interval)) {
			return Double.POSITIVE_INFINITY;
		}
		return interval[0];
	}

	private static boolean clipAxis(double start, double delta, double minimum, double maximum,
			double[] interval) {
		if (Math.abs(delta) < EPSILON) return start >= minimum && start <= maximum;
		double near = (minimum - start) / delta;
		double far = (maximum - start) / delta;
		if (near > far) {
			double swap = near;
			near = far;
			far = swap;
		}
		interval[0] = Math.max(interval[0], near);
		interval[1] = Math.min(interval[1], far);
		return interval[0] <= interval[1];
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record Vector(double x, double y, double z) {
		public static final Vector ZERO = new Vector(0.0D, 0.0D, 0.0D);

		public Vector add(Vector other) {
			return new Vector(x + other.x, y + other.y, z + other.z);
		}

		public Vector scale(double factor) {
			return new Vector(x * factor, y * factor, z * factor);
		}

		public double dot(Vector other) {
			return x * other.x + y * other.y + z * other.z;
		}

		public Vector cross(Vector other) {
			return new Vector(y * other.z - z * other.y,
					z * other.x - x * other.z, x * other.y - y * other.x);
		}

		public double lengthSquared() {
			return dot(this);
		}

		public double length() {
			return Math.sqrt(lengthSquared());
		}

		public Vector normalized() {
			double length = length();
			return length < EPSILON ? ZERO : scale(1.0D / length);
		}
	}

	public record Box(double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ) {
	}
}
