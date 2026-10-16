package dev.codex.flightarmor.client.animation;

import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按玩家保存并平滑计算高达风格悬空待机、全向推进、逆喷射急停和高速俯冲四肢姿态。
 */
public final class FlightPoseController {
	private static final Map<UUID, SmoothedPose> POSES = new HashMap<>();

	private FlightPoseController() {
	}

	public static void tick(Minecraft client) {
		if (client.level == null) {
			POSES.clear();
			return;
		}

		Set<UUID> loaded = new HashSet<>();
		for (Player player : client.level.players()) {
			loaded.add(player.getUUID());
			Input input = inputFor(client, player);
			SmoothedPose smoothed = POSES.computeIfAbsent(player.getUUID(), ignored -> new SmoothedPose());
			input = input.withStrafe(smoothed.resolveStrafe(input.strafe));
			float deployment = FlightArmorClient.flightVisualProgress(player, 1.0F);
			boolean diving = FlightArmorClient.isDiveVisualActive(player);
			float personalPhase = (player.getUUID().hashCode() & 1023) * (Mth.TWO_PI / 1024.0F);
			float animationTime = player.tickCount + personalPhase * 3.0F;
			ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
			FlightPoseFrame target = targetPose(input, diving, animationTime, personalPhase, variant)
					.scale(deployment);
			boolean forceNeutral = player.onGround() || deployment <= 0.001F;
			smoothed.tick(target, deployment > 0.001F, forceNeutral);
		}
		POSES.keySet().removeIf(id -> !loaded.contains(id));
	}

	public static FlightPoseFrame sample(Player player, float partialTick) {
		SmoothedPose pose = POSES.get(player.getUUID());
		return pose == null ? FlightPoseFrame.ZERO : pose.previous.lerp(pose.current, Mth.clamp(partialTick, 0.0F, 1.0F));
	}

	private static Input inputFor(Minecraft client, Player player) {
		if (client.player != null && client.player.getUUID().equals(player.getUUID())) {
			Input keys = new Input(FlightArmorClient.hoverForward(),
					FlightArmorClient.hoverStrafe(), FlightArmorClient.hoverVertical());
			Input motion = motionInput(player);
			// Keep authored motion after key release so limbs do not snap to hover.
			return new Input(
					Math.abs(keys.forward) > 0.01F ? keys.forward : motion.forward * 0.85F,
					Math.abs(keys.strafe) > 0.01F ? keys.strafe : motion.strafe * 0.85F,
					Math.abs(keys.vertical) > 0.01F ? keys.vertical : motion.vertical * 0.65F);
		}
		return motionInput(player);
	}

	private static Input motionInput(Player player) {
		Vec3 velocity = player.getDeltaMovement();
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double forwardX = -Mth.sin(yaw);
		double forwardZ = Mth.cos(yaw);
		double rightX = Mth.cos(yaw);
		double rightZ = Mth.sin(yaw);
		float forward = Mth.clamp((float) ((velocity.x * forwardX + velocity.z * forwardZ) / 0.32D), -1.0F, 1.0F);
		float strafe = Mth.clamp((float) ((velocity.x * rightX + velocity.z * rightZ) / 0.32D), -1.0F, 1.0F);
		float vertical = Mth.clamp((float) (velocity.y / 0.24D), -1.0F, 1.0F);
		return new Input(forward, strafe, vertical);
	}

