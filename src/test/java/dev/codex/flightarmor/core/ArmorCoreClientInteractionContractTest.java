package dev.codex.flightarmor.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Static/offline contract for the armor-core client. The screen may present server evidence, but
 * it must not recreate suit lifecycle policy or keep a second, less-authoritative command UI.
 */
public final class ArmorCoreClientInteractionContractTest {
	private static final Path PROJECT = Path.of(
			System.getProperty("flightArmor.projectDir", ".")).toAbsolutePath().normalize();

	private ArmorCoreClientInteractionContractTest() {
	}

	public static void main(String[] args) throws Exception {
		onlyTheAuthoritativeCommandScreenRemains();
		perSuitButtonsFollowServerPermissions();
		corePlanDoesNotInheritListSelection();
		pendingAndReceiptTargetsRemainStable();
		pendingReceiptTimeoutUnlocksWithoutRetrying();
		rolesAndLocationsAreExplicit();
	}

	private static void onlyTheAuthoritativeCommandScreenRemains() throws IOException {
		String initializer = source(
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java");
		require(initializer.contains(
						"MenuScreens.register(ModCoreContent.ARMOR_CORE_MENU, ArmorCoreCommandScreen::new)"),
				"armor core menu is not registered to the authoritative command screen");
		require(!initializer.contains("ArmorCoreScreen::new"),
				"legacy screen can bypass the authoritative command UI");
		require(!Files.exists(PROJECT.resolve(
						"src/client/java/dev/codex/flightarmor/client/core/ArmorCoreScreen.java")),
				"legacy armor core screen still duplicates unsafe command logic");
	}

	private static void perSuitButtonsFollowServerPermissions() throws IOException {
		String screen = commandScreen();
		require(screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_SET_PRIMARY)")
						&& screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR)")
						&& screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_LAUNCH_ESCORT)")
						&& screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_RETURN_HOME)")
						&& screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_FORGET_MISSING)"),
				"per-suit controls are not driven by the server action mask");
		require(screen.contains(
						"selected.allows(SuitRuntimeSnapshot.AllowedAction.CAN_DEPLOY_WORN)")
						&& screen.contains("ArmorCoreAction.DEPLOY_SELECTED_WORN"),
				"core panel cannot explicitly release the selected worn suit");
		require(screen.contains("primaryButton.visible = canSetPrimary")
						&& screen.contains("recallButton.visible = canRecallAndWear")
						&& screen.contains("escortButton.visible = canLaunchEscort")
						&& screen.contains("returnButton.visible = canReturnHome || returning")
						&& screen.contains("forgetButton.visible = canForgetMissing"),
				"screen still exposes actions that the selected suit is not authorized to execute");
	}

	private static void corePlanDoesNotInheritListSelection() throws IOException {
		String screen = commandScreen();
		require(screen.contains(
						"ArmorCoreAction.RECALL_ALL, null, \"正在调度全部战甲…\""),
				"core-level recall plan still inherits the currently highlighted suit");
		require(screen.contains("\"执行编队方案\"")
						&& screen.contains("完整穿戴时 B 会释放当前战甲"),
				"panel still implies that B always executes the formation plan");
		String reason = method(screen,
				"private static String recallAllReason(",
				"private static String statusDescription(");
		require(!reason.contains("ArmorCoreSnapshotPayload.Status."),
				"client still recreates server lifecycle policy for the global plan");
	}

	private static void pendingAndReceiptTargetsRemainStable() throws IOException {
		String screen = commandScreen();
		require(screen.contains("private String awaitingTargetName")
						&& screen.contains("目标：\" + awaitingTargetName"),
				"changing list selection can make an in-flight command appear to target another suit");
		require(screen.contains("snapshot.resultClientSequence() == awaitingSequence")
						&& screen.contains("snapshot.resultAction() == awaitingAction")
						&& screen.contains("Objects.equals(snapshot.resultSuitId(), awaitingSuit)"),
				"screen can consume a receipt belonging to another command or suit");
		require(screen.contains("noneMatch(entry -> entry.suitId().equals(snapshot.resultSuitId()))")
						&& screen.contains("\"已移除记录的结果\""),
				"successful forget receipt disappears as soon as its target entry is removed");
	}

	private static void pendingReceiptTimeoutUnlocksWithoutRetrying() throws IOException {
		String screen = commandScreen();
		require(screen.contains("RECEIPT_TIMEOUT_NANOS")
						&& screen.contains("expireAwaiting();")
						&& screen.contains("clearAwaiting();")
						&& screen.contains("请点击刷新"),
				"missing receipt can leave every panel control locked forever");
		String expiry = method(screen,
				"private void expireAwaiting()",
				"private void clearAwaiting()");
		require(!expiry.contains("ArmorCoreClient.send("),
				"client timeout must not automatically retry a suit movement command");
	}

	private static void rolesAndLocationsAreExplicit() throws IOException {
		String screen = commandScreen();
		require(screen.contains("\"主战甲\" : \"副战甲\"")
						&& screen.contains("\"已设返回平台\" : \"无返回平台\""),
				"multi-suit rows do not explicitly distinguish role and platform availability");
		require(screen.contains("dimensionLabel(entry) + \" · \" + distanceLabel(entry)"),
				"client still labels every suit location as a platform location");
		require(screen.contains("\"关联 \" + entries.size() + \" 套 · 可操作 \" + operable + \" 套\""),
				"header still presents missing or read-only records as online suits");
	}

	private static String commandScreen() throws IOException {
		return source(
				"src/client/java/dev/codex/flightarmor/client/core/ArmorCoreCommandScreen.java");
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
