package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.client.cruise.ArmorCruiseClient;
import dev.codex.flightarmor.client.cruise.ArmorEscortClient;
import dev.codex.flightarmor.client.cruise.CruiseDeploymentClient;
import dev.codex.flightarmor.client.cruise.CruiseDeploymentTimeline;
import dev.codex.flightarmor.client.cruise.DockingTimeline;
import dev.codex.flightarmor.client.cruise.RecallApproachTimeline;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import net.minecraft.client.model.ArmorStandModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只隐藏独立巡航载体的木质支架；OBJ 战甲仍由盔甲渲染层正常绘制。 */
@Mixin(ArmorStandModel.class)
public abstract class CruiseArmorStandModelMixin {
	@Shadow @Final private ModelPart rightBodyStick;
	@Shadow @Final private ModelPart leftBodyStick;
	@Shadow @Final private ModelPart shoulderStick;
	@Shadow @Final private ModelPart basePlate;

	@Inject(method = "setupAnim(Lnet/minecraft/world/entity/decoration/ArmorStand;FFFFF)V", at = @At("TAIL"))
	private void flightArmor$hideCruiseCarrierBody(ArmorStand stand, float limbSwing, float limbSwingAmount,
			float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		ArmorStandModel model = (ArmorStandModel) (Object) this;
		// 实体 tag 不保证随 spawn 包同步到客户端，payload 中的载体 UUID 才是权威来源。
		boolean retired = ArmorCruiseClient.isRetiredCarrier(stand);
		boolean carrier = ArmorCruiseClient.isCruiseCarrier(stand);
		boolean hideCarrierBody = carrier || retired || ArmorEscortClient.isEscortCarrier(stand);
		if (carrier) {
			ArmorCruisePhase phase = ArmorCruiseClient.phaseOfCarrier(stand);
			float partialTick = ageInTicks - stand.tickCount;
			RecallApproachTimeline.Sample approach = RecallApproachTimeline.sample(stand, partialTick);
			CarrierPose pose = switch (phase) {
				case CRUISING -> null; // 固定区域巡航保留服务端原创非对称姿势。
				case DEPLOYING -> {
					CruiseDeploymentTimeline.Sample deployment =
							CruiseDeploymentClient.carrierSample(stand, partialTick);
					yield NEUTRAL_POSE.lerp(CRUISE_POSE, deployment == null ? 0.0F : deployment.flight());
				}
				case RECALLING -> recallingPose(model, approach);
				case PHASING -> {
					float progress = ArmorCruiseClient.phasingProgress(stand, partialTick);
					yield progress < 0.5F
							? CRUISE_POSE.lerp(PHASE_FOLD_POSE, rangeEase(progress, 0.0F, 0.5F))
							: PHASE_FOLD_POSE.lerp(CRUISE_POSE,
									rangeEase(progress, 0.5F, 1.0F));
				}
				case OWNER_DOWN -> OWNER_GUARD_POSE;
				case DOCKING -> {
					float progress = ArmorCruiseClient.dockingProgress(stand, partialTick);
					// RECALLING 捕获点的末帧就是悬停姿势，DOCKING 不再跳回低风阻姿势。
					if (progress < DockingTimeline.FRAME_START) yield APPROACH_HOVER_POSE;
					if (progress < DockingTimeline.WRAP_START) {
						yield APPROACH_HOVER_POSE.lerp(DOCKING_FRAME_OPEN_POSE,
								rangeEase(progress, DockingTimeline.FRAME_START,
										DockingTimeline.WRAP_START));
					}
					if (progress < DockingTimeline.ZONE_LOCK_START) yield DOCKING_FRAME_OPEN_POSE;
					yield dockingClosingPose(progress);
				}
				case IDLE -> null;
			};
			if (pose != null) pose.apply(model);
		}

		// ArmorStandModel 会在多个实体之间复用，非载体必须显式恢复上一实体留下的可见性。
		model.head.visible = !hideCarrierBody;
		model.hat.visible = !hideCarrierBody;
		model.body.visible = !hideCarrierBody;
		model.rightArm.visible = !hideCarrierBody && stand.isShowArms();
		model.leftArm.visible = !hideCarrierBody && stand.isShowArms();
		model.rightLeg.visible = !hideCarrierBody;
		model.leftLeg.visible = !hideCarrierBody;
		rightBodyStick.visible = !hideCarrierBody;
		leftBodyStick.visible = !hideCarrierBody;
		shoulderStick.visible = !hideCarrierBody;
		basePlate.visible = !hideCarrierBody && !stand.isNoBasePlate();
	}

	/**
	 * 主骨骼、OBJ 肘膝和整甲根俯仰共用同一距离采样。entry=0 时从本帧服务端姿势开始，
	 * 2 tick 后完全进入目标姿势，直接召回不会出现六根骨骼同时跳变。
	 */
	private static CarrierPose recallingPose(ArmorStandModel model,
			RecallApproachTimeline.Sample approach) {
		CarrierPose target = LOW_DRAG_POSE.lerp(APPROACH_BRAKING_POSE,
				approach.brakingProgress()).lerp(APPROACH_HOVER_POSE, approach.hoverProgress());
		return CarrierPose.capture(model).lerp(target, approach.entryProgress());
	}

	private static float rangeEase(float value, float start, float end) {
		float clamped = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return clamped * clamped * (3.0F - 2.0F * clamped);
	}

	private static float degrees(float value) {
		return value * Mth.DEG_TO_RAD;
	}

