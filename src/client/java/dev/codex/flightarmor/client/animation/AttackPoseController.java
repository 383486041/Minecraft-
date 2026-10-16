package dev.codex.flightarmor.client.animation;

import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 把服务端权威的战斗阶段转成高达机甲风格战斗姿态：
 * <ul>
 *   <li>腕炮/步枪射击（R键）：高达经典空中举臂瞄准 + 副手横胸托护传感器 + 前后错腿稳姿</li>
 *   <li>胸炮/三联齐射（G键）：自由高达「全弹发射（Hi-MAT Full Burst）」昂胸张臂姿态</li>
 *   <li>光束军刀连斩（左键/J键）：袈裟斜劈 -> 逆风回旋横斩 -> 双手高出力突进破城劈</li>
 *   <li>陨星俯冲重击（X键）：三点式重装机甲英雄落地冲击姿态</li>
 * </ul>
 */
public final class AttackPoseController {
	private AttackPoseController() {
	}

	public static boolean isActive(Player player) {
		return ArmorCombatClient.phase(player) != ArmorCombatPhase.IDLE
				|| ArmorCombatClient.leftRecoil(player, 1.0F) > 0.01F
				|| ArmorCombatClient.rightRecoil(player, 1.0F) > 0.01F;
	}

	public static FlightPoseFrame apply(Player player, FlightPoseFrame base, float partialTick) {
		ArmorCombatPhase phase = ArmorCombatClient.phase(player);
		float phaseProgress = ArmorCombatClient.phaseProgress(player, partialTick);
		float leftRecoil = ArmorCombatClient.leftRecoil(player, partialTick);
		float rightRecoil = ArmorCombatClient.rightRecoil(player, partialTick);
		if (phase == ArmorCombatPhase.IDLE && leftRecoil <= 0.001F && rightRecoil <= 0.001F) {
			float coreImpulse = ArmorCombatClient.coreReleaseImpulse(player, partialTick);
			float impactImpulse = ArmorCombatClient.diveImpactImpulse(player, partialTick);
			if (coreImpulse <= 0.001F && impactImpulse <= 0.001F) return base;
		}
		ArmorAttackType type = ArmorCombatClient.attackType(player);
		if (type == ArmorAttackType.ENERGY_BLADE_SLASH
				|| type == ArmorAttackType.ENERGY_BLADE_COMBO) {
			return applyBladeSlash(player, base, phase, phaseProgress);
		}
		if (type == ArmorAttackType.CORE_BEAM
				|| ArmorCombatClient.coreReleaseImpulse(player, partialTick) > 0.001F) {
			return applyCoreBeam(player, base, partialTick, phase, phaseProgress);
		}
		if (type == ArmorAttackType.GRAVITY_DIVE_IMPACT
				|| ArmorCombatClient.diveImpactImpulse(player, partialTick) > 0.001F) {
			return applyDiveImpact(player, base, partialTick, phase, phaseProgress);
		}

		return applyWristPulse(player, base, partialTick, phase, phaseProgress,
				leftRecoil, rightRecoil);
	}

