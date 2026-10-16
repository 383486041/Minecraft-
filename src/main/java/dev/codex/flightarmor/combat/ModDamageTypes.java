package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 战甲武器伤害类型入口；数据包缺失时不会令服务端崩溃。 */
final class ModDamageTypes {
	static final ResourceKey<DamageType> GRAVITY_PULSE = ResourceKey.create(
			Registries.DAMAGE_TYPE, FlightArmorMod.id("gravity_pulse"));
	static final ResourceKey<DamageType> CORE_BEAM = ResourceKey.create(
			Registries.DAMAGE_TYPE, FlightArmorMod.id("core_beam"));
	static final ResourceKey<DamageType> GRAVITY_IMPACT = ResourceKey.create(
			Registries.DAMAGE_TYPE, FlightArmorMod.id("gravity_impact"));
	static final ResourceKey<DamageType> ARMOR_MISSILE = ResourceKey.create(
			Registries.DAMAGE_TYPE, FlightArmorMod.id("armor_missile"));
	private static final AtomicBoolean FALLBACK_REPORTED = new AtomicBoolean();

	private ModDamageTypes() {
	}

	static DamageSource gravityPulse(ServerPlayer attacker) {
		return source(attacker, GRAVITY_PULSE, "gravity_pulse");
	}

	static DamageSource gravityPulse(ServerPlayer attacker, Entity directSource) {
		return source(attacker, directSource, GRAVITY_PULSE, "gravity_pulse");
	}

	static DamageSource coreBeam(ServerPlayer attacker) {
		return source(attacker, CORE_BEAM, "core_beam");
	}

	static DamageSource coreBeam(ServerPlayer attacker, Entity directSource) {
		return source(attacker, directSource, CORE_BEAM, "core_beam");
	}

	static DamageSource gravityImpact(ServerPlayer attacker) {
		return source(attacker, GRAVITY_IMPACT, "gravity_impact");
	}

	static DamageSource gravityImpact(ServerPlayer attacker, Entity directSource) {
		return source(attacker, directSource, GRAVITY_IMPACT, "gravity_impact");
	}

	static DamageSource armorMissile(ServerPlayer attacker, ArmorHomingMissileEntity missile) {
		Optional<Holder.Reference<DamageType>> holder = attacker.registryAccess()
				.registryOrThrow(Registries.DAMAGE_TYPE)
				.getHolder(ARMOR_MISSILE);
		if (holder.isPresent()) {
			// DamageSource constructor order is direct entity, then causing entity.
			return new DamageSource(holder.get(), missile, attacker);
		}
		if (FALLBACK_REPORTED.compareAndSet(false, true)) {
			FlightArmorMod.LOGGER.warn("Missing flight_armor:armor_missile damage type; using indirectMagic");
		}
		return attacker.damageSources().indirectMagic(missile, attacker);
	}

	private static DamageSource source(ServerPlayer attacker, Entity directSource,
			ResourceKey<DamageType> key, String path) {
		Entity direct = directSource == null ? attacker : directSource;
		Optional<Holder.Reference<DamageType>> holder = attacker.registryAccess()
				.registryOrThrow(Registries.DAMAGE_TYPE)
				.getHolder(key);
		if (holder.isPresent()) {
			return new DamageSource(holder.get(), direct, attacker);
		}
		if (FALLBACK_REPORTED.compareAndSet(false, true)) {
			FlightArmorMod.LOGGER.warn("Missing flight_armor:{} damage type; using indirectMagic", path);
		}
		return attacker.damageSources().indirectMagic(direct, attacker);
	}

	private static DamageSource source(ServerPlayer attacker,
			ResourceKey<DamageType> key, String path) {
		Optional<Holder.Reference<DamageType>> holder = attacker.registryAccess()
				.registryOrThrow(Registries.DAMAGE_TYPE)
				.getHolder(key);
		if (holder.isPresent()) {
			// hitscan 没有弹丸实体，direct 与 causing entity 都归属发射玩家。
			return new DamageSource(holder.get(), attacker, attacker);
		}
		if (FALLBACK_REPORTED.compareAndSet(false, true)) {
			FlightArmorMod.LOGGER.warn("未找到 flight_armor:{} 伤害类型，本次运行回退为 indirectMagic", path);
		}
		return attacker.damageSources().indirectMagic(attacker, attacker);
	}
}
