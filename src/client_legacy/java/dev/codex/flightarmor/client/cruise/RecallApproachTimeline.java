package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.RecallApproachProfile;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端整甲召回姿态时间线。
 *
 * <p>姿态由载体到捕获点的距离驱动；速度则只读取客户端当前 tick 实际显示的
 * 位移。这样网络更新、服务端目标速度和召回阶段本身都不能伪造高速状态，载体
 * 一旦刹停，灯色、声场和音障特效会在同一 tick 结束。</p>
 */
public final class RecallApproachTimeline {
	private static final float DIVE_ROOT_PITCH_DEGREES = 76.0F;
	private static final float BRAKING_ROOT_PITCH_DEGREES = 14.0F;
	private static final float CRUISE_STRUCTURE_PROGRESS = 0.68F;
	private static final float DIVE_STRUCTURE_PROGRESS = 0.26F;
	private static final float BRAKING_STRUCTURE_PROGRESS = 0.50F;

	private RecallApproachTimeline() {
	}

	public static Sample sample(LivingEntity carrier, float partialTick) {
		float clampedPartialTick = Mth.clamp(partialTick, 0.0F, 1.0F);
		Player owner = ArmorCruiseClient.ownerOfCarrier(carrier);
		double distance = owner == null
				? RecallApproachProfile.BRAKING_START_DISTANCE
				: interpolatedDistance(carrier, owner, clampedPartialTick);

		float braking = (float) RecallApproachProfile.brakingProgress(distance);
		float hover = (float) RecallApproachProfile.hoverProgress(distance);
		float rawDive = (float) RecallApproachProfile.diveProgress(distance);
		ArmorCruisePhase phase = ArmorCruiseClient.phaseOfCarrier(carrier);
		float rawEntry = phase == ArmorCruisePhase.RECALLING
				? ArmorCruiseClient.recallingEntryProgress(carrier, clampedPartialTick)
				: 1.0F;
		float entry = 1.0F - (float) Math.pow(1.0F - rawEntry, 2.4D);

		// 72 -> 12 格连续从俯冲抬头，进入捕获区后完全恢复悬停姿态。
		float rootPitch = Mth.lerp(braking, DIVE_ROOT_PITCH_DEGREES,
				BRAKING_ROOT_PITCH_DEGREES);
		rootPitch = Mth.lerp(hover, rootPitch, 0.0F) * entry;

		float targetStructure = Mth.lerp(braking, DIVE_STRUCTURE_PROGRESS,
				BRAKING_STRUCTURE_PROGRESS);
		targetStructure = Mth.lerp(hover, targetStructure, CRUISE_STRUCTURE_PROGRESS);
		float structure = Mth.lerp(entry, CRUISE_STRUCTURE_PROGRESS, targetStructure);

		double observedBlocksPerTick = observedBlocksPerTick(carrier);
		float speed = Mth.clamp((float) (observedBlocksPerTick
				/ RecallApproachProfile.MAX_SPEED), 0.0F, 1.0F);

		return new Sample((float) distance, braking, hover, rawDive * entry,
				entry, rootPitch, structure, speed, (float) observedBlocksPerTick);
	}

	private static double interpolatedDistance(LivingEntity carrier, Player owner, float partialTick) {
		Vec3 carrierPosition = carrier.getPosition(partialTick);
		Vec3 ownerPosition = owner.getPosition(partialTick);
		double yawRadians = Math.toRadians(owner.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
		// 与服务端 dockingCaptureTarget 保持一致，避免客户端提前或延迟抬头。
		Vec3 capturePosition = ownerPosition
				.subtract(forward.scale(RecallApproachProfile.CAPTURE_REAR_OFFSET))
				.add(0.0D, RecallApproachProfile.CAPTURE_HEIGHT_OFFSET, 0.0D);
		return carrierPosition.distanceTo(capturePosition);
	}

	private static double observedBlocksPerTick(LivingEntity carrier) {
		// 只采样本客户端 tick 真正画出来的位移。不能使用目标速度或阶段初速度，
		// 否则载体已刹停时仍会残留音障、灯色和高速声场。
		double speed = carrier.getPosition(1.0F).distanceTo(carrier.getPosition(0.0F));
		return Double.isFinite(speed) ? speed : 0.0D;
	}

	/**
	 * @param brakingProgress 72 格为 0，12 格为 1
	 * @param hoverProgress 12 格为 0，进入捕获点容差时为 1
	 * @param diveProgress 已包含召回切入缓动的俯冲结构权重
	 * @param observedBlocksPerTick 当前客户端 tick 的真实可见位移
	 */
	public record Sample(float distance, float brakingProgress, float hoverProgress,
			float diveProgress, float entryProgress, float rootPitchDegrees,
			float structureProgress, float speedProgress, float observedBlocksPerTick) {
	}
}