	/** Applies the same authored combat vocabulary to autonomous cruise and escort carriers. */
	public static FlightPoseFrame applyAutonomous(LivingEntity carrier, FlightPoseFrame base,
			ArmorVariant variant, float partialTick) {
		ArmorCombatPhase phase = ArmorCombatClient.phase(carrier);
		ArmorAttackType type = ArmorCombatClient.attackType(carrier);
		float progress = ArmorCombatClient.phaseProgress(carrier, partialTick);
		boolean left = ArmorCombatClient.attacksWithLeftHand(carrier);
		float leftRecoil = ArmorCombatClient.leftRecoil(carrier, partialTick);
		float rightRecoil = ArmorCombatClient.rightRecoil(carrier, partialTick);
		float coreImpulse = ArmorCombatClient.coreReleaseImpulse(carrier, partialTick);
		float impactImpulse = ArmorCombatClient.diveImpactImpulse(carrier, partialTick);
		if (phase == ArmorCombatPhase.IDLE && leftRecoil <= 0.001F && rightRecoil <= 0.001F
				&& coreImpulse <= 0.001F && impactImpulse <= 0.001F) return base;

		if (type == ArmorAttackType.ENERGY_BLADE_SLASH
				|| type == ArmorAttackType.ENERGY_BLADE_COMBO) {
			int combo = ArmorCombatClient.bladeComboStep(carrier);
			FlightPoseFrame loaded = bladeKeyframe(base, left, variant, combo, BladeBeat.LOAD);
			FlightPoseFrame contact = bladeKeyframe(base, left, variant, combo, BladeBeat.CONTACT);
			FlightPoseFrame follow = bladeKeyframe(base, left, variant, combo, BladeBeat.FOLLOW_THROUGH);
			return switch (phase) {
				case CHARGING, WINDUP -> base.lerp(loaded, smoothstep(0.0F, 1.0F, progress));
				case FIRING -> progress < 0.50F
						? loaded.lerp(contact, smoothstep(0.0F, 0.50F, progress))
						: contact.lerp(follow, smoothstep(0.50F, 1.0F, progress));
				case RECOVERY -> follow.lerp(base, smoothstep(0.0F, 1.0F, progress));
				case IDLE -> base;
			};
		}

		float phaseWeight = switch (phase) {
			case CHARGING, WINDUP -> smoothstep(0.0F, 0.72F, progress);
			case FIRING -> 1.0F;
			case RECOVERY -> 1.0F - smoothstep(0.0F, 1.0F, progress);
			case IDLE -> 0.0F;
		};
		if (type == ArmorAttackType.CORE_BEAM || type == ArmorAttackType.CRUISE_CORE_BEAM) {
			return base.lerp(coreTarget(base, variant),
					Mth.clamp(Math.max(phaseWeight, coreImpulse), 0.0F, 1.0F));
		}
		if (type == ArmorAttackType.GRAVITY_DIVE_IMPACT
				|| type == ArmorAttackType.CRUISE_RAM
				|| type == ArmorAttackType.CRUISE_GROUND_SLAM) {
			return base.lerp(impactTarget(base, variant),
					Mth.clamp(Math.max(phaseWeight, impactImpulse), 0.0F, 1.0F));
		}
		if (type == ArmorAttackType.CRUISE_MISSILE_VOLLEY) {
			FlightPoseFrame volley = bracedFrame(base, -8, -4,
					-64, -28, -32, 44, -60, 28, 32, 42,
					-18, -4, -6, 48, -8, 4, 6, 26);
			return base.lerp(volley, Mth.clamp(Math.max(phaseWeight, 0.84F), 0.0F, 1.0F));
		}
		float recoil = left ? leftRecoil : rightRecoil;
		return wristPose(base, variant, left, phase, progress, recoil);
	}

	private static FlightPoseFrame applyWristPulse(Player player, FlightPoseFrame base,
			float partialTick, ArmorCombatPhase phase, float phaseProgress,
			float leftRecoil, float rightRecoil) {
		boolean left = ArmorCombatClient.attacksWithLeftHand(player);
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
		float recoil = left ? leftRecoil : rightRecoil;
		return wristPose(base, variant, left, phase, phaseProgress, recoil);
	}

