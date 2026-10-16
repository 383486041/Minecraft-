package dev.codex.flightarmor.combat;

/** 战甲武器的服务端硬参数，客户端展示不能覆盖这些值。 */
public final class ArmorCombatTuning {
	/** 高密度反重力核心：正常游玩中近乎不会耗尽，热量仍负责限制瞬时连续输出。 */
	public static final float MAX_ENERGY = 10_000.0F;
	public static final float MAX_HEAT = 100.0F;
	public static final float OVERHEAT_UNLOCK_HEAT = 60.0F;
	public static final float HEAT_DISSIPATION_PER_SECOND = 12.0F;
	public static final int PASSIVE_RECOVERY_DELAY_TICKS = 20;
	public static final float CRUISE_RECOVERY_PER_SECOND = 50.0F;
	public static final float FLIGHT_RECOVERY_PER_SECOND = 25.0F;
	public static final float MOVING_RECOVERY_PER_SECOND = 50.0F;
	public static final float IDLE_RECOVERY_PER_SECOND = 100.0F;

	public static final double WRIST_RANGE = 36.0D;
	public static final float WRIST_DAMAGE = 12.0F;
	public static final double WRIST_KNOCKBACK = 1.10D;
	public static final float WRIST_ENERGY_COST = 1.0F;
	public static final int WRIST_MIN_CHARGE_TICKS = 2;
	public static final int WRIST_MAX_CHARGE_TICKS = 24;
	public static final int WRIST_MAX_HOLD_TICKS = 80;
	public static final float WRIST_MAX_DAMAGE_MULTIPLIER = 2.4F;
	public static final double WRIST_MAX_RANGE_MULTIPLIER = 1.35D;
	public static final double WRIST_MAX_KNOCKBACK_MULTIPLIER = 1.55D;
	public static final float WRIST_MAX_ENERGY_COST = 4.0F;
	public static final float WRIST_MAX_HEAT = 10.0F;
	/** Native blast is a reward for a deliberate charge, never for a tap shot. */
	public static final float WRIST_EXPLOSION_CHARGE_THRESHOLD = 0.72F;

	public static final double UNDERWATER_WRIST_RANGE = 30.0D;
	public static final float UNDERWATER_WRIST_DAMAGE = 10.0F;
	public static final double UNDERWATER_WRIST_KNOCKBACK = 0.65D;
	public static final float UNDERWATER_WRIST_ENERGY_COST = 1.25F;
	public static final double WRIST_BLAST_RADIUS = 2.6D;
	public static final float WRIST_BLAST_DAMAGE_FACTOR = 0.50F;
	public static final double WRIST_MAX_BLAST_RADIUS = 5.2D;

	public static final float WRIST_HEAT = 3.0F;
	public static final int WRIST_COOLDOWN_TICKS = 8;
	public static final int AUTO_WRIST_COOLDOWN_TICKS = 12;
	public static final int WRIST_FIRING_TICKS = 3;
	public static final int WRIST_RECOVERY_TICKS = 3;
	public static final float BLADE_SLASH_ENERGY_COST = 2.0F;
	public static final float BLADE_SLASH_HEAT = 4.0F;
	public static final int BLADE_SLASH_COOLDOWN_TICKS = 20;
	/** Damage is resolved on this authored swing frame instead of key-down. */
	public static final int BLADE_SLASH_WINDUP_TICKS = 10;
	public static final int BLADE_SLASH_FIRING_TICKS = 4;
	public static final int BLADE_SLASH_RECOVERY_TICKS = 6;
	public static final float BLADE_COMBO_ENERGY_COST = 0.35F;
	public static final float BLADE_COMBO_HEAT = 0.75F;
	public static final int BLADE_COMBO_COOLDOWN_TICKS = 4;
	public static final int BLADE_COMBO_WINDUP_TICKS = 3;
	public static final int BLADE_COMBO_FIRING_TICKS = 3;
	public static final int BLADE_COMBO_RECOVERY_TICKS = 2;
	public static final int BLADE_COMBO_RESET_TICKS = 24;

