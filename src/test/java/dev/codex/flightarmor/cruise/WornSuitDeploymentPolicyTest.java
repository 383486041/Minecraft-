package dev.codex.flightarmor.cruise;

import java.util.UUID;

/** Offline regression coverage for exact-suit B-key routing with multiple physical suits. */
public final class WornSuitDeploymentPolicyTest {
	private static final UUID SUIT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SUIT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

	private WornSuitDeploymentPolicyTest() {
	}

	public static void main(String[] args) {
		require(route(false, null, false, null, false)
						== WornSuitDeploymentPolicy.Route.CONTINUE_CORE_PLAN,
				"empty armor slots must continue into the configured core plan");
		require(route(true, SUIT_A, false, null, false)
						== WornSuitDeploymentPolicy.Route.DEPLOY_AS_INDEPENDENT,
				"the first worn suit must become the owner independent carrier");
		require(route(true, SUIT_B, true, SUIT_A, true)
						== WornSuitDeploymentPolicy.Route.DEPLOY_AS_ESCORT,
				"a second worn suit must not recall or replace the existing independent suit");
		require(route(true, SUIT_B, true, null, true)
						== WornSuitDeploymentPolicy.Route.DEPLOY_AS_ESCORT,
				"a legacy locator with unknown suit identity must fail closed into a distinct escort");
		require(route(true, SUIT_A, true, SUIT_A, true)
						== WornSuitDeploymentPolicy.Route.REJECT_DUPLICATE_PHYSICAL_STATE,
				"the same suit cannot be both worn and held by a carrier");
		require(route(true, SUIT_B, true, SUIT_A, false)
						== WornSuitDeploymentPolicy.Route.REJECT_UNVERIFIED_INDEPENDENT_STATE,
				"an unverified independent physical record must fail closed");
		System.out.println("WornSuitDeploymentPolicyTest passed");
	}

	private static WornSuitDeploymentPolicy.Route route(boolean fullSetWorn, UUID wornSuitId,
			boolean carrierPresent, UUID carrierSuitId, boolean carrierStable) {
		return WornSuitDeploymentPolicy.decide(new WornSuitDeploymentPolicy.Evidence(
				fullSetWorn, wornSuitId, carrierPresent, carrierSuitId, carrierStable));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
