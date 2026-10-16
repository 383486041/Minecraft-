package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.cruise.ArmorCruiseController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版生成死亡掉落前，把完整飞行战甲交给唯一的死亡待机载体。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void flightArmor$captureWornArmorBeforeDrops(DamageSource source, CallbackInfo ci) {
		ArmorCruiseController.captureWornSetForDeath((ServerPlayer) (Object) this);
	}
}
