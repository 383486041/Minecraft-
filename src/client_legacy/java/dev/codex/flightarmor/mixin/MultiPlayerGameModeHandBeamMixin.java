package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 灭世光束模式下的左键必须整体让给扳机，包括**按住不放的持续挖掘**。
 *
 * <p>{@code Minecraft.startAttack} 只覆盖「按下的那一下」，持续按住时方块破坏走的是
 * {@code handleInputEvents} 里另一次调用：{@code MultiPlayerGameMode.continueDestroyBlock}。
 * 不拦这里，玩家按住扳机蓄力时会把准星指着的方块一路挖穿——一边蓄灭世光束一边挖矿，
 * 看起来就像「手炮把地挖没了」。</p>
 *
 * <p>只拦破坏，不动 {@code startDestroyBlock}/{@code attack}：那两个的入口都在
 * {@code startAttack} 里，已经在 {@code MinecraftBladeAttackMixin} 被整体吞掉了。</p>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeHandBeamMixin {
	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void flightArmor$suppressMiningWhileBeamArmed(BlockPos position, Direction direction,
			CallbackInfoReturnable<Boolean> callback) {
		if (ArmorCombatClient.handBeamArmed()) {
			callback.setReturnValue(false);
		}
	}
}
