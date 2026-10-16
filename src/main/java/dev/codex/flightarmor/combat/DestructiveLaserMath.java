package dev.codex.flightarmor.combat;

import java.util.ArrayList;
import java.util.List;

/** Pure voxel traversal and hardness-budget math for destructive hitscan beams. */
public final class DestructiveLaserMath {
	private static final double EPSILON = 1.0E-9D;
	private static final double MINIMUM_BLOCK_COST = 0.25D;
	private static final double MIN_DESTRUCTIVE_CHARGE = 0.70D;
	private static final double MAX_BUDGET = 90.0D;
	private static final double MAX_SINGLE_BLOCK_HARDNESS = 30.0D;
	private static final int MAX_DESTROYED_BLOCKS = 16;

	private DestructiveLaserMath() {
	}

	/**
	 * Amanatides-Woo traversal over a finite segment. Corner/edge ties advance all
	 * crossed axes together, so a zero-area touch does not visit unrelated side cells.
	 */
	public static List<VoxelStep> trace(Vector start, Vector end, int maximumVisitedVoxels) {
		if (start == null || end == null || !start.finite() || !end.finite()
				|| maximumVisitedVoxels <= 0) return List.of();

		double dx = end.x() - start.x();
		double dy = end.y() - start.y();
		double dz = end.z() - start.z();
		int x = floor(start.x());
		int y = floor(start.y());
		int z = floor(start.z());
		int targetX = floor(end.x());
		int targetY = floor(end.y());
		int targetZ = floor(end.z());
		int stepX = sign(dx);
		int stepY = sign(dy);
		int stepZ = sign(dz);
		double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
		double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
		double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);
		double nextX = firstBoundaryFraction(start.x(), x, dx, stepX);
		double nextY = firstBoundaryFraction(start.y(), y, dy, stepY);
		double nextZ = firstBoundaryFraction(start.z(), z, dz, stepZ);

		List<VoxelStep> result = new ArrayList<>(Math.min(maximumVisitedVoxels, 256));
		result.add(new VoxelStep(x, y, z, 0.0D, 0, 0, 0));
		while ((x != targetX || y != targetY || z != targetZ)
				&& result.size() < maximumVisitedVoxels) {
			double entry = Math.min(nextX, Math.min(nextY, nextZ));
			if (!Double.isFinite(entry) || entry > 1.0D + EPSILON) break;
			int normalX = 0;
			int normalY = 0;
			int normalZ = 0;
			if (nextX <= entry + EPSILON) {
				x += stepX;
				normalX = -stepX;
				nextX += deltaX;
			}
			if (nextY <= entry + EPSILON) {
				y += stepY;
				normalY = -stepY;
				nextY += deltaY;
			}
			if (nextZ <= entry + EPSILON) {
				z += stepZ;
				normalZ = -stepZ;
				nextZ += deltaZ;
			}
			result.add(new VoxelStep(x, y, z, clamp01(entry), normalX, normalY, normalZ));
		}
		return List.copyOf(result);
	}

	public static TerrainProfile terrainProfile(double effectiveCharge) {
		if (!Double.isFinite(effectiveCharge) || effectiveCharge <= MIN_DESTRUCTIVE_CHARGE) {
			return new TerrainProfile(false, 0.0D, 0.0D, 0);
		}
		double strength = clamp01(
				(effectiveCharge - MIN_DESTRUCTIVE_CHARGE) / (1.0D - MIN_DESTRUCTIVE_CHARGE));
		return new TerrainProfile(true, MAX_BUDGET * strength,
				MAX_SINGLE_BLOCK_HARDNESS * strength,
				Math.max(1, (int) Math.floor(MAX_DESTROYED_BLOCKS * strength)));
	}
	public static BudgetDecision tryConsume(double remainingBudget, double hardness,
			int alreadyDestroyed, int maximumDestroyedBlocks) {
		if (!Double.isFinite(remainingBudget) || remainingBudget < 0.0D
				|| !Double.isFinite(hardness) || hardness < 0.0D
				|| alreadyDestroyed < 0 || maximumDestroyedBlocks <= 0
				|| alreadyDestroyed >= maximumDestroyedBlocks) {
			return new BudgetDecision(false, 0.0D, Math.max(0.0D, remainingBudget));
		}
		double cost = Math.max(MINIMUM_BLOCK_COST, hardness);
		if (cost > remainingBudget + EPSILON) {
			return new BudgetDecision(false, cost, remainingBudget);
		}
		return new BudgetDecision(true, cost, Math.max(0.0D, remainingBudget - cost));
	}

	public static double blockCost(double hardness) {
		return !Double.isFinite(hardness) || hardness < 0.0D
				? Double.POSITIVE_INFINITY : Math.max(MINIMUM_BLOCK_COST, hardness);
	}

	private static double firstBoundaryFraction(double start, int voxel, double delta, int step) {
		if (step == 0) return Double.POSITIVE_INFINITY;
		double boundary = step > 0 ? voxel + 1.0D : voxel;
		return (boundary - start) / delta;
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	private static int sign(double value) {
		return value > EPSILON ? 1 : value < -EPSILON ? -1 : 0;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	public record Vector(double x, double y, double z) {
		boolean finite() {
			return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
		}
	}

	public record VoxelStep(int x, int y, int z, double entryFraction,
			int normalX, int normalY, int normalZ) {
	}

	public record TerrainProfile(boolean enabled, double budget,
			double singleBlockHardnessLimit, int maximumDestroyedBlocks) {
	}
	public record BudgetDecision(boolean allowed, double cost, double remainingBudget) {
	}
}
