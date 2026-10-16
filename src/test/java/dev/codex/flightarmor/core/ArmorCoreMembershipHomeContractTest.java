package dev.codex.flightarmor.core;

import dev.codex.flightarmor.network.ArmorCoreSnapshotPayload;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static/offline contract for the distinction between core membership and an optional platform
 * home. It deliberately reads source only where the current controllers have no pure policy API.
 */
public final class ArmorCoreMembershipHomeContractTest {
	private static final Path PROJECT = Path.of(
			System.getProperty("flightArmor.projectDir", ".")).toAbsolutePath().normalize();

	private ArmorCoreMembershipHomeContractTest() {
	}

	public static void main(String[] args) throws Exception {
		onlyPhysicalPlatformCommandsRequireHome();
		corePanelCarriesAuthoritativeHomeAndActionEvidence();
		platformlessOnlineTargetSurvivesQuickSwitchDiscovery();
		platformlessIndependentSuitCanBecomeEscort();
		outgoingQuickSwitchSuitRetainsSafeHomeRequirement();
		periodicWornReconciliationIsOwnershipIdempotent();
		statusQueriesNeverMigrateSuitMembership();
		heldCorePlatformBindingPreflightsBeforePhysicalCommit();
		physicalBDeploysTheExactWornSuitBeforeCoreFormation();
		destructionAndExplicitForgetRemainSeparate();
	}

