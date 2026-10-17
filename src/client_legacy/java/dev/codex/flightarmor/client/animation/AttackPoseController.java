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
 * 精细化高达战斗动作控制器（五阶段非线性关键帧 + 三轴扭腰锁敌 + 次级缓冲震颤）：
 * <ul>
 *   <li>腕炮/光束步枪射击（R键）：侧身24°切线立姿 -> 抬臂锁腕瞄准 -> 副手横胸托护传感器 -> 爆发后坐上跳 -> 液压复进缓冲 -> 垂枪收势</li>
 *   <li>胸炮全弹发射（G键）：交叉双臂护胸蓄能 -> 临界扩胸预备 -> 强袭自由式 Hi-MAT Full Burst 爆发后仰展翼 -> 持续照射高频能流震颤 -> 缓和归位</li>
 *   <li>巡航导弹齐射（遥测/齐射）：半蹲沉身防盾架势 + 多波次发射节奏性后坐顿挫</li>
 *   <li>光束军刀三段连斩（左键/J键）：大幅扭腰蓄力 -> 顶点蓄势停顿（Apex Hold） -> 极速斩切（Cut-In） -> 肉斩骨断顿挫（Hit-Stop） -> 振刀残心归位</li>
 *   <li>陨星俯冲重击（X键）：高空举拳蓄力 -> 巴巴托斯式三点单膝砸地 -> 冲击波二次下沉 -> 液压撑膝战术起身</li>
 * </ul>
 */
public final class AttackPoseController {
	private AttackPoseController() {
	}

	public static boolean isActive(Player player) {
		return ArmorCombatClient.phase(player) != ArmorCombatPhase.IDLE
				|| ArmorCombatClient.leftRecoil(player, 1.0F) > 0.01F
				|| ArmorCombatClient.rightRecoil(player, 1.0F) > 0.01F
				|| ArmorCombatClient.coreReleaseImpulse(player, 1.0F) > 0.01F
				|| ArmorCombatClient.diveImpactImpulse(player, 1.0F) > 0.01F;
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
		if (type == ArmorAttackType.CRUISE_MISSILE_VOLLEY) {
			ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
			return applyMissileVolley(base, variant, phase, phaseProgress, player.tickCount + partialTick);
		}

		return applyWristPulse(player, base, partialTick, phase, phaseProgress,
				leftRecoil, rightRecoil);
	}

