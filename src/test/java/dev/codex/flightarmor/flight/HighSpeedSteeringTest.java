package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.variant.VariantFlightProfile;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;

/** Pure regression coverage for controllable high-speed direction changes. */
public final class HighSpeedSteeringTest {
	private static final double EPSILON = 1.0E-8D;

	private HighSpeedSteeringTest() {
	}

	public static void main(String[] args) throws Exception {
		horizontalReverseNeverTurnsIntoAClimb();
		sharpTurnsEnterAndLeaveTheManeuverEnvelopeSmoothly();
		steeringAuthorityReturnsAsSpeedFalls();
		pitchConstraintPreservesCommandedSpeed();
		strafeKeysProvideHighSpeedSteeringAssist();
		quantizedLookSnapshotsAreDeterministic();
		sharedSteeringSnapshotIsWired();
		System.out.println("HighSpeedSteeringTest passed");
	}

	private static void horizontalReverseNeverTurnsIntoAClimb() {
		Vec3 target = new Vec3(0.0D, 0.0D, -1.0D);
		Vec3 direction = new Vec3(0.0D, 0.0D, 1.0D);
		double previousDot = direction.dot(target);
		for (int tick = 0; tick < 100 && direction.dot(target) < 1.0D - EPSILON; tick++) {
			direction = FlightPhysics.rotateToward(
					direction, target, Math.toRadians(2.0D));
			require(Math.abs(direction.y) < EPSILON,
					"horizontal U-turn gained a vertical component: " + direction);
			require(Math.abs(direction.length() - 1.0D) < EPSILON,
					"turning stopped returning a unit direction");
			double dot = direction.dot(target);
			require(dot + EPSILON >= previousDot,
					"U-turn moved away from its target heading");
			previousDot = dot;
		}
		require(direction.dot(target) > 1.0D - 1.0E-6D,
				"bounded horizontal U-turn did not converge");
	}

	private static void sharpTurnsEnterAndLeaveTheManeuverEnvelopeSmoothly() {
		VariantFlightProfile profile = SentinelFlightProfiles.OVERDRIVE_FLIGHT;
		double requested = profile.dive().targetSpeed();
		double gentle = FlightPhysics.highSpeedManeuverSpeedLimit(
				profile, requested, requested, Math.toRadians(4.0D));
		double medium = FlightPhysics.highSpeedManeuverSpeedLimit(
				profile, requested, requested, Math.toRadians(45.0D));
		double sharp = FlightPhysics.highSpeedManeuverSpeedLimit(
				profile, requested, requested, Math.toRadians(90.0D));
		require(Math.abs(gentle - requested) < EPSILON,
				"small steering corrections unexpectedly cut thrust");
		require(medium < gentle && sharp < medium,
				"maneuver speed limit is not monotonic with heading error");
		require(sharp >= profile.boost().targetSpeed()
						&& sharp < requested * 0.35D,
				"sharp-turn speed envelope is either abrupt or still uncontrollable: " + sharp);
	}

	private static void steeringAuthorityReturnsAsSpeedFalls() {
		VariantFlightProfile profile = SentinelFlightProfiles.OVERDRIVE_FLIGHT;
		VariantFlightProfile.Mode mode = profile.dive();
		double error = Math.toRadians(90.0D);
		double fullSpeed = FlightPhysics.highSpeedTurnRate(
				profile, mode, mode.targetSpeed(), error);
		double maneuverSpeed = FlightPhysics.highSpeedTurnRate(
				profile, mode, 12.0D, error);
		require(fullSpeed > mode.turnRateDegreesPerSecond(),
				"sharp-turn assist did not improve full-speed control");
		require(maneuverSpeed > fullSpeed
						&& maneuverSpeed <= profile.boost().turnRateDegreesPerSecond(),
				"steering authority did not recover continuously while braking");
	}

	private static void pitchConstraintPreservesCommandedSpeed() {
		VariantFlightProfile.Mode mode =
				SentinelFlightProfiles.OVERDRIVE_FLIGHT.dive();
		double commanded = mode.targetSpeed();
		Vec3 target = FlightPhysics.constrainedHighSpeedTarget(
				new Vec3(0.0D, 1.0D, 0.0D),
				new Vec3(0.0D, 0.0D, 1.0D), commanded, mode);
		require(Math.abs(target.length() - commanded) < EPSILON,
				"pitch clamp incorrectly shortened total commanded speed");
		require(Math.abs(target.y - mode.maxVerticalSpeed()) < EPSILON,
				"pitch clamp exceeded or failed to reach its vertical limit");
		require(target.horizontalDistance() > 0.0D,
				"vertical look lost the stabilizing forward component");
	}

	private static void strafeKeysProvideHighSpeedSteeringAssist() {
		Vec3 forward = new Vec3(0.0D, 0.0D, 1.0D);
		Vec3 right = FlightPhysics.rotateAroundY(forward, Math.toRadians(18.0D));
		Vec3 left = FlightPhysics.rotateAroundY(forward, Math.toRadians(-18.0D));
		require(right.x > 0.0D && left.x < 0.0D
						&& Math.abs(right.length() - 1.0D) < EPSILON
						&& Math.abs(left.length() - 1.0D) < EPSILON,
				"A/D high-speed steering assist is reversed or changes speed");
	}

	private static void quantizedLookSnapshotsAreDeterministic() {
		Vec3 first = FlightPhysics.lookDirection(137.25F, -28.5F);
		Vec3 second = FlightPhysics.lookDirection(137.25F, -28.5F);
		require(first.distanceToSqr(second) < EPSILON
						&& Math.abs(first.length() - 1.0D) < 1.0E-4D,
				"identical client/server steering snapshots produced different directions");
	}

	private static void sharedSteeringSnapshotIsWired() throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String payload = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/network/FlightInputPayload.java"));
		String client = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java"));
		String controller = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/flight/FlightController.java"));
		require(payload.contains("float steeringYaw, float steeringPitch")
						&& payload.contains("writeFloat(payload.steeringYaw())")
						&& payload.contains("buffer.readFloat()"),
				"flight input protocol lost the shared steering snapshot");
		require(client.contains("quantizeSteeringAngle")
						&& client.contains("FlightPhysics.lookDirection(steeringYaw, steeringPitch)")
						&& client.contains("current.steeringYaw(), current.steeringPitch()"),
				"client prediction and C2S input no longer consume the same quantized direction");
		require(controller.contains("state.steeringYaw(), state.steeringPitch()")
						&& controller.contains("if (!state.diving() || player.horizontalCollision")
						&& !controller.contains(
								"player, state.diving(), state.boosting(), state.movingForward()"),
				"server high-speed physics reverted to stale local look or per-tick forced velocity");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
