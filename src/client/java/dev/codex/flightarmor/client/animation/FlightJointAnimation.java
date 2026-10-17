package dev.codex.flightarmor.client.animation;

import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** 三轴躯干扭腰与连续肘膝弯曲的姿势入口（26.3 起不再依赖 playerAnimator/bendy-lib）。 */
public final class FlightJointAnimation {
	private FlightJointAnimation() {
	}

	/** 人物皮肤和 OBJ 装甲共同使用的唯一姿势入口。 */
	public static FlightPoseFrame poseFor(Player player, float partialTick) {
		if (ArmorAssemblyClient.isAnimating(player)) {
			return assemblyPose(player, partialTick);
		}
		return AttackPoseController.apply(player,
				FlightPoseController.sample(player, partialTick), partialTick);
	}

	/**
	 * 分件到达时采用三段式机械着甲序列：
	 * (1) 迎候展开（Open Reception）：四肢错峰张开留出外甲扣合净空；
	 * (2) 磁吸撞击顿挫（Magnetic Lock Jolt）：甲片扣合瞬间产生高频微幅反冲；
	 * (3) 液压锁死归位（Hydraulic Seal）：胸腔微挺、头盔下压完成气密闭锁。
	 */
	private static FlightPoseFrame assemblyPose(Player player, float partialTick) {
		float progress = Mth.clamp(ArmorAssemblyClient.assemblyProgress(player, partialTick), 0.0F, 1.0F);
		boolean feet = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.FEET);
		boolean legs = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.LEGS);
		boolean chest = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.CHEST);
		boolean head = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.HEAD);
		float leftArmStage = chest ? lockCurve(progress, 0.10F, 0.42F, 0.72F, 0.90F) : 0.0F;
		float rightArmStage = chest ? lockCurve(progress, 0.18F, 0.50F, 0.78F, 0.95F) : 0.0F;
		float leftLegStage = (feet || legs) ? lockCurve(progress, 0.06F, 0.36F, 0.66F, 0.86F) : 0.0F;
		float rightLegStage = (feet || legs) ? lockCurve(progress, 0.15F, 0.46F, 0.74F, 0.92F) : 0.0F;
		float helmetStage = head ? lockCurve(progress, 0.12F, 0.50F, 0.80F, 0.96F) : 0.0F;
		float torsoSettle = chest ? lockCurve(progress, 0.0F, 0.38F, 0.76F, 1.0F) : 0.0F;
		float lockJolt = Mth.sin(progress * Mth.PI * 5.0F) * smoothstep(0.52F, 0.82F, progress)
				* (1.0F - smoothstep(0.82F, 0.98F, progress));

		ArmorVariant variant = assemblyVariant(player);
		FlightPoseFrame local = new FlightPoseFrame(
				degrees(-4.0F * torsoSettle + 2.2F * lockJolt),
				degrees((leftArmStage - rightArmStage) * 9.0F),
				degrees((leftArmStage - rightArmStage) * 2.5F),
				degrees(-8.0F * helmetStage + 3.0F * lockJolt),
				degrees(-(leftArmStage - rightArmStage) * 7.0F),
				degrees(1.5F * lockJolt),
				degrees(-10.0F * torsoSettle - 28.0F * leftArmStage + 4.0F * lockJolt),
				degrees(-6.0F * leftArmStage),
				degrees(-22.0F * leftArmStage - 3.0F * lockJolt),
				degrees(-8.0F * torsoSettle - 25.0F * rightArmStage - 4.0F * lockJolt),
				degrees(6.0F * rightArmStage),
				degrees(20.0F * rightArmStage + 3.0F * lockJolt),
				degrees(-16.0F * leftLegStage), degrees(-4.0F * leftLegStage), degrees(-5.5F * leftLegStage),
				degrees(-10.0F * rightLegStage), degrees(4.0F * rightLegStage), degrees(4.5F * rightLegStage),
				degrees(12.0F * torsoSettle + 30.0F * leftArmStage),
				degrees(10.0F * torsoSettle + 26.0F * rightArmStage),
				degrees(24.0F * leftLegStage), degrees(16.0F * rightLegStage));
		local = assemblyStyle(local, variant, progress);
		FlightPoseFrame base = FlightPoseController.sample(player, partialTick);
		boolean lowerBody = feet || legs;
		return new FlightPoseFrame(
				chest ? local.bodyPitch() : base.bodyPitch(),
				chest ? local.bodyYaw() : base.bodyYaw(),
				chest ? local.bodyRoll() : base.bodyRoll(),
				head ? local.headPitchOffset() : base.headPitchOffset(),
				head ? local.headYawOffset() : base.headYawOffset(),
				head ? local.headRollOffset() : base.headRollOffset(),
				chest ? local.leftArmPitch() : base.leftArmPitch(),
				chest ? local.leftArmYaw() : base.leftArmYaw(),
				chest ? local.leftArmRoll() : base.leftArmRoll(),
				chest ? local.rightArmPitch() : base.rightArmPitch(),
				chest ? local.rightArmYaw() : base.rightArmYaw(),
				chest ? local.rightArmRoll() : base.rightArmRoll(),
				lowerBody ? local.leftLegPitch() : base.leftLegPitch(),
				lowerBody ? local.leftLegYaw() : base.leftLegYaw(),
				lowerBody ? local.leftLegRoll() : base.leftLegRoll(),
				lowerBody ? local.rightLegPitch() : base.rightLegPitch(),
				lowerBody ? local.rightLegYaw() : base.rightLegYaw(),
				lowerBody ? local.rightLegRoll() : base.rightLegRoll(),
				chest ? local.leftElbowBend() : base.leftElbowBend(),
				chest ? local.rightElbowBend() : base.rightElbowBend(),
				lowerBody ? local.leftKneeBend() : base.leftKneeBend(),
				lowerBody ? local.rightKneeBend() : base.rightKneeBend());
	}

	private static ArmorVariant assemblyVariant(Player player) {
		for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
				net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.FEET,
				net.minecraft.world.entity.EquipmentSlot.LEGS,
				net.minecraft.world.entity.EquipmentSlot.HEAD }) {
			if (ModItems.isExactFlightArmor(player.getItemBySlot(slot), slot)) {
				return ArmorVariantData.get(player.getItemBySlot(slot));
			}
		}
		return ArmorVariant.VANGUARD;
	}

	private static FlightPoseFrame assemblyStyle(FlightPoseFrame pose, ArmorVariant variant,
			float progress) {
		float lock = smoothstep(0.72F, 1.0F, progress);
		return switch (variant) {
			case VANGUARD -> pose;
			case SWIFT -> new FlightPoseFrame(
					pose.bodyPitch() + degrees(-5.0F * lock), pose.bodyYaw() * 1.15F, pose.bodyRoll() * 0.62F,
					pose.headPitchOffset() + degrees(2.0F * lock), pose.headYawOffset() * 1.15F, pose.headRollOffset() * 0.65F,
					pose.leftArmPitch() - degrees(8.0F), pose.leftArmYaw() * 0.72F, pose.leftArmRoll() * 0.74F,
					pose.rightArmPitch() - degrees(3.0F), pose.rightArmYaw() * 0.72F, pose.rightArmRoll() * 0.74F,
					pose.leftLegPitch() + degrees(5.0F), pose.leftLegYaw() * 0.65F, pose.leftLegRoll() * 0.65F,
					pose.rightLegPitch() - degrees(2.0F), pose.rightLegYaw() * 0.65F, pose.rightLegRoll() * 0.65F,
					pose.leftElbowBend() * 0.72F, pose.rightElbowBend() * 0.68F,
					pose.leftKneeBend() * 0.70F, pose.rightKneeBend() * 0.66F);
			case SENTINEL -> new FlightPoseFrame(
					pose.bodyPitch() + degrees(4.0F * lock), pose.bodyYaw() * 0.75F, pose.bodyRoll() * 0.42F,
					pose.headPitchOffset() - degrees(2.0F * lock), pose.headYawOffset() * 0.75F, pose.headRollOffset() * 0.45F,
					pose.leftArmPitch() - degrees(7.0F), pose.leftArmYaw() * 1.25F, pose.leftArmRoll() * 1.28F,
					pose.rightArmPitch() - degrees(6.0F), pose.rightArmYaw() * 1.25F, pose.rightArmRoll() * 1.28F,
					pose.leftLegPitch() - degrees(5.0F), pose.leftLegYaw() * 1.30F, pose.leftLegRoll() * 1.25F,
					pose.rightLegPitch() - degrees(4.0F), pose.rightLegYaw() * 1.30F, pose.rightLegRoll() * 1.25F,
					pose.leftElbowBend() + degrees(14.0F), pose.rightElbowBend() + degrees(12.0F),
					pose.leftKneeBend() + degrees(16.0F), pose.rightKneeBend() + degrees(14.0F));
		};
	}

	/**
	 * 带磁吸扣合顿挫（Overshoot & Lock）的三段式包络曲线。
	 */
	private static float lockCurve(float value, float start, float peak, float lockStart, float end) {
		if (value <= start || value >= end) return 0.0F;
		if (value < peak) {
			return smoothstep(start, peak, value);
		}
		if (value < lockStart) {
			float t = smoothstep(peak, lockStart, value);
			return 1.0F + 0.14F * Mth.sin(t * Mth.PI);
		}
		return 1.0F - smoothstep(lockStart, end, value);
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static float degrees(float value) {
		return value * Mth.DEG_TO_RAD;
	}
}
