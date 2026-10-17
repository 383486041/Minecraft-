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
 * 精细化高达飞行姿态控制器：
 * <ul>
 *   <li>三频谐波零重力悬停律动（反应炉呼吸起伏 + 推进器微幅漂移 + 伺服关节微调）</li>
 *   <li>瞬态加速度 G 力惯性冲击（加速突进瞬间头部微仰与四肢后掠、急停瞬间逆喷前蹬）</li>
 *   <li>分层跟随惯性插值（Overlapping Action：头部先导 -> 躯干跟进 -> 上肢 -> 下肢末端延迟）</li>
 * </ul>
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
			smoothed.updateAcceleration(input);
			float deployment = FlightArmorClient.flightVisualProgress(player, 1.0F);
			boolean diving = FlightArmorClient.isDiveVisualActive(player);
			float personalPhase = (player.getUUID().hashCode() & 1023) * (Mth.TWO_PI / 1024.0F);
			float animationTime = player.tickCount + personalPhase * 3.0F;
			ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
			FlightPoseFrame target = targetPose(
					input, smoothed.accelForward, smoothed.accelStrafe, smoothed.accelVertical,
					diving, animationTime, personalPhase, variant)
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

	private static FlightPoseFrame targetPose(Input input,
			float accelForward, float accelStrafe, float accelVertical,
			boolean diving, float animationTime, float personalPhase, ArmorVariant variant) {
		if (diving) return stylePose(divePose(animationTime, personalPhase), variant, MotionBeat.DIVE);

		float forward = Math.max(0.0F, input.forward);
		float backward = Math.max(0.0F, -input.forward);
		float left = Math.max(0.0F, -input.strafe);
		float right = Math.max(0.0F, input.strafe);
		float ascend = Math.max(0.0F, input.vertical);
		float descend = Math.max(0.0F, -input.vertical);
		float activity = Mth.clamp(forward + backward + left + right + ascend + descend,
				0.0F, 1.0F);

		FlightPoseFrame pose = stylePose(hoverPose(animationTime, personalPhase, 1.0F - activity * 0.65F),
				variant, MotionBeat.HOVER);
		pose = pose.lerp(stylePose(forwardPose(animationTime, personalPhase), variant, MotionBeat.FORWARD), eased(forward));
		pose = pose.lerp(stylePose(backwardPose(animationTime, personalPhase), variant, MotionBeat.BACKWARD), eased(backward));
		pose = pose.lerp(stylePose(leftStrafePose(animationTime, personalPhase), variant, MotionBeat.STRAFE), eased(left));
		pose = pose.lerp(stylePose(rightStrafePose(animationTime, personalPhase), variant, MotionBeat.STRAFE), eased(right));
		pose = pose.lerp(stylePose(ascendPose(animationTime, personalPhase), variant, MotionBeat.ASCEND), eased(ascend));
		pose = pose.lerp(stylePose(descendPose(animationTime, personalPhase), variant, MotionBeat.DESCEND), eased(descend));

		// 叠加瞬态加减速 G 力惯性响应（Whiplash & Counter-Sway）
		return applyGForceImpulse(pose, accelForward, accelStrafe, accelVertical);
	}

	/**
	 * 瞬态加减速 G 力惯性脉冲：
	 * 突进加速瞬间躯干前压、四肢因惯性微向后甩；急停瞬间膝部前顶、双臂前撑抵消惯性。
	 */
	private static FlightPoseFrame applyGForceImpulse(FlightPoseFrame base,
			float accelForward, float accelStrafe, float accelVertical) {
		if (Math.abs(accelForward) < 0.005F && Math.abs(accelStrafe) < 0.005F
				&& Math.abs(accelVertical) < 0.005F) {
			return base;
		}
		float fwdKick = Mth.clamp(accelForward * 2.4F, -1.0F, 1.0F);
		float strafeKick = Mth.clamp(accelStrafe * 2.2F, -1.0F, 1.0F);
		float vertKick = Mth.clamp(accelVertical * 2.0F, -1.0F, 1.0F);
		return new FlightPoseFrame(
				base.bodyPitch() + degrees(-6.5F * fwdKick - 3.0F * vertKick),
				base.bodyYaw() + degrees(-8.0F * strafeKick),
				base.bodyRoll() + degrees(5.5F * strafeKick),
				base.headPitchOffset() + degrees(5.0F * fwdKick + 2.5F * vertKick),
				base.headYawOffset() + degrees(6.5F * strafeKick),
				base.headRollOffset() + degrees(-3.5F * strafeKick),
				base.leftArmPitch() + degrees(12.0F * fwdKick + 6.0F * vertKick),
				base.leftArmYaw() + degrees(-4.0F * strafeKick),
				base.leftArmRoll() + degrees(-6.0F * Math.abs(fwdKick) - 5.0F * strafeKick),
				base.rightArmPitch() + degrees(12.0F * fwdKick + 6.0F * vertKick),
				base.rightArmYaw() + degrees(-4.0F * strafeKick),
				base.rightArmRoll() + degrees(6.0F * Math.abs(fwdKick) - 5.0F * strafeKick),
				base.leftLegPitch() + degrees(10.0F * fwdKick - 8.0F * vertKick),
				base.leftLegYaw(),
				base.leftLegRoll() + degrees(4.0F * strafeKick),
				base.rightLegPitch() + degrees(10.0F * fwdKick - 8.0F * vertKick),
				base.rightLegYaw(),
				base.rightLegRoll() + degrees(4.0F * strafeKick),
				base.leftElbowBend() + degrees(8.0F * Math.abs(fwdKick)),
				base.rightElbowBend() + degrees(8.0F * Math.abs(fwdKick)),
				base.leftKneeBend() + degrees(12.0F * Math.max(0.0F, -fwdKick) + 8.0F * Math.max(0.0F, -vertKick)),
				base.rightKneeBend() + degrees(10.0F * Math.max(0.0F, -fwdKick) + 8.0F * Math.max(0.0F, -vertKick))
		);
	}

	/**
	 * 高达经典空中待机立姿（Katoki Gundam Aerial Seed Stand）：
	 * 三频谐波叠加——主呼吸（胸腔起伏+肩臂舒展）、次级重心摆动（腰胯微拧+高低膝呼吸）、高频伺服微调。
	 */
	private static FlightPoseFrame hoverPose(float time, float phase, float idleWeight) {
		float breath = Mth.sin(time * 0.088F + phase) * idleWeight;
		float secondary = Mth.sin(time * 0.132F + phase + 1.57F) * idleWeight;
		float servo = Mth.sin(time * 0.280F + phase * 1.7F) * 0.45F * idleWeight;
		return frame3D(
				-4.2F + breath * 1.5F,
				-7.5F + secondary * 2.2F,
				secondary * 1.4F,
				5.2F - breath * 1.2F,
				6.5F - secondary * 1.8F,
				-secondary * 0.9F,
				-15.0F + breath * 2.8F, -10.0F + secondary * 1.5F, -17.5F - breath * 1.8F,
				-8.5F + secondary * 2.4F, 9.0F - breath * 1.4F, 15.5F + secondary * 1.6F,
				-28.0F + breath * 2.4F, -5.5F, -7.0F,
				6.5F - breath * 1.8F, 4.5F, 5.5F,
				36.0F + breath * 3.0F + servo,
				28.0F - secondary * 2.4F - servo,
				62.0F + breath * 3.8F + servo,
				20.0F - secondary * 2.0F);
	}

	/**
	 * 高达全推力突进姿态（High-Thrust Assault Cruise）：
	 * 躯干流线前压、微侧胸破风，头部逆向抬起锁定前方，双臂沿机身向斜后方掠翼收束，
	 * 前导腿屈膝切开气流、后掠腿延伸尾喷推进线，附带推进器高频谐波震颤。
	 */
	private static FlightPoseFrame forwardPose(float time, float phase) {
		float thrustVib = Mth.sin(time * 0.26F + phase) * 1.4F;
		float wingStabilize = Mth.cos(time * 0.18F + phase) * 1.1F;
		return frame3D(
				-16.5F + thrustVib * 0.4F,
				-5.0F + wingStabilize,
				0.0F,
				14.0F - thrustVib * 0.3F,
				4.5F - wingStabilize,
				0.0F,
				22.0F + thrustVib, -7.0F, -14.0F - wingStabilize,
				26.0F - thrustVib, 7.0F, 13.0F + wingStabilize,
				-16.0F + thrustVib * 0.6F, -3.5F, -4.5F,
				15.0F - thrustVib * 0.6F, 3.5F, 4.5F,
				26.0F + thrustVib, 20.0F - thrustVib,
				46.0F + thrustVib * 1.2F, 18.0F);
	}

	/**
	 * 高达逆喷射急停姿态（Reverse-Vernier Air-Brake）：
	 * 胸腰后仰、收颌凝视，双臂向斜前方张开反推平衡力矩，
	 * 双腿大幅前蹬并屈膝展开膝部与小腿制动喷口。
	 */
	private static FlightPoseFrame backwardPose(float time, float phase) {
		float brakePulse = Mth.sin(time * 0.30F + phase) * 1.5F;
		return frame3D(
				10.5F + brakePulse * 0.5F,
				4.0F,
				0.0F,
				-7.5F,
				-3.5F,
				0.0F,
				-28.0F + brakePulse, -12.0F, -22.0F,
				-22.0F - brakePulse, 11.0F, 20.0F,
				-38.0F + brakePulse, -6.0F, -8.0F,
				-24.0F - brakePulse, 6.0F, 7.0F,
				46.0F, 36.0F,
				72.0F + brakePulse, 50.0F - brakePulse);
	}

	/** 左向高机动横滑（Left High-G Banking Strafe）：机身左倾并向切线方向扭腰，头部逆向回望锁敌。 */
	private static FlightPoseFrame leftStrafePose(float time, float phase) {
		float sway = Mth.sin(time * 0.20F + phase) * 1.2F;
		return frame3D(
				-6.0F,
				14.0F + sway,
				-10.5F,
				4.5F,
				-11.5F - sway,
				6.5F,
				-28.0F + sway, -20.0F, -26.0F,
				12.0F - sway, 14.0F, 14.0F,
				-12.0F, -5.0F, -7.0F,
				-62.0F + sway, 10.0F, 13.0F,
				46.0F, 22.0F,
				24.0F, 82.0F);
	}

	/** 右向高机动横滑（Right High-G Banking Strafe）：镜像侧倾与扭腰锁敌姿态。 */
	private static FlightPoseFrame rightStrafePose(float time, float phase) {
		float sway = Mth.sin(time * 0.20F + phase) * 1.2F;
		return frame3D(
				-6.0F,
				-14.0F - sway,
				10.5F,
				4.5F,
				11.5F + sway,
				-6.5F,
				12.0F - sway, -14.0F, -14.0F,
				-28.0F + sway, 20.0F, 26.0F,
				-62.0F + sway, -10.0F, -13.0F,
				-12.0F, 5.0F, 7.0F,
				22.0F, 46.0F,
				82.0F, 24.0F);
	}

	/** 垂直拔升（Vertical Ascend Boost）：挺胸昂首、双臂向后下方展翼导流、左膝高提蓄势。 */
	private static FlightPoseFrame ascendPose(float time, float phase) {
		float lift = Mth.sin(time * 0.22F + phase) * 1.2F;
		return frame3D(
				-7.5F,
				-4.5F,
				0.0F,
				-12.0F + lift * 0.5F,
				4.0F,
				0.0F,
				-20.0F + lift, -13.0F, -24.0F,
				-14.0F - lift, 12.0F, 21.0F,
				-34.0F + lift, -5.0F, -7.0F,
				-8.0F, 4.5F, 5.5F,
				42.0F, 30.0F,
				68.0F, 26.0F);
	}

	/** 战术急降（Tactical Controlled Descend）：收腹沉身、双臂向斜外侧张开维持空气动力稳定。 */
	private static FlightPoseFrame descendPose(float time, float phase) {
		float sink = Mth.sin(time * 0.22F + phase) * 1.2F;
		return frame3D(
				-4.5F,
				-3.5F,
				0.0F,
				9.5F,
				3.0F,
				0.0F,
				10.0F + sink, -12.0F, -30.0F,
				14.0F - sink, 12.0F, 28.0F,
				-18.0F, -5.5F, -6.5F,
				-6.0F, 5.5F, 6.5F,
				28.0F, 22.0F,
				42.0F, 26.0F);
	}

	/** 高速俯冲突防（Hypersonic Dive）：全身收束为破空箭形，伴随气动高频微震。 */
	private static FlightPoseFrame divePose(float time, float phase) {
		float vib = Mth.sin(time * 0.48F + phase) * 0.9F;
		return frame3D(
				-20.0F,
				0.0F,
				0.0F,
				13.0F,
				0.0F,
				0.0F,
				34.0F + vib, -5.0F, -8.0F,
				31.0F - vib, 5.0F, 8.0F,
				16.0F, -2.5F, -3.5F,
				20.0F, 2.5F, 3.5F,
				-44.0F, -48.0F,
				14.0F, 18.0F);
	}

	private static FlightPoseFrame frame3D(
			float bodyPitch, float bodyYaw, float bodyRoll,
			float headPitch, float headYaw, float headRoll,
			float leftArmPitch, float leftArmYaw, float leftArmRoll,
			float rightArmPitch, float rightArmYaw, float rightArmRoll,
			float leftLegPitch, float leftLegYaw, float leftLegRoll,
			float rightLegPitch, float rightLegYaw, float rightLegRoll,
			float leftElbow, float rightElbow, float leftKnee, float rightKnee) {
		return new FlightPoseFrame(
				degrees(bodyPitch), degrees(bodyYaw), degrees(bodyRoll),
				degrees(headPitch), degrees(headYaw), degrees(headRoll),
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
					pose.bodyPitch() + degrees(-5.5F * stream),
					pose.bodyYaw() * 1.18F,
					pose.bodyRoll() * 0.82F,
					pose.headPitchOffset() + degrees(4.0F * stream),
					pose.headYawOffset() * 1.18F,
					pose.headRollOffset() * 0.78F,
					pose.leftArmPitch() + degrees(8.0F * stream), pose.leftArmYaw() * 0.78F,
					pose.leftArmRoll() * 0.75F,
					pose.rightArmPitch() + degrees(10.0F * stream), pose.rightArmYaw() * 0.78F,
					pose.rightArmRoll() * 0.75F,
					pose.leftLegPitch() + degrees(6.0F * stream), pose.leftLegYaw() * 0.68F,
					pose.leftLegRoll() * 0.65F,
					pose.rightLegPitch() + degrees(3.5F * stream), pose.rightLegYaw() * 0.68F,
					pose.rightLegRoll() * 0.65F,
					pose.leftElbowBend() * 0.84F, pose.rightElbowBend() * 0.80F,
					pose.leftKneeBend() * 0.82F, pose.rightKneeBend() * 0.76F);
		}
		float brace = beat == MotionBeat.HOVER || beat == MotionBeat.ASCEND ? 1.0F : 0.62F;
		return new FlightPoseFrame(
				pose.bodyPitch() * 0.72F,
				pose.bodyYaw() * 0.78F,
				pose.bodyRoll() * 0.56F,
				pose.headPitchOffset() * 0.80F,
				pose.headYawOffset() * 0.78F,
				pose.headRollOffset() * 0.56F,
				pose.leftArmPitch() - degrees(6.5F * brace), pose.leftArmYaw() * 1.22F,
				pose.leftArmRoll() * 1.28F,
				pose.rightArmPitch() - degrees(4.5F * brace), pose.rightArmYaw() * 1.22F,
				pose.rightArmRoll() * 1.28F,
				pose.leftLegPitch() - degrees(5.5F * brace), pose.leftLegYaw() * 1.26F,
				pose.leftLegRoll() * 1.24F,
				pose.rightLegPitch() - degrees(3.5F * brace), pose.rightLegYaw() * 1.26F,
				pose.rightLegRoll() * 1.24F,
				pose.leftElbowBend() + degrees(12.0F * brace),
				pose.rightElbowBend() + degrees(10.0F * brace),
				pose.leftKneeBend() + degrees(14.0F * brace),
				pose.rightKneeBend() + degrees(12.0F * brace));
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
		private float lastForward;
		private float lastStrafe;
		private float lastVertical;
		private float accelForward;
		private float accelStrafe;
		private float accelVertical;

		private void updateAcceleration(Input input) {
			float rawAccelFwd = input.forward - lastForward;
			float rawAccelStr = input.strafe - lastStrafe;
			float rawAccelVert = input.vertical - lastVertical;
			lastForward = input.forward;
			lastStrafe = input.strafe;
			lastVertical = input.vertical;
			accelForward = Mth.lerp(0.36F, accelForward * 0.78F, rawAccelFwd);
			accelStrafe = Mth.lerp(0.36F, accelStrafe * 0.78F, rawAccelStr);
			accelVertical = Mth.lerp(0.36F, accelVertical * 0.78F, rawAccelVert);
		}

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
				accelForward = 0.0F;
				accelStrafe = 0.0F;
				accelVertical = 0.0F;
				return;
			}
			previous = current;
			if (active) {
				// 分层惯性跟随：头部先导 0.44，躯干 0.31，上臂/大腿 0.25，肘膝末端 0.20
				current = current.lerpLayered(target, 0.44F, 0.31F, 0.25F, 0.20F);
			} else {
				current = current.lerpLayered(target, 0.28F, 0.22F, 0.18F, 0.15F);
			}
		}
	}
}
