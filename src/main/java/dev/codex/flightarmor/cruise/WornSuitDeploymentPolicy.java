package dev.codex.flightarmor.cruise;

import java.util.UUID;

/**
 * Pure B-key routing policy for a physically worn complete suit.
 *
 * <p>The owner-keyed independent controller deliberately remains a single primary carrier.
 * A different worn suit is therefore released into the suit-keyed escort controller instead of
 * recalling, replacing, or mutating the already-active independent carrier.</p>
 */
public final class WornSuitDeploymentPolicy {
	private WornSuitDeploymentPolicy() {
	}

	public static Route decide(Evidence evidence) {
		if (evidence == null || !evidence.fullSetWorn() || evidence.wornSuitId() == null) {
			return Route.CONTINUE_CORE_PLAN;
		}
		if (!evidence.independentCarrierPresent()) {
			return Route.DEPLOY_AS_INDEPENDENT;
		}
		if (evidence.independentSuitId() != null
				&& evidence.independentSuitId().equals(evidence.wornSuitId())) {
			return Route.REJECT_DUPLICATE_PHYSICAL_STATE;
		}
		if (!evidence.independentCarrierIdentityIsolated()) {
			return Route.REJECT_UNVERIFIED_INDEPENDENT_STATE;
		}
		return Route.DEPLOY_AS_ESCORT;
	}

	public enum Route {
		CONTINUE_CORE_PLAN,
		DEPLOY_AS_INDEPENDENT,
		DEPLOY_AS_ESCORT,
		REJECT_DUPLICATE_PHYSICAL_STATE,
		REJECT_UNVERIFIED_INDEPENDENT_STATE
	}

	public record Evidence(
			boolean fullSetWorn,
			UUID wornSuitId,
			boolean independentCarrierPresent,
			UUID independentSuitId,
			boolean independentCarrierIdentityIsolated
	) {
	}
}
