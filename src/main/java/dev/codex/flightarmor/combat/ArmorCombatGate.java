package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.assembly.ArmorAssemblyController;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.flight.FlightController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.platform.ChargingPlatformController;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.server.level.ServerPlayer;

/** 所有战甲武器共用的唯一服务端资格门禁。 */
public final class ArmorCombatGate {
	private ArmorCombatGate() {
	}

	public static Result evaluate(ServerPlayer player, ArmorAttackType type, ArmorAttackAction action,
			ArmorCombatState state, long now, float energyCost) {
		ArmorCombatDenyReason lifecycleReason = evaluateLifecycle(player, type, now);
		if (lifecycleReason != ArmorCombatDenyReason.NONE) return Result.deny(lifecycleReason);
		if ((type == ArmorAttackType.ENERGY_BLADE_TOGGLE
				|| type == ArmorAttackType.ENERGY_BLADE_SLASH
				|| type == ArmorAttackType.ENERGY_BLADE_COMBO)
				&& !ArmorConfigurationAccess.allowsHeavyMelee(player, now)) {
			return Result.deny(ArmorCombatDenyReason.UNSUPPORTED_ATTACK);
		}
		if ((type == ArmorAttackType.WRIST_GRAVITY_PULSE
				|| type == ArmorAttackType.CORE_BEAM
				|| type == ArmorAttackType.HAND_BEAM_PULSE
				|| type == ArmorAttackType.HAND_BEAM_LANCE)
				&& !ArmorConfigurationAccess.allowsRangedCombat(player, now)) {
			return Result.deny(ArmorCombatDenyReason.UNSUPPORTED_ATTACK);
		}
		if (!state.dimension.equals(player.level().dimension())) {
			return Result.deny(ArmorCombatDenyReason.DIMENSION_CHANGED);
		}
		if (state.overheated) return Result.deny(ArmorCombatDenyReason.OVERHEATED);
		return switch (type) {
			case WRIST_GRAVITY_PULSE -> evaluateWrist(action, state, now, energyCost);
			case CORE_BEAM -> evaluateCore(player, action, state, now, energyCost);
			case ENERGY_BLADE_TOGGLE -> action == ArmorAttackAction.PRESS
					&& state.phase == ArmorCombatPhase.IDLE
					? Result.allow() : Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
			case ENERGY_BLADE_SLASH -> evaluateBladeSlash(action, state, now, energyCost);
			case ENERGY_BLADE_COMBO -> evaluateBladeCombo(action, state, now, energyCost);
			case HAND_BEAM_PULSE, HAND_BEAM_LANCE ->
					evaluateHandBeam(action, state, now, energyCost);
			case GRAVITY_DIVE_IMPACT, CRUISE_RAM, CRUISE_CORE_BEAM,
					CRUISE_MISSILE_VOLLEY, CRUISE_GROUND_SLAM, INVALID ->
					Result.deny(ArmorCombatDenyReason.UNSUPPORTED_ATTACK);
		};
	}

	/**
	 * 增强能量光束两档共用同一个门禁。鼠标左键是扳机：按下发 {@code HAND_BEAM_LANCE} 的 PRESS
	 * 进入蓄力，松手发 RELEASE，**档位由服务端在释放那一刻按按住长度分流**
	 * （不足 {@link EnhancedBeamMath#LANCE_CLASSIFY_TICKS} 就是点射）。所以客户端不发分类包，
	 * 这里只校验状态机与资源，不重复一套计时。
	 *
	 * <p>PRESS 只收点射档的能量：蓄力本身不结算资源，真正的开销在释放时按蓄力档位算。</p>
	 *
	 * <p>两档共用 {@code state.handBeamCooldownUntilTick} 是有意的：点射与灭世之后都该有
	 * 恢复期，只是长度不同（2 tick 对 84 tick）。</p>
	 */
	private static Result evaluateHandBeam(ArmorAttackAction action, ArmorCombatState state,
			long now, float minimumEnergyCost) {
		if (action == ArmorAttackAction.PRESS) {
			if (state.phase != ArmorCombatPhase.IDLE) {
				return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
			}
			if (now < state.handBeamCooldownUntilTick) {
				return Result.deny(ArmorCombatDenyReason.COOLDOWN);
			}
			if (state.energy + 0.0001F < minimumEnergyCost) {
				return Result.deny(ArmorCombatDenyReason.NOT_ENOUGH_ENERGY);
			}
			return Result.allow();
		}
		if (action == ArmorAttackAction.RELEASE) {
			// 只有灭世档有可释放的蓄力；点射在 PRESS 上已经打完了。
			return state.phase == ArmorCombatPhase.CHARGING
					&& state.attackType == ArmorAttackType.HAND_BEAM_LANCE
					? Result.allow() : Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
		}
		return Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
	}

