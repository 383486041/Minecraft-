package dev.codex.flightarmor.cruise;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression contract for exact-suit routing and lossless carrier rollback. */
public final class WornSuitDeploymentContractTest {
	private WornSuitDeploymentContractTest() {
	}

	public static void main(String[] args) throws IOException {
		Path project = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String cruise = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseController.java"));
		String escort = Files.readString(project.resolve(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java"));

		String request = section(cruise,
				"public static void handleRequest",
				"public static boolean recallPlatformSuit");
		int worn = request.indexOf("if (ModItems.hasFullSet(player))");
		int partial = request.indexOf("if (!allArmorSlotsEmpty(player))");
		int local = request.indexOf("tryHandleCompleteInventorySet");
		int core = request.indexOf("executeConfiguredRecallPlanForB");
		int fallback = request.indexOf("CruiseState state");
		require(worn >= 0 && worn < partial && partial < local
						&& local < core && core < fallback,
				"B must resolve full/partial/local physical armor before core and owner fallbacks");
		require(request.contains("deployWornSuit(player, ownership.suitId())"),
				"B must use the exact server-resolved suitId");
		require(request.substring(partial, core).contains("ArmorAssemblyController.handleRequest(player)"),
				"partial worn armor must assemble locally instead of requesting clear remote slots");
		require(request.substring(partial, core).contains("activeCarrierSuitIds(player)")
						&& request.substring(partial, core)
								.contains("LocalAssemblyResult.HANDLED"),
				"empty slots plus a complete local second suit must not recall the existing carrier");

		String exactDeployment = section(cruise,
				"public static WornDeploymentResult deployWornSuit",
				"/** 独立巡航状态机");
		require(exactDeployment.contains("ArmorEscortController.deployWornSuit("),
				"a second worn suit must hand off to the suit-keyed escort controller");
		require(!exactDeployment.contains("independent.phase() =="),
				"an unrelated independent carrier phase must not globally lock the worn suit");

		String independentDeployment = section(cruise,
				"private static WornDeploymentResult deployFromPlayer",
				"private static void beginRecallOrPhasing");
		require(independentDeployment.contains("rollbackNewCruiseLedger("),
				"new independent carrier failures must revert their ledger admission");
		require(!independentDeployment.contains("rollbackSuitToCruising("),
				"new carrier rollback must not repeat CARRIED to CRUISE");
		String ledgerRollback = section(cruise,
				"private static void rollbackNewCruiseLedger",
				"/**");
		require(ledgerRollback.contains("markCruisingSuitCarried("),
				"new carrier rollback must perform CRUISE to CARRIED");

		String deathCapture = section(cruise,
				"public static void captureWornSetForDeath",
				"public static void handleRespawn");
		require(deathCapture.contains("captureWornSuitForDeath(")
						&& deathCapture.contains("handleDeath(player);"),
				"death must preserve secondary worn armor before marking the primary owner down");

		String wornEscort = section(escort,
				"public static SwitchDeploymentResult deployWornSuit",
				"Moves the just-retracted worn suit");
		require(wornEscort.contains("rollbackWornDeployment(")
						&& wornEscort.contains("rollbackCarriedSuitCruising("),
				"worn escort failures must verify physical and ledger rollback");
		require(wornEscort.contains("freeInventorySlots.size() < foreignSlots.size()"),
				"rollback must reserve destinations before clearing carrier items");
		System.out.println("WornSuitDeploymentContractTest passed");
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
