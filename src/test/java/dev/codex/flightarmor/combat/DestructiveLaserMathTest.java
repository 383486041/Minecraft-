package dev.codex.flightarmor.combat;

import java.util.List;

/** Offline regression gate for finite DDA ordering and hardness energy budgets. */
public final class DestructiveLaserMathTest {
	private DestructiveLaserMathTest() {
	}

	public static void main(String[] args) {
		positiveAxisTraversalIsOrdered();
		negativeCoordinatesRemainOrdered();
		cornerTieDoesNotVisitSideCells();
		hardnessRaisesCostAndBudgetStopsCleanly();
		blockLimitStopsConsumption();
	}

	private static void lowChargeHasNoTerrainBudget() {
		DestructiveLaserMath.TerrainProfile pulse = DestructiveLaserMath.terrainProfile(0.69D);
		DestructiveLaserMath.TerrainProfile threshold = DestructiveLaserMath.terrainProfile(0.70D);
		require(!pulse.enabled() && pulse.budget() == 0.0D && pulse.maximumDestroyedBlocks() == 0,
				"low-charge core pulses must never alter terrain");
		require(!threshold.enabled() && threshold.budget() == 0.0D,
				"the destructive threshold must start from a zero budget");
		DestructiveLaserMath.TerrainProfile full = DestructiveLaserMath.terrainProfile(1.0D);
		require(full.enabled() && close(full.budget(), 90.0D),
				"full charge must receive the finite maximum budget");
		require(close(full.singleBlockHardnessLimit(), 30.0D)
				&& full.maximumDestroyedBlocks() == 16,
				"full charge hard caps changed unexpectedly");
	}
	private static void positiveAxisTraversalIsOrdered() {
		List<DestructiveLaserMath.VoxelStep> steps = DestructiveLaserMath.trace(
				new DestructiveLaserMath.Vector(0.25D, 2.5D, -1.5D),
				new DestructiveLaserMath.Vector(3.75D, 2.5D, -1.5D), 32);
		require(steps.size() == 4, "unexpected +X voxel count: " + steps);
		for (int index = 0; index < steps.size(); index++) {
			require(steps.get(index).x() == index, "+X traversal order changed: " + steps);
		}
		require(close(steps.get(1).entryFraction(), 0.75D / 3.5D), "wrong first boundary fraction");
	}

	private static void negativeCoordinatesRemainOrdered() {
		List<DestructiveLaserMath.VoxelStep> steps = DestructiveLaserMath.trace(
				new DestructiveLaserMath.Vector(0.1D, 0.1D, 0.1D),
				new DestructiveLaserMath.Vector(-2.1D, 0.1D, 0.1D), 32);
		require(steps.size() == 4, "unexpected negative voxel count: " + steps);
		require(steps.get(1).x() == -1 && steps.get(2).x() == -2 && steps.get(3).x() == -3,
				"negative floor traversal is incorrect: " + steps);
		require(steps.get(1).normalX() == 1, "negative entry normal must face +X");
	}

	private static void cornerTieDoesNotVisitSideCells() {
		List<DestructiveLaserMath.VoxelStep> steps = DestructiveLaserMath.trace(
				new DestructiveLaserMath.Vector(0.5D, 0.5D, 0.5D),
				new DestructiveLaserMath.Vector(2.5D, 2.5D, 2.5D), 32);
		require(steps.size() == 3, "corner ties should advance diagonally once: " + steps);
		require(steps.get(1).x() == 1 && steps.get(1).y() == 1 && steps.get(1).z() == 1,
				"corner traversal entered an unrelated side cell: " + steps);
	}

	private static void hardnessRaisesCostAndBudgetStopsCleanly() {
		double soft = DestructiveLaserMath.blockCost(0.5D);
		double hard = DestructiveLaserMath.blockCost(4.0D);
		require(hard > soft, "harder blocks must consume more laser budget");
		DestructiveLaserMath.BudgetDecision first =
				DestructiveLaserMath.tryConsume(hard, 4.0D, 0, 20);
		require(first.allowed() && close(first.remainingBudget(), 0.0D),
				"an exact budget must permit one block");
		DestructiveLaserMath.BudgetDecision second =
				DestructiveLaserMath.tryConsume(first.remainingBudget(), 0.0D, 1, 20);
		require(!second.allowed(), "the beam must stop before overspending its budget");
		require(Double.isInfinite(DestructiveLaserMath.blockCost(-1.0D)),
				"unbreakable hardness must never receive a finite cost");
	}

	private static void blockLimitStopsConsumption() {
		DestructiveLaserMath.BudgetDecision decision =
				DestructiveLaserMath.tryConsume(10_000.0D, 0.0D, 20, 20);
		require(!decision.allowed(), "maximum destroyed block count must be a hard gate");
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) <= 1.0E-9D;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
