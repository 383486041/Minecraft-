package dev.codex.flightarmor.client.animation;

import net.minecraft.util.Mth;

/**
 * 一帧完整的飞行与战斗人物姿态；所有旋转单位均为弧度。
 *
 * <p>包含完整的躯干三轴（俯仰 bodyPitch、偏航扭腰 bodyYaw、横滚 bodyRoll）、
 * 头部三轴逆向代偿（headPitchOffset、headYawOffset、headRollOffset）、
 * 四肢三轴旋转与四处肘膝连续弯曲角度。</p>
 */
public record FlightPoseFrame(
		float bodyPitch, float bodyYaw, float bodyRoll,
		float headPitchOffset, float headYawOffset, float headRollOffset,
		float leftArmPitch, float leftArmYaw, float leftArmRoll,
		float rightArmPitch, float rightArmYaw, float rightArmRoll,
		float leftLegPitch, float leftLegYaw, float leftLegRoll,
		float rightLegPitch, float rightLegYaw, float rightLegRoll,
		float leftElbowBend, float rightElbowBend,
		float leftKneeBend, float rightKneeBend
) {
	public static final FlightPoseFrame ZERO = new FlightPoseFrame(
			0, 0, 0,
			0, 0, 0,
			0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0,
			0, 0, 0, 0
	);

	/**
	 * 兼容 20 参数构造器：根据左右手臂与双腿的不对称前后摆动自动解算自然的腰部扭转（bodyYaw）
	 * 与头部逆向锁敌偏航代偿（headYawOffset），让所有旧调用点自动获得立体扭腰动态。
	 */
	public FlightPoseFrame(
			float bodyPitch, float bodyRoll, float headPitchOffset, float headRollOffset,
			float leftArmPitch, float leftArmYaw, float leftArmRoll,
			float rightArmPitch, float rightArmYaw, float rightArmRoll,
			float leftLegPitch, float leftLegYaw, float leftLegRoll,
			float rightLegPitch, float rightLegYaw, float rightLegRoll,
			float leftElbowBend, float rightElbowBend,
			float leftKneeBend, float rightKneeBend
	) {
		this(
				bodyPitch,
				deriveBodyYaw(bodyRoll, leftArmPitch, leftArmYaw, rightArmPitch, rightArmYaw, leftLegPitch, rightLegPitch),
				bodyRoll,
				headPitchOffset,
				-deriveBodyYaw(bodyRoll, leftArmPitch, leftArmYaw, rightArmPitch, rightArmYaw, leftLegPitch, rightLegPitch) * 0.78F,
				headRollOffset,
				leftArmPitch, leftArmYaw, leftArmRoll,
				rightArmPitch, rightArmYaw, rightArmRoll,
				leftLegPitch, leftLegYaw, leftLegRoll,
				rightLegPitch, rightLegYaw, rightLegRoll,
				leftElbowBend, rightElbowBend,
				leftKneeBend, rightKneeBend
		);
	}

	private static float deriveBodyYaw(float bodyRoll,
			float leftArmPitch, float leftArmYaw,
			float rightArmPitch, float rightArmYaw,
			float leftLegPitch, float rightLegPitch) {
		float armDifferential = (rightArmPitch - leftArmPitch) * 0.16F
				+ (leftArmYaw + rightArmYaw) * 0.22F;
		float legDifferential = (leftLegPitch - rightLegPitch) * 0.08F;
		float rollCoupling = bodyRoll * 0.38F;
		float maxYaw = 32.0F * Mth.DEG_TO_RAD;
		return Mth.clamp(armDifferential + legDifferential + rollCoupling, -maxYaw, maxYaw);
	}

	public FlightPoseFrame withTorsoAndHeadYaw(float nextBodyYaw, float nextHeadYawOffset) {
		return new FlightPoseFrame(
				bodyPitch, nextBodyYaw, bodyRoll,
				headPitchOffset, nextHeadYawOffset, headRollOffset,
				leftArmPitch, leftArmYaw, leftArmRoll,
				rightArmPitch, rightArmYaw, rightArmRoll,
				leftLegPitch, leftLegYaw, leftLegRoll,
				rightLegPitch, rightLegYaw, rightLegRoll,
				leftElbowBend, rightElbowBend,
				leftKneeBend, rightKneeBend
		);
	}

	public FlightPoseFrame lerp(FlightPoseFrame target, float alpha) {
		return new FlightPoseFrame(
				Mth.lerp(alpha, bodyPitch, target.bodyPitch),
				Mth.lerp(alpha, bodyYaw, target.bodyYaw),
				Mth.lerp(alpha, bodyRoll, target.bodyRoll),
				Mth.lerp(alpha, headPitchOffset, target.headPitchOffset),
				Mth.lerp(alpha, headYawOffset, target.headYawOffset),
				Mth.lerp(alpha, headRollOffset, target.headRollOffset),
				Mth.lerp(alpha, leftArmPitch, target.leftArmPitch),
				Mth.lerp(alpha, leftArmYaw, target.leftArmYaw),
				Mth.lerp(alpha, leftArmRoll, target.leftArmRoll),
				Mth.lerp(alpha, rightArmPitch, target.rightArmPitch),
				Mth.lerp(alpha, rightArmYaw, target.rightArmYaw),
				Mth.lerp(alpha, rightArmRoll, target.rightArmRoll),
				Mth.lerp(alpha, leftLegPitch, target.leftLegPitch),
				Mth.lerp(alpha, leftLegYaw, target.leftLegYaw),
				Mth.lerp(alpha, leftLegRoll, target.leftLegRoll),
				Mth.lerp(alpha, rightLegPitch, target.rightLegPitch),
				Mth.lerp(alpha, rightLegYaw, target.rightLegYaw),
				Mth.lerp(alpha, rightLegRoll, target.rightLegRoll),
				Mth.lerp(alpha, leftElbowBend, target.leftElbowBend),
				Mth.lerp(alpha, rightElbowBend, target.rightElbowBend),
				Mth.lerp(alpha, leftKneeBend, target.leftKneeBend),
				Mth.lerp(alpha, rightKneeBend, target.rightKneeBend)
		);
	}

	/**
	 * 分层惯性插值（Overlapping Action）：头部最先响应，躯干次之，上臂与大腿带有质量惯性，
	 * 前臂与小腿（肘膝弯曲）具有自然的末端跟随延迟。
	 */
	public FlightPoseFrame lerpLayered(FlightPoseFrame target,
			float headAlpha, float torsoAlpha, float upperLimbAlpha,
			float lowerLimbAlpha) {
		return new FlightPoseFrame(
				Mth.lerp(torsoAlpha, bodyPitch, target.bodyPitch),
				Mth.lerp(torsoAlpha, bodyYaw, target.bodyYaw),
				Mth.lerp(torsoAlpha, bodyRoll, target.bodyRoll),
				Mth.lerp(headAlpha, headPitchOffset, target.headPitchOffset),
				Mth.lerp(headAlpha, headYawOffset, target.headYawOffset),
				Mth.lerp(headAlpha, headRollOffset, target.headRollOffset),
				Mth.lerp(upperLimbAlpha, leftArmPitch, target.leftArmPitch),
				Mth.lerp(upperLimbAlpha, leftArmYaw, target.leftArmYaw),
				Mth.lerp(upperLimbAlpha, leftArmRoll, target.leftArmRoll),
				Mth.lerp(upperLimbAlpha, rightArmPitch, target.rightArmPitch),
				Mth.lerp(upperLimbAlpha, rightArmYaw, target.rightArmYaw),
				Mth.lerp(upperLimbAlpha, rightArmRoll, target.rightArmRoll),
				Mth.lerp(upperLimbAlpha * 0.88F, leftLegPitch, target.leftLegPitch),
				Mth.lerp(upperLimbAlpha * 0.88F, leftLegYaw, target.leftLegYaw),
				Mth.lerp(upperLimbAlpha * 0.88F, leftLegRoll, target.leftLegRoll),
				Mth.lerp(upperLimbAlpha * 0.88F, rightLegPitch, target.rightLegPitch),
				Mth.lerp(upperLimbAlpha * 0.88F, rightLegYaw, target.rightLegYaw),
				Mth.lerp(upperLimbAlpha * 0.88F, rightLegRoll, target.rightLegRoll),
				Mth.lerp(lowerLimbAlpha, leftElbowBend, target.leftElbowBend),
				Mth.lerp(lowerLimbAlpha, rightElbowBend, target.rightElbowBend),
				Mth.lerp(lowerLimbAlpha, leftKneeBend, target.leftKneeBend),
				Mth.lerp(lowerLimbAlpha, rightKneeBend, target.rightKneeBend)
		);
	}

	public FlightPoseFrame scale(float value) {
		return ZERO.lerp(this, value);
	}
}