	/** 每根载体骨骼在对应 OBJ 分区移交 PlayerModel 之前精确归零。 */
	private static CarrierPose dockingClosingPose(float progress) {
		return new CarrierPose(
				DOCKING_FRAME_OPEN_POSE.head.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.HELMET_LOCK_START,
								DockingTimeline.HELMET_TRANSFER)),
				DOCKING_FRAME_OPEN_POSE.body.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.CHEST_LOCK_START,
								DockingTimeline.CHEST_TRANSFER)),
				DOCKING_FRAME_OPEN_POSE.leftArm.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.ZONE_LOCK_START,
								DockingTimeline.ARMS_TRANSFER)),
				DOCKING_FRAME_OPEN_POSE.rightArm.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.ZONE_LOCK_START,
								DockingTimeline.ARMS_TRANSFER)),
				DOCKING_FRAME_OPEN_POSE.leftLeg.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.ZONE_LOCK_START,
								DockingTimeline.BOOTS_TRANSFER)),
				DOCKING_FRAME_OPEN_POSE.rightLeg.lerp(PartPose.ZERO,
						rangeEase(progress, DockingTimeline.ZONE_LOCK_START,
								DockingTimeline.BOOTS_TRANSFER)));
	}

	private static final CarrierPose NEUTRAL_POSE = new CarrierPose(
			PartPose.ZERO, PartPose.ZERO, PartPose.ZERO, PartPose.ZERO, PartPose.ZERO, PartPose.ZERO);

	private static final CarrierPose CRUISE_POSE = new CarrierPose(
			new PartPose(-4, 7, -3), new PartPose(4, -5, 2),
			new PartPose(-18, 0, -16), new PartPose(12, 0, 22),
			new PartPose(8, 0, -5), new PartPose(-11, 0, 7));
	private static final CarrierPose LOW_DRAG_POSE = new CarrierPose(
			new PartPose(-2, 0, 0), new PartPose(2, 0, 0),
			new PartPose(-7, 0, -4), new PartPose(-7, 0, 4),
			new PartPose(2.5F, 0, -1.5F), new PartPose(2.5F, 0, 1.5F));
	private static final CarrierPose APPROACH_BRAKING_POSE = new CarrierPose(
			new PartPose(-7, 0, -2), new PartPose(5, 0, 0),
			new PartPose(-13, -3, -10), new PartPose(-10, 4, 12),
			new PartPose(7, -2, -4), new PartPose(-3, 2, 5));
	private static final CarrierPose APPROACH_HOVER_POSE = new CarrierPose(
			new PartPose(-4, 0, -2), new PartPose(3, 0, 0),
			new PartPose(-18, -3, -15), new PartPose(-12, 4, 17),
			new PartPose(8, -3, -6), new PartPose(-6, 3, 7));
	private static final CarrierPose PHASE_FOLD_POSE = new CarrierPose(
			new PartPose(-18, 0, 0), new PartPose(16, 0, 0),
			new PartPose(-68, 0, -18), new PartPose(-64, 0, 18),
			new PartPose(36, 0, -8), new PartPose(32, 0, 8));
	private static final CarrierPose OWNER_GUARD_POSE = new CarrierPose(
			new PartPose(-7, 0, -2), new PartPose(3, 0, 0),
			new PartPose(-16, 0, -12), new PartPose(-9, 0, 12),
			new PartPose(5, 0, -5), new PartPose(-2, 0, 5));
	private static final CarrierPose DOCKING_FRAME_OPEN_POSE = new CarrierPose(
			new PartPose(-3, 0, 0), new PartPose(5, 0, 0),
			new PartPose(-14, 0, -32), new PartPose(-14, 0, 32),
			new PartPose(8, 0, -10), new PartPose(8, 0, 10));

	private record PartPose(float x, float y, float z) {
		private static final PartPose ZERO = new PartPose(0, 0, 0);

		private PartPose lerp(PartPose target, float alpha) {
			return new PartPose(Mth.lerp(alpha, x, target.x), Mth.lerp(alpha, y, target.y),
					Mth.lerp(alpha, z, target.z));
		}

		private void apply(ModelPart part) {
			part.xRot = degrees(x);
			part.yRot = degrees(y);
			part.zRot = degrees(z);
		}

		private static PartPose capture(ModelPart part) {
			return new PartPose(part.xRot / Mth.DEG_TO_RAD,
					part.yRot / Mth.DEG_TO_RAD, part.zRot / Mth.DEG_TO_RAD);
		}
	}

	private record CarrierPose(PartPose head, PartPose body, PartPose leftArm, PartPose rightArm,
			PartPose leftLeg, PartPose rightLeg) {
		private static CarrierPose capture(ArmorStandModel model) {
			return new CarrierPose(PartPose.capture(model.head), PartPose.capture(model.body),
					PartPose.capture(model.leftArm), PartPose.capture(model.rightArm),
					PartPose.capture(model.leftLeg), PartPose.capture(model.rightLeg));
		}

		private CarrierPose lerp(CarrierPose target, float alpha) {
			return new CarrierPose(head.lerp(target.head, alpha), body.lerp(target.body, alpha),
					leftArm.lerp(target.leftArm, alpha), rightArm.lerp(target.rightArm, alpha),
					leftLeg.lerp(target.leftLeg, alpha), rightLeg.lerp(target.rightLeg, alpha));
		}

		private void apply(ArmorStandModel model) {
			head.apply(model.head);
			body.apply(model.body);
			leftArm.apply(model.leftArm);
			rightArm.apply(model.rightArm);
			leftLeg.apply(model.leftLeg);
			rightLeg.apply(model.rightLeg);
		}
	}
}
