package dev.codex.flightarmor.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative, bounded block phase for the player core beam. */
final class DestructiveLaserController {
	private static final int MAX_TRAVERSED_VOXELS = 256;
	private static final double MAX_DISTANCE = 84.0D;

	private DestructiveLaserController() {
	}

	static Result resolveCoreBeam(ServerPlayer attacker, Vec3 origin, Vec3 requestedEnd, float charge) {
		if (attacker == null || origin == null || requestedEnd == null
				|| !finite(origin) || !finite(requestedEnd)) {
			return new Result(origin == null ? Vec3.ZERO : origin, Vec3.ZERO,
					ArmorAttackHitKind.MISS, 0, 0.0D);
		}
		ServerLevel level = attacker.serverLevel();
		Vec3 delta = requestedEnd.subtract(origin);
		double requestedDistance = delta.length();
		if (requestedDistance < 1.0E-6D) {
			return new Result(origin, Vec3.ZERO, ArmorAttackHitKind.MISS, 0, 0.0D);
		}
		Vec3 end = requestedDistance > MAX_DISTANCE
				? origin.add(delta.scale(MAX_DISTANCE / requestedDistance)) : requestedEnd;
		DestructiveLaserMath.TerrainProfile profile = DestructiveLaserMath.terrainProfile(charge);

		// Low-charge pulses and disabled terrain griefing remain ordinary wall-blocked hitscan.
		if (!profile.enabled() || !TerrainDestructionGuard.terrainGriefAllowed(level, attacker)) {
			return clipWithoutDestruction(level, attacker, origin, end, 0.0D);
		}
		ListState state = new ListState(profile.budget());

		for (DestructiveLaserMath.VoxelStep step : DestructiveLaserMath.trace(
				toPure(origin), toPure(end), MAX_TRAVERSED_VOXELS)) {
			// Never remove a block containing the muzzle/player. It can only be an invalid placement state.
			if (step.entryFraction() <= 1.0E-9D) continue;
			BlockPos position = new BlockPos(step.x(), step.y(), step.z());
			// 护栏统一委托给 TerrainDestructionGuard：透明格跳过、受保护格直接挡停光束。
			TerrainDestructionGuard.Decision guard =
					TerrainDestructionGuard.evaluate(level, attacker, position);
			if (guard == TerrainDestructionGuard.Decision.SKIP) continue;
			if (guard == TerrainDestructionGuard.Decision.STOP) {
				return blocked(origin, end, step, state);
			}
			BlockState blockState = level.getBlockState(position);

			float hardness = blockState.getDestroySpeed(level, position);
			if (!Float.isFinite(hardness) || hardness < 0.0F
					|| hardness > profile.singleBlockHardnessLimit() + 1.0E-6D) {
				return blocked(origin, end, step, state);
			}
			DestructiveLaserMath.BudgetDecision spend = DestructiveLaserMath.tryConsume(
					state.remainingBudget, hardness, state.destroyedBlocks, profile.maximumDestroyedBlocks());
			if (!spend.allowed()) return blocked(origin, end, step, state);
			if (!TerrainDestructionGuard.commit(level, attacker, position, blockState)) {
				return blocked(origin, end, step, state);
			}
			state.remainingBudget = spend.remainingBudget();
			state.destroyedBlocks++;
		}
		return new Result(end, Vec3.ZERO, ArmorAttackHitKind.MISS,
				state.destroyedBlocks, state.remainingBudget);
	}

	private static Result clipWithoutDestruction(ServerLevel level, ServerPlayer attacker,
			Vec3 origin, Vec3 end, double budget) {
		BlockHitResult hit = level.clip(new ClipContext(
				origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return new Result(end, Vec3.ZERO, ArmorAttackHitKind.MISS, 0, budget);
		}
		return new Result(hit.getLocation(), Vec3.atLowerCornerOf(hit.getDirection().getNormal()),
				ArmorAttackHitKind.BLOCK, 0, budget);
	}

	private static Result blocked(Vec3 origin, Vec3 end,
			DestructiveLaserMath.VoxelStep step, ListState state) {
		Vec3 endpoint = origin.add(end.subtract(origin).scale(step.entryFraction()));
		Vec3 normal = new Vec3(step.normalX(), step.normalY(), step.normalZ());
		if (normal.lengthSqr() > 1.0D) normal = normal.normalize();
		return new Result(endpoint, normal, ArmorAttackHitKind.BLOCK,
				state.destroyedBlocks, state.remainingBudget);
	}

	private static DestructiveLaserMath.Vector toPure(Vec3 vector) {
		return new DestructiveLaserMath.Vector(vector.x, vector.y, vector.z);
	}

	private static boolean finite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}

	record Result(Vec3 endpoint, Vec3 impactNormal, ArmorAttackHitKind hitKind,
			int destroyedBlocks, double remainingBudget) {
	}

	private static final class ListState {
		private int destroyedBlocks;
		private double remainingBudget;

		private ListState(double remainingBudget) {
			this.remainingBudget = remainingBudget;
		}
	}
}