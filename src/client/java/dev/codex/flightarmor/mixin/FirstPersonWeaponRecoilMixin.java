package dev.codex.flightarmor.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** 让第一人称玩家手臂、OBJ 外甲和手持物共同使用同一腕炮后坐矩阵。 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonWeaponRecoilMixin {
	@WrapMethod(method = "submitArmWithItem")
	private void flightArmor$renderArmAndItemWithRecoil(
			PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handsState,
			float partialTick, float pitch, InteractionHand hand, float swingProgress,
			ItemStack stack, float equipProgress, PoseStack poseStack,
			SubmitNodeCollector collector, int packedLight, Operation<Void> original) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			original.call(playerState, handsState, partialTick, pitch, hand, swingProgress,
					stack, equipProgress, poseStack, collector, packedLight);
			return;
		}
		HumanoidArm renderedArm = hand == InteractionHand.MAIN_HAND
				? player.getMainArm() : player.getMainArm().getOpposite();
		boolean left = renderedArm == HumanoidArm.LEFT;
		float recoil = left
				? ArmorCombatClient.leftRecoil(player, partialTick)
				: ArmorCombatClient.rightRecoil(player, partialTick);
		float coreRelease = ArmorCombatClient.coreReleaseImpulse(player, partialTick);
		float impact = ArmorCombatClient.diveImpactImpulse(player, partialTick);
		ArmorCombatPhase phase = ArmorCombatClient.phase(player);
		float bladeDeployment = ArmorCombatClient.bladeProgress(player, partialTick);
		ArmorVariant bladeVariant = ArmorCombatClient.bladeVariant(player);
		ArmorVariant wornVariant = ModItems.fullSetVariant(player).orElse(bladeVariant);
		int bladeComboStep = ArmorCombatClient.bladeComboStep(player);
		ArmorAttackType currentAttack = ArmorCombatClient.attackType(player);
		boolean bladeSlash = (currentAttack == ArmorAttackType.ENERGY_BLADE_SLASH
				|| currentAttack == ArmorAttackType.ENERGY_BLADE_COMBO)
				&& phase != ArmorCombatPhase.IDLE;
		boolean wristAttack = currentAttack == ArmorAttackType.WRIST_GRAVITY_PULSE
				&& phase != ArmorCombatPhase.IDLE;
		poseStack.pushPose();
		try {
			if (bladeSlash) {
				boolean attackingLeft = ArmorCombatClient.attacksWithLeftHand(player);
				boolean attackingArm = left == attackingLeft;
				float phaseProgress = ArmorCombatClient.phaseProgress(player, partialTick);
				BladeCameraPose bladePose = BladeCameraPose.sample(
						phase, phaseProgress, attackingArm, left, bladeVariant, bladeComboStep,
						ArmorCombatClient.bladeComboLinked(player));
				bladePose.apply(poseStack);
			} else if (bladeDeployment > 0.001F) {
				BladeCameraPose.deployment(bladeDeployment, left, bladeVariant).apply(poseStack);
			} else if (wristAttack) {
				CameraActionPose.wristPulseTimeline(wornVariant, left, phase,
						ArmorCombatClient.phaseProgress(player, partialTick), recoil).apply(poseStack);
			} else if (recoil > 0.001F) {
				CameraActionPose.wristPulse(wornVariant, left, recoil).apply(poseStack);
			}
			if (!bladeSlash && coreRelease > 0.001F) {
				CameraActionPose.coreBeam(wornVariant, left, coreRelease).apply(poseStack);
			}
			if (!bladeSlash && impact > 0.001F) {
				CameraActionPose.impact(wornVariant, left, impact).apply(poseStack);
			}
			// The vanilla item swing would add a second, unrelated arc on top of the
			// armor-blade matrix. Keep equip bobbing, but suppress that duplicate arc.
			original.call(playerState, handsState, partialTick, pitch, hand, bladeSlash ? 0.0F : swingProgress,
					stack, equipProgress, poseStack, collector, packedLight);
		} finally {
			poseStack.popPose();
		}

		/*
		 * Vanilla visits the empty offhand but submitArmWithItem intentionally draws no arm for it.
		 * The old workaround called PlayerRenderer directly at the camera origin, which produced the
		 * detached hand near the player's head. Re-enter the vanilla arm transform instead, once from
		 * the main-hand lane, so the support arm owns a real mirrored first-person anchor.
		 */
		if (bladeDeployment > 0.001F && bladeVariant == ArmorVariant.SWIFT
				&& hand == InteractionHand.MAIN_HAND && player.getOffhandItem().isEmpty()
				&& !player.isInvisible()) {
			renderSyntheticSupportArm(player, playerState, partialTick, phase, bladeSlash, bladeDeployment,
					poseStack, collector, packedLight);
		}
	}

	private void renderSyntheticSupportArm(LocalPlayer player, PlayerRenderState playerState,
			float partialTick, ArmorCombatPhase phase, boolean bladeSlash, float bladeDeployment,
			PoseStack poseStack, SubmitNodeCollector collector, int packedLight) {
		HumanoidArm supportArm = player.getMainArm().getOpposite();
		boolean supportLeft = supportArm == HumanoidArm.LEFT;
		poseStack.pushPose();
		try {
			if (bladeSlash) {
				boolean attackingLeft = ArmorCombatClient.attacksWithLeftHand(player);
				float phaseProgress = ArmorCombatClient.phaseProgress(player, partialTick);
				BladeCameraPose.sample(phase, phaseProgress,
						supportLeft == attackingLeft, supportLeft, ArmorVariant.SWIFT,
						ArmorCombatClient.bladeComboStep(player),
						ArmorCombatClient.bladeComboLinked(player))
						.apply(poseStack);
			} else {
				BladeCameraPose.deployment(bladeDeployment, supportLeft, ArmorVariant.SWIFT)
						.apply(poseStack);
			}
			((ItemInHandRendererInvoker) (Object) this).flightArmor$renderPlayerArm(
					poseStack, collector, packedLight, 0.0F, 0.0F, supportArm, playerState);
		} finally {
			poseStack.popPose();
		}
	}

	private record CameraActionPose(
			float x, float y, float z, float pitch, float yaw, float roll
	) {
		private static CameraActionPose wristPulseTimeline(ArmorVariant variant, boolean left,
				ArmorCombatPhase phase, float progress, float recoil) {
			float side = left ? -1.0F : 1.0F;
			CameraActionPose zero = new CameraActionPose(0.0F, 0.0F, 0.0F,
					0.0F, 0.0F, 0.0F);
			CameraActionPose aim = switch (variant) {
				case VANGUARD -> new CameraActionPose(-side * 0.030F, -0.050F, -0.070F,
						-18.0F, side * 5.0F, side * 7.0F);
				case SWIFT -> new CameraActionPose(-side * 0.046F, -0.065F, -0.095F,
						-24.0F, -side * 3.0F, side * 10.0F);
				case SENTINEL -> new CameraActionPose(-side * 0.020F, -0.028F, -0.050F,
						-12.0F, side * 8.0F, side * 12.0F);
			};
			CameraActionPose draw = new CameraActionPose(-side * 0.018F, -0.082F, -0.028F,
					-32.0F, side * 12.0F, side * 14.0F);
			CameraActionPose kick = wristPulse(variant, left, 1.0F);
			CameraActionPose damper = aim.lerp(kick, 0.32F);
			return switch (phase) {
				case CHARGING, WINDUP -> progress < 0.44F
						? zero.lerp(draw, smoothstep(0.0F, 0.44F, progress))
						: draw.lerp(aim, smoothstep(0.44F, 1.0F, progress));
				case FIRING -> {
					if (progress < 0.20F) {
						yield aim.lerp(kick, bladeCutIn(progress / 0.20F));
					}
					if (progress < 0.58F) {
						yield kick.lerp(damper, smoothstep(0.20F, 0.58F, progress));
					}
					yield damper.lerp(aim, smoothstep(0.58F, 1.0F, progress));
				}
				case RECOVERY -> aim.lerp(zero, smoothstep(0.0F, 1.0F, progress));
				case IDLE -> kick.scale(recoil);
			};
		}

		private static CameraActionPose wristPulse(ArmorVariant variant, boolean left, float impulse) {
			float side = left ? -1.0F : 1.0F;
			CameraActionPose authored = switch (variant) {
				case VANGUARD -> new CameraActionPose(side * 0.024F, -0.020F, 0.090F, -7.0F, side * 1.5F, side * 4.0F);
				case SWIFT -> new CameraActionPose(side * 0.014F, -0.036F, 0.122F, -11.0F, -side * 3.0F, side * 7.5F);
				case SENTINEL -> new CameraActionPose(side * 0.040F, 0.006F, 0.072F, -4.0F, side * 4.0F, side * 9.0F);
			};
			return authored.scale(impulse);
		}

		private static CameraActionPose coreBeam(ArmorVariant variant, boolean left, float impulse) {
			float side = left ? -1.0F : 1.0F;
			CameraActionPose authored = switch (variant) {
				case VANGUARD -> new CameraActionPose(side * 0.030F, -0.034F, 0.095F, -6.0F, side * 3.0F, side * 4.0F);
				case SWIFT -> new CameraActionPose(side * 0.020F, -0.048F, 0.132F, -10.0F, -side * 2.0F, side * 6.0F);
				case SENTINEL -> new CameraActionPose(side * 0.050F, 0.010F, 0.112F, -3.0F, side * 5.0F, side * 10.0F);
			};
			return authored.scale(impulse);
		}

		private static CameraActionPose impact(ArmorVariant variant, boolean left, float impulse) {
			float side = left ? -1.0F : 1.0F;
			CameraActionPose authored = switch (variant) {
				case VANGUARD -> new CameraActionPose(side * 0.008F, -0.050F, 0.074F, 6.0F, 0.0F, side * 2.5F);
				case SWIFT -> new CameraActionPose(side * 0.015F, -0.036F, 0.090F, 4.0F, -side * 2.0F, side * 5.0F);
				case SENTINEL -> new CameraActionPose(side * 0.010F, -0.078F, 0.058F, 10.0F, side * 1.0F, side * 3.0F);
			};
			return authored.scale(impulse);
		}

		private CameraActionPose lerp(CameraActionPose target, float alpha) {
			return new CameraActionPose(
					Mth.lerp(alpha, x, target.x), Mth.lerp(alpha, y, target.y),
					Mth.lerp(alpha, z, target.z), Mth.lerp(alpha, pitch, target.pitch),
					Mth.lerp(alpha, yaw, target.yaw), Mth.lerp(alpha, roll, target.roll));
		}

		private CameraActionPose scale(float amount) {
			return new CameraActionPose(x * amount, y * amount, z * amount,
					pitch * amount, yaw * amount, roll * amount);
		}

		private void apply(PoseStack poseStack) {
			poseStack.translate(x, y, z);
			poseStack.rotateDegrees(Axis.ZP, roll);
			poseStack.rotateDegrees(Axis.YP, yaw);
			poseStack.rotateDegrees(Axis.XP, pitch);
		}
	}

	private record BladeCameraPose(
			float x, float y, float z, float pitch, float yaw, float roll
	) {
		private static BladeCameraPose deployment(float progress,
				boolean leftArm, ArmorVariant variant) {
			float side = leftArm ? -1.0F : 1.0F;
			float unlock = smoothstep(0.02F, 0.34F, progress);
			float settle = smoothstep(0.34F, 1.0F, progress);
			BladeCameraPose zero = new BladeCameraPose(0.0F, 0.0F, 0.0F,
					0.0F, 0.0F, 0.0F);
			BladeCameraPose opening = switch (variant) {
				case VANGUARD -> new BladeCameraPose(-side * 0.075F, 0.020F, 0.020F,
						2.0F, side * 5.0F, side * 6.0F);
				case SWIFT -> new BladeCameraPose(-side * 0.180F, -0.015F, -0.035F,
						-6.0F, side * 8.0F, side * 6.0F);
				case SENTINEL -> new BladeCameraPose(-side * 0.185F, 0.035F, -0.010F,
						4.0F, side * 6.0F, side * 5.0F);
			};
			BladeCameraPose ready = switch (variant) {
				case VANGUARD -> new BladeCameraPose(-side * 0.135F, 0.0F, -0.070F,
						-10.0F, side * 5.0F, side * 4.0F);
				case SWIFT -> new BladeCameraPose(-side * 0.205F, -0.020F, -0.090F,
						-12.0F, side * 6.0F, side * 7.0F);
				case SENTINEL -> new BladeCameraPose(-side * 0.205F, 0.018F, -0.050F,
						-6.0F, side * 4.0F, side * 4.0F);
			};
			return zero.lerp(opening, unlock).lerp(ready, settle);
		}

		private static BladeCameraPose sample(ArmorCombatPhase phase, float progress,
				boolean attackingArm, boolean leftArm, ArmorVariant variant, int comboStep,
				boolean linked) {
			float side = leftArm ? -1.0F : 1.0F;
			float reach = variant == ArmorVariant.SENTINEL ? 1.10F
					: variant == ArmorVariant.SWIFT ? 0.92F : 1.0F;
			float support = variant == ArmorVariant.SWIFT ? 1.30F : 1.0F;
			BladeCameraPose zero = new BladeCameraPose(0.0F, 0.0F, 0.0F,
					0.0F, 0.0F, 0.0F);
			BladeCameraPose load;
			BladeCameraPose contact;
			BladeCameraPose follow;
			if (attackingArm) {
				load = new BladeCameraPose(-side * 0.12F, -0.085F, 0.015F,
						-22.0F, side * 18.0F, side * 27.0F);
				contact = new BladeCameraPose(-side * 0.180F * reach, 0.018F,
						-0.155F * reach, -59.0F, -side * 43.0F, -side * 55.0F);
				follow = new BladeCameraPose(-side * 0.14F * reach, 0.110F,
						0.040F, -27.0F, -side * 49.0F, -side * 35.0F);
			} else {
				load = new BladeCameraPose(-side * 0.12F * support, 0.012F, -0.025F,
						-7.0F * support, -side * 5.0F, -side * 6.0F);
				contact = new BladeCameraPose(-side * 0.10F * support, -0.025F,
						0.075F, -15.0F * support, side * 9.0F, side * 12.0F);
				follow = new BladeCameraPose(-side * 0.08F * support, 0.015F,
						0.055F, -11.0F * support, side * 6.0F, side * 8.0F);
			}

			BladeCameraPose entry = linked && comboStep > 0
					? follow.comboStyle(variant, comboStep - 1, attackingArm) : zero;

			load = load.comboStyle(variant, comboStep, attackingArm);
			contact = contact.comboStyle(variant, comboStep, attackingArm);
			follow = follow.comboStyle(variant, comboStep, attackingArm);

			BladeCameraPose apex = entry.lerp(load, 1.12F);
			BladeCameraPose hitStop = contact.lerp(follow, 0.24F);
			BladeCameraPose flourish = follow.lerp(zero, 0.56F);
			return switch (phase) {
				case WINDUP, CHARGING -> progress < 0.76F
						? entry.lerp(load, bladeAnticipation(progress / 0.76F))
						: load.lerp(apex, smoothstep(0.76F, 1.0F, progress));
				case FIRING -> {
					if (progress < 0.22F) {
						yield apex.lerp(contact, bladeCutIn(progress / 0.22F));
					}
					if (progress < 0.42F) {
						yield contact.lerp(hitStop, smoothstep(0.22F, 0.42F, progress));
					}
					yield hitStop.lerp(follow, bladeCutOut((progress - 0.42F) / 0.58F));
				}
				case RECOVERY -> progress < 0.48F
						? follow.lerp(flourish, smoothstep(0.0F, 0.48F, progress))
						: flourish.lerp(zero, bladeSettle((progress - 0.48F) / 0.52F));
				case IDLE -> zero;
			};
		}

		private BladeCameraPose comboStyle(ArmorVariant variant, int comboStep,
				boolean attackingArm) {
			int step = Math.floorMod(comboStep, 3);
			return switch (variant) {
				case VANGUARD -> switch (step) {
					case 1 -> new BladeCameraPose(x * 0.96F,
							y + (attackingArm ? 0.016F : -0.006F), z - 0.018F,
							pitch + (attackingArm ? 4.0F : -2.0F),
							-yaw * 0.92F, -roll * 0.84F);
					case 2 -> attackingArm
							? new BladeCameraPose(x * 0.88F, y - 0.024F, z - 0.058F,
									pitch - 11.0F, yaw * 0.48F, roll * 0.58F)
							: new BladeCameraPose(x, y + 0.018F, z + 0.012F,
									pitch - 4.0F, -yaw * 0.48F, -roll * 0.55F);
					default -> this;
				};
				case SWIFT -> switch (step) {
					case 1 -> new BladeCameraPose(x * 1.06F, y - 0.010F, z - 0.028F,
							pitch + (attackingArm ? 7.0F : -5.0F), -yaw, -roll * 0.92F);
					case 2 -> new BladeCameraPose(x * (attackingArm ? 0.82F : 1.12F),
							y + (attackingArm ? -0.034F : 0.024F),
							z + (attackingArm ? -0.075F : 0.020F),
							pitch + (attackingArm ? -15.0F : -7.0F),
							attackingArm ? yaw * 0.35F : -yaw * 0.62F,
							attackingArm ? roll * 0.48F : -roll * 0.70F);
					default -> this;
				};
				case SENTINEL -> switch (step) {
					case 1 -> new BladeCameraPose(x * 1.04F,
							y + (attackingArm ? 0.026F : 0.010F), z - 0.010F,
							pitch + (attackingArm ? 9.0F : -1.0F),
							-yaw * 0.78F, -roll * 0.72F);
					case 2 -> new BladeCameraPose(x * (attackingArm ? 0.76F : 1.08F),
							y + (attackingArm ? -0.045F : 0.032F),
							z + (attackingArm ? -0.090F : 0.028F),
							pitch + (attackingArm ? -20.0F : -9.0F),
							attackingArm ? yaw * 0.28F : -yaw * 0.45F,
							attackingArm ? roll * 0.42F : -roll * 0.56F);
					default -> this;
				};
			};
		}

		private BladeCameraPose lerp(BladeCameraPose target, float alpha) {
			return new BladeCameraPose(
					Mth.lerp(alpha, x, target.x),
					Mth.lerp(alpha, y, target.y),
					Mth.lerp(alpha, z, target.z),
					Mth.lerp(alpha, pitch, target.pitch),
					Mth.lerp(alpha, yaw, target.yaw),
					Mth.lerp(alpha, roll, target.roll));
		}

		private void apply(PoseStack poseStack) {
			poseStack.translate(x, y, z);
			poseStack.rotateDegrees(Axis.ZP, roll);
			poseStack.rotateDegrees(Axis.YP, yaw);
			poseStack.rotateDegrees(Axis.XP, pitch);
		}
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
		float normalized = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return normalized * normalized * (3.0F - 2.0F * normalized);
	}
}
