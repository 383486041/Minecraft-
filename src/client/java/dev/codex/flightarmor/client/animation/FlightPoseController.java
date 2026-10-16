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

/** 按玩家保存并平滑计算悬空、方向移动和俯冲四肢姿态。 */
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
		if (diving) return stylePose(divePose(), variant, MotionBeat.DIVE);

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
		pose = pose.lerp(stylePose(forwardPose(), variant, MotionBeat.FORWARD), eased(forward));
		pose = pose.lerp(stylePose(backwardPose(), variant, MotionBeat.BACKWARD), eased(backward));
		pose = pose.lerp(stylePose(leftStrafePose(), variant, MotionBeat.STRAFE), eased(left));
		pose = pose.lerp(stylePose(rightStrafePose(), variant, MotionBeat.STRAFE), eased(right));
		pose = pose.lerp(stylePose(ascendPose(), variant, MotionBeat.ASCEND), eased(ascend));
		pose = pose.lerp(stylePose(descendPose(), variant, MotionBeat.DESCEND), eased(descend));
		return pose;
	}

	private static FlightPoseFrame hoverPose(float time, float phase, float idleWeight) {
		float leftBalance = Mth.sin(time * 0.105F + phase) * 2.2F * idleWeight;
		float rightBalance = Mth.sin(time * 0.127F + phase + 1.83F) * 1.8F * idleWeight;
		return frame(
				0.0F, 0.0F, 2.0F, 0.0F,
				-9.0F + leftBalance, -7.0F, -13.0F,
				-4.0F + rightBalance, 6.0F, 10.0F,
				-17.0F, -3.0F, -5.0F,
				-7.0F, 3.0F, 4.0F,
				28.0F + leftBalance * 0.35F, 20.0F - rightBalance * 0.28F,
				43.0F, 29.0F);
	}

	private static FlightPoseFrame forwardPose() {
		return frame(
				-7.0F, 0.0F, 8.0F, 0.0F,
				6.0F, -5.0F, -8.0F,
				10.0F, 4.0F, 7.0F,
				-7.0F, -2.0F, -3.0F,
				1.0F, 2.0F, 3.0F,
				18.0F, 12.0F, 26.0F, 18.0F);
	}

	private static FlightPoseFrame backwardPose() {
		return frame(
				3.0F, 0.0F, -3.0F, 0.0F,
				-15.0F, -8.0F, -16.0F,
				-8.0F, 7.0F, 12.0F,
				-25.0F, -4.0F, -6.0F,
				-13.0F, 4.0F, 5.0F,
				34.0F, 23.0F, 55.0F, 36.0F);
	}

	private static FlightPoseFrame leftStrafePose() {
		return frame(
				-2.0F, 0.0F, 3.0F, 0.0F,
				-18.0F, -15.0F, -20.0F,
				3.0F, 9.0F, 7.0F,
				-5.0F, -2.0F, -3.0F,
				-58.0F, 7.0F, 9.0F,
				37.0F, 16.0F, 16.0F, 72.0F);
	}

	private static FlightPoseFrame rightStrafePose() {
		return frame(
				-2.0F, 0.0F, 3.0F, 0.0F,
				2.0F, -8.0F, -7.0F,
				-17.0F, 14.0F, 19.0F,
				-57.0F, -7.0F, -9.0F,
				-4.0F, 2.0F, 3.0F,
				17.0F, 36.0F, 70.0F, 15.0F);
	}

	private static FlightPoseFrame ascendPose() {
		return frame(
				2.0F, 0.0F, -5.0F, 0.0F,
				-13.0F, -8.0F, -16.0F,
				-7.0F, 7.0F, 13.0F,
				-23.0F, -3.0F, -5.0F,
				-12.0F, 3.0F, 5.0F,
				32.0F, 21.0F, 49.0F, 34.0F);
	}

	private static FlightPoseFrame descendPose() {
		return frame(
				0.0F, 0.0F, 4.0F, 0.0F,
				4.0F, -8.0F, -25.0F,
				9.0F, 8.0F, 23.0F,
				-5.0F, -5.0F, -5.0F,
				3.0F, 5.0F, 5.0F,
				16.0F, 11.0F, 23.0F, 15.0F);
	}

	private static FlightPoseFrame divePose() {
		return frame(
				-12.0F, 0.0F, 7.0F, 0.0F,
				27.0F, -3.0F, -5.0F,
				24.0F, 3.0F, 5.0F,
				10.0F, -1.0F, -2.0F,
				15.0F, 1.0F, 2.0F,
				-48.0F, -52.0F, 8.0F, 11.0F);
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
					pose.bodyPitch() + degrees(-4.0F * stream), pose.bodyRoll() * 0.55F,
					pose.headPitchOffset() + degrees(2.0F * stream), pose.headRollOffset() * 0.60F,
					pose.leftArmPitch() + degrees(5.0F * stream), pose.leftArmYaw() * 0.72F,
					pose.leftArmRoll() * 0.68F,
					pose.rightArmPitch() + degrees(8.0F * stream), pose.rightArmYaw() * 0.72F,
					pose.rightArmRoll() * 0.68F,
					pose.leftLegPitch() + degrees(5.0F * stream), pose.leftLegYaw() * 0.58F,
					pose.leftLegRoll() * 0.55F,
					pose.rightLegPitch() + degrees(2.0F * stream), pose.rightLegYaw() * 0.58F,
					pose.rightLegRoll() * 0.55F,
					pose.leftElbowBend() * 0.78F, pose.rightElbowBend() * 0.72F,
					pose.leftKneeBend() * 0.76F, pose.rightKneeBend() * 0.70F);
		}
		float brace = beat == MotionBeat.HOVER || beat == MotionBeat.ASCEND ? 1.0F : 0.55F;
		return new FlightPoseFrame(
				pose.bodyPitch() * 0.62F, pose.bodyRoll() * 0.42F,
				pose.headPitchOffset() * 0.72F, pose.headRollOffset() * 0.45F,
				pose.leftArmPitch() - degrees(5.0F * brace), pose.leftArmYaw() * 1.18F,
				pose.leftArmRoll() * 1.28F,
				pose.rightArmPitch() - degrees(3.0F * brace), pose.rightArmYaw() * 1.18F,
				pose.rightArmRoll() * 1.28F,
				pose.leftLegPitch() - degrees(4.0F * brace), pose.leftLegYaw() * 1.26F,
				pose.leftLegRoll() * 1.24F,
				pose.rightLegPitch() - degrees(2.0F * brace), pose.rightLegYaw() * 1.26F,
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
			current = current.lerp(target, active ? 0.28F : 0.18F);
		}
	}
}
