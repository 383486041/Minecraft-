package dev.codex.flightarmor.suitpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline contract for the right-click suit panel and its server-authoritative controls. */
public final class ArmorSuitPanelContractTest {
	private static final Path PROJECT = Path.of(
			System.getProperty("flightArmor.projectDir", ".")).toAbsolutePath().normalize();

	private ArmorSuitPanelContractTest() {
	}

	public static void main(String[] args) throws Exception {
		serverRevalidatesEveryButton();
		nearbyCarrierRecallDoesNotRequireAnActiveCore();
		remoteControlsAreCarrierOnlyAndServerAuthored();
		clientNeverClosesOptimistically();
		panelExplainsCommandKeysAndPhysicalState();
		invalidNetworkActionsRemainReadOnly();
	}

	private static void serverRevalidatesEveryButton() throws IOException {
		String controller = controller();
		String handler = method(controller,
				"private static void handleAction(",
				"private static void handlePrimary(");
		require(handler.contains("ResolvedTarget target = resolve(viewer, session);"),
				"panel action does not resolve its physical target again");
		String resolve = method(controller,
				"private static ResolvedTarget resolve(",
				"private static boolean isPhysicalCarrier(");
		require(resolve.contains("viewer.distanceToSqr")
						&& controller.contains("PANEL_OPEN_DISTANCE_SQR = 14.0D * 14.0D")
						&& controller.contains("CARRIER_SESSION_DISTANCE_SQR = 48.0D * 48.0D")
						&& resolve.contains("platform.getOwnerUuid().filter(session.ownerId()::equals)")
						&& resolve.contains("platform.getSuitId().filter(session.suitId()::equals)")
						&& resolve.contains("readSuitIdentity(carrier)")
						&& resolve.contains("identity.ownerId().equals(session.ownerId())")
						&& resolve.contains("identity.suitId().equals(session.suitId())"),
				"panel action trusts stale client/session owner, suit, location or distance evidence");
	}

	private static void nearbyCarrierRecallDoesNotRequireAnActiveCore() throws IOException {
		String controller = controller();
		String primary = method(controller,
				"private static PrimaryActionState primaryAction(",
				"private static boolean isTransitioning(");
		int directCarrier = primary.indexOf("session.kind() != SubjectKind.PLATFORM");
		int carrierEnd = primary.indexOf("boolean allowed = runtimeState != null", directCarrier);
		int platformPolicy = primary.indexOf("runtimeState.allowedActions().contains(", carrierEnd);
		require(directCarrier >= 0 && carrierEnd > directCarrier && platformPolicy > carrierEnd,
				"physical carrier and platform recall policy are no longer separated");
		String carrierBranch = primary.substring(directCarrier, carrierEnd);
		require(carrierBranch.contains("PrimaryActionState.enabled(PrimaryAction.WEAR)")
						&& !carrierBranch.contains("runtimeState != null")
						&& !carrierBranch.contains("CORE_INACTIVE"),
				"nearby exact carrier recall is still incorrectly coupled to an active core");
		require(primary.substring(platformPolicy).contains(
						"SuitRuntimeSnapshot.AllowedAction.CAN_RECALL_AND_WEAR"),
				"platform recall no longer follows the core/platform runtime policy");
	}

	private static void remoteControlsAreCarrierOnlyAndServerAuthored() throws IOException {
		String controller = controller();
		String snapshot = method(controller,
				"private static void sendSnapshot(",
				"private static PrimaryActionState primaryAction(");
		require(snapshot.contains("session.kind() == SubjectKind.PLATFORM")
						&& snapshot.contains("Capability.TOGGLE_DISPLAY")
						&& !snapshot.contains("capabilities.add(Capability.SELECT_REMOTE)")
						&& !snapshot.contains("capabilities.add(Capability.TOGGLE_FOLLOW)"),
				"nearby telemetry panel must not maintain a second mode-control surface");
		require(controller.contains("case OPEN_COMMAND_CENTER")
						&& controller.contains("ArmorCoreController.openMenu(viewer)"),
				"nearby panel does not route owners into the unified command center");

		String screen = source(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelScreen.java");
		require(screen.contains("snapshot.allows(Capability.PRIMARY)")
						&& screen.contains("snapshot.allows(Capability.TOGGLE_DISPLAY)")
						&& screen.contains("OPEN_COMMAND_CENTER")
						&& screen.contains("打开统一指令中心"),
				"nearby panel lost its authorized actions or unified command-center entry");
		require(!screen.contains("snapshot.allows(Capability.SELECT_REMOTE)")
						&& !screen.contains("snapshot.allows(Capability.TOGGLE_FOLLOW)"),
				"nearby panel still duplicates command or formation mode buttons");
	}
	private static void clientNeverClosesOptimistically() throws IOException {
		String screen = source(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelScreen.java");
		String client = source(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelClient.java");
		require(!screen.contains("ArmorSuitPanelClient.send(snapshot.sessionId(),"
						+ " ArmorSuitPanelActionPayload.Action.PRIMARY);\\n\\t\\t\\t\\tonClose();"),
				"primary button still closes before the server accepts recall or switching");
		require(client.contains("if (payload.closeRequested())")
						&& client.contains("screen.matchesSession(payload.sessionId())")
						&& client.contains("if (payload.openRequested()"),
				"server receipts cannot refresh/close the exact panel without reopening a closed one");
		String controller = controller();
		require(controller.contains("if (outcome.closePanel())")
						&& controller.contains("sendSnapshot(viewer, session, target,"
						+ " outcome.message(), false, true)")
						&& controller.contains("sendSnapshot(viewer, session, target,"
						+ " outcome.message(), false, false)"),
				"server does not distinguish a started movement from a rejected operation");
	}

	private static void panelExplainsCommandKeysAndPhysicalState() throws IOException {
		String screen = source(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelScreen.java");
		for (String marker : new String[] {
				"\"M\", \"形态切换\"",
				"\"R\", \"腕炮 / 准星\"",
				"\"G\", \"胸炮 / 准星\"",
				"\"X\", \"近战 / 贴地\"",
				"\"J\", \"近战大招\"",
				"关闭面板后 H 收放守望僚机或切换巡航警戒",
				"snapshot.roleText()",
				"完整性 / 事务",
				"snapshot.positionText()",
				"snapshot.distanceText()"
		}) {
			require(screen.contains(marker), "right-click panel lost required status/help marker: " + marker);
		}
		require(screen.contains("PANEL_WIDTH = 520") && screen.contains("PANEL_HEIGHT = 352"),
				"redesigned panel dimensions or collision budget regressed");
		String controller = controller();
		require(controller.contains("ArmorConfigurationData.read(chest).mode()")
						&& controller.contains("\" · 重装攻坚\"")
						&& controller.contains("\" · 超驰飞行\""),
				"Sentinel's authoritative form is missing from the single-suit status panel");
	}

	private static void invalidNetworkActionsRemainReadOnly() throws IOException {
		String payload = source(
				"src/main/java/dev/codex/flightarmor/network/ArmorSuitPanelActionPayload.java");
		require(payload.contains("? values[id] : REFRESH"),
				"an invalid action id can still fall back to a movement command");
	}

	private static String controller() throws IOException {
		return source(
				"src/main/java/dev/codex/flightarmor/suitpanel/ArmorSuitPanelController.java");
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
