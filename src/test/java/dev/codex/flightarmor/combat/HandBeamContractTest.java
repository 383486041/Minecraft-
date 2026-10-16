package dev.codex.flightarmor.combat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 增强能量光束的源码不变量闸门。
 *
 * <p>这一套断言覆盖四类「编译器不会报、实机才炸」的问题：</p>
 * <ol>
 *   <li><b>协议顺序</b>——新枚举值必须追加在末尾，否则按 ordinal 编号的线协议会把旧客户端的
 *       攻击类型整体错位。</li>
 *   <li><b>护栏唯一</b>——灭世爆坑的地形破坏只能经 {@code ImpactBlastController.crater}，
 *       那里面才有 {@code TerrainDestructionGuard}。光束自己复制一份判定就等于穿墙后门。</li>
 *   <li><b>输入链路完整</b>——「切换 + 鼠标左键扳机」是三个文件凑出来的：
 *       {@code ArmorCombatClient} 读左键、{@code MinecraftBladeAttackMixin} 让位、
 *       {@code MultiPlayerGameModeHandBeamMixin} 拦持续挖掘。**混入类忘了注册就是静默失效**
 *       （按住扳机会一边蓄力一边把脚下的方块挖穿），所以注册表本身也要断言。</li>
 *   <li><b>距离预算同源</b>——射程与客户端特效预算必须来自同一个常量。两边各写一份的后果是
 *       「山被挖掉了却看不见光束」，那是最难排查的一类 bug。</li>
 * </ol>
 */
public final class HandBeamContractTest {
	private HandBeamContractTest() {
	}

