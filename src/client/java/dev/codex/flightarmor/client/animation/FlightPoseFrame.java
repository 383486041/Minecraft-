package dev.codex.flightarmor.client.animation;

import net.minecraft.util.Mth;

/** 一帧完整的飞行人物姿态；所有旋转单位均为弧度。 */
public record FlightPoseFrame(
		float bodyPitch, float bodyRoll, float headPitchOffset, float headRollOffset,
		float leftArmPitch, float leftArmYaw, float leftArmRoll,
		float rightArmPitch, float rightArmYaw, float rightArmRoll,
		float leftLegPitch, float leftLegYaw, float leftLegRoll,
		float rightLegPitch, float rightLegYaw, float rightLegRoll,
		float leftElbowBend, float rightElbowBend,
		float leftKneeBend, float rightKneeBend
) {
	public static final FlightPoseFrame ZERO = new FlightPoseFrame(
			0, 0, 0, 0,
			0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0,
			0, 0, 0, 0
	);

	public FlightPoseFrame lerp(FlightPoseFrame target, float alpha) {
		return new FlightPoseFrame(
				Mth.lerp(alpha, bodyPitch, target.bodyPitch), Mth.lerp(alpha, bodyRoll, target.bodyRoll),
				Mth.lerp(alpha, headPitchOffset, target.headPitchOffset), Mth.lerp(alpha, headRollOffset, target.headRollOffset),
				Mth.lerp(alpha, leftArmPitch, target.leftArmPitch), Mth.lerp(alpha, leftArmYaw, target.leftArmYaw), Mth.lerp(alpha, leftArmRoll, target.leftArmRoll),
				Mth.lerp(alpha, rightArmPitch, target.rightArmPitch), Mth.lerp(alpha, rightArmYaw, target.rightArmYaw), Mth.lerp(alpha, rightArmRoll, target.rightArmRoll),
				Mth.lerp(alpha, leftLegPitch, target.leftLegPitch), Mth.lerp(alpha, leftLegYaw, target.leftLegYaw), Mth.lerp(alpha, leftLegRoll, target.leftLegRoll),
				Mth.lerp(alpha, rightLegPitch, target.rightLegPitch), Mth.lerp(alpha, rightLegYaw, target.rightLegYaw), Mth.lerp(alpha, rightLegRoll, target.rightLegRoll),
				Mth.lerp(alpha, leftElbowBend, target.leftElbowBend), Mth.lerp(alpha, rightElbowBend, target.rightElbowBend),
				Mth.lerp(alpha, leftKneeBend, target.leftKneeBend), Mth.lerp(alpha, rightKneeBend, target.rightKneeBend)
		);
	}

	public FlightPoseFrame scale(float value) {
		return ZERO.lerp(this, value);
	}
}
