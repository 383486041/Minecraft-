package dev.codex.flightarmor.client.animation;

import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.codex.flightarmor.FlightArmorMod;

/** 注册 playerAnimator 与 bendy-lib 飞行关节层。 */
public final class FlightJointAnimationSetup {
	private static final int FLIGHT_POSE_PRIORITY = 1600;

	private FlightJointAnimationSetup() {
	}

	public static void initialize() {
		PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
				FlightArmorMod.id("flight_joint_validation"),
				FLIGHT_POSE_PRIORITY,
				FlightJointAnimation::new
		);
		FlightArmorMod.LOGGER.info("playerAnimator + bendy-lib 飞行肘膝层已注册");
	}
}
