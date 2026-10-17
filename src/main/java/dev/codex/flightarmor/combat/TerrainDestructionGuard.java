package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.illumination.ModIlluminationContent;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * 凌空战甲所有「非挖掘键」地形破坏的唯一护栏：核心束（{@link DestructiveLaserController}）、
 * 撞击爆炸与高速掠过破土（{@link ImpactBlastController}）都必须经由此类判定，不要再各自复制一份规则。
 *
 * <p>护栏与「代价」是两件事。代价（能量、热量、核心门控）可以按玩法取舍；护栏不行——
 * 少一条就会挖掉别人的领地、命令方块或基岩。判定分三态：透明格跳过、可破坏、必须停止。</p>
 */
final class TerrainDestructionGuard {
	/**
	 * 数据包可通过 {@code flight_armor:laser_protected} 方块标签追加禁挖方块。
	 * 标签 id 是既有契约，改名会让已有数据包静默失效。
	 */
	static final TagKey<Block> PROTECTED = TagKey.create(
			Registries.BLOCK, FlightArmorMod.id("laser_protected"));

	/** SKIP：透明格，不消耗预算也不算障碍；DESTROY：可以挖；STOP：立即终止这条破坏链。 */
	enum Decision {
		SKIP, DESTROY, STOP
	}

	private TerrainDestructionGuard() {
	}

	/** 服务端全局闸门：生存规则、建造权限与旁观身份。任一不满足则整条破坏链根本不启动。 */
	static boolean terrainGriefAllowed(ServerLevel level, ServerPlayer player) {
		return level != null && player != null
				&& !player.isSpectator()
				&& player.getAbilities().mayBuild
				&& Boolean.TRUE.equals(level.getGameRules().get(GameRules.MOB_GRIEFING));
	}

	static Decision evaluate(ServerLevel level, ServerPlayer player, BlockPos position) {
		if (level == null || player == null || position == null) return Decision.STOP;
		if (!level.hasChunkAt(position)) return Decision.STOP;
		BlockState state = level.getBlockState(position);
		if (state.isAir()) return Decision.SKIP;
		// 纯水/岩浆与水生植物是透明的，既不消耗预算也不阻挡。
		if (!state.getFluidState().isEmpty()
				&& state.getCollisionShape(level, position).isEmpty()) return Decision.SKIP;
		if (level.getBlockEntity(position) != null
				|| state.is(PROTECTED)
				|| forbiddenBlock(state.getBlock())
				|| !level.mayInteract(player, position)
				|| player.blockActionRestricted(level, position,
						player.gameMode.getGameModeForPlayer())) {
			return Decision.STOP;
		}
		return Decision.DESTROY;
	}

	/**
	 * 执行破坏：先给 Fabric 事件一次否决机会（领地/保护插件走这里），再确认方块没有被钩子
	 * 替换掉，最后原位替换为流体遗留状态——不产生掉落物，也不广播原版 2001 破坏粒子。
	 */
	static boolean commit(ServerLevel level, ServerPlayer player, BlockPos position,
			BlockState expected) {
		if (level == null || player == null || position == null || expected == null) return false;
		if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
				level, player, position, expected, null)) {
			return false;
		}
		// 钩子可能已经改写了世界；此时绝不能删除替换后的状态。
		if (level.getBlockState(position) != expected) return false;
		BlockState replacement = expected.getFluidState().createLegacyBlock();
		if (!level.setBlock(position, replacement, Block.UPDATE_ALL)) return false;
		level.gameEvent(player, GameEvent.BLOCK_DESTROY, position);
		return true;
	}

	/** 结构性/管理性方块永不参与地形破坏，与玩家权限无关。 */
	static boolean forbiddenBlock(Block block) {
		return block == Blocks.COMMAND_BLOCK
				|| block == Blocks.CHAIN_COMMAND_BLOCK
				|| block == Blocks.REPEATING_COMMAND_BLOCK
				|| block == Blocks.STRUCTURE_BLOCK
				|| block == Blocks.JIGSAW
				|| block == Blocks.END_PORTAL
				|| block == Blocks.END_PORTAL_FRAME
				|| block == Blocks.END_GATEWAY
				|| block == Blocks.NETHER_PORTAL
				|| block == Blocks.BARRIER
				|| block == Blocks.STRUCTURE_VOID
				|| block == Blocks.LIGHT
				|| block == Blocks.BEDROCK
				|| block == Blocks.MOVING_PISTON
				|| block == Blocks.PISTON_HEAD
				// 装甲自己的光锚点方块，与 minecraft:light 同等：不可破坏、也不终止破坏链的理由都一样。
				|| ModIlluminationContent.isAuraLightBlock(block);
	}
}
