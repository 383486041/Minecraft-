package dev.codex.flightarmor.combat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source contract for Sentinel's visual companion rays and exact-lane autonomous pod pulse.
 * The test deliberately prevents remote commands from inheriting the autonomous double-pod path.
 */
public final class SentinelDronePodContractTest {
	private SentinelDronePodContractTest() {
	}

	public static void main(String[] args) throws IOException {
		Path project = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String combat = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java");
		String state = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatState.java");
		String independent = source(project,
				"src/main/java/dev/codex/flightarmor/cruise/ArmorCruiseCombatController.java");
		String escort = source(project,
				"src/main/java/dev/codex/flightarmor/cruise/ArmorEscortController.java");
		String fx = source(project,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxController.java");

		String automaticCore = section(combat,
				"public static CruiseWeaponResult tryFireCruiseCore",
				"/** Vanguard escort ram.");
		require(automaticCore.contains("emitSentinelPodCompanionFx("),
				"autonomous Sentinel core fire lost its paired pod rays");
		require(count(automaticCore, "ArmorVariantAttackResolver.applyCore(") == 1,
				"companion rays must not repeat the main core damage/explosion transaction");

		String companion = section(combat,
				"private static List<SentinelPodTrace> validatedSentinelPodTraces",
				"/**\n\t * Exact-lane fallback");
		require(companion.contains("left ? 1 : 2")
						&& companion.contains("ArmorAttackHitKind.MISS")
						&& companion.contains("podHit.entity() == target"),
				"paired pod rays need fixed indexes, independent LOS validation and visual-only hits");
		require(!companion.contains(".hurt(") && !companion.contains("applyCore("),
				"visual companion rays acquired a second damage path");

		String pulse = section(combat,
				"public static CruiseWeaponResult tryFireSentinelDronePulse",
				"private static CombatRaycast.Result sentinelPodRay");
		require(pulse.contains("cruiseStateFor(owner, carrier)")
						&& pulse.contains("state.sentinelDroneCooldownUntilTick")
						&& pulse.contains("state.nextLeftSentinelDrone")
						&& pulse.contains("state.lastFireTick == now"),
				"Sentinel pod pulse must use one exact carrier lane and alternate pod sides");
		require(!pulse.contains("coreExplosion(") && !pulse.contains("applyCore("),
				"small Sentinel pod pulse must remain non-explosive");
		require(state.contains("long sentinelDroneCooldownUntilTick")
						&& state.contains("boolean nextLeftSentinelDrone = true"),
				"exact carrier state lost the pod cooldown/alternation fields");

		String remoteCore = section(combat,
				"public static CruiseWeaponResult tryFireRemoteCore",
				"/** One close-range strike");
		require(!remoteCore.contains("emitSentinelPodCompanionFx(")
						&& !remoteCore.contains("tryFireSentinelDronePulse("),
				"manual G command must not inherit autonomous pod duplicate fire");
		require(independent.contains("tryFireSentinelDronePulse(")
						&& escort.contains("tryFireSentinelDronePulse("),
				"both independent and escort Sentinel AI need the fallback pod pulse");

		String coreFx = section(fx,
				"case CORE_BEAM, CRUISE_CORE_BEAM ->",
				"case GRAVITY_DIVE_IMPACT");
		require(coreFx.contains("if (payload.eventIndex() == 0)")
						&& count(coreFx, "playCoreRelease(") == 1,
				"pod event indexes 1/2 must not replay the main core release sound");
		System.out.println("SentinelDronePodContractTest passed");
	}

	private static String source(Path project, String path) throws IOException {
		// 锚点里有跨行的多行串（写成 \n），而源码树里 CRLF / LF 是混着的（Windows 工具链漂移）。
		// 不归一化的话，「/**\n\t * Exact-lane fallback」这类锚点永远匹配不上 —— 测试会因为
		// 一个和代码逻辑无关的理由（行尾符）翻红。所有读源码的契约测试都该这样归一化。
		return Files.readString(project.resolve(path)).replace("\r\n", "\n");
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