	/** Applies the same authored 5-stage combat vocabulary to autonomous cruise and escort carriers. */
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
			return evaluateBladeTimeline(base, base, left, variant, combo, phase, progress);
		}

		if (type == ArmorAttackType.CORE_BEAM || type == ArmorAttackType.CRUISE_CORE_BEAM) {
			return evaluateCoreBeamTimeline(base, variant, phase, progress, coreImpulse,
					carrier.tickCount + partialTick);
		}
		if (type == ArmorAttackType.GRAVITY_DIVE_IMPACT
				|| type == ArmorAttackType.CRUISE_RAM
				|| type == ArmorAttackType.CRUISE_GROUND_SLAM) {
			return evaluateDiveImpactTimeline(base, variant, phase, progress, impactImpulse);
		}
		if (type == ArmorAttackType.CRUISE_MISSILE_VOLLEY) {
			return applyMissileVolley(base, variant, phase, progress, carrier.tickCount + partialTick);
		}
		float recoil = left ? leftRecoil : rightRecoil;
		return wristPose(base, variant, left, phase, progress, recoil);
	}

	private static FlightPoseFrame applyMissileVolley(FlightPoseFrame base, ArmorVariant variant,
			ArmorCombatPhase phase, float progress, float animTime) {
		float salvoPulse = Mth.sin(progress * Mth.PI * 6.0F) * (phase == ArmorCombatPhase.FIRING ? 1.0F : 0.25F);
		FlightPoseFrame brace = bracedFrame(base,
				-9.0F + 3.5F * salvoPulse,
				-4.5F - 2.0F * salvoPulse,
				-66.0F + 5.0F * salvoPulse, -28.0F, -34.0F, 48.0F + 4.0F * salvoPulse,
				-62.0F + 5.0F * salvoPulse, 28.0F, 34.0F, 46.0F + 4.0F * salvoPulse,
				-22.0F, -5.0F, -7.0F, 52.0F,
				-8.0F, 5.0F, 7.0F, 28.0F);
		float weight = switch (phase) {
			case CHARGING, WINDUP -> smoothstep(0.0F, 0.65F, progress);
			case FIRING -> 1.0F;
			case RECOVERY -> 1.0F - smoothstep(0.0F, 1.0F, progress);
			case IDLE -> 0.0F;
		};
		return base.lerp(brace, Mth.clamp(weight, 0.0F, 1.0F));
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
		return evaluateBladeTimeline(base, entry, attackingLeft, variant, comboStep, phase, phaseProgress);
	}

	private static FlightPoseFrame evaluateBladeTimeline(FlightPoseFrame base, FlightPoseFrame entry,
			boolean attackingLeft, ArmorVariant variant, int comboStep,
			ArmorCombatPhase phase, float phaseProgress) {
		FlightPoseFrame loaded = bladeKeyframe(base, attackingLeft, variant, comboStep, BladeBeat.LOAD);
		// 顶点蓄势微拉（Apex Tension Hold）：在挥刀前最后 24% 时间继续拧紧腰肩与手肘 12%
		FlightPoseFrame apex = entry.lerp(loaded, 1.12F);
		FlightPoseFrame contact = bladeKeyframe(base, attackingLeft, variant, comboStep, BladeBeat.CONTACT);
		FlightPoseFrame followThrough = bladeKeyframe(base, attackingLeft, variant, comboStep, BladeBeat.FOLLOW_THROUGH);
		// 肉斩骨断顿挫帧（Hit-Stop & Bite）：接触瞬间略微切入 followThrough 形成斩击贯通感
		FlightPoseFrame hitStop = contact.lerp(followThrough, 0.24F);
		// 残心收刀过渡帧（Chiburi Guard Settle）
		FlightPoseFrame flourishGuard = followThrough.lerp(base, 0.58F);

		return switch (phase) {
			case WINDUP, CHARGING -> phaseProgress < 0.76F
					? entry.lerp(loaded, bladeAnticipation(phaseProgress / 0.76F))
					: loaded.lerp(apex, smoothstep(0.76F, 1.0F, phaseProgress));
			case FIRING -> {
				if (phaseProgress < 0.22F) {
					yield apex.lerp(contact, bladeCutIn(phaseProgress / 0.22F));
				}
				if (phaseProgress < 0.42F) {
					yield contact.lerp(hitStop, smoothstep(0.22F, 0.42F, phaseProgress));
				}
				yield hitStop.lerp(followThrough, bladeCutOut((phaseProgress - 0.42F) / 0.58F));
			}
			case RECOVERY -> phaseProgress < 0.48F
					? followThrough.lerp(flourishGuard, smoothstep(0.0F, 0.48F, phaseProgress))
					: flourishGuard.lerp(base, bladeSettle((phaseProgress - 0.48F) / 0.52F));
			case IDLE -> base;
		};
	}

	private static FlightPoseFrame bladeKeyframe(FlightPoseFrame base, boolean attackingLeft,
			ArmorVariant variant, int comboStep, BladeBeat beat) {
		BladeAngles angles = BladeAngles.resolve(variant, comboStep, beat);
		float side = attackingLeft ? -1.0F : 1.0F;

		// 解算真实的腰胯水平扭转角（bodyYaw）与头部逆向锁敌偏航代偿（headYawOffset）
		float torsoTwistDeg = angles.bodyRoll() * 1.48F * side
				+ (angles.attackArmYaw() * 0.22F * side);
		float headCounterYawDeg = -torsoTwistDeg * 0.84F;

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
				base.bodyYaw() + degrees(torsoTwistDeg),
				base.bodyRoll() + degrees(angles.bodyRoll() * 0.52F * side),
				base.headPitchOffset() + degrees(angles.headPitch()),
				base.headYawOffset() + degrees(headCounterYawDeg),
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
		return evaluateCoreBeamTimeline(base, variant, phase, phaseProgress, release,
				player.tickCount + partialTick);
	}

	private static FlightPoseFrame evaluateCoreBeamTimeline(FlightPoseFrame base, ArmorVariant variant,
			ArmorCombatPhase phase, float phaseProgress, float release, float animTime) {
		FlightPoseFrame chargeCross = coreChargeTarget(base, variant);
		FlightPoseFrame preBurst = chargeCross.lerp(coreTarget(base, variant), 0.32F);
		FlightPoseFrame burstPose = coreTarget(base, variant);
		float tremor = Mth.sin(animTime * 0.85F) * 1.6F;
		FlightPoseFrame sustainedBurst = bracedFrame(burstPose,
				-1.5F + tremor * 0.4F, -1.0F - tremor * 0.3F,
				tremor, -tremor * 0.6F, -tremor * 0.5F, tremor,
				tremor, tremor * 0.6F, tremor * 0.5F, tremor,
				0, 0, 0, 0, 0, 0, 0, 0);
		FlightPoseFrame recoilJolt = bracedFrame(burstPose, -7, -5,
				-10, -10, -8, 8, -10, 10, 8, 8, -6, 0, 0, 8, 5, 0, 0, 6);
		return switch (phase) {
			case CHARGING, WINDUP -> phaseProgress < 0.68F
					? base.lerp(chargeCross, smoothstep(0.0F, 0.68F, phaseProgress))
					: chargeCross.lerp(preBurst, smoothstep(0.68F, 1.0F, phaseProgress));
			case FIRING -> {
				if (phaseProgress < 0.16F) {
					yield preBurst.lerp(recoilJolt, bladeCutIn(phaseProgress / 0.16F));
				}
				if (phaseProgress < 0.44F) {
					yield recoilJolt.lerp(sustainedBurst, smoothstep(0.16F, 0.44F, phaseProgress));
				}
				yield sustainedBurst;
			}
			case RECOVERY -> burstPose.lerp(base, smoothstep(0.0F, 1.0F, phaseProgress));
			case IDLE -> base.lerp(burstPose, Mth.clamp(release, 0.0F, 1.0F));
		};
	}

	private static FlightPoseFrame applyDiveImpact(Player player, FlightPoseFrame base, float partialTick,
			ArmorCombatPhase phase, float phaseProgress) {
		ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
		float impulse = ArmorCombatClient.diveImpactImpulse(player, partialTick);
		return evaluateDiveImpactTimeline(base, variant, phase, phaseProgress, impulse);
	}

	private static FlightPoseFrame evaluateDiveImpactTimeline(FlightPoseFrame base, ArmorVariant variant,
			ArmorCombatPhase phase, float phaseProgress, float impulse) {
		FlightPoseFrame windupPose = impactWindupTarget(base, variant);
		FlightPoseFrame slamPose = impactTarget(base, variant);
		FlightPoseFrame shockCompression = bracedFrame(slamPose, 5, 3,
				-6, -4, -4, 6, 4, 4, 4, 8, -8, 0, 0, 10, -4, 0, 0, 6);
		FlightPoseFrame standGuard = base.lerp(slamPose, 0.38F);
		return switch (phase) {
			case CHARGING, WINDUP -> base.lerp(windupPose, smoothstep(0.0F, 0.85F, phaseProgress));
			case FIRING -> {
				if (phaseProgress < 0.18F) {
					yield windupPose.lerp(shockCompression, bladeCutIn(phaseProgress / 0.18F));
				}
				if (phaseProgress < 0.48F) {
					yield shockCompression.lerp(slamPose, smoothstep(0.18F, 0.48F, phaseProgress));
				}
				yield slamPose;
			}
			case RECOVERY -> phaseProgress < 0.54F
					? slamPose.lerp(standGuard, smoothstep(0.0F, 0.54F, phaseProgress))
					: standGuard.lerp(base, smoothstep(0.54F, 1.0F, phaseProgress));
			case IDLE -> base.lerp(slamPose, Mth.clamp(impulse, 0.0F, 1.0F));
		};
	}

	private static FlightPoseFrame wristPose(FlightPoseFrame base, ArmorVariant variant,
			boolean left, ArmorCombatPhase phase, float progress, float recoil) {
		FlightPoseFrame drawPose = wristDrawTarget(base, variant, left);
		FlightPoseFrame ready = wristTarget(base, variant, left);
		FlightPoseFrame kick = wristFireTarget(base, variant, left);
		FlightPoseFrame absorber = ready.lerp(kick, 0.32F);
		return switch (phase) {
			case CHARGING, WINDUP -> progress < 0.46F
					? base.lerp(drawPose, smoothstep(0.0F, 0.46F, progress))
					: drawPose.lerp(ready, smoothstep(0.46F, 1.0F, progress));
			case FIRING -> {
				if (progress < 0.20F) {
					yield ready.lerp(kick, bladeCutIn(progress / 0.20F));
				}
				if (progress < 0.58F) {
					yield kick.lerp(absorber, smoothstep(0.20F, 0.58F, progress));
				}
				yield absorber.lerp(ready, smoothstep(0.58F, 1.0F, progress));
			}
			case RECOVERY -> ready.lerp(base, smoothstep(0.0F, 1.0F, progress));
			case IDLE -> base.lerp(kick, Mth.clamp(recoil, 0.0F, 1.0F));
		};
	}

	private static FlightPoseFrame wristDrawTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		return combatFrame(base, -2, 12 * side, 2, -4 * side,
				-54, 14 * side, -12 * side, 58,
				-36, 22 * side, 18 * side, 52,
				-14, 3 * side, 4 * side, 36,
				4, -2 * side, -3 * side, 16, left);
	}

	private static FlightPoseFrame coreChargeTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, 9, 9,
					-78, 32, 20, 80, -76, -32, -20, 78, -26, -4, -5, 56, -10, 4, 5, 36);
			case SWIFT -> bracedFrame(base, 7, 7,
					-86, 36, 24, 84, -84, -36, -24, 82, -20, -3, -4, 48, -6, 3, 4, 30);
			case SENTINEL -> bracedFrame(base, 11, 10,
					-70, 28, 18, 86, -68, -28, -18, 84, -32, -6, -8, 68, -18, 6, 8, 54);
		};
	}

	private static FlightPoseFrame impactWindupTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, -16, 14,
					-48, -26, -28, 66, -158, 16, 20, 46, -52, -6, -8, 88, 20, 4, 6, 26)
					.withTorsoAndHeadYaw(degrees(-22.0F), degrees(18.0F));
			case SWIFT -> bracedFrame(base, -20, 16,
					-56, -30, -32, 60, -164, 20, 24, 40, -56, -5, -7, 92, 24, 4, 5, 22)
					.withTorsoAndHeadYaw(degrees(-26.0F), degrees(21.0F));
			case SENTINEL -> bracedFrame(base, -12, 12,
					-142, -16, -20, 56, -152, 16, 20, 50, -44, -8, -10, 80, 16, 6, 8, 36)
					.withTorsoAndHeadYaw(degrees(-16.0F), degrees(14.0F));
		};
	}

	/**
	 * 高达经典侧身举臂射击姿势（Gundam SEED Aerial Rifle / Wrist Aim）：
	 * 躯干侧转 22° 形成低迎弹面切线立姿，头部逆向回转对准准星，射击臂平举，副手折肘横护胸前。
	 */
	private static FlightPoseFrame wristTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		FlightPoseFrame frame = switch (variant) {
			case VANGUARD -> combatFrame(base, -5, 8 * side, 3, -3 * side,
					-90, -6 * side, -8 * side, 12, -48, 30 * side, 24 * side, 66,
					-20, 4 * side, 5 * side, 46, 8, -3 * side, -4 * side, 18, left);
			case SWIFT -> combatFrame(base, -9, 6 * side, 5, -3 * side,
					-98, -4 * side, -5 * side, 8, -32, 34 * side, 28 * side, 54,
					-14, 3 * side, 4 * side, 36, 14, -3 * side, -4 * side, 22, left);
			case SENTINEL -> combatFrame(base, -2, 10 * side, 2, -4 * side,
					-78, -10 * side, -14 * side, 38, -56, 36 * side, 30 * side, 74,
					-26, 6 * side, 8 * side, 58, -8, -4 * side, -6 * side, 38, left);
		};
		return frame.withTorsoAndHeadYaw(
				base.bodyYaw() + degrees(-22.0F * side),
				base.headYawOffset() + degrees(20.0F * side));
	}

	private static FlightPoseFrame wristFireTarget(FlightPoseFrame base, ArmorVariant variant,
			boolean left) {
		float side = left ? -1.0F : 1.0F;
		FlightPoseFrame frame = switch (variant) {
			case VANGUARD -> combatFrame(base, 3, 12 * side, 0, -5 * side,
					-70, -12 * side, -16 * side, 36, -54, 32 * side, 26 * side, 72,
					-24, 5 * side, 7 * side, 52, 10, -4 * side, -5 * side, 22, left);
			case SWIFT -> combatFrame(base, -2, 10 * side, 1, -5 * side,
					-80, -10 * side, -13 * side, 26, -38, 36 * side, 30 * side, 58,
					-18, 4 * side, 5 * side, 42, 16, -4 * side, -5 * side, 28, left);
			case SENTINEL -> combatFrame(base, 7, 14 * side, 2, -6 * side,
					-56, -18 * side, -22 * side, 64, -62, 38 * side, 34 * side, 80,
					-30, 7 * side, 10 * side, 66, -12, -5 * side, -8 * side, 46, left);
		};
		return frame.withTorsoAndHeadYaw(
				base.bodyYaw() + degrees(-28.0F * side),
				base.headYawOffset() + degrees(25.0F * side));
	}

	/**
	 * 自由高达「全弹发射（Hi-MAT Full Burst）」胸炮与翼炮齐射姿势：
	 * 胸膛昂起前推，双臂向斜后下方大角度张开稳住反冲力矩，双腿一屈一伸锚定空中重心。
	 */
	private static FlightPoseFrame coreTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, -10, 4,
					-42, -32, -28, 52, -40, 30, 26, 48, -22, -6, -8, 50, -4, 5, 6, 24);
			case SWIFT -> bracedFrame(base, -13, 6,
					-58, -24, -20, 36, -56, 22, 18, 34, -14, -4, -5, 38, 8, 4, 5, 22);
			case SENTINEL -> bracedFrame(base, -7, 3,
					-36, -38, -30, 68, -34, 38, 30, 66, -26, -8, -11, 64, -14, 7, 10, 48);
		};
	}

	/**
	 * 巴巴托斯/强袭高达三点式超重落地冲击（Superhero Three-Point Mecha Landing）：
	 * 躯干深俯扭腰、单膝大角度屈折缓冲、主战拳垂直轰入地面、副手向后上方展臂平衡。
	 */
	private static FlightPoseFrame impactTarget(FlightPoseFrame base, ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> bracedFrame(base, 20, 8,
					-122, -12, -16, 12, -24, 28, 32, 54, -50, -6, -9, 88, -8, 5, 7, 38)
					.withTorsoAndHeadYaw(degrees(18.0F), degrees(-15.0F));
			case SWIFT -> bracedFrame(base, 15, 6,
					-102, -18, -24, 18, -30, 30, 34, 44, -36, -5, -7, 72, 12, 5, 6, 26)
					.withTorsoAndHeadYaw(degrees(22.0F), degrees(-18.0F));
			case SENTINEL -> bracedFrame(base, 24, 10,
					-128, -18, -22, 14, -114, 20, 24, 18, -62, -8, -12, 96, -42, 8, 12, 82)
					.withTorsoAndHeadYaw(degrees(14.0F), degrees(-12.0F));
		};
	}

	private static FlightPoseFrame bracedFrame(FlightPoseFrame base,
			float bodyPitch, float headPitch,
			float leftArmPitch, float leftArmYaw, float leftArmRoll, float leftElbow,
			float rightArmPitch, float rightArmYaw, float rightArmRoll, float rightElbow,
			float leftLegPitch, float leftLegYaw, float leftLegRoll, float leftKnee,
			float rightLegPitch, float rightLegYaw, float rightLegRoll, float rightKnee) {
		return new FlightPoseFrame(
				base.bodyPitch() + degrees(bodyPitch), base.bodyYaw(), base.bodyRoll(),
				base.headPitchOffset() + degrees(headPitch), base.headYawOffset(), base.headRollOffset(),
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
				case LOAD -> new BladeAngles(-10, -20, -4, 5, -134, 48, 36, 66,
						-32, 28, 22, 40, -20, 6, 8, 46, 12, -4, -5, 22);
				case CONTACT -> new BladeAngles(-16, 22, 5, -7, -60, -68, -32, 10,
						-48, -26, 28, 52, 16, -6, -8, 22, -22, 6, 8, 48);
				case FOLLOW_THROUGH -> new BladeAngles(-9, 26, 3, -7, -38, -80, -42, 34,
						-36, -18, 30, 48, 20, -5, -6, 28, -16, 6, 8, 38);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(-8, 24, -3, -5, -48, -64, -36, 68,
						-36, 34, 24, 44, 12, -4, -5, 26, -18, 5, 7, 42);
				case CONTACT -> new BladeAngles(-14, -22, 5, 7, -98, 70, 36, 8,
						-52, 30, -30, 50, -20, 6, 8, 48, 14, -5, -7, 22);
				case FOLLOW_THROUGH -> new BladeAngles(-8, -26, 3, 7, -50, 82, 42, 36,
						-40, 20, -32, 52, -14, 6, 8, 38, 18, -5, -6, 28);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-18, -8, -8, 3, -152, 14, 10, 76,
						-104, -26, -18, 62, -26, 6, 8, 56, 14, -5, -7, 32);
				case CONTACT -> new BladeAngles(-24, 16, 8, -6, -48, -24, -16, 6,
						-68, 34, 26, 50, 18, -7, -9, 26, -28, 6, 9, 60);
				case FOLLOW_THROUGH -> new BladeAngles(-14, 22, 3, -7, -32, -56, -34, 30,
						-52, 24, 28, 54, 22, -6, -8, 32, -18, 7, 10, 46);
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
				case LOAD -> new BladeAngles(-6, -22, -3, 5, -124, 42, 46, 70,
						-58, 26, 34, 66, -26, 7, 10, 60, -12, -6, -9, 50);
				case CONTACT -> new BladeAngles(6, 24, 5, -7, -64, -74, -42, 18,
						-72, -36, 40, 76, -32, 8, 12, 74, -20, -7, -10, 62);
				case FOLLOW_THROUGH -> new BladeAngles(2, 28, 3, -7, -40, -88, -50, 44,
						-60, -24, 44, 78, -26, 7, 11, 66, -16, -6, -9, 56);
			};
			if (step == 1) return switch (beat) {
				case LOAD -> new BladeAngles(3, 25, -3, -6, -38, -70, -46, 72,
						-70, 40, 46, 82, -14, -6, -9, 52, -26, 8, 11, 64);
				case CONTACT -> new BladeAngles(8, -25, 5, 8, -96, 78, 48, 16,
						-80, 44, -48, 72, -22, -7, -11, 64, -32, 9, 13, 74);
				case FOLLOW_THROUGH -> new BladeAngles(3, -28, 3, 8, -46, 92, 52, 46,
						-66, 30, -46, 76, -16, -6, -9, 56, -28, 8, 12, 68);
			};
			return switch (beat) {
				case LOAD -> new BladeAngles(-22, 0, -10, 0, -154, 8, 6, 82,
						-142, -10, -8, 84, -42, -9, -14, 90, -34, 9, 14, 80);
				case CONTACT -> new BladeAngles(-26, 0, 10, 0, -44, -10, -8, 8,
						-52, 12, 10, 12, -16, -8, -12, 68, -44, 10, 16, 96);
				case FOLLOW_THROUGH -> new BladeAngles(-16, 0, 3, 0, -28, -40, -28, 40,
						-92, 36, 24, 50, -10, -7, -10, 60, -36, 9, 14, 88);
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
					case LOAD -> new BladeAngles(-10, -16, -4, 4,
							-122, 52, 36, 58, -46, -52, -36, 56,
							-14, 4, 5, 34, 10, -3, -4, 22);
					case CONTACT -> new BladeAngles(-16, 18, 6, -6,
							-64, -64, -30, 10, -108, -56, 28, 14,
							12, -5, -6, 20, -18, 4, 6, 40);
					case FOLLOW_THROUGH -> new BladeAngles(-10, 22, 3, -6,
							-44, -76, -38, 30, -66, -68, 34, 34,
							16, -4, -5, 24, -12, 5, 7, 32);
				};
			}
			if (comboStep == 1) {
				return switch (beat) {
					case LOAD -> new BladeAngles(-10, 16, -4, -4,
							-48, -54, -36, 56, -122, 56, 36, 58,
							10, -4, -5, 22, -14, 3, 4, 34);
					case CONTACT -> new BladeAngles(-16, -18, 6, 6,
							-108, 56, 28, 14, -64, 64, -30, 10,
							-18, 5, 6, 40, 12, -4, -6, 20);
					case FOLLOW_THROUGH -> new BladeAngles(-10, -22, 3, 6,
							-66, 68, 34, 34, -44, 76, -38, 30,
							-12, 5, 7, 32, 16, -4, -5, 24);
				};
			}
			return switch (beat) {
				case LOAD -> new BladeAngles(-14, 0, -8, 0,
						-142, 22, 16, 64, -142, -22, -16, 64,
						-18, 4, 5, 44, -12, -4, -5, 34);
				case CONTACT -> new BladeAngles(-20, 0, 8, 0,
						-52, -48, -28, 8, -52, 48, 28, 8,
						14, -4, -5, 22, -22, 4, 6, 48);
				case FOLLOW_THROUGH -> new BladeAngles(-12, 0, 3, 0,
						-38, -64, -36, 28, -38, 64, 36, 28,
						18, -4, -5, 26, -16, 5, 7, 38);
			};
		}

		private static BladeAngles stylize(BladeAngles angles) {
			return angles;
		}
	}
}