	private static FlightPoseFrame applyBladeSlash(Player player, FlightPoseFrame base,
			ArmorCombatPhase phase, float phaseProgress) {
		boolean attackingLeft = ArmorCombatClient.attacksWithLeftHand(player);
		ArmorVariant variant = ArmorCombatClient.bladeVariant(player);
		int comboStep = ArmorCombatClient.bladeComboStep(player);
		boolean linked = ArmorCombatClient.bladeComboLinked(player);
		FlightPoseFrame entry = linked && comboStep > 0
				? bladeKeyframe(base, attackingLeft, variant, comboStep - 1, BladeBeat.FOLLOW_THROUGH)
				: base;
		FlightPoseFrame loaded = bladeKeyframe(
				base, attackingLeft, variant, comboStep, BladeBeat.LOAD);
		FlightPoseFrame contact = bladeKeyframe(base, attackingLeft, variant, comboStep, BladeBeat.CONTACT);
		FlightPoseFrame followThrough = bladeKeyframe(
				base, attackingLeft, variant, comboStep, BladeBeat.FOLLOW_THROUGH);

		return switch (phase) {
			case WINDUP, CHARGING -> entry.lerp(loaded, bladeAnticipation(phaseProgress));
			case FIRING -> {
				if (phaseProgress < 0.42F) {
					yield loaded.lerp(contact, bladeCutIn(phaseProgress / 0.42F));
				}
				yield contact.lerp(followThrough,
						bladeCutOut((phaseProgress - 0.42F) / 0.58F));
			}
			case RECOVERY -> followThrough.lerp(base, bladeSettle(phaseProgress));
			case IDLE -> base;
		};
	}

	private static FlightPoseFrame bladeKeyframe(FlightPoseFrame base, boolean attackingLeft,
			ArmorVariant variant, int comboStep, BladeBeat beat) {
		BladeAngles angles = BladeAngles.resolve(variant, comboStep, beat);
		float side = attackingLeft ? -1.0F : 1.0F;

		float attackArmPitch = degrees(angles.attackArmPitch());
		float attackArmYaw = degrees(angles.attackArmYaw() * side);
		float attackArmRoll = degrees(angles.attackArmRoll() * side);
		float supportArmPitch = degrees(angles.supportArmPitch());
		float supportArmYaw = degrees(angles.supportArmYaw() * -side);
		float supportArmRoll = degrees(angles.supportArmRoll() * -side);
		float attackLegPitch = (attackingLeft ? base.leftLegPitch() : base.rightLegPitch())
				+ degrees(angles.attackLegPitch());
		float attackLegYaw = (attackingLeft ? base.leftLegYaw() : base.rightLegYaw())
				+ degrees(angles.attackLegYaw() * side);
		float attackLegRoll = (attackingLeft ? base.leftLegRoll() : base.rightLegRoll())
				+ degrees(angles.attackLegRoll() * side);
		float supportLegPitch = (attackingLeft ? base.rightLegPitch() : base.leftLegPitch())
				+ degrees(angles.supportLegPitch());
		float supportLegYaw = (attackingLeft ? base.rightLegYaw() : base.leftLegYaw())
				+ degrees(angles.supportLegYaw() * -side);
		float supportLegRoll = (attackingLeft ? base.rightLegRoll() : base.leftLegRoll())
				+ degrees(angles.supportLegRoll() * -side);
		float attackElbow = degrees(angles.attackElbow());
		float supportElbow = degrees(angles.supportElbow());
		float attackKnee = (attackingLeft ? base.leftKneeBend() : base.rightKneeBend())
				+ degrees(angles.attackKnee());
		float supportKnee = (attackingLeft ? base.rightKneeBend() : base.leftKneeBend())
				+ degrees(angles.supportKnee());

		return new FlightPoseFrame(
				base.bodyPitch() + degrees(angles.bodyPitch()),
				base.bodyRoll() + degrees(angles.bodyRoll() * side),
				base.headPitchOffset() + degrees(angles.headPitch()),
				base.headRollOffset() + degrees(angles.headRoll() * -side),
				attackingLeft ? attackArmPitch : supportArmPitch,
				attackingLeft ? attackArmYaw : supportArmYaw,
				attackingLeft ? attackArmRoll : supportArmRoll,
				attackingLeft ? supportArmPitch : attackArmPitch,
				attackingLeft ? supportArmYaw : attackArmYaw,
				attackingLeft ? supportArmRoll : attackArmRoll,
				attackingLeft ? attackLegPitch : supportLegPitch,
				attackingLeft ? attackLegYaw : supportLegYaw,
				attackingLeft ? attackLegRoll : supportLegRoll,
				attackingLeft ? supportLegPitch : attackLegPitch,
				attackingLeft ? supportLegYaw : attackLegYaw,
				attackingLeft ? supportLegRoll : attackLegRoll,
				attackingLeft ? attackElbow : supportElbow,
				attackingLeft ? supportElbow : attackElbow,
				attackingLeft ? attackKnee : supportKnee,
				attackingLeft ? supportKnee : attackKnee);
	}

