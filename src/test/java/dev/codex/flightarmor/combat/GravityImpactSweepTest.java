package dev.codex.flightarmor.combat;

public final class GravityImpactSweepTest {
	private GravityImpactSweepTest() {
	}

	public static void main(String[] args) {
		thinWallCannotBeSkipped();
		entityIsHitBetweenEndpoints();
		wallNormalOpposesTravel();
		actionCanCommitOnlyOnce();
	}

	private static void thinWallCannotBeSkipped() {
		GravityImpactSweep.Contact hit = GravityImpactSweep.sweep(
				new GravityImpactSweep.Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3),
				new GravityImpactSweep.Vector(8.0, 0.0, 0.0),
				new GravityImpactSweep.Box(4.0, -1.0, -1.0, 4.1, 3.0, 1.0), 0.0);
		require(hit.hit() && hit.fraction() > 0.4 && hit.fraction() < 0.6,
				"a high-speed frame must sweep into a thin wall");
	}

	private static void entityIsHitBetweenEndpoints() {
		GravityImpactSweep.Contact hit = GravityImpactSweep.sweep(
				new GravityImpactSweep.Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3),
				new GravityImpactSweep.Vector(12.0, 0.0, 0.0),
				new GravityImpactSweep.Box(5.0, 0.0, -0.35, 5.7, 1.9, 0.35), 0.08);
		require(hit.hit() && hit.fraction() < 0.5,
				"an entity crossed between endpoints must be detected");
	}

	private static void wallNormalOpposesTravel() {
		GravityImpactSweep.Contact hit = GravityImpactSweep.sweep(
				new GravityImpactSweep.Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3),
				new GravityImpactSweep.Vector(3.0, 0.0, 1.0),
				new GravityImpactSweep.Box(2.0, -1.0, -2.0, 2.2, 3.0, 2.0), 0.0);
		require(hit.hit() && hit.normal().x() < -0.9 && hit.normal().y() == 0.0,
				"wall contact must return an outward braking normal");
	}

	private static void actionCanCommitOnlyOnce() {
		require(GravityImpactSweep.canCommit(7L, 6L, true), "new ready sequence must commit");
		require(!GravityImpactSweep.canCommit(7L, 7L, true), "same sequence must not repeat");
		require(!GravityImpactSweep.canCommit(7L, 6L, false), "cooldown must still gate a new action");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}