	public static void main(String[] args) throws IOException {
		Path project = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String attackType = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ArmorAttackType.java");
		String gate = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatGate.java");
		String combat = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java");
		String blast = source(project,
				"src/main/java/dev/codex/flightarmor/combat/ImpactBlastController.java");
		String beamMath = source(project,
				"src/main/java/dev/codex/flightarmor/combat/EnhancedBeamMath.java");
		String flightPhysics = source(project,
				"src/main/java/dev/codex/flightarmor/flight/FlightPhysics.java");
		String fxController = source(project,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxController.java");
		String fxRenderer = source(project,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxRenderer.java");
		String client = source(project,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatClient.java");
		String bladeMixin = source(project,
				"src/client/java/dev/codex/flightarmor/mixin/MinecraftBladeAttackMixin.java");
		String miningMixin = source(project,
				"src/client/java/dev/codex/flightarmor/mixin/MultiPlayerGameModeHandBeamMixin.java");
		String mixinConfig = source(project, "src/main/resources/flight_armor.mixins.json");
		String zhLang = source(project,
				"src/main/resources/assets/flight_armor/lang/zh_cn.json");
		String enLang = source(project,
				"src/main/resources/assets/flight_armor/lang/en_us.json");
		String mathTest = source(project,
				"src/test/java/dev/codex/flightarmor/combat/EnhancedBeamMathTest.java");

		// 1) 网络枚举只可追加：新档位必须排在既有值之后，否则 ordinal 会整体错位。
		require(attackType.contains("HAND_BEAM_PULSE") && attackType.contains("HAND_BEAM_LANCE"),
				"the two hand beam attack types must exist on the wire enum");
		require(attackType.indexOf("HAND_BEAM_PULSE") > attackType.indexOf("ENERGY_BLADE_COMBO")
						&& attackType.indexOf("HAND_BEAM_LANCE") > attackType.indexOf("HAND_BEAM_PULSE"),
				"hand beam types must be appended after the existing constants, never inserted");

		// 2) 门禁必须真的覆盖两档；少一档就会掉进 UNSUPPORTED_ATTACK 之外的分支。
		require(gate.contains("case HAND_BEAM_PULSE, HAND_BEAM_LANCE ->"),
				"the shared combat gate must route both hand beam modes");
		require(gate.contains("evaluateHandBeam(action, state, now, energyCost)"),
				"the gate must keep its dedicated hand beam evaluation");

		// 3) 服务端分派两档齐备，且**档位在松手那一刻按按住长度分流**。
		require(combat.contains("case HAND_BEAM_PULSE -> handleHandBeamPulse(")
						&& combat.contains("case HAND_BEAM_LANCE -> handleHandBeamLance("),
				"the combat controller must dispatch both hand beam modes");
		String lance = section(combat,
				"private static void handleHandBeamLance(ServerPlayer player, ArmorCombatState state,",
				"public static void beginDiveImpactAction(");
		require(lance.contains("EnhancedBeamMath.LANCE_CLASSIFY_TICKS"),
				"the tap/hold split must read the shared threshold, not a local copy");
		require(lance.contains("fireHandBeamPulse(player, state, now);")
						&& lance.contains("releaseHandBeamLance(player, state, now);"),
				"a release must resolve into either the tap or the lance, never both");

		// 4) 客户端输入链路：切换键 + 鼠标左键扳机 + 两颗混入类都必须到齐。
		String receiveFx = section(client,
				"private static void receiveFx(Minecraft client, ArmorAttackFxPayload payload) {",
				"private static void tickBladeStates(");
		require(receiveFx.contains("case HAND_BEAM_PULSE") && receiveFx.contains("case HAND_BEAM_LANCE"),
				"receiveFx switches exhaustively over the attack type; both cases are mandatory");
		require(client.contains("\"key.flight_armor.hand_beam\""),
				"the hand beam needs its own rebindable key");
		require(client.contains("client.options.keyAttack.isDown()"),
				"the trigger must read the real left-button state, not a per-tick edge");
		require(client.contains("ArmorAttackType.HAND_BEAM_LANCE,")
						&& client.contains("ArmorAttackAction.RELEASE)"),
				"the client must send the lance PRESS/RELEASE pair");
		require(client.contains("public static boolean handBeamArmed()"),
				"the armed switch must be readable by the input mixins");
		require(bladeMixin.contains("ArmorCombatClient.handBeamArmed()"),
				"the vanilla attack must be handed over to the trigger while the beam is armed");
		require(miningMixin.contains("continueDestroyBlock")
						&& miningMixin.contains("ArmorCombatClient.handBeamArmed()"),
				"holding the trigger must not dig through the block under the crosshair");
		require(mixinConfig.contains("\"MultiPlayerGameModeHandBeamMixin\""),
				"an unregistered mixin silently never runs; the mining suppressor must be listed");

		// 4b) 「已就绪」必须在开火不可能时自动收起，否则两颗混入类会继续吞掉左键，
		//     玩家脱了甲/进了遥控模式之后会觉得左键彻底坏了。
		require(section(client,
						"if (!ModItems.hasFullSet(client.player)) {",
						"if (!bladeExtended(client.player)) {")
						.contains("handBeamArmed = false;"),
				"taking the suit off must stow the beam");
		require(section(client,
						"if (remoteCommandMode) {",
						"if (!ModItems.hasFullSet(client.player)) {")
						.contains("handBeamArmed = false;"),
				"the remote command mode owns no trigger, so it must stow the beam");

		// 5) 地形破坏的唯一入口：crater 里必须有护栏，光束侧不许自己动世界。
		String crater = section(blast,
				"static int crater(ServerPlayer player, Vec3 center, double radius, double hardnessLimit,",
				"/** 球形破坏的共同实现");
		require(crater.contains("TerrainDestructionGuard.terrainGriefAllowed("),
				"the crater entry point must own the terrain destruction guardrail");
		require(crater.contains("carve(level, player, center, radius, hardnessLimit, budget, maximumBlocks)"),
				"the crater entry point must delegate to the shared carve implementation");

		String release = section(combat,
				"private static void releaseHandBeamLance(ServerPlayer player, ArmorCombatState state, long now) {",
				"private static float energyLimitedCharge(");
		require(release.contains("ImpactBlastController.crater(player, craterCenter,"),
				"the lance must destroy terrain through ImpactBlastController.crater");
		for (String forbidden : new String[]{"setBlock(", "destroyBlock(", "removeBlock(", "explode("}) {
			require(!release.contains(forbidden),
					"the lance must not touch the world directly, found: " + forbidden);
		}

		// 6) 单 tick 评估上限与离线数学测试里重抄的那份必须数值相同。
		//    比字符串相等更松、也更对：两边一个写 36864、一个写 36_864 不算脱钩。
		int evaluatedCap = (int) declaredNumber(blast, "MAX_EVALUATED_VOXELS");
		require((int) declaredNumber(mathTest, "EVALUATED_VOXEL_CAP") == evaluatedCap,
				"the offline math test must restate the same evaluated voxel ceiling");
		require(evaluatedCap > 0, "the evaluated voxel ceiling must be positive");

		// 7) 既有的守望副射线声音契约不得被手炮分支挤坏（真实踩过一次回归）。
		String coreFx = section(fxController,
				"case CORE_BEAM, CRUISE_CORE_BEAM ->",
				"case GRAVITY_DIVE_IMPACT");
		require(count(coreFx, "playCoreRelease(") == 1,
				"pod event indexes 1/2 must not replay the main core release sound");
		require(!coreFx.contains("HAND_BEAM"),
				"the hand beam cases must stay outside the CORE_BEAM -> GRAVITY_DIVE_IMPACT window");

		// 8) 距离预算必须同源：客户端两处都不许再出现自己那份距离字面量。
		require(fxController.contains("EnhancedBeamMath.MAX_LANCE_FX_RANGE"),
				"the fx controller's distance budget must derive from the shared constant");
		require(fxRenderer.contains("EnhancedBeamMath.MAX_LANCE_FX_RANGE"),
				"the renderer's draw distance must derive from the shared constant");
		require(declaredNumber(beamMath, "MAX_LANCE_FX_RANGE") >= 150.0D * 1.18D,
				"the shared FX budget must cover the longest variant's full-charge reach");

		// 9) 用户硬性数值：射程 100~150 格、爆坑直径 36 格起。改小任何一项都会翻红。
		require(declaredNumber(beamMath, "LANCE_MIN_RANGE") >= 100.0D,
				"even a light charge must reach 100 blocks");
		require(declaredNumber(beamMath, "LANCE_MAX_RANGE") >= 150.0D,
				"a full charge must reach 150 blocks");
		require(declaredNumber(beamMath, "MAX_CRATER_RADIUS") * 2.0D >= 36.0D,
				"a full charge must flatten a mountain top, not dent it");
		require(declaredNumber(beamMath, "LANCE_MAX_DAMAGE") >= 900.0D,
				"the annihilation beam is supposed to be absurdly strong");

		// 10) 撞击不减速：高速档必须跳过碰撞速度保留率（否则山炸开了人却卡在洞口）。
		require(flightPhysics.contains("if (player.horizontalCollision && !diving)"),
				"a high-speed impact must not brake the player just because the blocks broke");

		// 11) 双语键必须同时存在，否则一种语言会显示原始 translation key。
		for (String key : new String[]{"key.flight_armor.hand_beam", "hud.flight_armor.lance_charge",
				"hud.flight_armor.hand_beam_armed", "message.flight_armor.hand_beam_armed",
				"message.flight_armor.hand_beam_disarmed"}) {
			require(zhLang.contains("\"" + key + "\""), "zh_cn is missing " + key);
			require(enLang.contains("\"" + key + "\""), "en_us is missing " + key);
		}

		System.out.println("HandBeamContractTest passed");
	}

	private static String source(Path project, String path) throws IOException {
		return Files.readString(project.resolve(path));
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

	/**
	 * 读出源码里某个常量的数字字面量，而不是比对字面字符串。
	 *
	 * <p>这样 {@code 36864} 与 {@code 36_864} 视为同一个数：断言的是「两处口径一致」，
	 * 不是「两处写法一致」。写成字符串比对时，改个下划线分隔就会假翻红。</p>
	 */
	private static double declaredNumber(String source, String name) {
		int at = source.indexOf(name + " =");
		require(at >= 0, "missing constant declaration: " + name);
		int cursor = at + name.length();
		StringBuilder digits = new StringBuilder();
		while (cursor < source.length()) {
			char current = source.charAt(cursor);
			if (Character.isDigit(current) || current == '_' || current == '.') {
				digits.append(current);
			} else if (digits.length() > 0) {
				break;
			}
			cursor++;
		}
		require(digits.length() > 0, "constant " + name + " has no numeric literal");
		try {
			return Double.parseDouble(digits.toString().replace("_", ""));
		} catch (NumberFormatException failure) {
			throw new AssertionError("cannot parse constant " + name + ": " + digits, failure);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