	private static FlightPoseFrame applyCoreBeam(Player player, FlightPoseFrame base, float partialTick,
			ArmorCombatPhase phase, float phaseProgress) {
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
		float release = ArmorCombatClient.coreReleaseImpulse(player, partialTick);
		float phaseWeight = switch (phase) {
			case CHARGING, WINDUP -> smoothstep(0.0F, 0.72F, phaseProgress);
			case FIRING -> 1.0F;
			case RECOVERY -> 1.0F - smoothstep(0.0F, 1.0F, phaseProgress);
			case IDLE -> 0.0F;
		};
		float weight = Mth.clamp(Math.max(phaseWeight, release), 0.0F, 1.0F);
		FlightPoseFrame target = coreTarget(base, variant);
		return base.lerp(target, weight);
	}

	private static FlightPoseFrame applyDiveImpact(Player player, FlightPoseFrame base, float partialTick,
			ArmorCombatPhase phase, float phaseProgress) {
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
		float impulse = ArmorCombatClient.diveImpactImpulse(player, partialTick);
		float phaseWeight = switch (phase) {
			case FIRING -> 1.0F;
			case RECOVERY -> 1.0F - smoothstep(0.0F, 1.0F, phaseProgress);
			default -> 0.0F;
		};
		float weight = Mth.clamp(Math.max(impulse, phaseWeight), 0.0F, 1.0F);
		FlightPoseFrame target = impactTarget(base, variant);
		return base.lerp(target, weight);
	}

	private static FlightPoseFrame wristPose(FlightPoseFrame base, ArmorVariant variant,
			boolean left, ArmorCombatPhase phase, float progress, float recoil) {
		FlightPoseFrame ready = wristTarget(base, variant, left);
		FlightPoseFrame kick = wristFireTarget(base, variant, left);
		return switch (phase) {
			case CHARGING, WINDUP -> base.lerp(ready, smoothstep(0.0F, 0.62F, progress));
			case FIRING -> progress < 0.32F
					? ready.lerp(kick, smoothstep(0.0F, 0.32F, progress))
					: kick.lerp(ready, smoothstep(0.32F, 1.0F, progress));
			case RECOVERY -> ready.lerp(base, smoothstep(0.0F, 1.0F, progress));
			case IDLE -> base.lerp(kick, Mth.clamp(recoil, 0.0F, 1.0F));
		};
	}

