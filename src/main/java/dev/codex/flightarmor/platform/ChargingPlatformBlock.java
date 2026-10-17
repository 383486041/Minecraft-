package dev.codex.flightarmor.platform;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.suitpanel.ArmorSuitPanelController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 充能平台本体：潜行右键整套归还身上战甲，普通右键切换静态陈列，不打开容器。 */
public final class ChargingPlatformBlock extends BaseEntityBlock {
	public static final BooleanProperty PARTS = BooleanProperty.create("parts");
	private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D);

	public ChargingPlatformBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(PARTS, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PARTS);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ChargingPlatformBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
			Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide()) return null;
		return createTickerHelper(type, ModPlatformContent.CHARGING_PLATFORM_BLOCK_ENTITY,
				ChargingPlatformBlockEntity::serverTick);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform)) {
			return InteractionResult.FAIL;
		}
		if (stack.is(ModItems.ARMOR_NAVIGATION_CORE)) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				ArmorCoreController.bindHeldCoreToPlatform(serverPlayer, hand, platform);
			}
			return InteractionResult.SUCCESS;
		}

		if (player.isShiftKeyDown()) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) depositWornSet(serverPlayer, platform);
			return InteractionResult.SUCCESS;
		}
		ArmorVariant requestedVariant = variantForCatalyst(stack);
		if (level.isClientSide()) return requestedVariant != null
				? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND;
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;
		if (requestedVariant != null) {
			ChargingPlatformBlockEntity.VariantResult variantResult =
					platform.applyVariant(serverPlayer, requestedVariant);
			if (!variantResult.success()) {
				serverPlayer.sendOverlayMessage(Component.literal(variantResult.message()));
				return InteractionResult.FAIL;
			}
			if (variantResult.changed() && !serverPlayer.getAbilities().instabuild) stack.shrink(1);
			serverPlayer.sendOverlayMessage(Component.literal(
					(variantResult.changed() ? "\u6218\u7532\u5df2\u91cd\u6784\u4e3a" : "\u6218\u7532\u5df2\u7ecf\u662f")
							+ requestedVariant.chineseName()
							+ (variantResult.changed() ? "\uff0c\u56db\u4ef6\u5df2\u540c\u6b65" : "")));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}


	private static ArmorVariant variantForCatalyst(ItemStack stack) {
		if (stack.is(Items.DIAMOND)) return ArmorVariant.VANGUARD;
		if (stack.is(Items.ECHO_SHARD)) return ArmorVariant.SWIFT;
		if (stack.is(Items.HEART_OF_THE_SEA)) return ArmorVariant.SENTINEL;
		return null;
	}
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
			Player player, BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform)) {
			return InteractionResult.FAIL;
		}
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;

		if (player.isShiftKeyDown()) {
			depositWornSet(serverPlayer, platform);
			return InteractionResult.SUCCESS;
		}
		if (platform.isComplete()) {
			ArmorSuitPanelController.openPlatform(serverPlayer, platform);
			return InteractionResult.SUCCESS;
		}

		ChargingPlatformBlockEntity.ToggleResult result = platform.toggleDisplayMode(serverPlayer);
		if (!result.success()) {
			serverPlayer.sendOverlayMessage(Component.literal(result.message()));
			return InteractionResult.SUCCESS;
		}
		serverPlayer.sendOverlayMessage(Component.literal(
				result.mode() == ChargingPlatformDisplayMode.PARTS
						? "\u5145\u80fd\u5e73\u53f0\uff1a\u5206\u4ef6\u60ac\u6d6e\u9648\u5217\uff1b\u6309 B \u65f6\u5404\u90e8\u4ef6\u4f1a\u5206\u522b\u98de\u6765"
						: "\u5145\u80fd\u5e73\u53f0\uff1a\u6574\u7532\u5f85\u673a\u9648\u5217"));
		return InteractionResult.SUCCESS;
	}

	private static void depositWornSet(ServerPlayer player, ChargingPlatformBlockEntity platform) {
		if (ArmorAssemblyController.isAnimating(player)) {
			player.sendOverlayMessage(Component.literal("战甲部件正在移动，不能同时存入平台"));
			return;
		}
		if (ArmorCoreController.hasActiveQuickSwitch(player)) {
			player.sendOverlayMessage(Component.literal("战甲快速切换进行中，不能同时存入平台"));
			return;
		}
		if (!ModItems.hasFullSet(player)) {
			player.sendOverlayMessage(Component.literal(
					"需要先穿齐头盔、胸甲、护腿和战靴，再按住 Shift 右键平台"));
			return;
		}
		ItemStack wornChest = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
		ChargingPlatformBlockEntity.CompleteInsertResult result =
				platform.insertCompleteSet(player, wornChest);
		if (!result.success()) {
			player.sendOverlayMessage(Component.literal(result.message()));
			return;
		}
		ArmorCoreController.bindPlatformIfCoreEquipped(player, platform);
		player.sendOverlayMessage(Component.literal("身上完整战甲已整体归还平台"));
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		if (!state.is(level.getBlockState(pos).getBlock())
				&& level.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform) {
			for (ItemStack stack : platform.releaseForBlockRemoval()) Block.popResource(level, pos, stack);
		}
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}
}
