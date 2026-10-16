package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 在玩家难度修正之后、原版护甲计算之前缩放服务端入射伤害。
 * 只认完整同型号套装；创造无敌与绕过无敌的伤害完全交还原版处理。
 */
@Mixin(Player.class)
public abstract class PlayerVariantDamageMixin {
	@Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
	private void flightArmor$interceptOrdinaryHits(DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> callback) {
		Player self = (Player) (Object) this;
		if (!(self instanceof ServerPlayer player) || player.getAbilities().invulnerable
				|| source == null || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
				|| ModItems.fullSetVariant(player).isEmpty()) {
			return;
		}

		Entity direct = source.getDirectEntity();
		if (source.is(DamageTypeTags.IS_PROJECTILE) && direct instanceof Projectile projectile) {
			// AbstractArrow performs vanilla REVERSE deflection after hurt() returns
			// false. Running it here as well would reverse the arrow twice.
			if (!(projectile instanceof AbstractArrow)) {
				projectile.deflect(ProjectileDeflection.REVERSE, player, player, true);
			}
			player.level().broadcastEntityEvent(player, (byte) 29);
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.95F, 0.72F);
			callback.setReturnValue(false);
			return;
		}

		Entity attacker = source.getEntity();
		if (attacker instanceof Mob && !flightArmor$isHeavyThreat(attacker)) {
			// Zombies, skeleton melee, spiders and similar ordinary mobs cannot
			// impart a hurt flash or knockback through a sealed full suit.
			callback.setReturnValue(false);
		}
	}

	@ModifyArgs(method = "hurt", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
	private void flightArmor$applyVariantDamageProfile(Args args) {
		Player self = (Player) (Object) this;
		if (!(self instanceof ServerPlayer player) || player.getAbilities().invulnerable) return;

		DamageSource source = args.get(0);
		float amount = args.get(1);
		if (source == null || !Float.isFinite(amount) || amount <= 0.0F
				|| source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return;
		}
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(null);
		if (variant == null) return;

		// 穿齐同型号整套后坠落完全免疫：落地缓冲由装甲吸收，不再只靠飞行中清零 fallDistance。
		if (source.is(DamageTypes.FALL)) {
			args.set(1, 0.0F);
			return;
		}

		boolean flightCollision = source.is(DamageTypes.FLY_INTO_WALL);
		float scaled;
		if (flightArmor$isHeavyThreat(source.getEntity())) {
			float heavyMultiplier = switch (variant) {
				case VANGUARD -> 0.42F;
				case SWIFT -> 0.58F;
				case SENTINEL -> 0.28F;
			};
			scaled = amount * heavyMultiplier;
		} else {
			scaled = variant.defenseProfile().scaleDamage(amount, flightCollision);
		}
		if (Float.isFinite(scaled) && scaled >= 0.0F) {
			args.set(1, scaled);
		}
	}

	private static boolean flightArmor$isHeavyThreat(Entity attacker) {
		return attacker instanceof IronGolem
				|| attacker instanceof Warden
				|| attacker instanceof WitherBoss
				|| attacker instanceof EnderDragon
				|| attacker instanceof Ravager;
	}
}
