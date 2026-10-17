package dev.codex.flightarmor.illumination;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 装甲照明：穿着完整凌空战甲套装时，穿戴者所在格会被点亮，走出黑暗后自动熄灭。
 *
 * <p>装甲上那些发光线条只是 emissive 自发光着色，只让装甲自己变亮，照不亮环境；要照亮地形、
 * 生物和别的玩家，必须走真正的光照引擎。本类提供其中一条路：在穿戴者脚下维护一个隐形的
 * 光锚点方块（见 {@link ModIlluminationContent}），走原版光照引擎正常传播。</p>
 *
 * <p><b>它同时是彩色光的载体。</b>方块 id 按型号区分，客户端装了 Polytone + Veil 后，
 * {@code assets/flight_armor/polytone/colored_lights/*.json} 会把「哪个 id 发什么颜色」告诉它，
 * 于是先锋发青、疾影发紫、守望发金；没装的客户端照旧只看见白光。颜色是纯数据，改一个 hex 即可。</p>
 *
 * <p><b>但它不是首选。</b>方块光照被烘焙进区块网格顶点，放/收一格都要触发光照重算；高速移动
 * 时几乎每 tick 都在搬光源，所以这里只在客户端没装动态光源模组时兜底。装了 LambDynamicLights
 * 就整条链路让给它（见 {@code HANDLED_BY_DYNAMIC_LIGHTS_MOD}），那份光在客户端算出来，
 * 服务端一次 {@code setBlock} 都不做。两边的判定口径都要求「穿齐四件」。</p>
 *
 * <p><b>三条改坏就会出事的约束：</b></p>
 * <ul>
 *   <li>只占用空气格，绝不替换玩家脚下已有的方块，因此不存在「照明吃掉方块」。</li>
 *   <li>撤除前先确认那格仍是我们的光锚点方块，不会误删别人放到那里的东西。</li>
 *   <li>判定暗不暗之前必须先撤掉自己的旧光源。否则自己那点光会让读数恒为 15，
 *       行为就变成「点亮 → 判定够亮 → 熄灭 → 变黑 → 再点亮」的每刻振荡。</li>
 * </ul>
 *
 * <p>放/收方块统一用 {@link Block#UPDATE_ALL}：它是原版 {@code /setblock} 用的值，也是唯一
 * 能确定驱动光照引擎重算的 flags。光锚点方块没有形状、不是红石源、也不遮挡，
 * 被它触发的邻居更新判定结果都不变（沙子的落体判定只看自己下方，而光方块上方永远是玩家
 * 所站的空气格），所以这里不需要为省邻居更新换成 {@code UPDATE_CLIENTS}。</p>
 */
public final class ArmorIlluminationController {
	/**
	 * 光锚点方块状态：亮度等同萤石/海晶灯。颜色不在这里决定 —— 它挂在方块 id 上
	 * （见 {@link ModIlluminationContent}），客户端彩色光渲染器按 id 上色。
	 */
	private static BlockState illuminationFor(ArmorVariant variant) {
		return ModIlluminationContent.auraLight(variant).defaultBlockState()
				.setValue(LightBlock.LEVEL, LightBlock.MAX_LEVEL);
	}
	/** 环境亮度低于此值才点亮：月光（4）仍算暗，正午户外（15）不会点亮。 */
	private static final int DARKNESS_THRESHOLD = 9;
	/**
	 * 玩家在光源多少格内走动时不搬光源。每次搬移要付两次光照重算（撤旧 + 放新），
	 * 而 15 级光本来就覆盖 15 格，跟着玩家每一格挪一次纯属浪费。
	 */
	private static final int FOLLOW_RADIUS = 2;
	/**
	 * 装了动态光源模组（LambDynamicLights）时，照明整条链路交给它：那份光在客户端算出来，
	 * 不写方块、不驱动服务端光照重算，服务端 0 成本。此时本类退化成「没装模组时的兜底」，
	 * 一次 {@code setBlock} 都不做。
	 *
	 * <p>另一半对接不在代码里，而在
	 * {@code assets/flight_armor/dynamiclights/entity/flight_armor_set.json}：那份 JSON 由
	 * LambDynamicLights 自己读取，没装的客户端直接忽略。所以整套联动是软依赖，既不需要
	 * compileOnly 依赖，也不需要声明 entrypoint，更不会在缺模组时崩。</p>
	 *
	 * <p>判定用的是 Fabric Loader 的 mod 列表。单人游戏里集成服务端与客户端同一个 JVM，
	 * 读到的就是客户端的真实情况；专用服务器读的是服务端自己的列表（通常没装，于是走兜底）。</p>
	 *
	 * <p><b>彩色光的优先级高于这条让路。</b>装了 Polytone / Veil 时，光锚点方块不再只是「兜底光源」，
	 * 它同时是彩色光的载体（颜色挂在方块 id 上）。此时即便 LDL 也在，也照样放方块，
	 * 否则彩色光会整条消失 —— 那正是这个功能存在的理由。</p>
	 */
	private static final boolean COLORED_LIGHT_RENDERER =
			FabricLoader.getInstance().isModLoaded("polytone")
					|| FabricLoader.getInstance().isModLoaded("veil");

	private static final boolean HANDLED_BY_DYNAMIC_LIGHTS_MOD =
			FabricLoader.getInstance().isModLoaded("lambdynlights") && !COLORED_LIGHT_RENDERER;

	private static final Map<UUID, PlacedLight> PLACED = new HashMap<>();

	private ArmorIlluminationController() {
	}

	public static void tickServer(MinecraftServer server) {
		if (HANDLED_BY_DYNAMIC_LIGHTS_MOD) {
			// 玩家中途装上模组时，上一 tick 放下的方块光源仍要收干净，否则会留在存档里。
			if (!PLACED.isEmpty()) clear();
			return;
		}
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
			tickPlayer(player);
		}
		releaseOrphans(online);
	}

	private static void tickPlayer(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos target = player.blockPosition();
		PlacedLight current = PLACED.get(player.getUUID());
		if (current != null && current.follows(level, target)) return;

		if (current != null) {
			PLACED.remove(player.getUUID());
			extinguish(current);
		}
		if (!shouldIlluminate(player, level, target)) return;
		// 型号决定光的颜色：混装（型号不一致）时 fullSetVariant 为空，回退先锋型。
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.DEFAULT);
		if (level.setBlock(target, illuminationFor(variant), Block.UPDATE_ALL)) {
			PLACED.put(player.getUUID(), new PlacedLight(level, target));
		}
	}

	/** 调用这里时自己的旧光源必须已经撤掉，理由见类注释第三条。 */
	private static boolean shouldIlluminate(ServerPlayer player, ServerLevel level, BlockPos target) {
		if (!ModItems.hasFullSet(player)) return false;
		if (!level.getBlockState(target).isAir()) return false;
		return level.getMaxLocalRawBrightness(target) < DARKNESS_THRESHOLD;
	}

	/** 玩家离线、死亡或换维度时收走光源；按 UUID 查记录，不依赖玩家此刻所在维度。 */
	public static void release(ServerPlayer player) {
		PlacedLight light = PLACED.remove(player.getUUID());
		if (light != null) extinguish(light);
	}

	public static void clear() {
		for (PlacedLight light : PLACED.values()) extinguish(light);
		PLACED.clear();
	}

	/**
	 * 兜底：事件漏挂、或玩家以异常路径下线时，光靠 tick 里那份在线名单会漏掉残留光源，
	 * 那盏灯就会永久留在存档里。所以每 tick 核对一次，只留真正在线的人。
	 */
	private static void releaseOrphans(Set<UUID> online) {
		Iterator<Map.Entry<UUID, PlacedLight>> iterator = PLACED.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, PlacedLight> entry = iterator.next();
			if (online.contains(entry.getKey())) continue;
			extinguish(entry.getValue());
			iterator.remove();
		}
	}

	private static void extinguish(PlacedLight light) {
		ServerLevel level = light.level();
		BlockPos pos = light.pos();
		// 区块已卸载时够不着也不该崩：撤不掉就放弃，代价最多是那一格里留一格光。
		if (!level.hasChunkAt(pos)) return;
		if (!ModIlluminationContent.isAuraLight(level.getBlockState(pos))) return;
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private record PlacedLight(ServerLevel level, BlockPos pos) {
		boolean follows(ServerLevel other, BlockPos otherPos) {
			return level == other
					&& Math.abs(pos.getX() - otherPos.getX()) <= FOLLOW_RADIUS
					&& Math.abs(pos.getY() - otherPos.getY()) <= FOLLOW_RADIUS
					&& Math.abs(pos.getZ() - otherPos.getZ()) <= FOLLOW_RADIUS;
		}
	}
}