	/**
	 * 高达经典举臂射击姿势（Gundam SEED Aerial Rifle / Wrist Aim）：
	 * 射击臂平举对准视线，副手横在胸前辅助瞄准传感器，双腿一屈一伸形成空中张力。
	 */
	private static FlightPoseFrame wristTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		return switch (variant) {
			case VANGUARD -> combatFrame(base, -5, 8 * side, 3, -3 * side,
					-88, -6 * side, -8 * side, 16, -46, 28 * side, 22 * side, 62,
					-18, 4 * side, 5 * side, 44, 6, -3 * side, -4 * side, 18, left);
			case SWIFT -> combatFrame(base, -9, 6 * side, 5, -3 * side,
					-98, -4 * side, -5 * side, 10, -28, 32 * side, 26 * side, 48,
					-12, 3 * side, 4 * side, 34, 12, -3 * side, -4 * side, 22, left);
			case SENTINEL -> combatFrame(base, -2, 10 * side, 2, -4 * side,
					-76, -10 * side, -14 * side, 42, -54, 34 * side, 28 * side, 72,
					-24, 6 * side, 8 * side, 56, -8, -4 * side, -6 * side, 38, left);
		};
	}

	private static FlightPoseFrame wristFireTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		return switch (variant) {
			case VANGUARD -> combatFrame(base, 2, 11 * side, 1, -4 * side,
					-72, -10 * side, -14 * side, 32, -52, 30 * side, 25 * side, 68,
					-22, 5 * side, 7 * side, 50, 8, -4 * side, -5 * side, 22, left);
			case SWIFT -> combatFrame(base, -3, 9 * side, 2, -4 * side,
					-84, -8 * side, -11 * side, 22, -34, 35 * side, 30 * side, 54,
					-16, 4 * side, 5 * side, 40, 14, -4 * side, -5 * side, 28, left);
			case SENTINEL -> combatFrame(base, 6, 13 * side, 3, -5 * side,
					-58, -16 * side, -20 * side, 62, -60, 36 * side, 32 * side, 78,
					-28, 7 * side, 10 * side, 64, -12, -5 * side, -8 * side, 46, left);
		};
	}

	/**
	 * 自由高达「全弹发射（Hi-MAT Full Burst）」胸炮与翼炮齐射姿势：
	 * 胸膛昂起前推，双臂向斜下方大角度张开稳住反冲力矩，双腿一屈一伸锚定空中重心。
	 */
	private static FlightPoseFrame coreTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, -8, 4,
					-46, -28, -24, 54, -42, 26, 22, 50, -18, -5, -7, 46, -4, 5, 6, 24);
			case SWIFT -> bracedFrame(base, -11, 6,
					-64, -18, -16, 34, -60, 16, 15, 32, -12, -4, -5, 36, 6, 4, 5, 22);
			case SENTINEL -> bracedFrame(base, -5, 3,
					-38, -34, -26, 68, -36, 34, 26, 66, -24, -7, -10, 62, -14, 7, 10, 48);
		};
	}

	/**
	 * 巴巴托斯/强袭高达三点式超重落地冲击（Superhero Three-Point Mecha Landing）：
	 * 躯干深俯、单膝大角度屈折缓冲、主战拳砸向地面、副手向后上方展臂平衡。
	 */
	private static FlightPoseFrame impactTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, 18, 6,
					-118, -10, -14, 14, -26, 24, 28, 52, -46, -6, -9, 84, -8, 5, 7, 36);
			case SWIFT -> bracedFrame(base, 12, 5,
					-96, -16, -22, 20, -32, 26, 30, 42, -32, -5, -7, 68, 10, 5, 6, 24);
			case SENTINEL -> bracedFrame(base, 22, 8,
					-124, -16, -20, 16, -112, 18, 22, 20, -58, -8, -12, 92, -40, 8, 12, 78);
		};
	}

	private static FlightPoseFrame bracedFrame(FlightPoseFrame base,
			float bodyPitch, float headPitch,
			float leftArmPitch, float leftArmYaw, float leftArmRoll, float leftElbow,
			float rightArmPitch, float rightArmYaw, float rightArmRoll, float rightElbow,
			float leftLegPitch, float leftLegYaw, float leftLegRoll, float leftKnee,
			float rightLegPitch, float rightLegYaw, float rightLegRoll, float rightKnee) {
		return new FlightPoseFrame(
				base.bodyPitch() + degrees(bodyPitch), base.bodyRoll(),
				base.headPitchOffset() + degrees(headPitch), base.headRollOffset(),
				degrees(leftArmPitch), degrees(leftArmYaw), degrees(leftArmRoll),
				degrees(rightArmPitch), degrees(rightArmYaw), degrees(rightArmRoll),
				base.leftLegPitch() + degrees(leftLegPitch),
				base.leftLegYaw() + degrees(leftLegYaw), base.leftLegRoll() + degrees(leftLegRoll),
				base.rightLegPitch() + degrees(rightLegPitch),
				base.rightLegYaw() + degrees(rightLegYaw), base.rightLegRoll() + degrees(rightLegRoll),
				degrees(leftElbow), degrees(rightElbow),
				base.leftKneeBend() + degrees(leftKnee),
				base.rightKneeBend() + degrees(rightKnee));
	}

	private static FlightPoseFrame combatFrame(FlightPoseFrame base,
			float bodyPitch, float bodyRoll, float headPitch, float headRoll,
			float attackArmPitch, float attackArmYaw, float attackArmRoll, float attackElbow,
			float supportArmPitch, float supportArmYaw, float supportArmRoll, float supportElbow,
			float attackLegPitch, float attackLegYaw, float attackLegRoll, float attackKnee,
			float supportLegPitch, float supportLegYaw, float supportLegRoll, float supportKnee,
			boolean attackingLeft) {
		float attackLegBasePitch = attackingLeft ? base.leftLegPitch() : base.rightLegPitch();
		float attackLegBaseYaw = attackingLeft ? base.leftLegYaw() : base.rightLegYaw();
		float attackLegBaseRoll = attackingLeft ? base.leftLegRoll() : base.rightLegRoll();
		float supportLegBasePitch = attackingLeft ? base.rightLegPitch() : base.leftLegPitch();
		float supportLegBaseYaw = attackingLeft ? base.rightLegYaw() : base.leftLegYaw();
		float supportLegBaseRoll = attackingLeft ? base.rightLegRoll() : base.leftLegRoll();
		return new FlightPoseFrame(
				base.bodyPitch() + degrees(bodyPitch), base.bodyRoll() + degrees(bodyRoll),
				base.headPitchOffset() + degrees(headPitch), base.headRollOffset() + degrees(headRoll),
				degrees(attackingLeft ? attackArmPitch : supportArmPitch),
				degrees(attackingLeft ? attackArmYaw : supportArmYaw),
				degrees(attackingLeft ? attackArmRoll : supportArmRoll),
				degrees(attackingLeft ? supportArmPitch : attackArmPitch),
				degrees(attackingLeft ? supportArmYaw : attackArmYaw),
				degrees(attackingLeft ? supportArmRoll : attackArmRoll),
				attackingLeft ? attackLegBasePitch + degrees(attackLegPitch)
						: supportLegBasePitch + degrees(supportLegPitch),
				attackingLeft ? attackLegBaseYaw + degrees(attackLegYaw)
						: supportLegBaseYaw + degrees(supportLegYaw),
				attackingLeft ? attackLegBaseRoll + degrees(attackLegRoll)
						: supportLegBaseRoll + degrees(supportLegRoll),
				attackingLeft ? supportLegBasePitch + degrees(supportLegPitch)
						: attackLegBasePitch + degrees(attackLegPitch),
				attackingLeft ? supportLegBaseYaw + degrees(supportLegYaw)
						: attackLegBaseYaw + degrees(attackLegYaw),
				attackingLeft ? supportLegBaseRoll + degrees(supportLegRoll)
						: attackLegBaseRoll + degrees(attackLegRoll),
				degrees(attackingLeft ? attackElbow : supportElbow),
				degrees(attackingLeft ? supportElbow : attackElbow),
				base.leftKneeBend() + degrees(attackingLeft ? attackKnee : supportKnee),
				base.rightKneeBend() + degrees(attackingLeft ? supportKnee : attackKnee));
	}

	private static float bladeAnticipation(float value) {
		float t = Mth.clamp(value, 0.0F, 1.0F);
		float inverse = 1.0F - t;
		return 1.0F - inverse * inverse * inverse;
	}

	private static float bladeCutIn(float value) {
		float t = Mth.clamp(value, 0.0F, 1.0F);
		return t * t;
	}

	private static float bladeCutOut(float value) {
		float t = Mth.clamp(value, 0.0F, 1.0F);
		float inverse = 1.0F - t;
		return 1.0F - inverse * inverse;
	}

	private static float bladeSettle(float value) {
		float t = Mth.clamp(value, 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static float degrees(float value) {
		return value * Mth.DEG_TO_RAD;
	}

	private enum BladeBeat {
		LOAD,
		CONTACT,
		FOLLOW_THROUGH
	}

	private record BladeAngles(
			float bodyPitch, float bodyRoll, float headPitch, float headRoll,
			float attackArmPitch, float attackArmYaw, float attackArmRoll, float attackElbow,
			float supportArmPitch, float supportArmYaw, float supportArmRoll, float supportElbow,
			float attackLegPitch, float attackLegYaw, float attackLegRoll, float attackKnee,
			float supportLegPitch, float supportLegYaw, float supportLegRoll, float supportKnee
	) {
		private static BladeAngles resolve(ArmorVariant variant, int comboStep, BladeBeat beat) {
			int step = Math.floorMod(comboStep, 3);
			return switch (variant) {
				case VANGUARD -> vanguardCombo(step, beat);
				case SWIFT -> swiftDualCombo(step, beat);
				case SENTINEL -> sentinelHeavyCombo(step, beat);
			};
		}

		/**
		 * Vanguard Beam Saber Combo:
		 * Step 0: 高达大角度拔刀袈裟斩（Overhead Diagonal Saber Cleave）
		 * Step 1: 转腰逆风水平闪光斩（Reverse Spinning Horizontal Slash）
		 * Step 2: 突进双手高出力纵劈（Lunging Overhead Buster Slash）
		 */
		private static BladeAngles vanguardCombo(int step, BladeBeat beat) {
			if (step == 0) return switch (beat) {
				case LOAD -> new BladeAngles(-8, -16, -3, 4, -128, 44, 34, 62,
						-28, 24, 20, 36, -18, 5, 7, 42, 10, -4, -5, 20);
				case CONTACT -> new BladeAngles(-14, 18, 4, -6, -64, -62, -28, 12,
						-46, -22, 26, 48, 14, -6, -8, 20, -20, 5, 7, 46);
				case FOLLOW_THROUGH -> new BladeAngles(-8, 22, 2, -6, -42, -74, -38, 32,
						-34, -16, 28, 46, 18, -5, -6, 26, -14, 6, 8, 36);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(-6, 20, -2, -4, -52, -58, -32, 64,
						-34, 30, 22, 42, 10, -4, -5, 24, -16, 5, 7, 40);
				case CONTACT -> new BladeAngles(-12, -19, 4, 6, -96, 64, 32, 10,
						-50, 26, -28, 48, -18, 6, 8, 46, 12, -5, -7, 20);
				case FOLLOW_THROUGH -> new BladeAngles(-7, -22, 2, 6, -54, 74, 38, 34,
						-38, 18, -30, 50, -12, 6, 8, 36, 16, -5, -6, 26);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-16, -4, -6, 2, -144, 12, 8, 72,
						-96, -22, -16, 58, -22, 6, 8, 52, 12, -5, -7, 30);
				case CONTACT -> new BladeAngles(-22, 12, 6, -5, -52, -20, -14, 8,
						-64, 30, 24, 48, 16, -7, -9, 24, -26, 6, 9, 56);
				case FOLLOW_THROUGH -> new BladeAngles(-12, 18, 2, -6, -36, -52, -32, 28,
						-50, 22, 26, 52, 20, -6, -8, 30, -16, 7, 10, 44);
			};
		}

		/**
		 * Sentinel Heavy Hyper Beam Greatsword Combo:
		 * Step 0: 重装蓄力斜向断舰斩
		 * Step 1: 旋转重锤逆劈
		 * Step 2: 双手合握大剑破城纵斩（サンライズ立ち / 勇者一字斩收刀）
		 */
		private static BladeAngles sentinelHeavyCombo(int step, BladeBeat beat) {
			if (step == 0) return switch (beat) {
				case LOAD -> new BladeAngles(-5, -18, -2, 4, -118, 38, 42, 66,
						-54, 22, 32, 62, -24, 7, 10, 58, -10, -6, -9, 48);
				case CONTACT -> new BladeAngles(4, 20, 4, -6, -68, -68, -38, 20,
						-68, -32, 38, 74, -30, 8, 12, 70, -18, -7, -10, 60);
				case FOLLOW_THROUGH -> new BladeAngles(1, 24, 2, -6, -44, -82, -46, 42,
						-58, -22, 42, 76, -24, 7, 11, 64, -14, -6, -9, 54);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(2, 22, -2, -5, -42, -64, -42, 68,
						-66, 36, 44, 80, -12, -6, -9, 50, -24, 8, 11, 62);
				case CONTACT -> new BladeAngles(6, -22, 4, 7, -92, 72, 44, 18,
						-76, 40, -46, 70, -20, -7, -11, 62, -30, 9, 13, 72);
				case FOLLOW_THROUGH -> new BladeAngles(2, -25, 2, 7, -50, 86, 48, 44,
						-64, 28, -44, 74, -15, -6, -9, 54, -26, 8, 12, 66);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-20, 0, -8, 0, -148, 6, 4, 78,
						-136, -8, -6, 80, -38, -9, -14, 86, -32, 9, 14, 78);
				case CONTACT -> new BladeAngles(-24, 0, 8, 0, -48, -8, -6, 10,
						-56, 10, 8, 14, -14, -8, -12, 66, -42, 10, 16, 94);
				case FOLLOW_THROUGH -> new BladeAngles(-14, 0, 2, 0, -32, -36, -26, 38,
						-88, 32, 22, 48, -8, -7, -10, 58, -34, 9, 14, 86);
			};
		}

		/**
		 * Swift GN Twin Beam Tonfa Combo:
		 * Step 0: 能天使高速交叉突刺斩
		 * Step 1: 双刃回旋剪刀斩
		 * Step 2: Trans-Am 十字双刀疾风裂斩
		 */
		private static BladeAngles swiftDualCombo(int comboStep, BladeBeat beat) {
			if (comboStep == 0) {
				return switch (beat) {
					case LOAD -> new BladeAngles(-8, -12, -3, 3,
							-116, 48, 32, 54, -42, -48, -32, 52,
							-12, 4, 5, 32, 8, -3, -4, 20);
					case CONTACT -> new BladeAngles(-14, 14, 5, -5,
							-68, -58, -26, 12, -102, -52, 24, 16,
							10, -5, -6, 18, -16, 4, 6, 38);
					case FOLLOW_THROUGH -> new BladeAngles(-9, 17, 2, -5,
							-48, -70, -34, 28, -62, -64, 30, 32,
							14, -4, -5, 22, -10, 5, 7, 30);
				};
			}
			if (comboStep == 1) {
				return switch (beat) {
					case LOAD -> new BladeAngles(-8, 12, -3, -3,
							-44, -50, -32, 52, -116, 52, 32, 54,
							8, -4, -5, 20, -12, 3, 4, 32);
					case CONTACT -> new BladeAngles(-14, -14, 5, 5,
							-102, 52, 24, 16, -68, 58, -26, 12,
							-16, 5, 6, 38, 10, -4, -6, 18);
					case FOLLOW_THROUGH -> new BladeAngles(-9, -17, 2, 5,
							-62, 64, 30, 32, -48, 70, -34, 28,
							-10, 5, 7, 30, 14, -4, -5, 22);
				};
			}
			return switch (beat) {
				case LOAD -> new BladeAngles(-12, 0, -6, 0,
						-134, 18, 12, 60, -134, -18, -12, 60,
						-16, 4, 5, 42, -10, -4, -5, 32);
				case CONTACT -> new BladeAngles(-18, 0, 7, 0,
						-56, -42, -24, 10, -56, 42, 24, 10,
						12, -4, -5, 20, -20, 4, 6, 46);
				case FOLLOW_THROUGH -> new BladeAngles(-10, 0, 2, 0,
						-42, -58, -32, 26, -42, 58, 32, 26,
						16, -4, -5, 24, -14, 5, 7, 36);
			};
		}
	}
}
