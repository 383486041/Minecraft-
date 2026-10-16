package dev.codex.flightarmor.client.animation;

import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/** 使用 playerAnimator+bendy-lib 对原版完整皮肤网格进行连续肘膝弯曲。 */
public final class FlightJointAnimation implements IAnimation {
	private final AbstractClientPlayer player;
	private FlightPoseFrame pose = FlightPoseFrame.ZERO;

	public FlightJointAnimation(AbstractClientPlayer player) {
		this.player = player;
	}

	@Override
	public boolean isActive() {
		return ArmorAssemblyClient.isAnimating(player)
				|| AttackPoseController.isActive(player)
				|| FlightArmorClient.flightVisualProgress(player, 1.0F) > 0.001F
				|| player.isFallFlying();
	}

	@Override
	public void setupAnim(float tickDelta) {
		pose = poseFor(player, tickDelta);
	}

	/** 人物皮肤、弯曲库和 OBJ 装甲共同使用的唯一姿势入口。 */
	public static FlightPoseFrame poseFor(Player player, float partialTick) {
		if (ArmorAssemblyClient.isAnimating(player)) {
			return assemblyPose(player, partialTick);
		}
		return AttackPoseController.apply(player,
				FlightPoseController.sample(player, partialTick), partialTick);
	}

	/**
	 * 分件到达时使用错峰接受动作：手臂保持离身，正在锁定的一侧短暂抬起，
	 * 头盔闭合前轻微抬头。角度左右不完全镜像，避免机械人偶式同步。 */
	private static FlightPoseFrame assemblyPose(Player player, float partialTick) {
		float progress = Mth.clamp(ArmorAssemblyClient.assemblyProgress(player, partialTick), 0.0F, 1.0F);
		boolean feet = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.FEET);
		boolean legs = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.LEGS);
		boolean chest = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.CHEST);
		boolean head = ArmorAssemblyClient.affectsSlot(player, net.minecraft.world.entity.EquipmentSlot.HEAD);
		float leftArmStage = chest ? pulse(progress, 0.12F, 0.44F, 0.88F) : 0.0F;
		float rightArmStage = chest ? pulse(progress, 0.20F, 0.54F, 0.94F) : 0.0F;
		float leftLegStage = (feet || legs) ? pulse(progress, 0.08F, 0.38F, 0.84F) : 0.0F;
		float rightLegStage = (feet || legs) ? pulse(progress, 0.18F, 0.50F, 0.92F) : 0.0F;
		float helmetStage = head ? pulse(progress, 0.10F, 0.52F, 0.94F) : 0.0F;
		float torsoSettle = chest ? pulse(progress, 0.0F, 0.42F, 1.0F) : 0.0F;

		ArmorVariant variant = assemblyVariant(player);
		FlightPoseFrame local = new FlightPoseFrame(
				degrees(-2.5F * torsoSettle), 0.0F, degrees(-6.0F * helmetStage), 0.0F,
				degrees(-7.0F * torsoSettle - 25.0F * leftArmStage), degrees(-2.0F * leftArmStage),
				degrees(-18.0F * leftArmStage),
				degrees(-5.0F * torsoSettle - 22.0F * rightArmStage), degrees(3.0F * rightArmStage),
				degrees(16.0F * rightArmStage),
				degrees(-12.0F * leftLegStage), degrees(-2.0F * leftLegStage), degrees(-4.0F * leftLegStage),
				degrees(-7.0F * rightLegStage), degrees(2.0F * rightLegStage), degrees(3.0F * rightLegStage),
				degrees(9.0F * torsoSettle + 25.0F * leftArmStage),
				degrees(6.0F * torsoSettle + 22.0F * rightArmStage),
				degrees(18.0F * leftLegStage), degrees(11.0F * rightLegStage));
		local = assemblyStyle(local, variant, progress);
		FlightPoseFrame base = FlightPoseController.sample(player, partialTick);
		boolean lowerBody = feet || legs;
		return new FlightPoseFrame(
				chest ? local.bodyPitch() : base.bodyPitch(),
				chest ? local.bodyRoll() : base.bodyRoll(),
				head ? local.headPitchOffset() : base.headPitchOffset(),
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
					pose.bodyPitch() + degrees(-5.0F * lock), pose.bodyRoll() * 0.62F,
					pose.headPitchOffset() + degrees(2.0F * lock), pose.headRollOffset() * 0.65F,
					pose.leftArmPitch() - degrees(8.0F), pose.leftArmYaw() * 0.72F, pose.leftArmRoll() * 0.74F,
					pose.rightArmPitch() - degrees(3.0F), pose.rightArmYaw() * 0.72F, pose.rightArmRoll() * 0.74F,
					pose.leftLegPitch() + degrees(5.0F), pose.leftLegYaw() * 0.65F, pose.leftLegRoll() * 0.65F,
					pose.rightLegPitch() - degrees(2.0F), pose.rightLegYaw() * 0.65F, pose.rightLegRoll() * 0.65F,
					pose.leftElbowBend() * 0.72F, pose.rightElbowBend() * 0.68F,
					pose.leftKneeBend() * 0.70F, pose.rightKneeBend() * 0.66F);
			case SENTINEL -> new FlightPoseFrame(
					pose.bodyPitch() + degrees(4.0F * lock), pose.bodyRoll() * 0.42F,
					pose.headPitchOffset() - degrees(2.0F * lock), pose.headRollOffset() * 0.45F,
					pose.leftArmPitch() - degrees(7.0F), pose.leftArmYaw() * 1.25F, pose.leftArmRoll() * 1.28F,
					pose.rightArmPitch() - degrees(6.0F), pose.rightArmYaw() * 1.25F, pose.rightArmRoll() * 1.28F,
					pose.leftLegPitch() - degrees(5.0F), pose.leftLegYaw() * 1.30F, pose.leftLegRoll() * 1.25F,
					pose.rightLegPitch() - degrees(4.0F), pose.rightLegYaw() * 1.30F, pose.rightLegRoll() * 1.25F,
					pose.leftElbowBend() + degrees(14.0F), pose.rightElbowBend() + degrees(12.0F),
					pose.leftKneeBend() + degrees(16.0F), pose.rightKneeBend() + degrees(14.0F));
		};
	}
	private static float pulse(float value, float start, float peak, float end) {
		if (value <= start || value >= end) return 0.0F;
		return value < peak
				? smoothstep(start, peak, value)
				: 1.0F - smoothstep(peak, end, value);
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static float degrees(float value) {
		return value * Mth.DEG_TO_RAD;
	}

	@Override
	public @NotNull Vec3f get3DTransform(@NotNull String modelName, @NotNull TransformType type,
			float tickDelta, @NotNull Vec3f value0) {
		if (type == TransformType.BEND) {
			if (modelName.equals("body") || modelName.equals("torso")) return Vec3f.ZERO;
			float bend = switch (modelName) {
				case "leftArm", "left_arm" -> pose.leftElbowBend();
				case "rightArm", "right_arm" -> pose.rightElbowBend();
				case "leftLeg", "left_leg" -> pose.leftKneeBend();
				case "rightLeg", "right_leg" -> pose.rightKneeBend();
				default -> 0.0F;
			};
			return bend == 0.0F ? value0 : new Vec3f(0.0F, bend, 0.0F);
		}
		if (type != TransformType.ROTATION) return value0;

		/*
		 * Player Animator is now the single owner of animated joint rotation. The synthetic
		 * "body" root stays transparent so camera/body mods can compose below us; "torso" owns
		 * the authored armor lean. Head offsets remain additive to preserve the player's look.
		 */
		return switch (modelName) {
			case "body" -> new Vec3f(pose.bodyPitch(), value0.getY(), pose.bodyRoll());
			case "torso" -> value0;
			case "head" -> value0.add(new Vec3f(
					pose.headPitchOffset(), 0.0F, pose.headRollOffset()));
			case "leftArm", "left_arm" -> new Vec3f(
					pose.leftArmPitch(), pose.leftArmYaw(), pose.leftArmRoll());
			case "rightArm", "right_arm" -> new Vec3f(
					pose.rightArmPitch(), pose.rightArmYaw(), pose.rightArmRoll());
			case "leftLeg", "left_leg" -> new Vec3f(
					pose.leftLegPitch(), pose.leftLegYaw(), pose.leftLegRoll());
			case "rightLeg", "right_leg" -> new Vec3f(
					pose.rightLegPitch(), pose.rightLegYaw(), pose.rightLegRoll());
			default -> value0;
		};
	}
}