	private static void onlyPhysicalPlatformCommandsRequireHome() {
		require(ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_PLATFORM),
				"platform-origin recall must still validate its physical home");
		require(ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.LAUNCH_ESCORT_PLATFORM),
				"platform-origin escort launch must still validate its physical home");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.ADOPT_INDEPENDENT_ESCORT),
				"an online independent carrier must not need a platform merely to become an escort");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_INDEPENDENT),
				"independent carrier recall was coupled to an optional platform");
		require(!ArmorFormationPlanner.requiresPlatformHome(
						ArmorFormationPlanner.CommandType.RECALL_PRIMARY_ESCORT),
				"escort carrier recall was coupled to an optional platform");
	}

	private static void corePanelCarriesAuthoritativeHomeAndActionEvidence() throws IOException {
		Set<String> components = Arrays.stream(ArmorCoreSnapshotPayload.Entry.class.getRecordComponents())
				.map(RecordComponent::getName)
				.collect(Collectors.toSet());
		require(components.contains("hasHome") || components.contains("homeHealth"),
				"core snapshot entry must distinguish an optional home from a current position");
		require(components.contains("allowedActions") || components.contains("actionMask"),
				"core snapshot entry must carry server-authoritative allowed actions");

		String screen = source("src/client/java/dev/codex/flightarmor/client/core/ArmorCoreCommandScreen.java");
		require(screen.contains(".allowedActions()") || screen.contains(".actionMask()"),
				"core panel still guesses commands solely from legacy status");
		require(screen.contains(".hasHome()") || screen.contains(".homeHealth()"),
				"core panel still presents every online suit as if it had a platform home");

		String preview = source(
				"src/client/java/dev/codex/flightarmor/client/core/TacticalFormationPreviewRenderer.java");
		require(preview.contains(".hasHome()") || preview.contains(".homeHealth()"),
				"formation preview still draws a fabricated platform for platformless suits");
	}

	private static void platformlessOnlineTargetSurvivesQuickSwitchDiscovery() throws IOException {
		String controller = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		String discovery = method(controller,
				"private static SwitchTarget nextAvailableSwitchTarget(",
				"private static boolean isSwitchTargetAvailable(");
		require(discovery.contains("record.linkedSuitIds"),
				"quick-switch discovery must enumerate core suits, not platform homes");
		require(!discovery.contains("new ArrayList<>(record.bindings.values())"),
				"platformless online targets are still filtered out before runtime-source checks");
		String cycle = method(controller,
				"public static void handleQuickSwitch(",
				"/** Returns whether the exact suit selected by a status panel");
		require(cycle.contains("record.linkedSuitIds.size()"),
				"N-key switching still counts platform homes instead of core suits");
		require(!cycle.contains("ArmorCruiseController.hasActiveCruise(player)"),
				"N-key switching still rejects the exact online cruise target before source resolution");

		String targeted = method(controller,
				"public static boolean canTargetedQuickSwitch(",
				"public static TargetedSwitchResult beginTargetedQuickSwitch(");
		require(targeted.contains("record.linkedSuitIds.contains(targetSuitId)"),
				"targeted switch must authorize the target by core membership");
		require(!targeted.contains("target != null && isSwitchTargetAvailable"),
				"targeted switch still requires a target platform before checking an online carrier");

		String begin = method(controller,
				"public static TargetedSwitchResult beginTargetedQuickSwitch(",
				"public record TargetedSwitchResult");
		require(!begin.contains("currentBinding == null || target == null"),
				"an online target carrier must not inherit the outgoing suit's home requirement");
		require(!targeted.contains("ArmorCruiseController.hasActiveCruise(player)"),
				"targeted switch still rejects every request merely because an exact cruise target exists");
		require(begin.contains("resolveSwitchTarget(player, record, targetSuitId)"),
				"targeted switch does not resolve the exact runtime source before starting");
	}

	private static void platformlessIndependentSuitCanBecomeEscort() throws IOException {
		String escort = source(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java");
		String adoption = method(escort,
				"static EscortCommandResult adoptIndependentCarrier(",
				"static EscortCommandResult adoptIndependentCarrierForReturn(");
		String policy = method(escort,
				"static boolean isOptionalHomeComplete(",
				"static EscortCommandResult adoptIndependentCarrier(");
		require(policy.contains("present == 0 || present == 3"),
				"optional escort home must accept either no fields or one complete triple");
		require(adoption.contains("!isOptionalHomeComplete(homePlatformId, homeDimension, homePos)"),
				"independent-to-escort adoption does not reject a partially populated home");
	}

	private static void outgoingQuickSwitchSuitRetainsSafeHomeRequirement() throws IOException {
		String savedData = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreSavedData.java");
		int recordStart = savedData.indexOf("record QuickSwitchRecord(");
		require(recordStart >= 0, "missing persisted quick-switch record");
		String record = savedData.substring(recordStart);
		require(record.contains("PlatformBinding outgoingBinding"),
				"quick-switch persistence no longer records the outgoing suit's safe home");
		require(record.contains("outgoingBinding == null"),
				"outgoing quick-switch suit must keep a safe-home precondition until rollback is redesigned");
	}

	private static void periodicWornReconciliationIsOwnershipIdempotent() throws IOException {
		String controller = source(
				"src/main/java/dev/codex/flightarmor/platform/ChargingPlatformController.java");
		String ensure = method(controller,
				"public static OwnershipResult ensureOwnedEquippedSet(",
				"private static boolean hasOtherClaimedPart(");
		int fastPath = ensure.indexOf(
				"present.location() == ArmorOwnershipSavedData.Location.CARRIED");
		int mutation = ensure.indexOf("ArmorOwnershipSavedData.prepareCarriedClaim(");
		require(fastPath >= 0 && mutation > fastPath,
				"stable identified CARRIED armor must return before ownership revision mutation");
		require(ensure.substring(fastPath, mutation).contains("OwnershipResult.owned(requestedSuit)"),
				"stable worn ownership fast path does not return the existing suit identity");
	}

	private static void statusQueriesNeverMigrateSuitMembership() throws IOException {
		String controller = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		String action = method(controller,
				"public static void handleAction(",
				"private static boolean isMovementAction(");
		require(!action.contains("reconcileWornSuitAssociation(player)"),
				"a core command or REFRESH still mutates worn-suit membership before resolving status");
		String snapshot = method(controller,
				"private static void sendSnapshot(ServerPlayer player, String resultMessage,",
				"/**\n\t * Reconciles physical worn-suit evidence");
		require(!snapshot.contains("reconcileWornSuitAssociation(player)"),
				"sending a status snapshot still migrates a suit between core networks");
		String reconciliation = method(controller,
				"private static boolean reconcileWornSuitAssociation(",
				"private static ArmorCoreSnapshotPayload.Status legacyCoreStatus(");
		require(reconciliation.contains("associateUnlinkedSuit(")
						&& !reconciliation.contains("associateSuit("),
				"automatic equipment discovery must only claim a suit absent from every core network");
		String deploymentAssociation = method(controller,
				"public static boolean associateWornSuitForDeployment(",
				"/**\n\t * Executes the configured formation");
		require(deploymentAssociation.contains("record.linkedSuitIds.contains(suitId)")
						&& deploymentAssociation.contains("associateUnlinkedSuit(")
						&& !deploymentAssociation.contains("associateSuit("),
				"deploying a worn suit can silently migrate it away from another core network");

		String savedData = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreSavedData.java");
		String safeAssociation = method(savedData,
				"boolean associateUnlinkedSuit(",
				"/**\n\t * Removes membership");
		require(safeAssociation.contains("for (NetworkRecord network : record.networks.values())")
						&& safeAssociation.contains("network.linkedSuitIds.contains(suitId)"),
				"unlinked discovery does not fail closed when another core already owns membership");
	}

	private static void heldCorePlatformBindingPreflightsBeforePhysicalCommit() throws IOException {
		String controller = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		String binding = method(controller,
				"public static boolean bindHeldCoreToPlatform(",
				"public static boolean bindPlatformIfCoreEquipped(");
		int preflight = binding.indexOf("data.canBind(");
		int shrink = binding.indexOf("held.shrink(1)");
		int activate = binding.indexOf("data.setCore(player.getUUID(), activated)");
		require(preflight >= 0 && shrink > preflight && activate > preflight,
				"held-core platform binding mutates the item/core before capacity and identity preflight");
		require(binding.contains("data.setCore(player.getUUID(), previous)")
						&& binding.contains("held.grow(1)"),
				"an unexpected platform-binding commit failure cannot restore the old core and held item");
		require(binding.contains("isCoreChangeLocked(player)"),
				"platform binding can still change core topology during a suit movement transaction");
	}

	private static void physicalBDeploysTheExactWornSuitBeforeCoreFormation() throws IOException {
		String cruise = source(
				"src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseController.java");
		String request = method(cruise,
				"public static void handleRequest(ServerPlayer player)",
				"public static boolean recallPlatformSuit");
		int wornRoute = request.indexOf("if (ModItems.hasFullSet(player))");
		int coreFormation = request.indexOf("executeConfiguredRecallPlanForB(player)");
		require(wornRoute >= 0 && coreFormation > wornRoute,
				"B must resolve the complete physical suit before any configured formation");
		require(request.contains("deployWornSuit(player, ownership.suitId())"),
				"B must deploy the exact server-resolved worn suitId, including a linked secondary");

		String controller = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		String formationOnly = method(controller,
				"public static BKeyPlanResult executeConfiguredRecallPlanForB(",
				"public enum BKeyPlanResult");
		require(!formationOnly.contains("ModItems.hasFullSet(player)")
						&& !formationOnly.contains("ownership.suitId().equals(record.primarySuitId)"),
				"the empty-slot formation planner must not reinterpret an already resolved worn suit");
		require(controller.contains("public static void handleQuickSwitch("),
				"role switching must remain on N/the panel instead of overloading physical B");
	}

	private static void destructionAndExplicitForgetRemainSeparate() throws IOException {
		String initializer = source("src/main/java/dev/codex/flightarmor/FlightArmorMod.java");
		require(initializer.contains("ArmorCoreController.handleEntityUnload(entity, level)")
						&& initializer.contains("ArmorEscortController.handleEntityUnload(entity, level)"),
				"entity unload registration must dispatch both destruction and escort persistence");

		String controller = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		String forget = method(controller,
				"private static String forgetMissingSuit(",
				"private static boolean hasLoadedSuitPartEvidence(");
		require(forget.contains("ArmorCruiseController.hasActiveCruise(player)")
						&& forget.contains("ArmorEscortController.hasRecoverableSuit("),
				"explicit forget does not fail closed around persisted carrier locators");
		require(forget.contains("OwnershipLocation.LOOSE")
						&& forget.contains("!looseLevel.hasChunkAt(ownership.pos())"),
				"explicit forget can erase an item whose last loose chunk is unloaded");
		require(forget.contains("removeSuitFromActiveCore(")
						&& !forget.contains("removeConfirmedDestroyedSuit("),
				"panel forget must preserve physical ownership and inactive core networks");

		String lifecycle = method(controller,
				"public static void handleEntityUnload(",
				"private static void processConfirmedDestroyedSuits(");
		require(lifecycle.contains("ArmorCoreLifecyclePolicy.confirmsDroppedPartDestruction("),
				"ItemEntity unload bypasses the conservative destruction policy");
	}

	private static String source(String relative) throws IOException {
		Path path = PROJECT.resolve(relative).normalize();
		require(path.startsWith(PROJECT), "source path escaped project root");
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static String method(String source, String startMarker, String endMarker) {
		int start = source.indexOf(startMarker);
		require(start >= 0, "missing source marker: " + startMarker);
		int end = source.indexOf(endMarker, start + startMarker.length());
		require(end > start, "missing source marker: " + endMarker);
		return source.substring(start, end);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
