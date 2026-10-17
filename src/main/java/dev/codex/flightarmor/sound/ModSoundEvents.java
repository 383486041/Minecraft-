package dev.codex.flightarmor.sound;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * 凌空战甲的统一声音事件表。
 *
 * <p>业务逻辑只引用这里的事件，不直接拼接资源路径。随机变体、流式读取与具体音频文件
 * 均由 {@code sounds.json} 管理。</p>
 */
public final class ModSoundEvents {
	public static final SoundEvent ASSEMBLY_RECALL_START = register("armor.assembly.recall_start");
	public static final SoundEvent ASSEMBLY_INBOUND_LIGHT = register("armor.assembly.inbound_light");
	public static final SoundEvent ASSEMBLY_INBOUND_MEDIUM = register("armor.assembly.inbound_medium");
	public static final SoundEvent ASSEMBLY_INBOUND_HEAVY = register("armor.assembly.inbound_heavy");
	public static final SoundEvent ASSEMBLY_INBOUND_HELMET = register("armor.assembly.inbound_helmet");
	public static final SoundEvent ASSEMBLY_INBOUND_FACEPLATE = register("armor.assembly.inbound_faceplate");
	public static final SoundEvent ASSEMBLY_LOCK_BOOT = register("armor.assembly.lock_boot");
	public static final SoundEvent ASSEMBLY_LOCK_LIMB = register("armor.assembly.lock_limb");
	public static final SoundEvent ASSEMBLY_LOCK_TORSO = register("armor.assembly.lock_torso");
	public static final SoundEvent ASSEMBLY_HELMET_SEAL = register("armor.assembly.helmet_seal");
	public static final SoundEvent ASSEMBLY_CORE_CHARGE = register("armor.assembly.core_charge");
	public static final SoundEvent ASSEMBLY_CORE_LOCK = register("armor.assembly.core_lock");
	public static final SoundEvent ASSEMBLY_UNLOCK_BOOT = register("armor.assembly.unlock_boot");
	public static final SoundEvent ASSEMBLY_UNLOCK_LIMB = register("armor.assembly.unlock_limb");
	public static final SoundEvent ASSEMBLY_UNLOCK_TORSO = register("armor.assembly.unlock_torso");
	public static final SoundEvent ASSEMBLY_RETRACT_LIGHT = register("armor.assembly.retract_light");
	public static final SoundEvent ASSEMBLY_RETRACT_MEDIUM = register("armor.assembly.retract_medium");
	public static final SoundEvent ASSEMBLY_RETRACT_HEAVY = register("armor.assembly.retract_heavy");

	public static final SoundEvent CORE_WAKE = register("armor.core.wake");
	public static final SoundEvent CORE_ONLINE = register("armor.core.online");
	public static final SoundEvent CORE_IDLE_INT = register("armor.core.idle_int");
	public static final SoundEvent CORE_IDLE_EXT = register("armor.core.idle_ext");
	public static final SoundEvent CORE_SHUTDOWN = register("armor.core.shutdown");

	public static final SoundEvent FLIGHT_ENGAGE = register("armor.flight.engage");
	public static final SoundEvent FLIGHT_DISENGAGE = register("armor.flight.disengage");
	public static final SoundEvent FLIGHT_HOVER_INT_LOOP = register("armor.flight.hover_int_loop");
	public static final SoundEvent FLIGHT_HOVER_EXT_LOOP = register("armor.flight.hover_ext_loop");
	public static final SoundEvent FLIGHT_FIELD_LOOP = register("armor.flight.field_loop");
	public static final SoundEvent FLIGHT_AIR_INT_LOOP = register("armor.flight.air_int_loop");
	public static final SoundEvent FLIGHT_AIR_EXT_LOOP = register("armor.flight.air_ext_loop");
	public static final SoundEvent FLIGHT_BOOST_ENTER = register("armor.flight.boost_enter");
	public static final SoundEvent FLIGHT_BOOST_LOOP = register("armor.flight.boost_loop");
	public static final SoundEvent FLIGHT_BOOST_RELEASE = register("armor.flight.boost_release");
	public static final SoundEvent FLIGHT_DIVE_ENTER = register("armor.flight.dive_enter");
	public static final SoundEvent FLIGHT_DIVE_LOOP = register("armor.flight.dive_loop");
	public static final SoundEvent FLIGHT_DIVE_RECOVER = register("armor.flight.dive_recover");
	public static final SoundEvent FLIGHT_LAND_SOFT = register("armor.flight.land_soft");
	public static final SoundEvent FLIGHT_LAND_HARD = register("armor.flight.land_hard");
	public static final SoundEvent FLIGHT_FLYBY = register("armor.flight.flyby");
	public static final SoundEvent FLIGHT_SONIC_INTERNAL = register("armor.flight.sonic_internal");
	public static final SoundEvent FLIGHT_SONIC_EXTERNAL = register("armor.flight.sonic_external");

