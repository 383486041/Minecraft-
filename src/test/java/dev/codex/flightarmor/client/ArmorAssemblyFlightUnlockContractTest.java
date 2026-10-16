package dev.codex.flightarmor.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards the takeoff-without-thrust regression. A stale assembly snapshot may retain its
 * server-side animating bit, but it must not keep normal WASD flight locked after the authored
 * client timeline has completed.
 */
public final class ArmorAssemblyFlightUnlockContractTest {
	private ArmorAssemblyFlightUnlockContractTest() {
	}

	public static void main(String[] args) throws Exception {
		Path project = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String assemblyClient = Files.readString(project.resolve(
				"src/client/java/dev/codex/flightarmor/client/assembly/ArmorAssemblyClient.java"),
				StandardCharsets.UTF_8);
		String flightClient = Files.readString(project.resolve(
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java"),
				StandardCharsets.UTF_8);
		String flightController = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/flight/FlightController.java"),
				StandardCharsets.UTF_8);

		require(assemblyClient.contains(
						"return state != null && state.locksFlight && isVisualAnimating(state);"),
				"completed assembly timelines must release the normal flight input lock");
		require(!assemblyClient.contains(
						"state.active && state.animating && state.locksFlight"),
				"raw packet animation flags must not become a permanent WASD lock");
		require(assemblyClient.contains("if (lastDimension != null && !lastDimension.equals(current))")
						&& assemblyClient.contains("STATES.clear();")
						&& assemblyClient.contains("lastRequestGameTime = Long.MIN_VALUE / 2L;")
						&& assemblyClient.contains("capturedOperationTick = Integer.MIN_VALUE;")
						&& assemblyClient.contains("if (current == null) return;"),
				"dimension clock changes must clear stale visual locks and local capture throttles");
		require(flightClient.contains(
						"boolean coreFlightReady = flightGear && !armorAssembling;")
						&& flightClient.contains(
						"boolean bootOnlyAssemblyHover = active && bootAssemblyHoverReady && !coreFlightReady;")
						&& flightClient.contains(
						"boolean controlsUnlocked = active && !bootOnlyAssemblyHover && client.screen == null;"),
				"completed full armor does not leave the client in boot-only hover");
		require(flightClient.contains(
						"current.equals(lastSent) && player.tickCount - lastPacketTick < 2"),
				"held WASD input is not periodically resent after the server unlock edge");
		require(flightController.contains(
						"&& !canUseRegularFlightGear(player) && isBootHoverAuthorized(player, previous)")
						&& flightController.contains(
						"boolean controlsUnlocked = !bootAssemblyOverride;")
						&& flightController.contains(
						"controlsUnlocked && payload.movingForward()"),
				"server boot hover override does not downgrade on the first regular-flight input");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
