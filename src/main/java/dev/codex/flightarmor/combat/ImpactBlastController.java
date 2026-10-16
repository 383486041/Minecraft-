package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 撞击爆破：高速飞行撞到方块时，在撞击点炸开一个球形爆坑，而不是沿运动方向钻一条隧道。
 *
 * <p>形状由 {@link ImpactBlastMath} 给（半径随撞击速度增长、上限 {@link ImpactBlastMath#MAX_RADIUS} 格）。
 * 破坏靠原版 {@code setBlock} 自动同步，所以本类不需要配套网络包。爆炸的粒子、音效与实体伤害
 * 交给原版爆炸（{@link ArmorVariantAttackResolver#applyImpactBlast}），且方块交互恒定
 * {@code ExplosionInteraction.NONE}——地形破坏一律走 {@link TerrainDestructionGuard}，
 * 绝不让原版球形爆炸绕过 mobGriefing、保护标签与领地钩子。</p>
 *
 * <p>球形爆破里单块被护栏拒绝只跳过这一块：一块基岩或一个受保护方块不该终止整片爆炸。</p>
 */
public final class ImpactBlastController {
	/**
	 * 单次爆破最多评估多少格。「评估」只统计真正落在球内的格，所以上限要按最大的球给：
	 * 撞击爆破半径 9 时球内约 3 千格，灭世光束半径 20（硬上限）时约 33552 格。这里取 36864，
	 * 在最大球之上留一点余量。这个上限只是防御异常输入——真正限制破坏量的始终是
	 * {@code maximumDestroyedBlocks} 与预算，调高它不会让任何一次爆破多挖一块。
	 */
	private static final int MAX_EVALUATED_VOXELS = 36_864;
	/**
	 * 同一玩家两次撞击爆破之间的最小间隔（tick）。贴墙斜飞时几乎每 tick 都碰撞，
	 * 不设间隔会一秒炸二十次、把整片山瞬间抹平并卡服。
	 */
	private static final long BLAST_COOLDOWN_TICKS = 5L;
	private static final Map<UUID, Long> LAST_BLAST_TICK = new ConcurrentHashMap<>();

	private ImpactBlastController() {
	}

	/**
	 * 在撞击点炸开一个球，半径与预算由撞击前速度决定。
	 *
	 * @return 本次真正掀掉的方块数；0 表示没达到撞击速度、没穿整套甲、冷却未到或被护栏拦下。
	 */
	public static int detonate(ServerPlayer player, Vec3 origin, Vec3 direction, double impactSpeed) {
		if (player == null || !finite(origin) || !finite(direction)) return 0;
		if (direction.lengthSqr() < 1.0E-8D) return 0;
		ServerLevel level = player.serverLevel();
		if (!TerrainDestructionGuard.terrainGriefAllowed(level, player)) return 0;

		ImpactBlastMath.Profile profile = ImpactBlastMath.profile(
				ModItems.fullSetVariant(player).orElse(null), impactSpeed);
		if (!profile.enabled()) return 0;
		long now = level.getGameTime();
		if (!cooldownReady(player, now)) return 0;

		Vec3 unit = direction.normalize();
		// 炸点推到身体前方一点：贴着身体炸会把刚飞过的地面一起掀掉，也会在自己脚下开洞。
		Vec3 center = origin.add(unit.scale(profile.radius() * 0.35D));
		int destroyed = carve(level, player, center, profile.radius(),
				profile.hardnessLimit(), profile.budget(), profile.maximumDestroyedBlocks());
		if (destroyed <= 0) return 0;
		// 只在真的炸掉东西之后才亮表现、才吃冷却；被领地拦下的空炸不该刷屏。
		markBlast(player, now);
		ArmorVariantAttackResolver.applyImpactBlast(player, center, profile.power());
		return destroyed;
	}

	/**
	 * 高速掠过的冲击波：以 {@code center} 为中心、半径随马赫数增长，把周围的方块掀开。
	 *
	 * <p>与 {@link #detonate} 共用 {@link #carve}，只差半径、硬度上限与预算的取法，
	 * 所以护栏与「单块被拒只跳过它自己」的语义天然一致。</p>
	 */
	public static int shockwave(ServerPlayer player, Vec3 center, double mach) {
		if (player == null || !finite(center)) return 0;
		if (!Double.isFinite(mach) || mach < 1.0D) return 0;
		ServerLevel level = player.serverLevel();
		if (!TerrainDestructionGuard.terrainGriefAllowed(level, player)) return 0;
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(null);
		if (variant == null) return 0;

		double radius = clamp(mach * 2.0D, 2.0D, 12.0D);
		double hardnessLimit = switch (variant) {
			case VANGUARD -> 32.0D;
			case SWIFT -> 55.0D;
			case SENTINEL -> 24.0D;
		};
		double budget = clamp(mach * mach * 12.0D, 24.0D, 1800.0D);
		int maximumBlocks = (int) clamp(Math.round(mach * 24.0D), 16, 320);
		return carve(level, player, center, radius, hardnessLimit, budget, maximumBlocks);
	}

	/**
	 * 增强能量光束的灭世爆坑入口：半径、硬度上限、预算与块数上限全部由
	 * {@link EnhancedBeamMath} 给定，但**护栏仍在这里统一执行**——光束不能自己复制一份
	 * 判定，那等于开一个绕过 mobGriefing、保护标签与领地钩子的后门。
	 *
	 * <p>刻意不复用 {@link #detonate} 的 {@link #BLAST_COOLDOWN_TICKS}：灭世光束有自己的
	 * 84 tick 武器冷却，再叠一层 5 tick 的撞击冷却只会让「同一 tick 连开两炮」这种
	 * 本来就被武器冷却挡住的场景多一层无意义的耦合。</p>
	 *
	 * @return 本次真正掀掉的方块数。
	 */
	static int crater(ServerPlayer player, Vec3 center, double radius, double hardnessLimit,
			double budget, int maximumBlocks) {
		if (player == null || !finite(center)) return 0;
		ServerLevel level = player.serverLevel();
		if (!TerrainDestructionGuard.terrainGriefAllowed(level, player)) return 0;
		return carve(level, player, center, radius, hardnessLimit, budget, maximumBlocks);
	}

	/** 球形破坏的共同实现：撞击爆破、掠过破土与灭世爆坑只差在半径、硬度上限与预算。 */
	static int carve(ServerLevel level, ServerPlayer player, Vec3 center,
			double radius, double hardnessLimit, double budget, int maximumDestroyedBlocks) {
		if (!finite(center) || !Double.isFinite(radius) || radius <= 0.0D
				|| maximumDestroyedBlocks <= 0 || !Double.isFinite(budget) || budget < 0.0D) {
			return 0;
		}
		BlockPos origin = BlockPos.containing(center);
		int span = (int) Math.ceil(radius);
		double radiusSqr = radius * radius;
		int destroyed = 0;
		int evaluated = 0;
		double remaining = budget;
		for (BlockPos cursor : BlockPos.betweenClosed(
				origin.offset(-span, -span, -span), origin.offset(span, span, span))) {
			if (destroyed >= maximumDestroyedBlocks || evaluated >= MAX_EVALUATED_VOXELS) break;
			// 用格中心到爆炸中心的距离判球内，别用整数格距离，否则球会歪半格。
			double dx = cursor.getX() + 0.5D - center.x;
			double dy = cursor.getY() + 0.5D - center.y;
			double dz = cursor.getZ() + 0.5D - center.z;
			if (dx * dx + dy * dy + dz * dz > radiusSqr) continue;
			evaluated++;
			BlockPos position = cursor.immutable();
			TerrainDestructionGuard.Decision decision =
					TerrainDestructionGuard.evaluate(level, player, position);
			// 空气与纯流体天然跳过，所以相邻判定重叠时不会重复消耗预算。
			if (decision == TerrainDestructionGuard.Decision.SKIP) continue;
			// 球形爆破里一块基岩/受保护方块/领地只跳过它自己，不终止整片爆炸。
			if (decision == TerrainDestructionGuard.Decision.STOP) continue;
			BlockState state = level.getBlockState(position);
			float hardness = state.getDestroySpeed(level, position);
			if (!Float.isFinite(hardness) || hardness < 0.0F
					|| hardness > hardnessLimit + 1.0E-6D) continue;
			DestructiveLaserMath.BudgetDecision spend = DestructiveLaserMath.tryConsume(
					remaining, hardness, destroyed, maximumDestroyedBlocks);
			if (!spend.allowed()) break;
			if (!TerrainDestructionGuard.commit(level, player, position, state)) continue;
			remaining = spend.remainingBudget();
			destroyed++;
		}
		return destroyed;
	}

	private static boolean cooldownReady(ServerPlayer player, long gameTime) {
		Long last = LAST_BLAST_TICK.get(player.getUUID());
		// 服务器重启会让 gameTime 回到小值，此时把旧记录当成已过期而不是无限冷却。
		return last == null || gameTime < last || gameTime - last >= BLAST_COOLDOWN_TICKS;
	}

	private static void markBlast(ServerPlayer player, long gameTime) {
		LAST_BLAST_TICK.put(player.getUUID(), gameTime);
	}

	/** 停飞/退出时清理冷却记录，避免 UUID 常驻内存。 */
	public static void remove(ServerPlayer player) {
		if (player != null) LAST_BLAST_TICK.remove(player.getUUID());
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static boolean finite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}
}
