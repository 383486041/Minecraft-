package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.variant.ArmorVariant;

public final class RemoteArmorCommandPolicyTest {
	private RemoteArmorCommandPolicyTest() {
	}

	public static void main(String[] args) {
		require(RemoteArmorCommandPolicy.responseTicks(0.0D) == 6, "near command latency");
		require(RemoteArmorCommandPolicy.responseTicks(16.0D) == 8, "middle command latency");
		require(RemoteArmorCommandPolicy.responseTicks(48.0D) == 12, "edge command latency");
		require(RemoteArmorCommandPolicy.responseTicks(Double.NaN) == 12, "invalid distance fails slow");
		var vanguard = RemoteArmorCommandPolicy.ultimateProfile(ArmorVariant.VANGUARD);
		var swift = RemoteArmorCommandPolicy.ultimateProfile(ArmorVariant.SWIFT);
		var sentinel = RemoteArmorCommandPolicy.ultimateProfile(ArmorVariant.SENTINEL);
		require(vanguard.damage() > sentinel.damage() && sentinel.damage() > swift.damage(),
				"variant damage identities");
		require(swift.cooldownTicks() < sentinel.cooldownTicks()
						&& sentinel.cooldownTicks() < vanguard.cooldownTicks(),
				"variant cooldown identities");
		var formation = RemoteFollowPolicy.plan(4.0D, 0.2D, false, true, true);
		var combat = RemoteFollowPolicy.plan(4.0D, 0.2D, true, true, true);
		var catchUp = RemoteFollowPolicy.plan(18.0D, 2.5D, false, true, true);
		var occluded = RemoteFollowPolicy.plan(5.0D, 0.2D, false, false, true);
		var lost = RemoteFollowPolicy.plan(47.0D, 0.0D, false, true, true);
		var crossDimension = RemoteFollowPolicy.plan(2.0D, 0.0D, false, true, false);
		require(formation.layer() == RemoteFollowPolicy.Layer.FORMATION,
				"near suit uses formation layer");
		require(combat.layer() == RemoteFollowPolicy.Layer.COMBAT_DETACHED,
				"combat temporarily detaches from formation");
		require(catchUp.layer() == RemoteFollowPolicy.Layer.CATCH_UP
						&& catchUp.maxSpeed() > formation.maxSpeed()
						&& catchUp.predictionTicks() > formation.predictionTicks(),
				"far suit predicts farther and catches up faster");
		require(occluded.layer() == RemoteFollowPolicy.Layer.CATCH_UP
						&& occluded.verticalClearance() > catchUp.verticalClearance(),
				"occluded route gains extra vertical clearance");
		require(lost.layer() == RemoteFollowPolicy.Layer.LOST && !lost.controlsMovement(),
				"near command boundary fails closed");
		require(crossDimension.layer() == RemoteFollowPolicy.Layer.LOST
						&& !crossDimension.controlsMovement(),
				"cross-dimension follow never teleports");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