	private static FlightPoseFrame targetPose(Input input, boolean diving,
			float animationTime, float personalPhase, ArmorVariant variant) {
		if (diving) return stylePose(divePose(animationTime, personalPhase), variant, MotionBeat.DIVE);

		float forward = Math.max(0.0F, input.forward);
		float backward = Math.max(0.0F, -input.forward);
		float left = Math.max(0.0F, -input.strafe);
		float right = Math.max(0.0F, input.strafe);
		float ascend = Math.max(0.0F, input.vertical);
		float descend = Math.max(0.0F, -input.vertical);
		float activity = Mth.clamp(forward + backward + left + right + ascend + descend,
				0.0F, 1.0F);

		FlightPoseFrame pose = stylePose(hoverPose(animationTime, personalPhase, 1.0F - activity),
				variant, MotionBeat.HOVER);
		pose = pose.lerp(stylePose(forwardPose(animationTime, personalPhase), variant, MotionBeat.FORWARD), eased(forward));
		pose = pose.lerp(stylePose(backwardPose(), variant, MotionBeat.BACKWARD), eased(backward));
		pose = pose.lerp(stylePose(leftStrafePose(), variant, MotionBeat.STRAFE), eased(left));
		pose = pose.lerp(stylePose(rightStrafePose(), variant, MotionBeat.STRAFE), eased(right));
		pose = pose.lerp(stylePose(ascendPose(), variant, MotionBeat.ASCEND), eased(ascend));
		pose = pose.lerp(stylePose(descendPose(), variant, MotionBeat.DESCEND), eased(descend));
		return pose;
	}

	/**
	 * 高达经典空中待机立姿（Gundam Aerial Seed Stand）：
	 * 胸膛微挺、下颌微收（从 V 字天线下方凝视前方），双臂微曲自然外展，
	 * 左膝高抬弯曲、右腿向后下方舒展，配合零重力微幅呼吸起伏。
	 */
	private static FlightPoseFrame hoverPose(float time, float phase, float idleWeight) {
		float breath = Mth.sin(time * 0.095F + phase) * idleWeight;
		float secondary = Mth.sin(time * 0.135F + phase + 1.57F) * idleWeight;
		return frame(
				-3.5F + breath * 1.2F, secondary * 1.1F, 4.5F - breath * 1.0F, -secondary * 0.8F,
				-14.0F + breath * 2.6F, -9.0F, -16.0F - breath * 1.5F,
				-8.0F + secondary * 2.2F, 8.0F, 14.0F + secondary * 1.4F,
				-26.0F + breath * 2.0F, -4.5F, -6.5F,
				-6.0F - breath * 1.4F, 4.0F, 5.5F,
				34.0F + breath * 2.5F, 26.0F - secondary * 2.0F,
				56.0F + breath * 3.0F, 22.0F - secondary * 1.8F);
	}

	/**
	 * 高达推进突进姿态：躯干前倾破风、头部微抬保持平视，双臂向斜后方收拢导流，
	 * 前导腿屈膝稳住重心、后掠腿延伸推进线。
	 */
	private static FlightPoseFrame forwardPose(float time, float phase) {
		float micro = Mth.sin(time * 0.22F + phase) * 1.2F;
		return frame(
				-14.0F, 0.0F, 12.0F, 0.0F,
				18.0F + micro, -6.0F, -12.0F,
				22.0F - micro, 6.0F, 11.0F,
				-14.0F, -3.0F, -4.0F,
				12.0F, 3.0F, 4.0F,
				24.0F, 18.0F, 42.0F, 16.0F);
	}

	/**
	 * 高达逆喷射急停/后退姿态：上身后仰、收颌，双臂前推平衡力矩，
	 * 双膝前屈将小腿与脚底推进喷口朝向正前方制动。
	 */
	private static FlightPoseFrame backwardPose() {
		return frame(
				8.0F, 0.0F, -6.0F, 0.0F,
				-24.0F, -10.0F, -20.0F,
				-18.0F, 9.0F, 18.0F,
				-34.0F, -5.0F, -7.0F,
				-22.0F, 5.0F, 6.0F,
				42.0F, 32.0F, 66.0F, 46.0F);
	}

	/** 左向高机动横滑：机身向内侧倾转，外侧腿蓄力蹬出、内侧腿高抬屈膝。 */
	private static FlightPoseFrame leftStrafePose() {
		return frame(
				-5.0F, -8.0F, 4.0F, 5.0F,
				-24.0F, -18.0F, -24.0F,
				8.0F, 12.0F, 11.0F,
				-10.0F, -4.0F, -6.0F,
				-58.0F, 8.0F, 11.0F,
				42.0F, 20.0F, 22.0F, 76.0F);
	}

