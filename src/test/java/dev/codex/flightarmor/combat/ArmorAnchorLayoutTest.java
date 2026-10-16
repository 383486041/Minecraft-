package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline regression gate for semantic armor-space anchors and migrated call sites. */
public final class ArmorAnchorLayoutTest {
	private ArmorAnchorLayoutTest() {
	}

	public static void main(String[] args) throws Exception {
		leftAndRightAnchorsMirrorForEveryProfile();
		variantsAndPosesRemainDistinct();
		missileLanesDoNotCollapse();
		firstPersonUsesTheSameSemanticSides();
		legacyHardCodedEmittersStayRemoved();
	}

	private static void leftAndRightAnchorsMirrorForEveryProfile() {
		for (ArmorVariant variant : ArmorVariant.values()) {
			for (ArmorAnchorPose pose : ArmorAnchorPose.values()) {
				assertMirror(variant, pose, ArmorSemanticAnchor.LEFT_PALM,
						ArmorSemanticAnchor.RIGHT_PALM);
				assertMirror(variant, pose, ArmorSemanticAnchor.LEFT_BOOT_SOLE,
						ArmorSemanticAnchor.RIGHT_BOOT_SOLE);
				assertMirror(variant, pose, ArmorSemanticAnchor.LEFT_MISSILE_RACK,
						ArmorSemanticAnchor.RIGHT_MISSILE_RACK);
				assertMirror(variant, pose, ArmorSemanticAnchor.LEFT_DRONE_POD,
						ArmorSemanticAnchor.RIGHT_DRONE_POD);
				assertMirror(variant, pose, ArmorSemanticAnchor.LEFT_WING_CANNON,
						ArmorSemanticAnchor.RIGHT_WING_CANNON);
				ArmorAnchorLayout.LocalOffset core = ArmorAnchorLayout.resolve(
						variant, pose, ArmorSemanticAnchor.CHEST_CORE);
				require(close(core.right(), 0.0D), "chest core must remain centered");
			}
		}
	}

	private static void variantsAndPosesRemainDistinct() {
		double swiftPalm = Math.abs(ArmorAnchorLayout.resolve(ArmorVariant.SWIFT,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_PALM).right());
		double sentinelPalm = Math.abs(ArmorAnchorLayout.resolve(ArmorVariant.SENTINEL,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_PALM).right());
		require(sentinelPalm > swiftPalm + 0.05D,
				"heavy and swift armor palm anchors collapsed to one width");
		double standingCore = ArmorAnchorLayout.resolve(ArmorVariant.VANGUARD,
				ArmorAnchorPose.STANDING, ArmorSemanticAnchor.CHEST_CORE).up();
		double hoverCore = ArmorAnchorLayout.resolve(ArmorVariant.VANGUARD,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.CHEST_CORE).up();
		double swimmingCore = ArmorAnchorLayout.resolve(ArmorVariant.VANGUARD,
				ArmorAnchorPose.SWIMMING, ArmorSemanticAnchor.CHEST_CORE).up();
		require(hoverCore > standingCore && swimmingCore < standingCore,
				"pose-specific chest anchors are not ordered around the standing pose");
		ArmorAnchorLayout.LocalOffset pod = ArmorAnchorLayout.resolve(ArmorVariant.SENTINEL,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_DRONE_POD);
		require(pod.forward() < 0.0D && Math.abs(pod.right()) > sentinelPalm,
				"Sentinel drone pod must remain behind and outside the arm line");
	}

	private static void missileLanesDoNotCollapse() {
		ArmorAnchorLayout.LocalOffset first = ArmorAnchorLayout.resolve(ArmorVariant.SWIFT,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_MISSILE_RACK, 0);
		ArmorAnchorLayout.LocalOffset third = ArmorAnchorLayout.resolve(ArmorVariant.SWIFT,
				ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_MISSILE_RACK, 1);
		require(close(third.up() - first.up(), 0.10D),
				"missile rack lanes must have deterministic vertical separation");
	}

	private static void firstPersonUsesTheSameSemanticSides() {
		ArmorAnchorLayout.LocalOffset left = ArmorAnchorLayout.resolveFirstPerson(
				ArmorVariant.SENTINEL, ArmorAnchorPose.HOVER, ArmorSemanticAnchor.LEFT_PALM);
		ArmorAnchorLayout.LocalOffset right = ArmorAnchorLayout.resolveFirstPerson(
				ArmorVariant.SENTINEL, ArmorAnchorPose.HOVER, ArmorSemanticAnchor.RIGHT_PALM);
		require(left.right() < 0.0D && right.right() > 0.0D,
				"first-person palm side convention differs from world-space anchors");
		require(close(left.right(), -right.right()) && close(left.up(), right.up())
				&& close(left.forward(), right.forward()),
				"first-person palms must be mirrored semantic anchors");
	}

	private static void legacyHardCodedEmittersStayRemoved() throws IOException {
		assertSource("src/main/java/dev/codex/flightarmor/combat/CombatRaycast.java",
				"ArmorAnchorResolver.resolve", "approximateWristOrigin", "approximateCoreOrigin");
		assertSource("src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java",
				"ArmorSemanticAnchor.missileRack", "missileOrigin(");
		assertSource("src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseCombatController.java",
				"tryFireCruiseWrist", "wristOrigin(");
		assertSource("src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java",
				"tryFireCruiseCore", "wristOrigins(", "coreOrigin(");
		assertSource("src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxRenderer.java",
				"ArmorAnchorResolver.resolveFirstPerson", "camera.add(forward.scale(0.46D))");
		assertSource("src/client/java/dev/codex/flightarmor/client/render/SwiftFlightTrailRenderer.java",
				"ArmorAnchorResolver.resolveFromBasis", "case LEFT_BOOT ->");
	}

	private static void assertMirror(ArmorVariant variant, ArmorAnchorPose pose,
			ArmorSemanticAnchor leftAnchor, ArmorSemanticAnchor rightAnchor) {
		ArmorAnchorLayout.LocalOffset left = ArmorAnchorLayout.resolve(variant, pose, leftAnchor);
		ArmorAnchorLayout.LocalOffset right = ArmorAnchorLayout.resolve(variant, pose, rightAnchor);
		require(left.right() < 0.0D && right.right() > 0.0D,
				"anchor sides are not signed: " + variant + "/" + pose + "/" + leftAnchor);
		require(close(left.right(), -right.right()) && close(left.up(), right.up())
				&& close(left.forward(), right.forward()),
				"anchor pair is not mirrored: " + variant + "/" + pose + "/" + leftAnchor);
	}

	private static void assertSource(String path, String required, String... forbidden)
			throws IOException {
		String source = Files.readString(Path.of(path));
		require(source.contains(required), "missing unified anchor call in " + path);
		for (String legacy : forbidden) {
			require(!source.contains(legacy), "legacy hard-coded anchor remains in " + path + ": " + legacy);
		}
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) <= 1.0E-9D;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
