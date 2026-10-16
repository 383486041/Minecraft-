package dev.codex.flightarmor.combat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression contract for owner/worn and physical carrier combat isolation. */
public final class ArmorCombatLaneIsolationContractTest {
	private ArmorCombatLaneIsolationContractTest() {
	}

	public static void main(String[] args) throws IOException {
		Path project = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String combat = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java"));
		String missile = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/combat/ArmorHomingMissileEntity.java"));
		String escort = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java"));
		String independent = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseCombatController.java"));

		require(combat.contains("record CruiseLaneKey(UUID ownerId, UUID suitId, UUID carrierId)"),
				"carrier resources must be keyed by owner+suit+physical carrier");
		String cruiseEntries = section(combat,
				"public static CruiseWristResult tryFireCruiseWrist",
				"private static ArmorVariant cruiseVariant");
		require(!cruiseEntries.contains("stateFor(owner)"),
				"no autonomous weapon may use the persistent worn-player lane");
		require(count(cruiseEntries, "cruiseStateFor(owner, carrier)") >= 8,
				"every autonomous resource/cooldown weapon entry must use its carrier lane");
		require(section(combat,
				"public static CruiseWeaponResult tryFireSentinelDronePulse",
				"private static CombatRaycast.Result sentinelPodRay")
						.contains("cruiseStateFor(owner, carrier)"),
				"Sentinel detached-pod pulse must not fall back to the owner/worn lane");
		require(combat.contains("tickCruiseResources(ServerPlayer owner, Entity carrier)"),
				"resource recovery must identify the exact carrier");
		require(!section(combat, "public static void cancel(ServerPlayer player",
				"/**").contains("cancelDelayedCruiseAttacksByOwner"),
				"cancelling the worn player lane must not cancel autonomous suits");
		require(missile.contains("discardByCarrier(UUID suitId, UUID carrierId, long carrierRevision)"),
				"missile cancellation must support exact carrier incarnation");
		require(independent.contains("discardByCarrier("),
				"independent suit invalidation must not discard another suit's missiles");
		require(!section(independent, "public static void cancelByOwnerId(UUID ownerId)",
				"public static boolean isGuardEnabled").contains("discardByOwner("),
				"single independent-suit transition must not discard another carrier's missiles");
		require(section(combat, "public static void remove(ServerPlayer player)",
				"public static void cancelDelayedCruiseAttacks")
						.contains("cancelDelayedCruiseAttacksByOwner(player.getUUID())")
						&& section(combat,
								"private static void cancelDelayedCruiseAttacksByOwner(UUID ownerId)",
								"private static void handleServerStopping")
								.contains("discardByOwner(ownerId)"),
				"disconnect-wide cleanup must still discard every missile owned by the player");
		require(escort.contains("state.carrier.getUUID(), state.carrierRevision"),
				"escort invalidation must identify exact carrier and revision");
		String blade = section(combat, "private static void fireBladeSlash(",
				"private static boolean bladeTargetOccluded(");
		for (String marker : java.util.List.of(
				"state.activeLeftHand = state.nextLeftHand",
				"state.nextLeftHand = !state.nextLeftHand",
				"ArmorSemanticAnchor.palm(leftHand)",
				"ArmorAttackType.ENERGY_BLADE_SLASH, variant, leftHand, origin",
				"boolean supportLeft = !leftHand",
				"ArmorSemanticAnchor.palm(supportLeft)")) {
			require(blade.contains(marker),
					"dual-arm blade server synchronization lost marker: " + marker);
		}
		System.out.println("ArmorCombatLaneIsolationContractTest passed");
	}

	private static int count(String source, String needle) {
		int result = 0;
		for (int at = 0; (at = source.indexOf(needle, at)) >= 0; at += needle.length()) result++;
		return result;
	}

	private static String section(String source, String startMarker, String endMarker) {
		int start = source.indexOf(startMarker);
		int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());
		require(start >= 0 && end > start,
				"missing source contract markers: " + startMarker + " -> " + endMarker);
		return source.substring(start, end);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