	/** 右向高机动横滑：镜像的高机动侧倾切线姿态。 */
	private static FlightPoseFrame rightStrafePose() {
		return frame(
				-5.0F, 8.0F, 4.0F, -5.0F,
				8.0F, -12.0F, -11.0F,
				-24.0F, 18.0F, 24.0F,
				-58.0F, -8.0F, -11.0F,
				-10.0F, 4.0F, 6.0F,
				20.0F, 42.0F, 76.0F, 22.0F);
	}

	/** 垂直拔升：胸膛昂起、抬头望向天顶，双臂向两侧后下方张开导流，左膝高提。 */
	private static FlightPoseFrame ascendPose() {
		return frame(
				-6.0F, 0.0F, -10.0F, 0.0F,
				-18.0F, -11.0F, -22.0F,
				-12.0F, 10.0F, 19.0F,
				-30.0F, -4.0F, -6.0F,
				-10.0F, 4.0F, 5.0F,
				38.0F, 28.0F, 62.0F, 28.0F);
	}

	/** 战术急降：重心下沉、双臂向斜外侧展开维持稳定翼面。 */
	private static FlightPoseFrame descendPose() {
		return frame(
				-4.0F, 0.0F, 8.0F, 0.0F,
				8.0F, -10.0F, -28.0F,
				12.0F, 10.0F, 26.0F,
				-14.0F, -5.0F, -6.0F,
				-4.0F, 5.0F, 6.0F,
				24.0F, 18.0F, 36.0F, 22.0F);
	}

	/** 高速俯冲突防：全身收束为破空箭形。 */
	private static FlightPoseFrame divePose(float time, float phase) {
		float vib = Mth.sin(time * 0.45F + phase) * 0.8F;
		return frame(
				-18.0F, 0.0F, 11.0F, 0.0F,
				32.0F + vib, -4.0F, -7.0F,
				29.0F - vib, 4.0F, 7.0F,
				14.0F, -2.0F, -3.0F,
				18.0F, 2.0F, 3.0F,
				-46.0F, -50.0F, 12.0F, 15.0F);
	}

	private static FlightPoseFrame frame(
			float bodyPitch, float bodyRoll, float headPitch, float headRoll,
			float leftArmPitch, float leftArmYaw, float leftArmRoll,
			float rightArmPitch, float rightArmYaw, float rightArmRoll,
			float leftLegPitch, float leftLegYaw, float leftLegRoll,
			float rightLegPitch, float rightLegYaw, float rightLegRoll,
			float leftElbow, float rightElbow, float leftKnee, float rightKnee) {
		return new FlightPoseFrame(
				degrees(bodyPitch), degrees(bodyRoll), degrees(headPitch), degrees(headRoll),
				degrees(leftArmPitch), degrees(leftArmYaw), degrees(leftArmRoll),
				degrees(rightArmPitch), degrees(rightArmYaw), degrees(rightArmRoll),
				degrees(leftLegPitch), degrees(leftLegYaw), degrees(leftLegRoll),
				degrees(rightLegPitch), degrees(rightLegYaw), degrees(rightLegRoll),
				degrees(leftElbow), degrees(rightElbow), degrees(leftKnee), degrees(rightKnee));
	}