	private static Result evaluateWrist(ArmorAttackAction action, ArmorCombatState state,
			long now, float energyCost) {
		if (action == ArmorAttackAction.PRESS) {
			if (state.phase != ArmorCombatPhase.IDLE) return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
			if (now < state.wristCooldownUntilTick) return Result.deny(ArmorCombatDenyReason.COOLDOWN);
			if (state.energy + 0.0001F < energyCost) {
				return Result.deny(ArmorCombatDenyReason.NOT_ENOUGH_ENERGY);
			}
			return Result.allow();
		}
		if (action == ArmorAttackAction.RELEASE) {
			return state.phase == ArmorCombatPhase.CHARGING
					&& state.attackType == ArmorAttackType.WRIST_GRAVITY_PULSE
					? Result.allow() : Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
		}
		return Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
	}

	private static Result evaluateBladeCombo(ArmorAttackAction action, ArmorCombatState state,
			long now, float energyCost) {
		if (action != ArmorAttackAction.PRESS || !state.bladeExtended) {
			return Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
		}
		if (now < state.bladeReadyAtTick) return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
		boolean linkedRecovery = state.phase == ArmorCombatPhase.RECOVERY
				&& state.attackType == ArmorAttackType.ENERGY_BLADE_COMBO;
		if (state.phase != ArmorCombatPhase.IDLE && !linkedRecovery) {
			return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
		}
		if (now < state.wristCooldownUntilTick) return Result.deny(ArmorCombatDenyReason.COOLDOWN);
		if (state.energy + 0.0001F < energyCost) {
			return Result.deny(ArmorCombatDenyReason.NOT_ENOUGH_ENERGY);
		}
		return Result.allow();
	}
	private static Result evaluateBladeSlash(ArmorAttackAction action, ArmorCombatState state,
			long now, float energyCost) {
		if (action != ArmorAttackAction.PRESS || !state.bladeExtended) {
			return Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
		}
		if (now < state.bladeReadyAtTick) return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
		if (state.phase != ArmorCombatPhase.IDLE) return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
		if (now < state.wristCooldownUntilTick) return Result.deny(ArmorCombatDenyReason.COOLDOWN);
		if (state.energy + 0.0001F < energyCost) {
			return Result.deny(ArmorCombatDenyReason.NOT_ENOUGH_ENERGY);
		}
		return Result.allow();
	}

	private static Result evaluateCore(ServerPlayer player, ArmorAttackAction action,
			ArmorCombatState state, long now, float energyCost) {
		if (FlightController.isBoostActive(player)) return Result.deny(ArmorCombatDenyReason.BOOSTING);
		if (action == ArmorAttackAction.PRESS) {
			if (state.phase != ArmorCombatPhase.IDLE) return Result.deny(ArmorCombatDenyReason.ATTACK_BUSY);
			if (now < state.coreCooldownUntilTick) return Result.deny(ArmorCombatDenyReason.COOLDOWN);
			if (state.energy + 0.0001F < energyCost) {
				return Result.deny(ArmorCombatDenyReason.NOT_ENOUGH_ENERGY);
			}
			return Result.allow();
		}
		if (action == ArmorAttackAction.RELEASE) {
			return state.phase == ArmorCombatPhase.CHARGING
					&& state.attackType == ArmorAttackType.CORE_BEAM
					? Result.allow() : Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
		}
		return Result.deny(ArmorCombatDenyReason.INVALID_ACTION);
	}

