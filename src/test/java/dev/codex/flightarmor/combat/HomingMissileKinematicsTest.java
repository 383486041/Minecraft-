package dev.codex.flightarmor.combat;

/** Offline regression gate for bounded homing and swept missile contact math. */
public final class HomingMissileKinematicsTest {
	private HomingMissileKinematicsTest() {
	}

	public static void main(String[] args) {
		turningIsBoundedAndAccelerating();
		speedStopsAtMaximum();
		straightAimDoesNotWobble();
		sweptSegmentFindsFirstContact();
		offsetSegmentMisses();
		startingInsideContactsImmediately();
	}

	private static void turningIsBoundedAndAccelerating() {
		double turn = Math.toRadians(15.0D);
		HomingMissileKinematics.Vector previous = new HomingMissileKinematics.Vector(0.50D, 0.0D, 0.0D);
		HomingMissileKinematics.Vector next = HomingMissileKinematics.steer(previous,
				new HomingMissileKinematics.Vector(0.0D, 0.0D, 10.0D), 0.10D, 1.70D, turn);
		require(close(next.length(), 0.60D, 1.0E-9D), "missile acceleration must be deterministic");
		double angle = Math.acos(previous.normalized().dot(next.normalized()));
		require(angle > 0.0D && angle <= turn + 1.0E-9D,
				"guidance exceeded the turn cap: " + Math.toDegrees(angle));
	}

	private static void speedStopsAtMaximum() {
		HomingMissileKinematics.Vector velocity = new HomingMissileKinematics.Vector(0.3D, 0.0D, 0.0D);
		HomingMissileKinematics.Vector aim = new HomingMissileKinematics.Vector(10.0D, 3.0D, 2.0D);
		for (int tick = 0; tick < 80; tick++) {
			velocity = HomingMissileKinematics.steer(velocity, aim, 0.095D, 1.72D, Math.toRadians(15.0D));
			require(velocity.length() <= 1.72D + 1.0E-9D, "speed exceeded the hard maximum");
		}
		require(close(velocity.length(), 1.72D, 1.0E-9D), "speed never reached the hard maximum");
	}

	private static void straightAimDoesNotWobble() {
		HomingMissileKinematics.Vector velocity = new HomingMissileKinematics.Vector(0.0D, 0.0D, 0.52D);
		HomingMissileKinematics.Vector next = HomingMissileKinematics.steer(velocity,
				new HomingMissileKinematics.Vector(0.0D, 0.0D, 8.0D), 0.095D, 1.72D,
				Math.toRadians(15.0D));
		require(close(next.x(), 0.0D, 1.0E-12D) && close(next.y(), 0.0D, 1.0E-12D)
				&& next.z() > velocity.z(), "straight guidance introduced lateral wobble");
	}

	private static void sweptSegmentFindsFirstContact() {
		double target = HomingMissileKinematics.segmentAabbEntry(
				new HomingMissileKinematics.Vector(-5.0D, 0.0D, 0.0D),
				new HomingMissileKinematics.Vector(5.0D, 0.0D, 0.0D),
				new HomingMissileKinematics.Box(-1.0D, -1.0D, -1.0D, 1.0D, 1.0D, 1.0D));
		double wall = HomingMissileKinematics.segmentAabbEntry(
				new HomingMissileKinematics.Vector(-5.0D, 0.0D, 0.0D),
				new HomingMissileKinematics.Vector(5.0D, 0.0D, 0.0D),
				new HomingMissileKinematics.Box(2.0D, -2.0D, -2.0D, 2.2D, 2.0D, 2.0D));
		require(close(target, 0.40D, 1.0E-9D), "unexpected swept target fraction: " + target);
		require(target < wall, "nearest collision ordering must select the target before the wall");
	}

	private static void offsetSegmentMisses() {
		double fraction = HomingMissileKinematics.segmentAabbEntry(
				new HomingMissileKinematics.Vector(-5.0D, 3.0D, 0.0D),
				new HomingMissileKinematics.Vector(5.0D, 3.0D, 0.0D),
				new HomingMissileKinematics.Box(-1.0D, -1.0D, -1.0D, 1.0D, 1.0D, 1.0D));
		require(Double.isInfinite(fraction), "offset sweep must miss");
	}

	private static void startingInsideContactsImmediately() {
		double fraction = HomingMissileKinematics.segmentAabbEntry(
				HomingMissileKinematics.Vector.ZERO,
				new HomingMissileKinematics.Vector(4.0D, 0.0D, 0.0D),
				new HomingMissileKinematics.Box(-1.0D, -1.0D, -1.0D, 1.0D, 1.0D, 1.0D));
		require(fraction == 0.0D, "an overlap at segment start must resolve at t=0");
	}

	private static boolean close(double left, double right, double tolerance) {
		return Math.abs(left - right) <= tolerance;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