	public static final int CORE_MIN_CHARGE_TICKS = 6;
	public static final int CORE_MAX_CHARGE_TICKS = 30;
	public static final int CORE_MAX_HOLD_TICKS = 100;
	public static final double CORE_MIN_RANGE = 44.0D;
	public static final double CORE_MAX_RANGE = 72.0D;
	public static final float CORE_MIN_DAMAGE = 10.0F;
	public static final float CORE_MAX_DAMAGE = 36.0F;
	public static final float CORE_MIN_ENERGY_COST = 4.0F;
	public static final float CORE_MAX_ENERGY_COST = 15.0F;
	public static final float CORE_MIN_HEAT = 5.0F;
	public static final float CORE_MAX_HEAT = 20.0F;
	public static final double CORE_MIN_KNOCKBACK = 0.80D;
	public static final double CORE_MAX_KNOCKBACK = 2.20D;
	public static final double CORE_MIN_BLAST_RADIUS = 2.5D;
	public static final double CORE_MAX_BLAST_RADIUS = 5.0D;
	public static final float CORE_BLAST_DAMAGE_FACTOR = 0.60F;
	/** Swift keeps its penetrator identity until an almost-full chest-cannon charge. */
	public static final float SWIFT_CORE_EXPLOSION_CHARGE_THRESHOLD = 0.84F;
	public static final int CORE_COOLDOWN_TICKS = 32;
	public static final int CORE_FIRING_TICKS = 5;
	public static final int CORE_RECOVERY_TICKS = 8;

	public static final double DIVE_IMPACT_MIN_SPEED = 0.85D;
	public static final double DIVE_IMPACT_MID_SPEED = 1.25D;
	public static final double DIVE_IMPACT_HIGH_SPEED = 1.65D;
	public static final float DIVE_IMPACT_LOW_DAMAGE = 12.0F;
	public static final float DIVE_IMPACT_MID_DAMAGE = 24.0F;
	public static final float DIVE_IMPACT_HIGH_DAMAGE = 40.0F;
	public static final double DIVE_IMPACT_LOW_RADIUS = 3.5D;
	public static final double DIVE_IMPACT_MID_RADIUS = 5.5D;
	public static final double DIVE_IMPACT_HIGH_RADIUS = 8.0D;
	public static final double DIVE_IMPACT_MAX_KNOCKBACK = 2.4D;
	public static final float DIVE_IMPACT_ENERGY_COST = 6.0F;
	public static final float DIVE_IMPACT_HEAT = 10.0F;
	public static final int DIVE_IMPACT_COOLDOWN_TICKS = 24;
	public static final int DIVE_IMPACT_FIRING_TICKS = 7;
	public static final int DIVE_IMPACT_RECOVERY_TICKS = 8;
	public static final int DIVE_IMPACT_MAX_TARGETS = 96;

	/** Vanguard escort ram; velocity and distance are both server-authoritative. */
	public static final double CRUISE_RAM_MIN_SPEED = 0.80D;
	public static final double CRUISE_RAM_MAX_TARGET_DISTANCE = 3.25D;
	public static final float CRUISE_RAM_BASE_DAMAGE = 16.0F;
	public static final float CRUISE_RAM_SPEED_DAMAGE = 7.0F;
	public static final float CRUISE_RAM_MAX_DAMAGE = 30.0F;
	public static final double CRUISE_RAM_KNOCKBACK = 1.35D;
	public static final float CRUISE_RAM_ENERGY_COST = 2.0F;
	public static final float CRUISE_RAM_HEAT = 5.0F;
	public static final int CRUISE_RAM_COOLDOWN_TICKS = 30;
	public static final int CRUISE_RAM_FIRING_TICKS = 6;
	/** Only a committed high-speed collision creates a native air-burst. */
	public static final double CRUISE_RAM_EXPLOSION_SPEED_THRESHOLD = 1.55D;

