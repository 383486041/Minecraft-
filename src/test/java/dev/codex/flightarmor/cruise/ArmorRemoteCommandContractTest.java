package dev.codex.flightarmor.cruise;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Static wiring contract for the client/server remote command boundary. */
public final class ArmorRemoteCommandContractTest {
	private ArmorRemoteCommandContractTest() {
	}

	public static void main(String[] args) throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String controller = read(root,
				"src/main/java/dev/codex/flightarmor/cruise/ArmorRemoteCommandController.java");
		String payload = read(root,
				"src/main/java/dev/codex/flightarmor/network/ArmorRemoteCommandPayload.java");
		String ackPayload = read(root,
				"src/main/java/dev/codex/flightarmor/network/ArmorRemoteCommandAckPayload.java");
		String combatClient = read(root,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatClient.java");
		String independent = read(root,
				"src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseController.java");
		String escort = read(root,
				"src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java");
		String initializer = read(root,
				"src/main/java/dev/codex/flightarmor/FlightArmorMod.java");

		require(controller.contains("public static Result select(ServerPlayer owner, UUID suitId)"),
				"stable select API");
		require(controller.contains("public static Result toggleFollow(ServerPlayer owner, UUID suitId)"),
				"stable follow API");
		require(controller.contains("public static boolean isSelected(UUID ownerId, UUID suitId)"),
				"stable selected query");
		require(controller.contains("public static boolean isFollowing(UUID ownerId, UUID suitId)"),
				"stable following query");
		require(controller.contains("owner.distanceToSqr(handle.carrier()) <= MAX_DISTANCE_SQR")
						&& controller.contains("owner.distanceToSqr(escort.carrier()) <= MAX_DISTANCE_SQR")
						&& controller.contains("view.dimension().equals(owner.level().dimension())"),
				"independent and escort distance/dimension revalidation");
		require(!controller.contains("if (ModItems.hasFullSet(owner))"),
				"explicit remote selection is still rejected merely because another suit is worn");
		require(controller.contains("suitId.equals(state.suitId)")
						&& controller.contains("R/G/X/J 恢复控制身上战甲"),
				"selecting the selected suit must cancel explicit remote routing");
		require(!controller.contains("hasActiveCruise"),
				"unrelated owner-level cruise state must not globally lock an exact selected suit");
		require(payload.contains("long clientSequence")
						&& payload.contains("long selectionGeneration")
						&& !payload.contains("Vec3") && !payload.contains("UUID"),
				"remote packet must carry only sequence/session intent, never target or direction");
		require(controller.contains("payload.selectionGeneration() != state.selectionGeneration")
						&& controller.contains("UUID carrierId")
						&& controller.contains("long carrierRevision")
						&& controller.contains("state.matches(resolved)")
						&& controller.contains("resetForSelection(resolved, nextSelectionGeneration())"),
				"remote routing must bind the exact carrier incarnation and reject stale sessions");
		require(combatClient.contains("if (remoteCommandMode)")
						&& combatClient.contains("Command.WRIST")
						&& combatClient.contains("Command.CORE")
						&& combatClient.contains("Command.TOGGLE_MELEE")
						&& combatClient.contains("Command.MELEE_ULTIMATE")
						&& combatClient.contains("remoteSelectionGeneration"),
				"client does not prioritize the explicit remote mode for R/G/X/J");
		require(payload.contains("RECALL_NEAREST")
						&& combatClient.contains("ArmorAssemblyClient.isModifierDown()")
						&& combatClient.contains("Command.RECALL_NEAREST")
						&& controller.contains("payload.selectionGeneration() != 0L")
						&& controller.contains("STATES.containsKey(owner.getUUID())")
						&& controller.contains("allArmorSlotsEmpty(owner)")
						&& controller.contains("thenComparing(candidate -> candidate.suitId.toString())"),
				"nearby recall must be an explicit modifier-chord deterministic server command");
		require(payload.contains("TOGGLE_NEAREST_REMOTE")
						&& combatClient.contains("Command.TOGGLE_NEAREST_REMOTE")
						&& combatClient.contains("remoteToggleKey")
						&& controller.contains("handleNearestRemoteToggle")
						&& controller.contains("NEAREST_REMOTE_SEQUENCES")
						&& controller.contains("private static Result toggleOrReport(")
						&& controller.contains("nearestRecallCandidate(owner)"),
				"the one-key remote toggle must reuse the same deterministic nearest-carrier resolution");
		require(ackPayload.contains("Outcome outcome")
						&& ackPayload.contains("long selectionGeneration")
						&& ackPayload.contains("OUT_OF_RANGE")
						&& ackPayload.contains("NO_TARGET")
						&& combatClient.contains("receiveRemoteAck")
						&& controller.contains("指令未能在响应窗口内执行")
						&& controller.contains("指令目标已失效"),
				"remote commands have no authoritative accepted/failure acknowledgement");
		require(controller.contains("clampToControlSphere(owner")
						&& controller.contains("carrierHasLineOfSight(carrier, target)")
						&& count(controller, "insideControlSphere(") >= 6
						&& !controller.contains(".filter(owner::hasLineOfSight)"),
				"controlled movement can escape the owner sphere or melee LOS still uses owner");
		require(controller.contains("RemoteFollowPolicy.plan(")
						&& controller.contains("predictedFormationPoint(")
						&& controller.contains("carrierHasRouteLineOfSight(")
						&& controller.contains("groundAwareFollowPoint(")
						&& controller.contains("state.holding = true")
						&& controller.contains("state.holdAnchor = resolved.carrier.position()"),
				"follow layers, owner prediction, route visibility and reliable HOLD are not wired");
		require(count(controller,
						"ArmorConfigurationAccess.allowsRangedCombat(") >= 2
						&& count(controller,
								"ArmorConfigurationAccess.allowsHeavyMelee(") >= 3
						&& controller.contains("当前形态未展开远程武器")
						&& controller.contains("当前形态未锁定重型近战结构"),
				"remote R/G/X/J must reject an incompatible Sentinel form before changing AI state");
		require(controller.contains("if (fired) state.pending = null;")
						&& !controller.contains("now + 1L >= state.pending.expiresAt"),
				"failed ranged execution must remain pending until the authoritative timeout ACK");
		require(controller.contains("战甲仍在执行上一条指令，请等待完成后再操作")
						&& controller.contains("private record PendingCommand(long clientSequence")
						&& count(controller,
								"sendAck(owner, state.pending.clientSequence, state.suitId, true") >= 3
						&& !section(controller, "case TOGGLE_MELEE -> {",
								"case MELEE_ULTIMATE -> {").contains("state.pending = null"),
				"accepted remote attacks must not be silently overwritten or cancelled");
		int remoteIndependent = independent.indexOf(
				"ArmorRemoteCommandController.tickIndependent");
		int autonomousIndependent = independent.indexOf(
				"ArmorCruiseCombatController.tick", remoteIndependent);
		require(remoteIndependent >= 0 && autonomousIndependent > remoteIndependent,
				"independent remote override precedes autonomous AI");
		int remoteEscort = escort.indexOf("ArmorRemoteCommandController.tickEscort");
		int escortTarget = escort.indexOf("Vec3 commandAnchor", remoteEscort);
		require(remoteEscort >= 0 && escortTarget > remoteEscort,
				"escort remote override precedes autonomous movement");
		require(initializer.contains("playC2S().register(ArmorRemoteCommandPayload.TYPE")
						&& initializer.contains("playS2C().register(ArmorRemoteCommandAckPayload.TYPE")
						&& initializer.contains("ArmorRemoteCommandController.handleCommand")
						&& initializer.contains("ArmorRemoteCommandController.tickServer"),
				"payload and lifecycle registration");
	}

	private static String read(Path root, String relative) throws Exception {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
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
