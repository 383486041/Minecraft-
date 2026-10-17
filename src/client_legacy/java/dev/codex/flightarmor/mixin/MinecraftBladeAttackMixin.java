package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把原版左键攻击从「挖矿/空挥」手里接管过来，按当前模式分派：
 * <ul>
 *   <li>已切进灭世光束模式 → 左键整体让给手炮扳机（蓄力/开火由 {@code ArmorCombatClient.tick}
 *       轮询按下状态驱动），这里只负责把原版行为吞掉；</li>
 *   <li>否则 → 走已展开光刃的自编连段时间线（撞到方块时仍保持原版，光刃不该禁用挖矿）。</li>
 * </ul>
 * 顺序不能反：两种模式都想吃左键，先问手炮才不会有「一按同时出光刃和手炮」。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftBladeAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void flightArmor$routeBladePrimaryAttack(CallbackInfoReturnable<Boolean> callback) {
		Minecraft client = (Minecraft) (Object) this;
		if (ArmorCombatClient.handBeamArmed()) {
			callback.setReturnValue(true);
			return;
		}
		if (ArmorCombatClient.tryPrimaryBladeAttack(client)) {
			callback.setReturnValue(true);
		}
	}
}