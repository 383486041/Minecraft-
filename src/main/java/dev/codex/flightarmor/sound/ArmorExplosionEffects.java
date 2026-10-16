package dev.codex.flightarmor.sound;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Opt-in explosion helpers for Flight Armor effects.
 *
 * <p>The important boundary is that this class never intercepts Minecraft
 * audio globally. Only a call site that invokes one of these methods receives
 * the quieter sound event, so TNT and creepers remain completely untouched.</p>
 */
public final class ArmorExplosionEffects {
	private ArmorExplosionEffects() {
	}

	public static Explosion explodeWithSonicReport(ServerLevel level, Entity source,
			Vec3 center, float power, boolean fire, Level.ExplosionInteraction interaction) {
		return explode(level, source, null, null, center, power, fire, interaction,
				ModSoundEvents.sonicExplosionSound());
	}

	/**
	 * Reusable API for chest-beam, charged wrist-cannon and selected ram
	 * explosions. It keeps native Minecraft explosion damage/knockback and
	 * particles while replacing only this call's report with the lighter mix.
	 */
	public static Explosion explodeWithLightReport(ServerLevel level, Entity source,
			Vec3 center, float power, boolean fire, Level.ExplosionInteraction interaction) {
		return explode(level, source, null, null, center, power, fire, interaction,
				ModSoundEvents.lightweightWeaponExplosionSound());
	}

	public static Explosion explodeWithLightReport(ServerLevel level, Entity source,
			DamageSource damageSource, ExplosionDamageCalculator damageCalculator,
			Vec3 center, float power, boolean fire, Level.ExplosionInteraction interaction) {
		return explode(level, source, damageSource, damageCalculator, center, power, fire,
				interaction, ModSoundEvents.lightweightWeaponExplosionSound());
	}

	private static Explosion explode(ServerLevel level, Entity source,
			DamageSource damageSource, ExplosionDamageCalculator damageCalculator,
			Vec3 center, float power, boolean fire, Level.ExplosionInteraction interaction,
			Holder<SoundEvent> sound) {
		if (level == null || center == null || interaction == null
				|| !finite(center) || !Float.isFinite(power) || power <= 0.0F) {
			throw new IllegalArgumentException("invalid armor explosion");
		}
		ParticleOptions small = ParticleTypes.EXPLOSION;
		ParticleOptions large = ParticleTypes.EXPLOSION_EMITTER;
		return level.explode(source, damageSource, damageCalculator,
				center.x, center.y, center.z, power, fire, interaction,
				small, large, sound);
	}

	private static boolean finite(Vec3 value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y)
				&& Double.isFinite(value.z);
	}
}
