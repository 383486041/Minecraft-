package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Consumes managed-carrier damage before ArmorStand invulnerability or break/drop logic. The exact
 * original DamageSource is forwarded, preserving both direct and causing attacker attribution.
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandIntegrityMixin {
	@Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
	private void flightArmor$consumeCarrierDamage(DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> callback) {
		ArmorStand carrier = (ArmorStand) (Object) this;
		if (!ArmorIntegrityController.isManagedCarrier(carrier)) return;
		callback.setReturnValue(ArmorIntegrityController.handleCarrierDamage(carrier, source, amount));
	}
}
