package dev.codex.flightarmor.item;

import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.core.ArmorCoreIdentity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/** 装甲网络授权介质；物品本身不保存战甲，真实四件仍在平台、玩家或载体中。 */
public final class ArmorNavigationCoreItem extends Item {
	public ArmorNavigationCoreItem(Properties properties) {
		super(properties.stacksTo(1).fireResistant());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			ArmorCoreController.activateAndOpen(serverPlayer, hand);
		}
		return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
	}

	/**
	 * Keep ordinary block interactions intact. Charging platforms consume the bind branch;
	 * non-platform blocks use default block behavior.
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		// ChargingPlatformBlock consumes held-core interaction before Item#useOn runs.
		// Every other block keeps its normal interaction path.
		return InteractionResult.PASS;
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return ArmorCoreIdentity.read(stack).isPresent();
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
			List<Component> tooltip, TooltipFlag flag) {
		ArmorCoreIdentity.read(stack).ifPresentOrElse(
				identity -> tooltip.add(Component.literal("已认主 · 核心 "
						+ identity.coreId().toString().substring(0, 8)).withStyle(ChatFormatting.AQUA)),
				() -> tooltip.add(Component.literal("\u653E\u5165\u6838\u5FC3\u88C5\u5907\u680F\uFF0C\u6216\u624B\u6301\u53F3\u952E\u6FC0\u6D3B\u5E76\u8BA4\u4E3B")
						.withStyle(ChatFormatting.GRAY)));
		tooltip.add(Component.literal("\u624B\u6301\u53F3\u952E\u5E73\u53F0\u7ED1\u5B9A\uFF1B\u6309 K \u6253\u5F00\u6838\u5FC3\u9762\u677F")
				.withStyle(ChatFormatting.DARK_AQUA));
		tooltip.add(Component.literal("\u666E\u901A\u65B9\u5757\u53F3\u952E\u4E0D\u4F1A\u5207\u6362\u6216\u6E05\u7A7A\u6838\u5FC3\u7F51\u7EDC")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
