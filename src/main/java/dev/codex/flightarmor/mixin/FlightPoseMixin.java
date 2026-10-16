package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class FlightPoseMixin {
	@Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
	private void flightArmor$keepCustomDivePose(CallbackInfo callback) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (entity instanceof Player player && ModItems.hasFlightGear(player) && player.isFallFlying()) callback.cancel();
	}

	@Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFallFlying()Z"))
	private boolean flightArmor$keepCustomPhysics(LivingEntity entity) {
		if (entity instanceof Player player && ModItems.hasFlightGear(player) && entity.isFallFlying()) return false;
		return entity.isFallFlying();
	}

	@Redirect(
			method = {"travel", "handleRelativeFrictionAndCalculateMovement"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/LivingEntity;moveRelative(FLnet/minecraft/world/phys/Vec3;)V")
	)
	private void flightArmor$suppressDuplicateVanillaThrust(LivingEntity entity, float amount, Vec3 input) {
		if (entity instanceof Player player && FlightPhysics.isControllerActive(player)) return;
		entity.moveRelative(amount, input);
	}
}