	/**
	 * Vanguard cruise ground slam. This is a separate vertical maneuver and never
	 * reuses the forward-ram commit path or its visual type.
	 */
	public static final double CRUISE_SLAM_MIN_DOWNWARD_SPEED = 0.90D;
	/** Normalized downward component; prevents a fast diagonal ram from masquerading as a slam. */
	public static final double CRUISE_SLAM_MIN_DOWNWARD_COMPONENT = 0.88D;
	/** The committed contact must remain directly beneath the carrier, not merely nearby. */
	public static final double CRUISE_SLAM_MAX_HORIZONTAL_CONTACT_DISTANCE = 1.35D;
	public static final double CRUISE_SLAM_MAX_VERTICAL_CONTACT_DISTANCE = 2.25D;
	/** Thin collision-shape probe around the reported surface; accepts slabs and stairs. */
	public static final double CRUISE_SLAM_GROUND_PROBE_RADIUS = 0.28D;
	public static final double CRUISE_SLAM_GROUND_PROBE_DEPTH = 0.34D;
	public static final float CRUISE_SLAM_DAMAGE = 26.0F;
	public static final double CRUISE_SLAM_RADIUS = 5.5D;
	public static final double CRUISE_SLAM_KNOCKBACK = 2.10D;
	public static final float CRUISE_SLAM_ENERGY_COST = 3.0F;
	public static final float CRUISE_SLAM_HEAT = 7.0F;
	public static final int CRUISE_SLAM_COOLDOWN_TICKS = 42;
	public static final int CRUISE_SLAM_FIRING_TICKS = 9;

	/** Sentinel escort chest cannon; every physical carrier owns an isolated runtime lane. */
	public static final double CRUISE_CORE_RANGE = 52.0D;
	public static final float CRUISE_CORE_DAMAGE = 18.0F;
	public static final double CRUISE_CORE_KNOCKBACK = 1.45D;
	public static final float CRUISE_CORE_ENERGY_COST = 4.0F;
	public static final float CRUISE_CORE_HEAT = 7.0F;
	public static final int CRUISE_CORE_COOLDOWN_TICKS = 42;
	public static final int CRUISE_CORE_FIRING_TICKS = 7;
	/** Small non-explosive shot emitted by one detached Sentinel back pod. */
	public static final double SENTINEL_DRONE_PULSE_RANGE = 48.0D;
	public static final float SENTINEL_DRONE_PULSE_DAMAGE = 5.5F;
	public static final double SENTINEL_DRONE_PULSE_KNOCKBACK = 0.38D;
	public static final float SENTINEL_DRONE_PULSE_ENERGY_COST = 0.75F;
	public static final float SENTINEL_DRONE_PULSE_HEAT = 1.25F;
	public static final int SENTINEL_DRONE_PULSE_COOLDOWN_TICKS = 12;
	public static final int SENTINEL_DRONE_PULSE_FIRING_TICKS = 4;

	/** Swift escort missile rain; every missile is resolved by a delayed server task. */
	public static final double CRUISE_MISSILE_RANGE = 56.0D;
	public static final int CRUISE_MISSILE_MAX_TARGETS = 6;
	public static final float CRUISE_MISSILE_DAMAGE = 8.0F;
	public static final double CRUISE_MISSILE_KNOCKBACK = 0.55D;
	public static final float CRUISE_MISSILE_ENERGY_PER_TARGET = 0.75F;
	public static final float CRUISE_MISSILE_BASE_HEAT = 2.0F;
	public static final float CRUISE_MISSILE_HEAT_PER_TARGET = 0.80F;
	public static final int CRUISE_MISSILE_COOLDOWN_TICKS = 34;
	public static final int CRUISE_MISSILE_BASE_FLIGHT_TICKS = 8;
	public static final int CRUISE_MISSILE_STAGGER_TICKS = 2;

	private ArmorCombatTuning() {
	}
}
