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
 * 把服务端权威的战斗阶段转成玩家姿势：腕炮后坐、核心束蓄力/释放、俯冲冲击与光刃斩击各有独立通道，
 * 非战斗状态直接返回基础姿势。
 *
 * <p>关节弯曲由 {@link FlightJointAnimation#poseFor(Player, float)} 统一驱动；本类只叠加战斗相关
 * 的关节与 OBJ 装甲偏移，不改动 HumanoidModel 的其余姿态。</p>
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
			FlightPoseFrame volley = bracedFrame(base, -6, -3,
					-58, -24, -28, 38, -54, 23, 27, 36,
					-5, -3, -4, 27, -14, 3, 4, 39);
			return base.lerp(volley, Mth.clamp(Math.max(phaseWeight, 0.82F), 0.0F, 1.0F));
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

	/**
	 * A blade strike is a locomotion-compatible overlay rather than another wrist-cannon aim pose.
	 *
	 * <p>The authoritative WINDUP loads the torso and blade, then FIRING snaps through
	 * the target and carries the weapon into follow-through. RECOVERY blends the complete
	 * skeleton back into the current flight or ground pose. The support arm and the two
	 * legs use different targets so the player never becomes a mirrored mannequin.</p>
	 */
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
				/*
				 * The server now owns a real WINDUP phase and commits the ranged
				 * blade wave when that phase ends. Start FIRING from the loaded
				 * keyframe instead of replaying base -> load, otherwise a latency-free
				 * client visibly snaps the arm backwards on the authoritative strike.
				 */
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
	private static FlightPoseFrame wristTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		return switch (variant) {
			case VANGUARD -> combatFrame(base, -3, 5 * side, -2, -2 * side,
					-82, -5 * side, -7 * side, 22, -26, 17 * side, 18 * side, 39,
					-7, 3 * side, 4 * side, 31, 2, -2 * side, -3 * side, 18, left);
			case SWIFT -> combatFrame(base, -7, 3 * side, -5, -2 * side,
					-101, -3 * side, -4 * side, 12, 18, 24 * side, 28 * side, 30,
					3, 2 * side, 3 * side, 16, -15, -3 * side, -4 * side, 34, left);
			case SENTINEL -> combatFrame(base, 1, 7 * side, -1, -3 * side,
					-67, -11 * side, -15 * side, 57, -42, 24 * side, 27 * side, 66,
					-19, 5 * side, 8 * side, 52, -8, -4 * side, -6 * side, 44, left);
		};
	}

	private static FlightPoseFrame wristFireTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		return switch (variant) {
			case VANGUARD -> combatFrame(base, 1, 8 * side, 1, -3 * side,
					-68, -9 * side, -12 * side, 34, -34, 20 * side, 23 * side, 46,
					-11, 4 * side, 6 * side, 38, 5, -3 * side, -4 * side, 22, left);
			case SWIFT -> combatFrame(base, -3, 6 * side, -2, -3 * side,
					-84, -8 * side, -10 * side, 23, 10, 30 * side, 34 * side, 36,
					6, 3 * side, 4 * side, 21, -19, -4 * side, -6 * side, 41, left);
			case SENTINEL -> combatFrame(base, 7, 10 * side, 2, -4 * side,
					-52, -17 * side, -22 * side, 72, -51, 28 * side, 33 * side, 76,
					-25, 7 * side, 10 * side, 61, -12, -5 * side, -8 * side, 51, left);
		};
	}
	private static FlightPoseFrame coreTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, -4, -3,
					-43, -21, -12, 52, -38, 19, 11, 46, -8, -3, -4, 32, -2, 3, 4, 23);
			case SWIFT -> bracedFrame(base, -9, -6,
					-66, -9, -7, 27, -63, 8, 7, 24, 2, -2, -3, 18, -13, 3, 4, 35);
			case SENTINEL -> bracedFrame(base, 1, -1,
					-34, -28, -18, 69, -31, 27, 18, 66, -22, -6, -9, 58, -15, 6, 9, 51);
		};
	}

	private static FlightPoseFrame impactTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, 13, 5,
					-112, -8, -12, 12, -31, 19, 22, 48, -38, -5, -7, 76, -11, 4, 6, 42);
			case SWIFT -> bracedFrame(base, -5, 4,
					-78, -18, -23, 24, -43, 20, 27, 38, -17, -4, -5, 34, 8, 4, 5, 20);
			case SENTINEL -> bracedFrame(base, 18, 8,
					-119, -16, -20, 15, -108, 17, 21, 18, -54, -7, -11, 88, -42, 7, 11, 79);
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

		/** Vanguard: advancing shoulder cut, reverse waist cut, then a committed drive cleave. */
		private static BladeAngles vanguardCombo(int step, BladeBeat beat) {
			if (step == 0) return switch (beat) {
				case LOAD -> new BladeAngles(-6, -12, -2, 3, -38, 48, 32, 58,
						-24, 20, 17, 31, -13, 4, 6, 32, 8, -3, -4, 18);
				case CONTACT -> new BladeAngles(-10, 14, -4, -5, -108, -55, -24, 10,
						-42, -18, 22, 41, 11, -5, -7, 17, -16, 4, 6, 38);
				case FOLLOW_THROUGH -> new BladeAngles(-6, 17, 1, -5, -62, -68, -35, 31,
						-31, -13, 24, 43, 16, -4, -5, 22, -10, 5, 7, 31);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(-5, 16, -2, -3, -44, -52, -28, 61,
						-30, 27, 20, 38, 8, -3, -4, 20, -13, 4, 6, 34);
				case CONTACT -> new BladeAngles(-9, -15, -4, 5, -101, 58, 26, 12,
						-46, 22, -24, 44, -15, 5, 7, 39, 10, -4, -6, 18);
				case FOLLOW_THROUGH -> new BladeAngles(-5, -18, 1, 5, -58, 67, 33, 33,
						-34, 16, -27, 46, -9, 5, 7, 32, 15, -4, -5, 23);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-12, -3, -5, 1, -72, 14, 10, 71,
						-43, -24, -16, 52, -17, 5, 7, 43, 9, -4, -6, 27);
				case CONTACT -> new BladeAngles(-17, 9, -7, -4, -132, -17, -12, 7,
						-58, 28, 21, 46, 13, -6, -8, 22, -21, 5, 8, 48);
				case FOLLOW_THROUGH -> new BladeAngles(-9, 15, 0, -5, -87, -49, -29, 29,
						-47, 19, 24, 49, 18, -5, -7, 28, -13, 6, 9, 39);
			};
		}

		/** Sentinel: armored hook, counter-sweep, then a two-arm breaker committed through the hips. */
		private static BladeAngles sentinelHeavyCombo(int step, BladeBeat beat) {
			if (step == 0) return switch (beat) {
				case LOAD -> new BladeAngles(-3, -14, -1, 3, -27, 39, 42, 63,
						-49, 18, 29, 59, -20, 6, 9, 54, -8, -5, -8, 45);
				case CONTACT -> new BladeAngles(2, 17, -3, -5, -88, -63, -35, 23,
						-65, -29, 35, 71, -27, 7, 11, 67, -15, -6, -9, 57);
				case FOLLOW_THROUGH -> new BladeAngles(0, 20, 1, -5, -49, -79, -43, 43,
						-56, -20, 39, 73, -22, 6, 10, 61, -11, -5, -8, 52);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(1, 18, -2, -4, -33, -59, -39, 66,
						-63, 33, 41, 79, -10, -5, -8, 47, -22, 7, 10, 59);
				case CONTACT -> new BladeAngles(4, -19, -4, 6, -93, 68, 40, 19,
						-72, 38, -43, 67, -17, -6, -10, 58, -28, 8, 12, 69);
				case FOLLOW_THROUGH -> new BladeAngles(1, -21, 1, 6, -52, 82, 46, 45,
						-61, 27, -41, 72, -13, -5, -8, 52, -24, 7, 11, 63);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-16, 0, -5, 0, -91, 7, 5, 77,
						-83, -9, -6, 79, -34, -8, -13, 82, -29, 8, 13, 76);
				case CONTACT -> new BladeAngles(-21, 0, -8, 0, -143, -6, -4, 11,
						-133, 9, 6, 15, -12, -7, -11, 62, -39, 9, 15, 92);
				case FOLLOW_THROUGH -> new BladeAngles(-12, 0, -1, 0, -99, -33, -23, 41,
						-92, 29, 20, 47, -7, -6, -9, 55, -31, 8, 13, 83);
			};
		}
		private static BladeAngles swiftDualCombo(int comboStep, BladeBeat beat) {
			if (comboStep == 0) {
				return switch (beat) {
					case LOAD -> new BladeAngles(-5, -8, -2, 2,
							-31, 49, 31, 51, -35, -45, -29, 48,
							-9, 3, 4, 25, 5, -2, -3, 18);
					case CONTACT -> new BladeAngles(-9, 10, -4, -4,
							-105, -54, -24, 13, -96, -48, 22, 16,
							7, -4, -5, 16, -12, 3, 5, 31);
					case FOLLOW_THROUGH -> new BladeAngles(-6, 13, 0, -4,
							-61, -67, -31, 28, -57, -60, 27, 31,
							12, -3, -4, 19, -8, 4, 6, 27);
				};
			}
			if (comboStep == 1) {
				return switch (beat) {
					case LOAD -> new BladeAngles(-5, 8, -2, -2,
							-36, -47, -30, 49, -30, 50, 29, 52,
							5, -3, -4, 18, -9, 2, 3, 26);
					case CONTACT -> new BladeAngles(-9, -10, -4, 4,
							-97, 49, 23, 16, -106, 55, -25, 13,
							-12, 4, 5, 31, 7, -3, -5, 16);
					case FOLLOW_THROUGH -> new BladeAngles(-6, -13, 0, 4,
							-58, 61, 28, 31, -62, 68, -32, 28,
							-8, 4, 6, 27, 12, -3, -4, 19);
				};
			}
			return switch (beat) {
				case LOAD -> new BladeAngles(-8, 0, -5, 0,
						-54, 12, 8, 57, -54, -12, -8, 57,
						-12, 3, 4, 34, -7, -3, -4, 27);
				case CONTACT -> new BladeAngles(-14, 0, -7, 0,
						-103, 5, 2, 12, -103, -5, -2, 12,
						8, -3, -4, 18, -15, 3, 5, 39);
				case FOLLOW_THROUGH -> new BladeAngles(-8, 0, -1, 0,
						-72, 10, 12, 27, -72, -10, -12, 27,
						12, -3, -4, 22, -10, 4, 6, 33);
			};
		}
		private BladeAngles stylize(ArmorVariant variant, int comboStep, BladeBeat beat) {
			if (comboStep == 0) return this;
			if (comboStep == 1) {
				/* Reverse waist cut: invert the lateral load without mirroring both limbs together. */
				return new BladeAngles(
						bodyPitch - 1.5F, -bodyRoll * 0.82F, headPitch, -headRoll,
						attackArmPitch + 7.0F, -attackArmYaw * 0.88F,
						-attackArmRoll * 0.72F, attackElbow + 5.0F,
						supportArmPitch - 6.0F, -supportArmYaw * 0.70F,
						-supportArmRoll * 0.64F, supportElbow + 8.0F,
						attackLegPitch - 3.0F, -attackLegYaw, -attackLegRoll, attackKnee + 4.0F,
						supportLegPitch + 4.0F, -supportLegYaw, -supportLegRoll, supportKnee + 7.0F);
			}
			/* Third hit: armor-specific committed cleave with a guarded support arm. */
			float heavy = variant == ArmorVariant.SENTINEL ? 1.18F
					: variant == ArmorVariant.SWIFT ? 0.90F : 1.0F;
			return switch (beat) {
				case LOAD -> new BladeAngles(
						-10.0F * heavy, -4.0F, -4.0F, 2.0F,
						-66.0F, 20.0F, 14.0F, 68.0F,
						-34.0F, -20.0F, -12.0F, 48.0F,
						-14.0F, 4.0F, 5.0F, 37.0F,
						8.0F, -3.0F, -4.0F, 21.0F);
				case CONTACT -> new BladeAngles(
						-14.0F * heavy, 8.0F, -6.0F, -3.0F,
						-124.0F, -18.0F, -10.0F, 9.0F,
						-52.0F, 24.0F, 18.0F, 40.0F,
						10.0F, -5.0F, -6.0F, 20.0F,
						-18.0F, 4.0F, 6.0F, 42.0F);
				case FOLLOW_THROUGH -> new BladeAngles(
						-8.0F * heavy, 13.0F, 0.0F, -5.0F,
						-82.0F, -44.0F, -26.0F, 28.0F,
						-43.0F, 16.0F, 20.0F, 44.0F,
						15.0F, -4.0F, -5.0F, 25.0F,
						-11.0F, 5.0F, 7.0F, 35.0F);
			};
		}
	}
}