	private static FlightPoseFrame stylePose(FlightPoseFrame pose, ArmorVariant variant,
			MotionBeat beat) {
		if (variant == ArmorVariant.VANGUARD) return pose;
		if (variant == ArmorVariant.SWIFT) {
			float stream = beat == MotionBeat.FORWARD || beat == MotionBeat.DIVE ? 1.0F : 0.0F;
			return new FlightPoseFrame(
					pose.bodyPitch() + degrees(-5.0F * stream), pose.bodyRoll() * 0.72F,
					pose.headPitchOffset() + degrees(3.5F * stream), pose.headRollOffset() * 0.70F,
					pose.leftArmPitch() + degrees(7.0F * stream), pose.leftArmYaw() * 0.75F,
					pose.leftArmRoll() * 0.72F,
					pose.rightArmPitch() + degrees(9.0F * stream), pose.rightArmYaw() * 0.75F,
					pose.rightArmRoll() * 0.72F,
					pose.leftLegPitch() + degrees(6.0F * stream), pose.leftLegYaw() * 0.65F,
					pose.leftLegRoll() * 0.62F,
					pose.rightLegPitch() + degrees(3.0F * stream), pose.rightLegYaw() * 0.65F,
					pose.rightLegRoll() * 0.62F,
					pose.leftElbowBend() * 0.82F, pose.rightElbowBend() * 0.78F,
					pose.leftKneeBend() * 0.80F, pose.rightKneeBend() * 0.74F);
		}
		float brace = beat == MotionBeat.HOVER || beat == MotionBeat.ASCEND ? 1.0F : 0.60F;
		return new FlightPoseFrame(
				pose.bodyPitch() * 0.68F, pose.bodyRoll() * 0.50F,
				pose.headPitchOffset() * 0.78F, pose.headRollOffset() * 0.52F,
				pose.leftArmPitch() - degrees(6.0F * brace), pose.leftArmYaw() * 1.20F,
				pose.leftArmRoll() * 1.28F,
				pose.rightArmPitch() - degrees(4.0F * brace), pose.rightArmYaw() * 1.20F,
				pose.rightArmRoll() * 1.28F,
				pose.leftLegPitch() - degrees(5.0F * brace), pose.leftLegYaw() * 1.26F,
				pose.leftLegRoll() * 1.24F,
				pose.rightLegPitch() - degrees(3.0F * brace), pose.rightLegYaw() * 1.26F,
				pose.rightLegRoll() * 1.24F,
				pose.leftElbowBend() + degrees(12.0F * brace),
				pose.rightElbowBend() + degrees(10.0F * brace),
				pose.leftKneeBend() + degrees(13.0F * brace),
				pose.rightKneeBend() + degrees(11.0F * brace));
	}

	private enum MotionBeat {
		HOVER, FORWARD, BACKWARD, STRAFE, ASCEND, DESCEND, DIVE
	}

	private static float eased(float value) {
		float clamped = Mth.clamp(value, 0.0F, 1.0F);
		return clamped * clamped * (3.0F - 2.0F * clamped);
	}

	private static float degrees(float degrees) {
		return degrees * Mth.DEG_TO_RAD;
	}

	private static float clampDegrees(float value, float min, float max) {
		return degrees(Mth.clamp(value, min, max));
	}

	private record Input(float forward, float strafe, float vertical) {
		private Input withStrafe(float nextStrafe) {
			return new Input(forward, nextStrafe, vertical);
		}
	}

	private static final class SmoothedPose {
		private FlightPoseFrame previous = FlightPoseFrame.ZERO;
		private FlightPoseFrame current = FlightPoseFrame.ZERO;
		private float directionalStrafe;
		private float pendingStrafe;

		private float resolveStrafe(float rawStrafe) {
			if (Math.abs(rawStrafe) < 0.05F) {
				pendingStrafe = 0.0F;
				directionalStrafe = Mth.lerp(0.30F, directionalStrafe, 0.0F);
				return directionalStrafe;
			}
			if (pendingStrafe != 0.0F) {
				if (Math.signum(rawStrafe) == Math.signum(directionalStrafe)) {
					pendingStrafe = 0.0F;
				} else if (Math.abs(directionalStrafe) > 0.12F) {
					directionalStrafe = Mth.lerp(0.38F, directionalStrafe, 0.0F);
					return directionalStrafe;
				} else {
					directionalStrafe = 0.0F;
					rawStrafe = pendingStrafe;
					pendingStrafe = 0.0F;
				}
			}
			if (directionalStrafe * rawStrafe < -0.01F) {
				pendingStrafe = rawStrafe;
				directionalStrafe = Mth.lerp(0.38F, directionalStrafe, 0.0F);
				return directionalStrafe;
			}
			directionalStrafe = Mth.lerp(0.34F, directionalStrafe, rawStrafe);
			return directionalStrafe;
		}

		private void tick(FlightPoseFrame target, boolean active, boolean forceNeutral) {
			if (forceNeutral) {
				previous = FlightPoseFrame.ZERO;
				current = FlightPoseFrame.ZERO;
				return;
			}
			previous = current;
			current = current.lerp(target, active ? 0.30F : 0.20F);
		}
	}
}