	public static final SoundEvent UNDERWATER_HOVER_LOOP = register("armor.underwater.hover_loop");
	public static final SoundEvent UNDERWATER_FIELD_LOOP = register("armor.underwater.field_loop");
	public static final SoundEvent UNDERWATER_BOOST_ENTER = register("armor.underwater.boost_enter");
	public static final SoundEvent UNDERWATER_DIVE_LOOP = register("armor.underwater.dive_loop");

	// 武器事件保持独立命名，避免复用飞行加速或着甲锁定音造成混音语义冲突。
	public static final SoundEvent WEAPON_WRIST_PULSE_FIRE_EXT =
			register("armor.weapon.wrist_pulse_fire_ext");
	public static final SoundEvent WEAPON_WRIST_PULSE_FIRE_INT =
			register("armor.weapon.wrist_pulse_fire_int");
	public static final SoundEvent WEAPON_WRIST_PULSE_IMPACT =
			register("armor.weapon.wrist_pulse_impact");
	public static final SoundEvent WEAPON_CORE_CHARGE_INT_LOOP =
			register("armor.weapon.core_charge_int_loop");
	public static final SoundEvent WEAPON_CORE_CHARGE_EXT_LOOP =
			register("armor.weapon.core_charge_ext_loop");
	public static final SoundEvent WEAPON_CORE_RELEASE_INT =
			register("armor.weapon.core_release_int");
	public static final SoundEvent WEAPON_CORE_RELEASE_EXT =
			register("armor.weapon.core_release_ext");
	public static final SoundEvent WEAPON_CORE_CANCEL =
			register("armor.weapon.core_cancel");
	public static final SoundEvent WEAPON_CORE_IMPACT =
			register("armor.weapon.core_impact");
	public static final SoundEvent WEAPON_GRAVITY_IMPACT_INT =
			register("armor.weapon.gravity_impact_int");
	public static final SoundEvent WEAPON_GRAVITY_IMPACT_EXT =
			register("armor.weapon.gravity_impact_ext");
	/**
	 * A deliberately restrained explosion report used only when this mod
	 * explicitly selects it through the long {@code ServerLevel.explode}
	 * overload. Vanilla TNT, creepers and other mods keep their normal mix.
	 */
	public static final SoundEvent WEAPON_EXPLOSION_LIGHT =
			register("armor.weapon.explosion_light");
	public static final SoundEvent WEAPON_BLADE_DEPLOY_INT =
			register("armor.weapon.blade_deploy_int");
	public static final SoundEvent WEAPON_BLADE_DEPLOY_EXT =
			register("armor.weapon.blade_deploy_ext");
	public static final SoundEvent WEAPON_BLADE_RETRACT_INT =
			register("armor.weapon.blade_retract_int");
	public static final SoundEvent WEAPON_BLADE_RETRACT_EXT =
			register("armor.weapon.blade_retract_ext");
	public static final SoundEvent WEAPON_BLADE_HUM_INT =
			register("armor.weapon.blade_hum_int");
	public static final SoundEvent WEAPON_BLADE_HUM_EXT =
			register("armor.weapon.blade_hum_ext");
	public static final SoundEvent WEAPON_BLADE_SLASH_INT =
			register("armor.weapon.blade_slash_int");
	public static final SoundEvent WEAPON_BLADE_SLASH_EXT =
			register("armor.weapon.blade_slash_ext");

	// 控制器使用的简短别名，全部指向上面的同一注册事件。
	public static final SoundEvent CORE_LOOP = CORE_IDLE_INT;
	public static final SoundEvent HOVER_LOOP = FLIGHT_HOVER_INT_LOOP;
	public static final SoundEvent FIELD_LOOP = FLIGHT_FIELD_LOOP;
	public static final SoundEvent AIR_LOOP = FLIGHT_AIR_INT_LOOP;
	public static final SoundEvent BOOST_ENTER = FLIGHT_BOOST_ENTER;
	public static final SoundEvent BOOST_LOOP = FLIGHT_BOOST_LOOP;
	public static final SoundEvent BOOST_RELEASE = FLIGHT_BOOST_RELEASE;
	public static final SoundEvent DIVE_ENTER = FLIGHT_DIVE_ENTER;
	public static final SoundEvent DIVE_LOOP = FLIGHT_DIVE_LOOP;
	public static final SoundEvent DIVE_RECOVER = FLIGHT_DIVE_RECOVER;

	private ModSoundEvents() {
	}

	/**
	 * 主初始化器调用此方法可明确触发类加载，实际注册在静态字段初始化阶段完成。
	 */
	public static void initialize() {
		FlightArmorMod.LOGGER.info("凌空战甲声音事件已注册");
	}

	public static Holder<SoundEvent> sonicExplosionSound() {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(FLIGHT_SONIC_EXTERNAL);
	}

	public static Holder<SoundEvent> lightweightWeaponExplosionSound() {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(WEAPON_EXPLOSION_LIGHT);
	}

	private static SoundEvent register(String path) {
		Identifier id = FlightArmorMod.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}
}
