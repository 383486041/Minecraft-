package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 服务端权威缩放完整同型号套装受到的击退，不改变客户端或其他生物。 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityVariantKnockbackMixin {
	@ModifyVariable(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double flightArmor$applyVariantKnockbackProfile(double strength) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof ServerPlayer player) || !Double.isFinite(strength)) return strength;
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(null);
		return variant == null ? strength : strength * variant.defenseProfile().knockbackMultiplier();
	}
}
