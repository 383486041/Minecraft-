package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Entity registrations owned by the combat subsystem. */
public final class ModCombatEntities {
	public static final ResourceKey<EntityType<?>> HOMING_MISSILE_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, FlightArmorMod.id("homing_missile"));
	public static final ResourceKey<EntityType<?>> SENTINEL_WINGMAN_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, FlightArmorMod.id("sentinel_wingman"));

	public static final EntityType<ArmorHomingMissileEntity> HOMING_MISSILE =
			EntityType.Builder.<ArmorHomingMissileEntity>of(ArmorHomingMissileEntity::new, MobCategory.MISC)
					.sized(0.32F, 0.32F)
					.clientTrackingRange(12)
					.updateInterval(1)
					.build(HOMING_MISSILE_KEY);
	public static final EntityType<SentinelWingmanEntity> SENTINEL_WINGMAN =
			EntityType.Builder.<SentinelWingmanEntity>of(SentinelWingmanEntity::new, MobCategory.MISC)
					.sized(0.92F, 0.62F)
					.clientTrackingRange(18)
					.updateInterval(1)
					.build(SENTINEL_WINGMAN_KEY);

	private static boolean initialized;

	private ModCombatEntities() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		Registry.register(BuiltInRegistries.ENTITY_TYPE, HOMING_MISSILE_KEY, HOMING_MISSILE);
		Registry.register(BuiltInRegistries.ENTITY_TYPE, SENTINEL_WINGMAN_KEY, SENTINEL_WINGMAN);
		initialized = true;
	}
}