	/** 生命周期检查也供 tick 在穿脱/巡航状态突变时取消活动攻击姿势。 */
	public static ArmorCombatDenyReason evaluateLifecycle(ServerPlayer player) {
		return evaluateLifecycle(player, ArmorAttackType.INVALID,
				player == null ? 0L : player.level().getGameTime());
	}

	/**
	 * Attack-aware lifecycle used on intent receipt and every active-phase tick.
	 * A stable exact Sentinel in OVERDRIVE may charge/release its chest + wing G
	 * salvo while high-speed flight reports the legacy dive flag. Ctrl boost is
	 * still denied independently by the core gate and active-charge tick.
	 */
	public static ArmorCombatDenyReason evaluateLifecycle(ServerPlayer player,
			ArmorAttackType activeType, long now) {
		ArmorCombatDenyReason base = evaluateBaseLifecycle(player);
		if (base != ArmorCombatDenyReason.NONE) return base;
		if (FlightController.isDiveActive(player)
				&& !allowsSentinelOverdriveCoreInHighSpeedFlight(
						player, activeType, now)) {
			return ArmorCombatDenyReason.DIVING;
		}
		return ArmorCombatDenyReason.NONE;
	}

	private static boolean allowsSentinelOverdriveCoreInHighSpeedFlight(
			ServerPlayer player, ArmorAttackType activeType, long now) {
		return activeType == ArmorAttackType.CORE_BEAM
				&& ModItems.fullSetVariant(player).orElse(null) == ArmorVariant.SENTINEL
				&& ArmorConfigurationAccess.allowsRangedCombat(player, now);
	}

	/** 俯冲冲击正是在 diving=true 的接触帧调用，因此只复用其余生命周期门禁。 */
	public static ArmorCombatDenyReason evaluateDiveImpactLifecycle(ServerPlayer player) {
		return evaluateBaseLifecycle(player);
	}

	private static ArmorCombatDenyReason evaluateBaseLifecycle(ServerPlayer player) {
		if (!player.isAlive()) return ArmorCombatDenyReason.PLAYER_DEAD;
		if (player.isSpectator()) return ArmorCombatDenyReason.SPECTATOR;
		if (player.isSleeping()) return ArmorCombatDenyReason.SLEEPING;
		if (player.isPassenger()) return ArmorCombatDenyReason.RIDING;
		if (ArmorAssemblyController.isAnimating(player)) return ArmorCombatDenyReason.ASSEMBLY_ACTIVE;
		if (ModItems.fullSetVariant(player).isEmpty()) return ArmorCombatDenyReason.MISSING_FULL_SET;
		if (!ChargingPlatformController.canUseEquippedFlightArmor(player)) {
			return ArmorCombatDenyReason.NOT_ARMOR_OWNER;
		}
		if (ModItems.fullSetVariant(player).orElse(null) == ArmorVariant.SENTINEL) {
			if (ArmorConfigurationAccess.configuredSentinel(player).isEmpty()) {
				return ArmorCombatDenyReason.MISSING_FULL_SET;
			}
			if (ArmorConfigurationAccess.isTransitioning(
					player, player.level().getGameTime())) {
				return ArmorCombatDenyReason.ATTACK_BUSY;
			}
		}
		return ArmorCombatDenyReason.NONE;
	}

	public record Result(boolean allowed, ArmorCombatDenyReason reason) {
		private static Result allow() {
			return new Result(true, ArmorCombatDenyReason.NONE);
		}

		private static Result deny(ArmorCombatDenyReason reason) {
			return new Result(false, reason);
		}
	}
}
