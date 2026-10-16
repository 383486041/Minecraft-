package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.animation.AttackPoseController;
import dev.codex.flightarmor.client.animation.FlightJointAnimation;
import dev.codex.flightarmor.client.animation.FlightPoseFrame;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyPart;
import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.client.cruise.ArmorCruiseClient;
import dev.codex.flightarmor.client.cruise.ArmorEscortClient;
import dev.codex.flightarmor.client.cruise.CruiseDeploymentClient;
import dev.codex.flightarmor.client.cruise.CruiseDeploymentTimeline;
import dev.codex.flightarmor.client.cruise.CruiseCombatClient;
import dev.codex.flightarmor.client.cruise.DockingTimeline;
import dev.codex.flightarmor.client.cruise.RecallApproachTimeline;
import dev.codex.flightarmor.client.platform.PlatformRecallClient;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.CruiseCombatBehavior;
import dev.codex.flightarmor.cruise.CruiseCombatMode;
import dev.codex.flightarmor.cruise.CruiseCombatStage;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.configuration.SentinelVisualZone;
import dev.codex.flightarmor.combat.SentinelBreakerTimeline;
import dev.codex.flightarmor.flight.FlightLightColorCurve;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorEscortStatePayload;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Matrix3f;

import java.util.Locale;

/**
 * Fabric 原生 OBJ 盔甲渲染器。
 *
 * <p>分组名决定骨骼、装备槽、材质和动态部件。例如：
 * {@code head_shell_titanium}、{@code body_core_led}、
 * {@code body_vane_left_dark}、{@code left_arm_plate_titanium}、
 * {@code right_leg_frame_dark}、{@code left_boot_ring_led}。</p>
 */
public final class FlightObjArmorRenderer implements ArmorRenderer {
	/** 使用游戏内置纯白纹理，OBJ 的外观完全由顶点色与法线决定。 */
	public static final ResourceLocation BASE_TEXTURE =
			ResourceLocation.withDefaultNamespace("textures/misc/white.png");
	public static final ResourceLocation EMISSIVE_TEXTURE =
			ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	private static final ArmorObjMesh.Color TITANIUM = new ArmorObjMesh.Color(238, 242, 248, 255);
	private static final ArmorObjMesh.Color LIGHT_TITANIUM = new ArmorObjMesh.Color(246, 198, 52, 255);
	private static final ArmorObjMesh.Color GUNMETAL = new ArmorObjMesh.Color(46, 54, 66, 255);
	private static final ArmorObjMesh.Color SHADOW = new ArmorObjMesh.Color(20, 24, 32, 255);
	private static final ArmorObjMesh.Color ACCENT = new ArmorObjMesh.Color(36, 92, 186, 255);
	private static final ArmorObjMesh.Color CRIMSON = new ArmorObjMesh.Color(212, 46, 52, 255);
	private static final ArmorObjMesh.Color VISOR = new ArmorObjMesh.Color(42, 242, 196, 255);

	private static final ArmorObjMesh.Color SWIFT_PRIMARY = new ArmorObjMesh.Color(230, 228, 242, 255);
	private static final ArmorObjMesh.Color SWIFT_HIGHLIGHT = new ArmorObjMesh.Color(242, 202, 78, 255);
	private static final ArmorObjMesh.Color SWIFT_ACCENT = new ArmorObjMesh.Color(72, 48, 148, 255);
	private static final ArmorObjMesh.Color SWIFT_CRIMSON = new ArmorObjMesh.Color(196, 38, 112, 255);
	private static final ArmorObjMesh.Color SWIFT_GUNMETAL = new ArmorObjMesh.Color(42, 36, 58, 255);
	private static final ArmorObjMesh.Color SWIFT_SHADOW = new ArmorObjMesh.Color(18, 14, 28, 255);
	private static final ArmorObjMesh.Color SWIFT_VISOR = new ArmorObjMesh.Color(236, 72, 255, 255);

	private static final ArmorObjMesh.Color SENTINEL_PRIMARY = new ArmorObjMesh.Color(242, 238, 228, 255);
	private static final ArmorObjMesh.Color SENTINEL_HIGHLIGHT = new ArmorObjMesh.Color(238, 188, 54, 255);
	private static final ArmorObjMesh.Color SENTINEL_ACCENT = new ArmorObjMesh.Color(38, 52, 82, 255);
	private static final ArmorObjMesh.Color SENTINEL_CRIMSON = new ArmorObjMesh.Color(178, 42, 44, 255);
	private static final ArmorObjMesh.Color SENTINEL_GUNMETAL = new ArmorObjMesh.Color(46, 42, 38, 255);
	private static final ArmorObjMesh.Color SENTINEL_SHADOW = new ArmorObjMesh.Color(20, 18, 16, 255);
	private static final ArmorObjMesh.Color SENTINEL_VISOR = new ArmorObjMesh.Color(54, 244, 210, 255);

	private static final java.util.Map<String, GroupBinding> BINDING_CACHE =
			new java.util.concurrent.ConcurrentHashMap<>(256);
	/** 在客户端初始化中调用；同时启用 F3+T OBJ 热重载。 */
	public static void initialize() {
		FlightArmorObjResources.registerReloadListener();
		ArmMountedBladeRenderer.bootstrap();
		ArmorRenderer.register(new FlightObjArmorRenderer(),
				ModItems.FLIGHT_HELMET,
				ModItems.FLIGHT_CHESTPLATE,
				ModItems.FLIGHT_LEGGINGS,
				ModItems.FLIGHT_BOOTS);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			if (renderer instanceof PlayerRenderer playerRenderer) {
				helper.register(new DockingArmorOverlay(playerRenderer));
				helper.register(new DeploymentArmorOverlay(playerRenderer));
				helper.register(new ArmMountedBladeLayer(playerRenderer));
				helper.register(new ArmorCorePendantLayer(playerRenderer));
			}
		});
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource buffers, ItemStack stack,
			LivingEntity entity, EquipmentSlot slot, int packedLight,
			HumanoidModel<LivingEntity> contextModel) {
		// IDLE 快照可能领先实体移除一帧；墓碑窗口内既不渲染旧装备，也不让木架兜底露出。
		if (ArmorCruiseClient.isRetiredCarrier(entity)) {
			return;
		}
		ArmorObjMesh mesh = FlightArmorObjResources.mesh(ArmorVariantData.get(stack));
		if (mesh.groups().isEmpty()) {
			return;
		}

		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
		ArmorVariant armorVariant = ArmorVariantData.get(stack);
		if (entity instanceof Player player && CruiseDeploymentClient.suppressWornArmor(player, partialTick)) {
			return;
		}
		boolean mainCruiseCarrier = ArmorCruiseClient.isCruiseCarrier(entity);
		boolean escortCarrier = ArmorEscortClient.isEscortCarrier(entity);
		boolean cruiseCarrier = mainCruiseCarrier || escortCarrier;
		ArmorEscortStatePayload.EscortPhase escortPhase = ArmorEscortClient.phaseOf(entity);
		ArmorCruisePhase cruisePhase = mainCruiseCarrier
				? ArmorCruiseClient.phaseOfCarrier(entity)
				: escortCarrier ? ArmorCruisePhase.CRUISING : ArmorCruisePhase.IDLE;
		CruiseCombatStage combatStage = mainCruiseCarrier
				&& cruisePhase == ArmorCruisePhase.CRUISING
				? CruiseCombatClient.stageOfCarrier(entity) : CruiseCombatStage.IDLE;
		float combatStageProgress = combatStage == CruiseCombatStage.IDLE
				? 0.0F : CruiseCombatClient.stageProgress(entity, partialTick);
		if (mainCruiseCarrier && CruiseDeploymentClient.hideCarrierBeforeHandoff(entity, partialTick)) {
			return;
		}
		CruiseDeploymentTimeline.Sample deploymentSample = mainCruiseCarrier
				? CruiseDeploymentClient.carrierSample(entity, partialTick) : null;
		PlatformRecallClient.Sample platformRecall = entity instanceof Player player
				? PlatformRecallClient.sample(player, partialTick) : PlatformRecallClient.Sample.NONE;
		float deploymentDockingProgress = deploymentSample == null ? 0.0F
				: Mth.lerp(deploymentSample.open(), DockingTimeline.FRAME_START, DockingTimeline.WRAP_START);
		float dockingProgress = cruiseCarrier && cruisePhase == ArmorCruisePhase.DOCKING
				? ArmorCruiseClient.dockingProgress(entity, partialTick) : 0.0F;
		float exoskeletonProgress = platformRecall.active() && platformRecall.assembled()
				? platformRecall.wrapProgress()
				: deploymentSample == null ? dockingProgress : deploymentDockingProgress;
		float dockingEase = dockingMechanicalProgress(dockingProgress);
		float phasingProgress = cruiseCarrier && cruisePhase == ArmorCruisePhase.PHASING
				? ArmorCruiseClient.phasingProgress(entity, partialTick) : 0.0F;
		float phasingFold = phasingFoldProgress(phasingProgress);
		RecallApproachTimeline.Sample recallApproach = cruiseCarrier
				? RecallApproachTimeline.sample(entity, partialTick) : null;
		float flightProgress = escortCarrier ? switch (escortPhase) {
			case LAUNCHING -> 0.44F;
			case TRANSIT -> 0.92F;
			case ESCORTING -> 0.68F;
		} : cruiseCarrier ? switch (cruisePhase) {
			case CRUISING -> 0.68F;
			case DEPLOYING -> deploymentSample == null ? 0.68F : 0.68F * deploymentSample.flight();
			case RECALLING -> recallApproach.structureProgress();
			case PHASING -> phasingFlightProgress(phasingProgress);
			case OWNER_DOWN -> 0.46F;
			case DOCKING -> Mth.lerp(dockingEase, 0.68F, 0.0F);
			case IDLE -> 0.0F;
		} : flightProgress(entity, partialTick);
		float diveProgress = escortCarrier
				? escortPhase == ArmorEscortStatePayload.EscortPhase.TRANSIT ? 0.78F : 0.0F
				: cruiseCarrier
				? cruisePhase == ArmorCruisePhase.RECALLING ? recallApproach.diveProgress() : 0.0F
				: entity instanceof Player player
				? FlightArmorClient.diveVisualProgress(player, partialTick)
				: (entity.isFallFlying() ? 1.0F : 0.0F);
		float boostProgress = cruiseCarrier ? 0.0F : entity instanceof Player player
				? FlightArmorClient.boostVisualProgress(player, partialTick)
				: (entity.isSprinting() ? 1.0F : 0.0F);
		float actualSpeedProgress = flightSpeedProgress(entity, partialTick);
		float speedProgress = escortCarrier ? recallApproach.speedProgress() : cruiseCarrier ? switch (cruisePhase) {
			case CRUISING -> 0.12F;
			case DEPLOYING -> deploymentSample == null ? 0.12F : 0.12F * deploymentSample.flight();
			case RECALLING -> recallApproach.speedProgress();
			case PHASING -> Mth.lerp(phasingFold, 0.82F, 0.16F);
			case OWNER_DOWN -> 0.04F;
			case DOCKING -> 0.0F;
			case IDLE -> 0.0F;
		} : actualSpeedProgress;
		// 常态穿戴时保持稳定的满亮青色；只有动力飞行启动后才让真实速度驱动颜色变化。
		float ledSpeedProgress = flightProgress > 0.01F ? speedProgress : 0.0F;
		float animationTime = entity.tickCount + partialTick;
		boolean assembling = entity instanceof Player player && ArmorAssemblyClient.isAnimating(player);
		FlightPoseFrame flightPose = escortCarrier
				? AttackPoseController.applyAutonomous(entity,
						cruiseCarrierPose(escortPhase == ArmorEscortStatePayload.EscortPhase.TRANSIT
								? ArmorCruisePhase.RECALLING : ArmorCruisePhase.CRUISING,
								0.0F, 0.0F, recallApproach, 1.0F,
								CruiseCombatStage.IDLE, 0.0F, armorVariant),
						armorVariant, partialTick)
				: cruiseCarrier
				? AttackPoseController.applyAutonomous(entity,
						cruiseCarrierPose(cruisePhase, dockingProgress, phasingProgress, recallApproach,
								deploymentSample == null ? 0.0F : deploymentSample.flight(),
								combatStage, combatStageProgress, armorVariant),
						armorVariant, partialTick)
				: entity instanceof Player player
				? FlightJointAnimation.poseFor(player, partialTick)
				: FlightPoseFrame.ZERO;
		boolean platformAssembled = platformRecall.active() && platformRecall.assembled();
		boolean platformParts = platformRecall.active() && !platformRecall.assembled();
		boolean partAssembly = assembling && !platformRecall.active();

		// 整甲俯仰必须在各 ModelPart 和各 OBJ 分组变换之前统一应用，否则头、胸、腿会绕各自原点散开。
		poseStack.pushPose();
		if (escortCarrier && escortPhase == ArmorEscortStatePayload.EscortPhase.TRANSIT) {
			applyRootPitch(poseStack, Mth.lerp(Mth.clamp(speedProgress, 0.0F, 1.0F), 22.0F, 72.0F));
		}
		if (mainCruiseCarrier && cruisePhase == ArmorCruisePhase.CRUISING) {
			applyCruiseCombatRootTransform(poseStack, combatStage, combatStageProgress);
		}
		if (cruiseCarrier && cruisePhase == ArmorCruisePhase.RECALLING) {
			applyRecallRootTransform(poseStack, recallApproach);
		}
		if (cruiseCarrier && cruisePhase == ArmorCruisePhase.DOCKING) {
			applyDockingRootTransform(poseStack, entity, dockingProgress, partialTick);
		}
		if (platformAssembled) {
			translateWorldDelta(poseStack, platformRecall.rootOffset());
			applyRootPitch(poseStack, platformRecall.rootPitchDegrees());
			float recallScale = platformRecall.assembledVisualScale();
			poseStack.scale(recallScale, recallScale, recallScale);
		}
		if (deploymentSample != null) {
			applyDeploymentRootTransform(poseStack, entity, deploymentSample.travel(), partialTick);
		}

		VertexConsumer baseConsumer = ItemRenderer.getArmorFoilBuffer(buffers,
				RenderType.armorCutoutNoCull(BASE_TEXTURE), stack.hasFoil());
		for (ArmorObjMesh.Group group : mesh.groups()) {
			GroupBinding binding = GroupBinding.from(group.name());
			if (binding == null || binding.slot() != slot || isLed(group.name())) {
				continue;
			}
			if (cruisePhase == ArmorCruisePhase.DOCKING
					&& dockingRegionTransferredToOwner(group.name(), binding, dockingProgress)) continue;
			ArmorAssemblyPart assemblyPart = assemblyPartFor(group.name(), binding);
			float assemblyPartProgress = partAssembly
					? ArmorAssemblyClient.partProgress((Player) entity, assemblyPart, partialTick)
					: 1.0F;
			if (partAssembly && assemblyPartProgress <= 0.0001F) {
				continue;
			}
			poseStack.pushPose();
			applyPlatformPartTransform(poseStack, platformRecall, binding, assemblyPart);
			renderGroup(mesh, group, binding, contextModel, poseStack, baseConsumer, entity,
					packedLight, variantBaseColor(armorVariant, group.name(), colorFor(group.name(), 1.0F)), flightProgress, speedProgress,
					diveProgress, animationTime, flightPose, exoskeletonProgress,
					assemblyPart, assemblyPartProgress, partAssembly);
			poseStack.popPose();
		}

		// 发光层常态也渲染。entityTranslucentEmissive + FULL_BRIGHT 保证昼夜、水下均清晰可见。
		VertexConsumer emissiveConsumer = buffers.getBuffer(RenderType.entityTranslucentEmissive(EMISSIVE_TEXTURE));
		for (ArmorObjMesh.Group group : mesh.groups()) {
			GroupBinding binding = GroupBinding.from(group.name());
			if (binding == null || binding.slot() != slot || !isLed(group.name())) {
				continue;
			}
			if (cruisePhase == ArmorCruisePhase.DOCKING
					&& dockingRegionTransferredToOwner(group.name(), binding, dockingProgress)) continue;
			ArmorAssemblyPart assemblyPart = assemblyPartFor(group.name(), binding);
			float assemblyPartProgress = partAssembly
					? ArmorAssemblyClient.partProgress((Player) entity, assemblyPart, partialTick)
					: 1.0F;
			// 发光线路在部件完成接近、开始对正之后才上电，避免远处飞行时整片提前亮起。
			if (partAssembly && assemblyPartProgress < 0.78F) {
				continue;
			}
			ArmorObjMesh.Color emissiveColor = switch (cruisePhase) {
				case DEPLOYING -> dockingLedColor(exoskeletonProgress, group.name());
				case PHASING -> phasingLedColor(phasingProgress);
				case OWNER_DOWN -> ownerDownLedColor(animationTime);
				case DOCKING -> dockingLedColor(dockingProgress, group.name());
				default -> ledColor(ledSpeedProgress);
			};
			if (platformAssembled) {
				emissiveColor = platformAssembledRecallLedColor(platformRecall, group.name());
			}
			if (platformParts) {
				emissiveColor = platformRecallLedColor(platformRecall, binding.slot(), assemblyPart);
			}
			if (!platformAssembled && !platformParts) {
				emissiveColor = variantLedColor(
						entity, armorVariant, group.name(), emissiveColor, ledSpeedProgress);
			}
			emissiveColor = weaponLedColor(entity, group.name(), partialTick, emissiveColor);
			if (partAssembly) {
				emissiveColor = mixColor(new ArmorObjMesh.Color(255, 246, 255, 255), emissiveColor,
						smoothstep(0.78F, 1.0F, assemblyPartProgress));
			}
			emissiveColor = cruiseCombatLedColor(entity, group.name(), partialTick, emissiveColor);
			poseStack.pushPose();
			applyPlatformPartTransform(poseStack, platformRecall, binding, assemblyPart);
			renderGroup(mesh, group, binding, contextModel, poseStack, emissiveConsumer, entity,
					LightTexture.FULL_BRIGHT, emissiveColor,
					flightProgress, speedProgress, diveProgress, animationTime, flightPose,
					exoskeletonProgress, assemblyPart, assemblyPartProgress, partAssembly);
			poseStack.popPose();
		}
		if (cruiseCarrier && slot == EquipmentSlot.CHEST) {
			ArmMountedBladeRenderer.renderAutonomous(poseStack, buffers, entity, contextModel,
					armorVariant, false, partialTick, flightPose);
			if (armorVariant == ArmorVariant.SWIFT) {
				ArmMountedBladeRenderer.renderAutonomous(poseStack, buffers, entity, contextModel,
						armorVariant, true, partialTick, flightPose);
			}
		}
		poseStack.popPose();
	}

	/**
	 * ArmorStand 只能提供整条手臂/腿的旋转，肘膝弯曲由 OBJ 分段在客户端补齐。
	 * 左右角度刻意不同，形成稳定的反重力悬浮姿态；召回时则收拢以减少迎风面积。
	 */
	private static FlightPoseFrame cruiseCarrierPose(ArmorCruisePhase phase, float dockingProgress,
			float phasingProgress, RecallApproachTimeline.Sample recallApproach, float deploymentFlight,
			CruiseCombatStage combatStage, float combatStageProgress, ArmorVariant variant) {
		FlightPoseFrame hoverPose = new FlightPoseFrame(
				-3.5F * Mth.DEG_TO_RAD, 0.0F, 4.5F * Mth.DEG_TO_RAD, 0.0F,
				-12.0F * Mth.DEG_TO_RAD, -8.0F * Mth.DEG_TO_RAD, -15.0F * Mth.DEG_TO_RAD,
				-7.0F * Mth.DEG_TO_RAD, 7.0F * Mth.DEG_TO_RAD, 13.0F * Mth.DEG_TO_RAD,
				-22.0F * Mth.DEG_TO_RAD, -4.0F * Mth.DEG_TO_RAD, -6.0F * Mth.DEG_TO_RAD,
				-5.0F * Mth.DEG_TO_RAD, 4.0F * Mth.DEG_TO_RAD, 5.0F * Mth.DEG_TO_RAD,
				32.0F * Mth.DEG_TO_RAD, 24.0F * Mth.DEG_TO_RAD,
				52.0F * Mth.DEG_TO_RAD, 20.0F * Mth.DEG_TO_RAD);
		FlightPoseFrame divePose = new FlightPoseFrame(
				-12.0F * Mth.DEG_TO_RAD, 0.0F, 8.0F * Mth.DEG_TO_RAD, 0.0F,
				24.0F * Mth.DEG_TO_RAD, -3.0F * Mth.DEG_TO_RAD, -6.0F * Mth.DEG_TO_RAD,
				22.0F * Mth.DEG_TO_RAD, 3.0F * Mth.DEG_TO_RAD, 6.0F * Mth.DEG_TO_RAD,
				10.0F * Mth.DEG_TO_RAD, -2.0F * Mth.DEG_TO_RAD, -3.0F * Mth.DEG_TO_RAD,
				14.0F * Mth.DEG_TO_RAD, 2.0F * Mth.DEG_TO_RAD, 3.0F * Mth.DEG_TO_RAD,
				10.0F * Mth.DEG_TO_RAD, 8.0F * Mth.DEG_TO_RAD,
				12.0F * Mth.DEG_TO_RAD, 10.0F * Mth.DEG_TO_RAD);
		FlightPoseFrame brakingPose = new FlightPoseFrame(
				6.0F * Mth.DEG_TO_RAD, 0.0F, -4.0F * Mth.DEG_TO_RAD, 0.0F,
				-20.0F * Mth.DEG_TO_RAD, -9.0F * Mth.DEG_TO_RAD, -18.0F * Mth.DEG_TO_RAD,
				-15.0F * Mth.DEG_TO_RAD, 8.0F * Mth.DEG_TO_RAD, 16.0F * Mth.DEG_TO_RAD,
				-30.0F * Mth.DEG_TO_RAD, -5.0F * Mth.DEG_TO_RAD, -6.0F * Mth.DEG_TO_RAD,
				-18.0F * Mth.DEG_TO_RAD, 5.0F * Mth.DEG_TO_RAD, 6.0F * Mth.DEG_TO_RAD,
				38.0F * Mth.DEG_TO_RAD, 28.0F * Mth.DEG_TO_RAD,
				60.0F * Mth.DEG_TO_RAD, 42.0F * Mth.DEG_TO_RAD);
		hoverPose = carrierStyle(hoverPose, variant, false);
		divePose = carrierStyle(divePose, variant, true);
		brakingPose = carrierStyle(brakingPose, variant, false);
		FlightPoseFrame approachPose = divePose.lerp(brakingPose, recallApproach.brakingProgress())
				.lerp(hoverPose, recallApproach.hoverProgress());
		// 切入召回的短过渡由 RecallApproachProfile.ENTRY_POSE_TICKS 统一计时，直接召回和折跃重入都不会瞬切。
		FlightPoseFrame recallPose = hoverPose.lerp(approachPose, recallApproach.entryProgress());
		FlightPoseFrame phaseFoldPose = new FlightPoseFrame(
				0.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				112.0F * Mth.DEG_TO_RAD, 104.0F * Mth.DEG_TO_RAD,
				96.0F * Mth.DEG_TO_RAD, 88.0F * Mth.DEG_TO_RAD);
		FlightPoseFrame ownerDownPose = new FlightPoseFrame(
				0.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				35.0F * Mth.DEG_TO_RAD, 27.0F * Mth.DEG_TO_RAD,
				18.0F * Mth.DEG_TO_RAD, 24.0F * Mth.DEG_TO_RAD);
		FlightPoseFrame dockingFrameOpenPose = new FlightPoseFrame(
				0.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				42.0F * Mth.DEG_TO_RAD, 38.0F * Mth.DEG_TO_RAD,
				30.0F * Mth.DEG_TO_RAD, 26.0F * Mth.DEG_TO_RAD);
		return switch (phase) {
			case CRUISING -> cruiseCombatPose(hoverPose, combatStage, combatStageProgress);
			case DEPLOYING -> FlightPoseFrame.ZERO.lerp(hoverPose, deploymentFlight);
			case RECALLING -> recallPose;
			case PHASING -> phasingProgress < 0.5F
					? hoverPose.lerp(phaseFoldPose, smoothstep(0.0F, 0.5F, phasingProgress))
					: phaseFoldPose.lerp(hoverPose, smoothstep(0.5F, 1.0F, phasingProgress));
			case OWNER_DOWN -> ownerDownPose;
			case DOCKING -> {
				if (dockingProgress < DockingTimeline.FRAME_START) yield hoverPose;
				if (dockingProgress < DockingTimeline.WRAP_START) {
					yield hoverPose.lerp(dockingFrameOpenPose,
							smoothstep(DockingTimeline.FRAME_START, DockingTimeline.WRAP_START, dockingProgress));
				}
				if (dockingProgress < DockingTimeline.ZONE_LOCK_START) yield dockingFrameOpenPose;
				yield dockingJointClosurePose(dockingProgress);
			}
			case IDLE -> FlightPoseFrame.ZERO;
		};
	}

	private static FlightPoseFrame carrierStyle(FlightPoseFrame pose, ArmorVariant variant,
			boolean highSpeed) {
		if (variant == ArmorVariant.VANGUARD) return pose;
		if (variant == ArmorVariant.SWIFT) {
			return new FlightPoseFrame(
					pose.bodyPitch() - (highSpeed ? 5.0F * Mth.DEG_TO_RAD : 2.0F * Mth.DEG_TO_RAD),
					pose.bodyRoll() * 0.55F, pose.headPitchOffset(), pose.headRollOffset() * 0.55F,
					pose.leftArmPitch(), pose.leftArmYaw() * 0.68F, pose.leftArmRoll() * 0.64F,
					pose.rightArmPitch(), pose.rightArmYaw() * 0.68F, pose.rightArmRoll() * 0.64F,
					pose.leftLegPitch(), pose.leftLegYaw() * 0.58F, pose.leftLegRoll() * 0.58F,
					pose.rightLegPitch(), pose.rightLegYaw() * 0.58F, pose.rightLegRoll() * 0.58F,
					pose.leftElbowBend() * 0.65F, pose.rightElbowBend() * 0.65F,
					pose.leftKneeBend() * 0.62F, pose.rightKneeBend() * 0.62F);
		}
		float brace = highSpeed ? 0.55F : 1.0F;
		return new FlightPoseFrame(
				pose.bodyPitch() * 0.65F, pose.bodyRoll() * 0.40F,
				pose.headPitchOffset(), pose.headRollOffset() * 0.42F,
				pose.leftArmPitch(), pose.leftArmYaw() * 1.25F, pose.leftArmRoll() * 1.30F,
				pose.rightArmPitch(), pose.rightArmYaw() * 1.25F, pose.rightArmRoll() * 1.30F,
				pose.leftLegPitch(), pose.leftLegYaw() * 1.32F, pose.leftLegRoll() * 1.28F,
				pose.rightLegPitch(), pose.rightLegYaw() * 1.32F, pose.rightLegRoll() * 1.28F,
				pose.leftElbowBend() + 12.0F * brace * Mth.DEG_TO_RAD,
				pose.rightElbowBend() + 10.0F * brace * Mth.DEG_TO_RAD,
				pose.leftKneeBend() + 14.0F * brace * Mth.DEG_TO_RAD,
				pose.rightKneeBend() + 12.0F * brace * Mth.DEG_TO_RAD);
	}
	private static FlightPoseFrame cruiseCombatPose(FlightPoseFrame hoverPose,
			CruiseCombatStage stage, float progress) {
		float eased = smoothstep(0.0F, 1.0F, progress);
		return switch (stage) {
			case VANGUARD_CLIMB -> hoverPose.lerp(RAM_CLIMB_POSE, eased);
			case VANGUARD_LOCK -> RAM_CLIMB_POSE.lerp(RAM_LOCK_POSE, eased);
			case VANGUARD_DIVE -> RAM_LOCK_POSE.lerp(RAM_DIVE_POSE, eased);
			case VANGUARD_PULL_UP -> RAM_DIVE_POSE.lerp(RAM_PULL_UP_POSE, eased);
			case VANGUARD_ANALYZE -> RAM_PULL_UP_POSE.lerp(RAM_ANALYZE_POSE, eased);
			case VANGUARD_WRIST -> RAM_ANALYZE_POSE.lerp(RAM_WRIST_POSE, eased);
			case VANGUARD_SLAM_CLIMB -> hoverPose.lerp(SLAM_CLIMB_POSE, eased);
			case VANGUARD_SLAM_HOVER -> SLAM_CLIMB_POSE.lerp(SLAM_HOVER_POSE, eased);
			case VANGUARD_SLAM_DROP -> SLAM_HOVER_POSE.lerp(SLAM_DROP_POSE, eased);
			case VANGUARD_SLAM_IMPACT -> SLAM_DROP_POSE.lerp(SLAM_IMPACT_POSE, eased);
			case VANGUARD_SLAM_RECOVER -> SLAM_IMPACT_POSE.lerp(hoverPose, eased);
			case IDLE, SWIFT_TRACK, SWIFT_VOLLEY, SWIFT_ESCAPE,
					SENTINEL_HOLD, SENTINEL_FIRE, COOLDOWN, INVALID -> hoverPose;
		};
	}

	/**
	 * Ram rotates the whole suit into a low-drag flight line. Ground slam remains nearly upright
	 * and communicates its weight through a deep, asymmetric three-point landing instead.
	 */
	private static void applyCruiseCombatRootTransform(PoseStack poseStack,
			CruiseCombatStage stage, float progress) {
		float eased = smoothstep(0.0F, 1.0F, progress);
		float pitch = switch (stage) {
			case VANGUARD_CLIMB -> Mth.lerp(eased, 0.0F, -30.0F);
			case VANGUARD_LOCK -> Mth.lerp(eased, -30.0F, 24.0F);
			case VANGUARD_DIVE -> Mth.lerp(eased, 24.0F, 76.0F);
			case VANGUARD_PULL_UP -> Mth.lerp(eased, 76.0F, -26.0F);
			case VANGUARD_ANALYZE -> Mth.lerp(eased, -26.0F, 0.0F);
			case VANGUARD_WRIST -> 0.0F;
			case VANGUARD_SLAM_CLIMB -> Mth.lerp(eased, 0.0F, -9.0F);
			case VANGUARD_SLAM_HOVER -> Mth.lerp(eased, -9.0F, 2.0F);
			case VANGUARD_SLAM_DROP -> Mth.lerp(eased, 2.0F, 8.0F);
			case VANGUARD_SLAM_IMPACT -> Mth.lerp(eased, 8.0F, 1.0F);
			case VANGUARD_SLAM_RECOVER -> Mth.lerp(eased, 1.0F, 0.0F);
			case IDLE, SWIFT_TRACK, SWIFT_VOLLEY, SWIFT_ESCAPE,
					SENTINEL_HOLD, SENTINEL_FIRE, COOLDOWN, INVALID -> 0.0F;
		};
		applyRootPitch(poseStack, pitch);
		if (stage == CruiseCombatStage.VANGUARD_SLAM_IMPACT) {
			float compression = Mth.sin(eased * Mth.PI);
			poseStack.translate(0.0F, 0.12F * compression, 0.0F);
		} else if (stage == CruiseCombatStage.VANGUARD_SLAM_RECOVER) {
			float rebound = Mth.sin(eased * Mth.PI);
			poseStack.translate(0.0F, -0.08F * rebound, 0.0F);
		}
	}

	private static FlightPoseFrame combatPose(
			float bodyPitch, float bodyRoll, float headPitch, float headRoll,
			float leftArmPitch, float leftArmYaw, float leftArmRoll,
			float rightArmPitch, float rightArmYaw, float rightArmRoll,
			float leftLegPitch, float leftLegYaw, float leftLegRoll,
			float rightLegPitch, float rightLegYaw, float rightLegRoll,
			float leftElbow, float rightElbow, float leftKnee, float rightKnee) {
		return new FlightPoseFrame(
				bodyPitch * Mth.DEG_TO_RAD, bodyRoll * Mth.DEG_TO_RAD,
				headPitch * Mth.DEG_TO_RAD, headRoll * Mth.DEG_TO_RAD,
				leftArmPitch * Mth.DEG_TO_RAD, leftArmYaw * Mth.DEG_TO_RAD,
				leftArmRoll * Mth.DEG_TO_RAD,
				rightArmPitch * Mth.DEG_TO_RAD, rightArmYaw * Mth.DEG_TO_RAD,
				rightArmRoll * Mth.DEG_TO_RAD,
				leftLegPitch * Mth.DEG_TO_RAD, leftLegYaw * Mth.DEG_TO_RAD,
				leftLegRoll * Mth.DEG_TO_RAD,
				rightLegPitch * Mth.DEG_TO_RAD, rightLegYaw * Mth.DEG_TO_RAD,
				rightLegRoll * Mth.DEG_TO_RAD,
				leftElbow * Mth.DEG_TO_RAD, rightElbow * Mth.DEG_TO_RAD,
				leftKnee * Mth.DEG_TO_RAD, rightKnee * Mth.DEG_TO_RAD);
	}

	private static final FlightPoseFrame RAM_CLIMB_POSE = combatPose(
			-8, 0, -4, 0,
			10, -3, -7, 14, 4, 8,
			-4, -2, -5, 12, 3, 6,
			12, 8, 26, 12);
	private static final FlightPoseFrame RAM_LOCK_POSE = combatPose(
			2, 0, -2, 0,
			7, -2, -3, 7, 2, 3,
			5, -1, -2, 8, 1, 2,
			5, 4, 9, 6);
	private static final FlightPoseFrame RAM_DIVE_POSE = combatPose(
			8, 0, 4, 0,
			20, 0, -2, 20, 0, 2,
			9, 0, -2, 6, 0, 2,
			2, 2, 4, 3);
	private static final FlightPoseFrame RAM_PULL_UP_POSE = combatPose(
			-10, 0, -5, 0,
			-24, -8, -18, -18, 8, 18,
			-7, -3, -5, 13, 3, 6,
			28, 20, 32, 15);
	private static final FlightPoseFrame RAM_ANALYZE_POSE = combatPose(
			1, -3, -3, 2,
			-26, -12, -15, -10, 9, 17,
			-18, -5, -7, 5, 4, 7,
			38, 21, 48, 18);
	private static final FlightPoseFrame RAM_WRIST_POSE = combatPose(
			2, 0, -2, 0,
			-18, -8, -12, -72, 5, 8,
			-6, -3, -5, 6, 3, 6,
			30, 12, 28, 14);

	private static final FlightPoseFrame SLAM_CLIMB_POSE = combatPose(
			-4, 0, -4, 0,
			-20, -8, -20, -16, 9, 20,
			-20, -4, -8, 4, 4, 8,
			42, 34, 54, 28);
	private static final FlightPoseFrame SLAM_HOVER_POSE = combatPose(
			7, 0, 5, 0,
			-62, -15, -28, -56, 14, 27,
			-46, -8, -12, -34, 8, 12,
			78, 68, 104, 86);
	private static final FlightPoseFrame SLAM_DROP_POSE = combatPose(
			10, 0, 7, 0,
			-34, -8, -12, -28, 9, 13,
			-52, -6, -10, -36, 6, 10,
			58, 48, 112, 92);
	private static final FlightPoseFrame SLAM_IMPACT_POSE = combatPose(
			18, -7, 8, 5,
			-78, -18, -22, -24, 15, 30,
			-82, -12, -18, 18, 8, 13,
			96, 52, 124, 48);

	/** 绕整甲髋部统一俯仰；该变换包住四个装备槽的所有 OBJ 分组。 */
	private static void applyRecallRootTransform(PoseStack poseStack,
			RecallApproachTimeline.Sample approach) {
		applyRootPitch(poseStack, approach.rootPitchDegrees());
	}

	private static void applyRootPitch(PoseStack poseStack, float pitch) {
		if (Math.abs(pitch) <= 0.001F) return;
		poseStack.translate(0.0F, 0.75F, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
		poseStack.translate(0.0F, -0.75F, 0.0F);
	}

	/** 巡航脱甲在交接后沿抬升弧线整套弹出，避免从玩家碰撞箱中心直线穿到巡航位。 */
	private static void applyDeploymentRootTransform(PoseStack poseStack, LivingEntity carrier,
			float travel, float partialTick) {
		Vec3 origin = CruiseDeploymentClient.handoffOrigin(carrier, partialTick);
		if (origin == null) return;
		float t = Mth.clamp(travel, 0.0F, 1.0F);
		Vec3 target = carrier.getPosition(partialTick);
		Vec3 direction = target.subtract(origin);
		Vec3 horizontal = new Vec3(direction.x, 0.0D, direction.z);
		Vec3 side;
		if (horizontal.lengthSqr() > 1.0E-6D) {
			side = new Vec3(horizontal.z, 0.0D, -horizontal.x).normalize();
		} else {
			side = new Vec3(1.0D, 0.0D, 0.0D);
		}
		Vec3 control = origin.add(direction.scale(0.30D))
				.add(side.scale(0.28D)).add(0.0D, 0.88D, 0.0D);
		Vec3 desired = quadraticBezier(origin, control, target, t);
		translateWorldDelta(poseStack, desired.subtract(target));
	}

	/** DOCKING 的前 6 tick 直接完成弧线贴近；展开的甲片始终绕在体表外侧。 */
	private static void applyDockingRootTransform(PoseStack poseStack, LivingEntity carrier,
			float progress, float partialTick) {
		Player owner = ArmorCruiseClient.ownerOfCarrier(carrier);
		if (owner == null) return;
		float normalized = Mth.clamp((progress - DockingTimeline.FRAME_START)
				/ (DockingTimeline.ZONE_LOCK_START - DockingTimeline.FRAME_START), 0.0F, 1.0F);
		float approach = 1.0F - (float) Math.pow(1.0F - normalized, 2.45D);
		Vec3 current = carrier.getPosition(partialTick);
		Vec3 target = owner.getPosition(partialTick);
		double yaw = Math.toRadians(owner.getYRot());
		Vec3 right = new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw));
		double arc = Math.sin(Math.PI * approach);
		Vec3 desired = current.lerp(target, approach)
				.add(right.scale(arc * 0.24D)).add(0.0D, arc * 0.38D, 0.0D);
		translateWorldDelta(poseStack, desired.subtract(current));
	}

	private static Vec3 quadraticBezier(Vec3 from, Vec3 control, Vec3 to, float progress) {
		float inverse = 1.0F - progress;
		return from.scale(inverse * inverse)
				.add(control.scale(2.0F * inverse * progress))
				.add(to.scale(progress * progress));
	}

	private static void applyPlatformPartTransform(PoseStack poseStack,
			PlatformRecallClient.Sample recall, GroupBinding binding, ArmorAssemblyPart assemblyPart) {
		if (!recall.active() || recall.assembled()) return;
		PlatformRecallClient.PartTrajectory trajectory =
				recall.partTrajectory(binding.slot(), assemblyPart);
		translateWorldDelta(poseStack, trajectory.offset());
		float recallScale = recall.partVisualScale(binding.slot(), assemblyPart);
		poseStack.scale(recallScale, recallScale, recallScale);
	}

	private static void translateWorldDelta(PoseStack poseStack, Vec3 delta) {
		if (delta.lengthSqr() <= 1.0E-8D) return;
		Matrix3f inverse = new Matrix3f(poseStack.last().pose()).invert();
		Vector3f local = new Vector3f((float) delta.x, (float) delta.y, (float) delta.z);
		inverse.transform(local);
		poseStack.translate(local.x(), local.y(), local.z());
	}

	/** 肘、膝必须在对应 OBJ 分区交给 PlayerModel 前已经回到玩家局部零位。 */
	private static FlightPoseFrame dockingJointClosurePose(float progress) {
		float elbowRemaining = 1.0F - smoothstep(DockingTimeline.ZONE_LOCK_START,
				DockingTimeline.ARMS_TRANSFER, progress);
		// FEET 与腿甲共用 LEFT/RIGHT_LEG 基骨和膝轴；靴在视觉 tick 10 交接，膝必须先同步归零。
		float kneeRemaining = 1.0F - smoothstep(DockingTimeline.ZONE_LOCK_START,
				DockingTimeline.BOOTS_TRANSFER, progress);
		return new FlightPoseFrame(
				0.0F, 0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				0.0F, 0.0F, 0.0F,
				42.0F * Mth.DEG_TO_RAD * elbowRemaining,
				38.0F * Mth.DEG_TO_RAD * elbowRemaining,
				30.0F * Mth.DEG_TO_RAD * kneeRemaining,
				26.0F * Mth.DEG_TO_RAD * kneeRemaining);
	}

	@Override
	public boolean shouldRenderDefaultHeadItem(LivingEntity entity, ItemStack stack) {
		return false;
	}

	/**
	 * 在原版第一人称手臂绘制完成后，仅补绘当前一侧的胸甲手臂分组。
	 * <p>
	 * 这里直接复用 {@link PlayerRenderer} 已经设置好的手臂 ModelPart，因此挖掘、攻击、使用物品等
	 * 第一人称变换仍完全由原版控制；不会额外绘制躯干、另一只手，也不会修改第三人称模型姿势。
	 */
	public static void renderFirstPersonArm(PoseStack poseStack, MultiBufferSource buffers,
			int packedLight, AbstractClientPlayer player,
			PlayerModel<AbstractClientPlayer> model, boolean leftArm) {
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
		PlatformRecallClient.Sample platformRecall = PlatformRecallClient.sample(player, partialTick);
		boolean platformAssembled = platformRecall.active() && platformRecall.assembled();
		if (platformRecall.active() && !platformRecall.firstPersonChestReady()) {
			return;
		}

		CruiseDeploymentTimeline.Sample deploymentSample =
				CruiseDeploymentClient.playerSample(player, partialTick);
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		ArmorVariant armorVariant = resolveArmorVariant(player, EquipmentSlot.CHEST);
		if (!chest.is(ModItems.FLIGHT_CHESTPLATE) && deploymentSample == null) {
			return;
		}
		ArmorObjMesh mesh = FlightArmorObjResources.mesh(armorVariant);
		if (mesh.groups().isEmpty()) {
			return;
		}

		Anchor requestedAnchor = leftArm ? Anchor.LEFT_ARM : Anchor.RIGHT_ARM;
		float flightProgress = flightProgress(player, partialTick);
		float diveProgress = FlightArmorClient.diveVisualProgress(player, partialTick);
		float boostProgress = FlightArmorClient.boostVisualProgress(player, partialTick);
		float speedProgress = flightSpeedProgress(player, partialTick);
		float ledSpeedProgress = flightProgress > 0.01F ? speedProgress : 0.0F;
		float animationTime = player.tickCount + partialTick;
		boolean chestAnimating = ArmorAssemblyClient.isAnimating(player)
				&& ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.CHEST) && !platformRecall.active();
		float deploymentOpenProgress = platformAssembled ? platformRecall.wrapProgress()
				: deploymentSample == null ? 0.0F
						: Mth.lerp(deploymentSample.open(), DockingTimeline.FRAME_START, DockingTimeline.WRAP_START);

		VertexConsumer base = ItemRenderer.getArmorFoilBuffer(buffers,
				RenderType.armorCutoutNoCull(BASE_TEXTURE), chest.hasFoil());
		for (ArmorObjMesh.Group group : mesh.groups()) {
			GroupBinding binding = GroupBinding.from(group.name());
			if (binding == null || binding.slot() != EquipmentSlot.CHEST
					|| binding.anchor() != requestedAnchor || isLed(group.name())) {
				continue;
			}
			ArmorAssemblyPart assemblyPart = assemblyPartFor(group.name(), binding);
			float assemblyProgress = chestAnimating
					? ArmorAssemblyClient.partProgress(player, assemblyPart, partialTick) : 1.0F;
			if (chestAnimating && assemblyProgress <= 0.0001F) continue;
			renderGroup(mesh, group, binding, model, poseStack, base, player, packedLight,
					variantBaseColor(armorVariant, group.name(), colorFor(group.name(), 1.0F)), flightProgress, speedProgress,
					diveProgress, animationTime, FlightPoseFrame.ZERO,
					deploymentOpenProgress, assemblyPart, assemblyProgress, chestAnimating);
		}

		VertexConsumer emissive = buffers.getBuffer(
				RenderType.entityTranslucentEmissive(EMISSIVE_TEXTURE));
		ArmorObjMesh.Color ledColor = ledColor(ledSpeedProgress);
		for (ArmorObjMesh.Group group : mesh.groups()) {
			GroupBinding binding = GroupBinding.from(group.name());
			if (binding == null || binding.slot() != EquipmentSlot.CHEST
					|| binding.anchor() != requestedAnchor || !isLed(group.name())) {
				continue;
			}
			ArmorAssemblyPart assemblyPart = assemblyPartFor(group.name(), binding);
			float assemblyProgress = chestAnimating
					? ArmorAssemblyClient.partProgress(player, assemblyPart, partialTick) : 1.0F;
			if (chestAnimating && assemblyProgress < 0.78F) continue;
			ArmorObjMesh.Color deploymentLedColor = deploymentSample == null ? ledColor
					: dockingLedColor(deploymentOpenProgress, group.name());
			deploymentLedColor = variantLedColor(
					player, armorVariant, group.name(), deploymentLedColor, ledSpeedProgress);
			ArmorObjMesh.Color armLedColor = weaponLedColor(player, group.name(), partialTick, deploymentLedColor);
			renderGroup(mesh, group, binding, model, poseStack, emissive, player,
					LightTexture.FULL_BRIGHT, armLedColor, flightProgress, speedProgress,
					diveProgress, animationTime, FlightPoseFrame.ZERO,
					deploymentOpenProgress, assemblyPart, assemblyProgress, chestAnimating);
		}
	}

	private static void renderGroup(ArmorObjMesh mesh, ArmorObjMesh.Group group,
			GroupBinding binding, HumanoidModel<?> model, PoseStack poseStack,
			VertexConsumer consumer, LivingEntity entity, int packedLight, ArmorObjMesh.Color color,
			float flightProgress, float speedProgress, float diveProgress,
			float animationTime, FlightPoseFrame flightPose, float dockingProgress,
			ArmorAssemblyPart assemblyPart,
			float assemblyPartProgress, boolean assembling) {
		ArmorVariant variant = resolveArmorVariant(entity, binding.slot());
		ModelPart part = binding.part(model);
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		applyJointTransform(poseStack, binding, flightPose, entity);
		applyDockingExoskeletonTransform(poseStack, group, binding, dockingProgress);
		applyAssemblyTransform(poseStack, group, assemblyPart, assemblyPartProgress, assembling);
		applyDynamicTransform(poseStack, group, variant, entity,
				flightProgress, speedProgress, diveProgress, animationTime);
		boolean renderPhysicalGeometry = applySentinelDronePodTransform(
				poseStack, group, variant, entity, animationTime);
		applyCombatTransform(poseStack, group, entity, animationTime);
		if (renderPhysicalGeometry) {
			mesh.render(group, poseStack.last(), consumer, packedLight, color);
		}
		poseStack.popPose();
	}

	/**
	 * 整甲展开/包覆共用的机械净空变换。这里的位移单位位于 1/16 缩放之后，因此必须使用
	 * “模型像素”而不是零点几的世界格；前甲向 -Z、后甲向 +Z、左右肢体向各自外侧展开，
	 * 闭合过程中只从体表外侧接近最终位置，不再横穿玩家躯干。
	 */
	private static void applyDockingExoskeletonTransform(PoseStack poseStack, ArmorObjMesh.Group group,
			GroupBinding binding, float progress) {
		if (progress <= DockingTimeline.FRAME_START || progress >= 0.9999F) return;
		float frameUnlock = smoothstep(DockingTimeline.FRAME_START, DockingTimeline.WRAP_START, progress);
		float zoneLock = dockingZoneLockProgress(group.name(), binding, progress);
		float open = frameUnlock * (1.0F - zoneLock);
		if (open <= 0.0001F) return;

		String name = group.name();
		boolean coaxial = group.coaxialShell();
		boolean rear = group.rearGroup() || isRearArmorGroup(name);
		float side = armorSide(name);
		float clearanceArc = Mth.sin(Mth.clamp(open, 0.0F, 1.0F) * Mth.PI);
		if (coaxial) {
			// 360° 闭合环套/底衬不可向单一 -Z/+Z 轴平移（否则后壁会直接横穿玩家躯干或四肢），
			// 而是沿肢体外侧径向展开并伴随环形扩张净空，闭合时贴着体表无穿模收束。
			switch (binding.anchor()) {
				case HEAD -> {
					poseStack.translate(0.0F, -2.6F * open, 0.0F);
					scaleAround(poseStack, group, 1.0F + 0.16F * open,
							1.0F + 0.05F * open, 1.0F + 0.16F * open);
				}
				case BODY -> {
					poseStack.translate(0.0F, -0.8F * open, 0.0F);
					scaleAround(poseStack, group, 1.0F + 0.18F * open,
							1.0F + 0.04F * open, 1.0F + 0.20F * open);
				}
				case LEFT_ARM -> {
					poseStack.translate(7.4F * open, -1.0F * open, 0.0F);
					rotateAround(poseStack, group, 0.0F, 0.0F, -14.0F * open);
					scaleAround(poseStack, group, 1.0F + 0.12F * open,
							1.0F + 0.02F * open, 1.0F + 0.12F * open);
				}
				case RIGHT_ARM -> {
					poseStack.translate(-7.4F * open, -1.0F * open, 0.0F);
					rotateAround(poseStack, group, 0.0F, 0.0F, 14.0F * open);
					scaleAround(poseStack, group, 1.0F + 0.12F * open,
							1.0F + 0.02F * open, 1.0F + 0.12F * open);
				}
				case LEFT_LEG -> {
					poseStack.translate(5.0F * open, -0.6F * open, 0.0F);
					rotateAround(poseStack, group, 0.0F, 0.0F, -10.0F * open);
					scaleAround(poseStack, group, 1.0F + 0.12F * open,
							1.0F + 0.02F * open, 1.0F + 0.12F * open);
				}
				case RIGHT_LEG -> {
					poseStack.translate(-5.0F * open, -0.6F * open, 0.0F);
					rotateAround(poseStack, group, 0.0F, 0.0F, 10.0F * open);
					scaleAround(poseStack, group, 1.0F + 0.12F * open,
							1.0F + 0.02F * open, 1.0F + 0.12F * open);
				}
			}
			return;
		}

		switch (binding.anchor()) {
			case HEAD -> {
				float z = (rear ? 5.8F : -6.2F) * open + (rear ? 0.9F : -0.9F) * clearanceArc;
				float y = (name.contains("crown") ? -3.4F : -1.0F) * open;
				poseStack.translate(side * 2.8F * open, y, z);
				rotateAround(poseStack, group, (rear ? -14.0F : 16.0F) * open,
						-side * 10.0F * open, side * 9.0F * open);
			}
			case BODY -> {
				float z = (rear ? 6.2F : -5.6F) * open + (rear ? 1.1F : -1.1F) * clearanceArc;
				poseStack.translate(side * 4.4F * open, -1.0F * open, z);
				rotateAround(poseStack, group, (rear ? -8.0F : 10.0F) * open,
						-side * 12.0F * open, -side * 10.0F * open);
			}
			case LEFT_ARM -> {
				float z = (rear ? 3.4F : -3.2F) * open + (rear ? 0.6F : -0.6F) * clearanceArc;
				poseStack.translate(8.4F * open + 0.8F * clearanceArc, -1.2F * open, z);
				rotateAround(poseStack, group, 2.5F * open, -14.0F * open, -20.0F * open);
			}
			case RIGHT_ARM -> {
				float z = (rear ? 3.4F : -3.2F) * open + (rear ? 0.6F : -0.6F) * clearanceArc;
				poseStack.translate(-8.4F * open - 0.8F * clearanceArc, -1.2F * open, z);
				rotateAround(poseStack, group, 2.5F * open, 14.0F * open, 20.0F * open);
			}
			case LEFT_LEG -> {
				float z = (rear ? 3.0F : -2.8F) * open + (rear ? 0.5F : -0.5F) * clearanceArc;
				poseStack.translate(5.8F * open + 0.6F * clearanceArc, -0.6F * open, z);
				rotateAround(poseStack, group, 2.0F * open, -10.0F * open, -12.0F * open);
			}
			case RIGHT_LEG -> {
				float z = (rear ? 3.0F : -2.8F) * open + (rear ? 0.5F : -0.5F) * clearanceArc;
				poseStack.translate(-5.8F * open - 0.6F * clearanceArc, -0.6F * open, z);
				rotateAround(poseStack, group, 2.0F * open, 10.0F * open, 12.0F * open);
			}
		}
		scaleAround(poseStack, group, 1.0F + 0.045F * open,
				1.0F + 0.02F * open, 1.0F + 0.045F * open);
	}

	private static boolean isRearArmorGroup(String name) {
		return containsAny(name, "rear", "backplate", "spine", "heel", "vane_",
				"flight_fin", "cover_outer", "cover_inner", "hand_shell",
				"wrist_lock", "emitter_shutter", "emitter_track");
	}

	private static float armorSide(String name) {
		if (name.contains("left") || name.contains("_l_")) return 1.0F;
		if (name.contains("right") || name.contains("_r_")) return -1.0F;
		return 0.0F;
	}

	private static boolean containsAny(String value, String... fragments) {
		for (String fragment : fragments) {
			if (value.contains(fragment)) return true;
		}
		return false;
	}

	private static float dockingZoneLockProgress(String name, GroupBinding binding, float progress) {
		if (name.contains("core_reactor") || name.contains("core_housing") || name.contains("core_cradle")) {
			return smoothstep(DockingTimeline.CORE_START, DockingTimeline.CORE_TRANSFER, progress);
		}
		return switch (binding.slot()) {
			case FEET -> smoothstep(DockingTimeline.ZONE_LOCK_START, DockingTimeline.BOOTS_TRANSFER, progress);
			case LEGS -> binding.segment() == LimbSegment.SHIN
					? smoothstep(DockingTimeline.BOOTS_TRANSFER, DockingTimeline.SHINS_TRANSFER, progress)
					: smoothstep(DockingTimeline.LEGS_LOCK_START, DockingTimeline.LEGS_TRANSFER, progress);
			case CHEST -> binding.anchor() == Anchor.BODY
					? smoothstep(DockingTimeline.ARMS_TRANSFER, DockingTimeline.CHEST_TRANSFER, progress)
					: smoothstep(DockingTimeline.CHEST_LOCK_START, DockingTimeline.ARMS_TRANSFER, progress);
			case HEAD -> smoothstep(DockingTimeline.CHEST_TRANSFER, DockingTimeline.HELMET_TRANSFER, progress);
			default -> smoothstep(DockingTimeline.ZONE_LOCK_START, DockingTimeline.CORE_START, progress);
		};
	}

	/** 同一 OBJ 分区只由载体或玩家覆盖层中的一个渲染，阈值两侧不会出现双甲。 */
	private static boolean dockingRegionTransferredToOwner(String name, GroupBinding binding, float progress) {
		float threshold;
		if (name.contains("core_reactor") || name.contains("core_housing") || name.contains("core_cradle")) {
			threshold = DockingTimeline.CORE_TRANSFER;
		} else {
			threshold = switch (binding.slot()) {
				case FEET -> DockingTimeline.BOOTS_TRANSFER;
				case LEGS -> binding.segment() == LimbSegment.SHIN
						? DockingTimeline.SHINS_TRANSFER : DockingTimeline.LEGS_TRANSFER;
				case CHEST -> binding.anchor() == Anchor.BODY
						? DockingTimeline.CHEST_TRANSFER : DockingTimeline.ARMS_TRANSFER;
				case HEAD -> DockingTimeline.HELMET_TRANSFER;
				default -> 1.01F;
			};
		}
		return progress >= threshold;
	}

	/**
	 * 将逻辑部件从角色局部空间中的召回点沿二次贝塞尔曲线吸附到最终装甲位置。
	 * 此变换必须位于肘膝关节之后、飞行展开之前，才能让同一部件既跟随接受姿势又保持独立飞入轨迹。
	 */
	private static void applyAssemblyTransform(PoseStack poseStack, ArmorObjMesh.Group group,
			ArmorAssemblyPart part, float rawProgress, boolean assembling) {
		if (!assembling || part == null || rawProgress >= 0.9999F) {
			return;
		}

		// 高初速磁吸：前半段快速跨越空间，末段减速并留出可读的机械锁定。
		float progress = 1.0F - (float) Math.pow(1.0F - rawProgress, 2.8D);
		float inverse = 1.0F - progress;
		// P2 是最终装甲原点 (0, 0, 0)，因此公式的末项可以省略。
		float x = inverse * inverse * part.p0X() + 2.0F * inverse * progress * part.p1X();
		float y = inverse * inverse * part.p0Y() + 2.0F * inverse * progress * part.p1Y();
		float z = inverse * inverse * part.p0Z() + 2.0F * inverse * progress * part.p1Z();
		// 接近体表阶段（0.55 -> 0.96）施加外法向净空弧，避免闭合环套斜切肢体。
		float approachClearance = Mth.sin(smoothstep(0.42F, 0.96F, rawProgress) * Mth.PI);
		float normalZ = group.rearGroup() ? 0.85F : -0.85F;
		poseStack.translate(x, y, z + (group.coaxialShell() ? 0.0F : normalZ * approachClearance));

		float rotationRemaining = 1.0F - smoothstep(0.06F, 0.84F, rawProgress);
		if (group.coaxialShell()) {
			rotationRemaining *= 0.45F;
		}
		rotateAroundPoint(poseStack, part.pivotX(), part.pivotY(), part.pivotZ(),
				part.rotX() * rotationRemaining,
				part.rotY() * rotationRemaining,
				part.rotZ() * rotationRemaining);
		// 闭合环套在接近阶段保持轻微径向扩张，最后 28% 轻微扩张后回弹，形成“吸附—咬合”冲击感。
		float lockPulse = (float) Math.sin(Math.PI * smoothstep(0.72F, 1.0F, rawProgress)) * 0.055F;
		float shellClearance = group.coaxialShell() ? 0.11F * (1.0F - smoothstep(0.55F, 0.96F, rawProgress)) : 0.0F;
		scaleAround(poseStack, group, 1.0F + lockPulse + shellClearance,
				1.0F + lockPulse * 0.55F, 1.0F + lockPulse + shellClearance);
	}

	/** 未被精确命名规则覆盖的旧分组仍按身体锚点归入最近的逻辑部件，禁止组装开头残留悬空甲片。 */
	private static ArmorAssemblyPart assemblyPartFor(ArmorObjMesh.Group group, GroupBinding binding) {
		ArmorAssemblyPart classified = group.classifiedAssemblyPart();
		if (classified != null) return classified;
		return assemblyPartFor(group.name(), binding);
	}

	private static ArmorAssemblyPart assemblyPartFor(String groupName, GroupBinding binding) {
		ArmorAssemblyPart classified = ArmorAssemblyPart.fromGroupName(groupName);
		if (classified != null) return classified;
		return switch (binding.anchor()) {
			case HEAD -> ArmorAssemblyPart.HELMET_SHELL;
			case BODY -> ArmorAssemblyPart.TORSO;
			case LEFT_ARM -> binding.segment() == LimbSegment.FOREARM
					? ArmorAssemblyPart.LEFT_FOREARM : ArmorAssemblyPart.LEFT_UPPER_ARM;
			case RIGHT_ARM -> binding.segment() == LimbSegment.FOREARM
					? ArmorAssemblyPart.RIGHT_FOREARM : ArmorAssemblyPart.RIGHT_UPPER_ARM;
			case LEFT_LEG -> binding.slot() == EquipmentSlot.FEET
					? ArmorAssemblyPart.LEFT_BOOT : ArmorAssemblyPart.LEFT_LEG;
			case RIGHT_LEG -> binding.slot() == EquipmentSlot.FEET
					? ArmorAssemblyPart.RIGHT_BOOT : ArmorAssemblyPart.RIGHT_LEG;
		};
	}

	private static void applyJointTransform(PoseStack poseStack, GroupBinding binding,
			FlightPoseFrame pose, LivingEntity entity) {
		CruiseCombatStage combatStage = CruiseCombatClient.stageOfCarrier(entity);
		if (combatStage.isRam() || combatStage.isGroundSlam()
				|| combatStage == CruiseCombatStage.VANGUARD_WRIST) {
			applyCruiseCombatAnchorOffset(poseStack, binding.anchor(), pose);
		}
		if (binding.segment() == LimbSegment.ELBOW) {
			boolean left = binding.anchor() == Anchor.LEFT_ARM;
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(
					(left ? pose.leftElbowBend() : pose.rightElbowBend()) * 0.5F));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		} else if (binding.segment() == LimbSegment.FOREARM) {
			boolean left = binding.anchor() == Anchor.LEFT_ARM;
			// PAL 的人物手臂在标准 12 像素手臂中点弯曲；盔甲必须使用相同肘轴。
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(left ? pose.leftElbowBend() : pose.rightElbowBend()));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		} else if (binding.segment() == LimbSegment.SHIN) {
			boolean left = binding.anchor() == Anchor.LEFT_LEG;
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(left ? pose.leftKneeBend() : pose.rightKneeBend()));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		} else if (binding.segment() == LimbSegment.KNEE) {
			boolean left = binding.anchor() == Anchor.LEFT_LEG;
			float bend = left ? pose.leftKneeBend() : pose.rightKneeBend();
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(bend * 0.5F));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		}
	}

	private static void applyDynamicTransform(PoseStack poseStack, ArmorObjMesh.Group group,
			ArmorVariant variant, LivingEntity entity, float progress, float speedProgress,
			float diveProgress, float animationTime) {
		// Sentinel configuration is a persistent suit state, not a synonym for "currently flying".
		// Apply its mechanical reconfiguration before the ordinary flight-progress early return so
		// the two silhouettes remain visible while standing, hovering and being inspected.
		applySentinelConfigurationTransform(
				poseStack, group, variant, entity, animationTime);
		if (progress <= 0.0001F) {
			return;
		}
		String name = group.name();
		float deployed = progress * progress * (3.0F - 2.0F * progress);
		float early = staged(progress, 0.0F, 0.48F);
		float middle = staged(progress, 0.16F, 0.76F);
		float late = staged(progress, 0.34F, 1.0F);

		if (name.contains("core_cradle")) {
			poseStack.translate(0.0F, -0.02F * early, -0.06F * early);
		} else if (name.contains("core_housing")) {
			poseStack.translate(0.0F, -0.04F * early, -0.14F * early);
			scaleAroundPoint(poseStack, 0.0F, 3.65F, -3.10F,
					1.0F + 0.04F * early, 1.0F + 0.04F * early, 1.0F + 0.02F * early);
		} else if (name.contains("core_reactor")) {
			float pulseAmplitude = switch (variant) {
				case VANGUARD -> 0.020F;
				case SWIFT -> 0.030F;
				case SENTINEL -> 0.015F;
			};
			float pulseFrequency = switch (variant) {
				case VANGUARD -> 0.32F;
				case SWIFT -> 0.54F;
				case SENTINEL -> 0.22F;
			};
			float pulse = 1.0F + early * (0.020F + pulseAmplitude * speedProgress
					* Mth.sin(animationTime * (pulseFrequency + speedProgress * 0.45F)));
			poseStack.translate(0.0F, -0.04F * early,
					(-0.18F - 0.06F * speedProgress) * early);
			scaleAroundPoint(poseStack, 0.0F, 3.65F, -3.10F,
					pulse + 0.04F * early, pulse + 0.04F * early, 1.0F + 0.03F * early);
		}

		// 阶段一：高达头部天线微扬、双目聚焦与护颊散热开裂（Unicorn / Nu Psycho-Frame Head Unlock）
		if (name.contains("visor_lens")) {
			poseStack.translate(0.0F, -0.06F * early, -0.14F * early);
		} else if (name.contains("visor_upper_frame") || name.contains("visor_sentinel_upper_lock")) {
			poseStack.translate(0.0F, -0.14F * early, -0.12F * early);
			rotateAround(poseStack, group, -3.5F * early, 0.0F, 0.0F);
		}

		if (name.contains("crown_crest") || name.contains("head_brow")
				|| name.contains("brow_shield") || name.contains("crown_bastion")) {
			poseStack.translate(0.0F, -0.28F * middle, -0.16F * middle);
			rotateAround(poseStack, group, -4.5F * middle, 0.0F, 0.0F);
		}

		if (name.contains("head_cheek_left") || name.contains("head_cheek_right")
				|| name.contains("jaw_guard")) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			poseStack.translate(direction * 0.34F * middle, 0.04F * middle, -0.16F * middle);
			rotateAround(poseStack, group, 0.0F, direction * -8.5F * middle, direction * -4.0F * middle);
		}

		if (name.contains("head_rear_left") || name.contains("head_rear_right")
				|| name.contains("head_sentinel_rear_")) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			float finExtra = name.contains("_fin_") ? switch (variant) {
				case VANGUARD -> 0.36F;
				case SWIFT -> 0.78F;
				case SENTINEL -> 0.24F;
			} : 0.0F;
			poseStack.translate(direction * (0.28F + finExtra * 0.28F) * late,
					-0.12F * late, (0.62F + finExtra) * late);
			rotateAround(poseStack, group, 3.0F * late,
					direction * (8.5F + finExtra * 14.0F) * late, direction * 4.0F * late);
		}

		// 阶段二：胸部排热百叶窗开启、胸甲外扩露出精神感应框架、前裙甲微抬让出提膝空间
		if (name.contains("body_chest_upper") || name.contains("chest_crown")) {
			poseStack.translate(0.0F, -0.16F * early, -0.22F * early);
			rotateAround(poseStack, group, -9.0F * early, 0.0F, 0.0F);
		} else if (name.equals("body_chest_titanium") || name.equals("body_sentinel_chest_bastion_titanium")) {
			poseStack.translate(0.0F, -0.18F * middle, -0.20F * middle);
			scaleAroundPoint(poseStack, 0.0F, 2.6F, -2.8F,
					1.0F + 0.045F * middle, 1.0F + 0.025F * middle, 1.0F);
		} else if (name.contains("body_chest_trace_led") || name.contains("body_sentinel_segment_trace_led")) {
			poseStack.translate(0.0F, -0.12F * middle, -0.26F * middle);
		} else if (name.equals("body_waist_titanium") || name.equals("body_sentinel_waist_apron_titanium")
				|| name.contains("waist_buckle")) {
			poseStack.translate(0.0F, -0.12F * middle, -0.20F * middle);
			rotateAroundPoint(poseStack, 0.0F, 9.2F, -2.6F, -7.5F * middle, 0.0F, 0.0F);
		}

		// 阶段二：RG 高达肩甲抬升外展（露出肩部下沿推进喷口）与前臂稳定翼滑移
		if (name.contains("upper_pauldron") || name.contains("upper_bastion")
				|| name.contains("upper_tower")) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			float sentinel = variant == ArmorVariant.SENTINEL ? 1.0F : 0.0F;
			float retract = 1.0F - diveProgress * Mth.lerp(sentinel, 0.65F, 0.28F);
			float shoulderTravel = Mth.lerp(sentinel, 0.64F, 0.92F);
			poseStack.translate(direction * shoulderTravel * middle * retract,
					Mth.lerp(sentinel, -0.42F, -0.58F) * middle * retract,
					Mth.lerp(sentinel, 0.14F, 0.24F) * middle * retract);
			rotateAround(poseStack, group, -3.0F * middle * retract,
					direction * 6.0F * middle * retract,
					direction * Mth.lerp(sentinel, -12.0F, -18.0F) * middle * retract);
		} else if (name.contains("forearm_outer_flank") || name.contains("forearm_outer_shield")) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			poseStack.translate(direction * 0.34F * middle, -0.14F * middle, 0.16F * middle);
			rotateAround(poseStack, group, 0.0F, 0.0F, direction * -6.0F * middle);
		}

		// 阶段三：背部 Hi-MAT 机翼 / 翼状浮游炮高机动展开
		if (name.contains("vane") || name.contains("flight_fin") || name.contains("wing")) {
			boolean left = name.contains("left") || name.contains("_l_") || name.endsWith("_l");
			float direction = left ? 1.0F : -1.0F;
			boolean sentinelWing = variant == ArmorVariant.SENTINEL
					&& name.contains("_sentinel_");
			float wingLoad = diveProgress;
			if (sentinelWing) {
				float active = sentinelOverdriveProgress(entity, animationTime)
						* smoothstep(0.04F, 0.58F, progress);
				float tier = sentinelWingTier(name);
				float speedSweep = active * smoothstep(0.10F, 0.85F, speedProgress);
				float diveTuck = active * diveProgress;
				float hoverFlap = active * (1.0F - speedProgress)
						* Mth.sin(animationTime * 0.55F + tier * 0.9F);
				float bend = speedSweep + 0.70F * diveTuck;
				float flapLift = hoverFlap * (0.16F + 0.26F * tier);
				float flapPitch = hoverFlap * (1.5F + 2.5F * tier);
				poseStack.translate(
						direction * (0.10F + 0.16F * tier) * bend,
						(0.05F + 0.08F * tier) * bend + flapLift,
						(0.16F + 0.20F * tier) * bend);
				rotateAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
						(-4.0F - 6.0F * tier) * bend + flapPitch,
						direction * (-3.0F - 6.0F * tier) * bend,
						direction * (5.0F + 9.0F * tier) * bend);
				return;
			}

			float wingStage = late;
			float hoverBreath = (1.0F - speedProgress) * Mth.sin(animationTime * 0.16F) * 0.18F;
			float hoverX = switch (variant) {
				case VANGUARD -> 3.15F;
				case SWIFT -> 4.10F;
				case SENTINEL -> 3.15F;
			};
			float diveX = switch (variant) {
				case VANGUARD -> 1.05F;
				case SWIFT -> 1.52F;
				case SENTINEL -> 1.05F;
			};
			float travelX = Mth.lerp(wingLoad, hoverX + hoverBreath, diveX);
			float travelY = Mth.lerp(wingLoad,
					-0.78F,
					variant == ArmorVariant.SWIFT ? 1.08F : 0.82F);
			float travelZ = Mth.lerp(wingLoad,
					variant == ArmorVariant.SWIFT ? 1.56F : 1.24F,
					1.72F);
			poseStack.translate(
					direction * travelX * wingStage,
					travelY * wingStage,
					travelZ * wingStage);
			rotateAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
					Mth.lerp(wingLoad, -10.0F,
							variant == ArmorVariant.SWIFT ? -24.0F : -18.0F) * wingStage,
					direction * Mth.lerp(wingLoad,
							variant == ArmorVariant.SWIFT ? -44.0F : -34.0F,
							-12.0F) * wingStage,
					direction * Mth.lerp(wingLoad,
							-26.0F,
							variant == ArmorVariant.SWIFT ? -4.0F : -6.0F) * wingStage);
			if (name.contains("spine")) {
				poseStack.translate(direction * 0.22F * late, 0.0F, 0.16F * late);
			} else if (name.contains("field") || name.contains("trace_led")) {
				poseStack.translate(direction * 0.42F * late, 0.0F, 0.26F * late);
				scaleAround(poseStack, group, 1.0F, 1.0F + (0.16F + 0.20F * speedProgress) * late, 1.0F);
			}
		}

		// 阶段三：RG Nu 高达大腿裂甲展开、膝部竖向黄色喷口前移抬升、小腿后侧推进护罩张开
		if (name.contains("thigh_plate") || name.contains("thigh_bastion")) {
			poseStack.translate(0.0F, -0.16F * middle, -0.18F * middle);
			scaleAround(poseStack, group, 1.0F + 0.04F * middle, 1.0F, 1.0F);
		} else if (name.contains("thigh_energy_trace") || name.contains("thigh_node_led")) {
			poseStack.translate(0.0F, -0.14F * middle, -0.24F * middle);
		} else if (name.contains("thigh_knee") || name.contains("knee_lock")
				|| name.contains("thigh_flank_trim") || name.contains("sentinel_hip_shield")) {
			poseStack.translate(0.0F, -0.26F * middle, -0.34F * middle);
			rotateAround(poseStack, group, -11.0F * middle, 0.0F, 0.0F);
		} else if (name.contains("thigh_outer_flank") || name.contains("thigh_fin")) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			float sentinel = variant == ArmorVariant.SENTINEL ? 1.0F : 0.0F;
			float retract = 1.0F - diveProgress * Mth.lerp(sentinel, 0.70F, 0.25F);
			poseStack.translate(direction * Mth.lerp(sentinel, 0.32F, 0.52F) * middle * retract,
					0.0F, Mth.lerp(sentinel, 0.18F, 0.30F) * middle * retract);
			rotateAround(poseStack, group, 0.0F, 0.0F, direction * -6.0F * middle * retract);
		}

		if (name.contains("calf_crest")) {
			poseStack.translate(0.0F, -0.20F * middle, -0.24F * middle);
			rotateAround(poseStack, group, -8.5F * middle, 0.0F, 0.0F);
		} else if (name.contains("calf_rear") || name.contains("heel")) {
			poseStack.translate(0.0F, 0.16F * late, 0.56F * late);
			rotateAround(poseStack, group, 12.0F * late, 0.0F, 0.0F);
		} else if (name.contains("boot") && (name.contains("outer_guard")
				|| name.contains("outer_stabilizer") || name.contains("calf_fin"))) {
			boolean left = name.contains("left");
			float direction = left ? 1.0F : -1.0F;
			float sentinel = variant == ArmorVariant.SENTINEL ? 1.0F : 0.0F;
			poseStack.translate(direction * Mth.lerp(sentinel, 0.34F, 0.52F) * middle,
					0.0F, Mth.lerp(sentinel, 0.22F, 0.36F) * middle);
			rotateAround(poseStack, group, 4.0F * middle, direction * -9.0F * middle, 0.0F);
		}

		if (name.contains("boot_ring") || name.contains("ankle_ring") || name.contains("gravity_ring")) {
			float spinDirection = name.contains("right") ? -1.0F : 1.0F;
			float spinMultiplier = switch (variant) {
				case VANGUARD -> 1.0F;
				case SWIFT -> 1.65F;
				case SENTINEL -> 0.42F;
			};
			rotateAround(poseStack, group, 0.0F,
					spinDirection * spinMultiplier
							* (animationTime * (4.0F + 16.0F * speedProgress)) % 360.0F, 0.0F);
			float ringScale = 1.0F + (switch (variant) {
				case VANGUARD -> 0.09F + 0.16F * speedProgress;
				case SWIFT -> 0.14F + 0.25F * speedProgress;
				case SENTINEL -> 0.05F + 0.08F * speedProgress;
			}) * deployed;
			scaleAround(poseStack, group, ringScale, 1.0F, ringScale);
		}

		if (name.contains("sole_field")) {
			poseStack.translate(0.0F, 0.05F * speedProgress * deployed, 0.0F);
			float fieldScale = 1.0F + (variant == ArmorVariant.SWIFT ? 0.30F
					: variant == ArmorVariant.SENTINEL ? 0.12F : 0.20F) * speedProgress * deployed;
			float fieldLength = variant == ArmorVariant.SWIFT ? fieldScale * 1.12F : fieldScale;
			scaleAround(poseStack, group, fieldScale, 1.0F, fieldLength);
		}
	}

	private static float sentinelWingTier(String name) {
		if (name.contains("drone_pod")) return 1.0F;
		if (name.contains("_tip_")) return 1.0F;
		if (name.contains("_secondary_")) return 0.74F;
		if (name.contains("_cannon_")) return 0.62F;
		if (name.contains("_primary_")) return 0.44F;
		return 0.18F;
	}

	/**
	 * Reconfigures the same Sentinel geometry between a folded fortress and a large flight wing.
	 *
	 * <p>The item stores the target mode and transition start tick. Since there are exactly two
	 * configurations, the inverse of the target is the unambiguous transition source. No client
	 * cache is needed, so chunk reloads, first-person arms and remote carriers all reconstruct the
	 * same pose from authoritative item data.</p>
	 */
	private static void applySentinelConfigurationTransform(
			PoseStack poseStack, ArmorObjMesh.Group group, ArmorVariant variant,
			LivingEntity entity, float animationTime) {
		String name = group.name().toLowerCase(Locale.ROOT);
		if (variant != ArmorVariant.SENTINEL) return;
		SentinelVisualZone zone = SentinelVisualZone.fromGroup(name);
		if (zone == SentinelVisualZone.NONE) return;

		float overdrive = sentinelOverdriveProgress(entity, animationTime);
		float streamline = smoothstep(0.0F, 1.0F, overdrive);
		boolean left = name.contains("left");
		boolean right = name.contains("right");
		float direction = left ? 1.0F : right ? -1.0F : 0.0F;

		if (zone == SentinelVisualZone.WING || zone == SentinelVisualZone.DRONE_POD) {
			boolean detachableModule = isSentinelDetachableModule(name);
			float wingmanFold = 0.0F;
			if (detachableModule) {
				/*
				 * The detachable leaves first retract from the large overdrive wing into their
				 * raw common socket frame. The shared hinge articulation then folds those same
				 * leaves into the autonomous craft; no plate is scaled away or replaced.
				 */
				wingmanFold = sentinelWingmanFoldProgress(entity, animationTime);
				streamline *= 1.0F - wingmanFold;
			}
			if (zone == SentinelVisualZone.DRONE_POD) {
				float release = smoothstep(0.30F, 0.74F, streamline);
				poseStack.translate(direction * 4.70F * release,
						0.78F * release, 2.90F * release);
				rotateAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
						-16.0F * release,
						direction * -38.0F * release,
						direction * -86.0F * release);
				float compactX = Mth.lerp(wingmanFold, 0.78F, 1.0F);
				float compactY = Mth.lerp(wingmanFold, 0.56F, 1.0F);
				float compactZ = Mth.lerp(wingmanFold, 0.82F, 1.0F);
				scaleAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
						Mth.lerp(release, compactX, 1.0F),
						Mth.lerp(release, compactY, 1.0F),
						Mth.lerp(release, compactZ, 1.0F));
				return;
			}

			float tier = sentinelWingTier(name);
			float start = name.contains("_root_") ? 0.04F
					: name.contains("_primary_") ? 0.12F
					: name.contains("_cannon_") ? 0.20F
					: name.contains("_secondary_") ? 0.26F : 0.34F;
			float end = name.contains("_root_") ? 0.54F
					: name.contains("_primary_") ? 0.68F
					: name.contains("_cannon_") ? 0.78F
					: name.contains("_secondary_") ? 0.86F : 0.96F;
			float extension = smoothstep(start, end, streamline);
			float foldedSpan = name.contains("_root_") ? 0.70F
					: name.contains("_primary_") ? 0.46F
					: name.contains("_cannon_") ? 0.62F
					: name.contains("_secondary_") ? 0.36F : 0.30F;
			float travelX = Mth.lerp(tier, 1.45F, 6.90F);
			float travelY = Mth.lerp(tier, -0.72F, 0.66F);
			float travelZ = Mth.lerp(tier, 0.92F, 3.36F);
			float pitch = Mth.lerp(tier, -7.0F, -21.0F);
			float yaw = Mth.lerp(tier, -20.0F, -48.0F);
			float roll = Mth.lerp(tier, -58.0F, -108.0F);
			float compactX = detachableModule
					? Mth.lerp(wingmanFold, 0.82F, 1.0F) : 0.82F;
			float compactY = detachableModule
					? Mth.lerp(wingmanFold, foldedSpan, 1.0F) : foldedSpan;
			float compactZ = detachableModule
					? Mth.lerp(wingmanFold, 0.80F, 1.0F) : 0.80F;
			poseStack.translate(
					direction * travelX * extension,
					travelY * extension,
					travelZ * extension);
			rotateAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
					pitch * extension,
					direction * yaw * extension,
					direction * roll * extension);
			scaleAroundPoint(poseStack, direction * 3.62F, 2.55F, 2.52F,
					Mth.lerp(extension, compactX, 1.0F),
					Mth.lerp(extension, compactY, 1.0F),
					Mth.lerp(extension, compactZ, 1.0F));
			return;
		}

		/*
		 * Every armor zone has a separate mechanical phase. The offsets are intentionally
		 * larger than ordinary breathing/flight motion: the viewer must read one complete
		 * suit changing configuration, including faceplate, chest, gauntlets, waist, knees
		 * and boots, rather than mistaking two moving wings for the whole transformation.
		 */
		if (group.coaxialShell()) {
			// 闭合底衬与环形骨架保持原位包覆，避免缩放或单向位移穿透外层重甲。
			return;
		}
		float zOut = group.rearGroup() ? 1.0F : -1.0F;
		if (zone == SentinelVisualZone.HEAD) {
			float headPhase = smoothstep(0.02F, 0.48F, streamline);
			float lift = Mth.sin(headPhase * Mth.PI);
			if (name.contains("jaw_guard")) {
				poseStack.translate(direction * (0.28F * headPhase + 0.18F * lift),
						-0.18F * headPhase, -0.22F * headPhase - 0.24F * lift);
				rotateAround(poseStack, group, -6.0F * headPhase,
						direction * -9.0F * headPhase,
						direction * 6.0F * headPhase);
			} else if (name.contains("crown") || name.contains("brow")) {
				poseStack.translate(0.0F, -0.36F * headPhase - 0.14F * lift,
						-0.18F * headPhase - 0.20F * lift);
				scaleAround(poseStack, group,
						1.0F + 0.025F * headPhase,
						1.0F - 0.020F * headPhase,
						1.0F + 0.030F * headPhase);
			} else if (name.contains("rear_")) {
				poseStack.translate(direction * 0.34F * headPhase,
						0.08F * headPhase, 0.56F * headPhase + 0.18F * lift);
				rotateAround(poseStack, group, -5.0F * headPhase,
						direction * 10.0F * headPhase,
						direction * -5.0F * headPhase);
			} else {
				poseStack.translate(direction * 0.14F * headPhase,
						-0.18F * headPhase, zOut * (0.14F * headPhase + 0.16F * lift));
				scaleAround(poseStack, group,
						1.0F + 0.020F * headPhase,
						1.0F - 0.015F * headPhase,
						1.0F + 0.020F * headPhase);
			}
		} else if (zone == SentinelVisualZone.TORSO) {
			float torsoPhase = smoothstep(0.08F, 0.64F, streamline);
			float lift = Mth.sin(torsoPhase * Mth.PI);
			if (name.contains("backplate") || name.contains("spine")) {
				poseStack.translate(0.0F, -0.20F * torsoPhase, 0.42F * torsoPhase + 0.22F * lift);
				scaleAround(poseStack, group,
						1.0F - 0.04F * torsoPhase,
						1.0F + 0.035F * torsoPhase,
						1.0F + 0.04F * torsoPhase);
			} else if (name.contains("waist_apron")) {
				float waistPhase = smoothstep(0.20F, 0.78F, streamline);
				float waistLift = Mth.sin(waistPhase * Mth.PI);
				poseStack.translate(0.0F, 0.24F * waistPhase, -0.18F * waistPhase - 0.20F * waistLift);
				scaleAround(poseStack, group,
						1.0F - 0.04F * waistPhase,
						1.0F - 0.03F * waistPhase,
						1.0F + 0.02F * waistPhase);
			} else if (name.startsWith("core_")) {
				poseStack.translate(0.0F, -0.18F * torsoPhase, -0.22F * torsoPhase - 0.14F * lift);
				scaleAroundPoint(poseStack, 0.0F, 3.65F, -3.10F,
						1.0F + 0.10F * torsoPhase,
						1.0F + 0.10F * torsoPhase,
						1.0F + 0.04F * torsoPhase);
			} else {
				poseStack.translate(direction * 0.16F * torsoPhase,
						-0.14F * torsoPhase, zOut * (0.16F * torsoPhase + 0.22F * lift));
				scaleAround(poseStack, group,
						1.0F - 0.03F * torsoPhase,
						1.0F - 0.02F * torsoPhase,
						1.0F + 0.03F * torsoPhase);
			}
		} else if (zone == SentinelVisualZone.ARM) {
			float armPhase = smoothstep(0.14F, 0.74F, streamline);
			boolean upper = name.contains("upper_");
			float platePhase = upper ? armPhase : smoothstep(0.22F, 0.82F, streamline);
			float lift = Mth.sin(platePhase * Mth.PI);
			poseStack.translate(
					direction * ((upper ? 0.28F : 0.18F) * platePhase + 0.24F * lift),
					(upper ? -0.22F : 0.14F) * platePhase,
					zOut * (0.18F * platePhase + 0.24F * lift));
			scaleAround(poseStack, group,
					1.0F - 0.025F * platePhase,
					1.0F - 0.020F * platePhase,
					1.0F + 0.025F * platePhase);
			if (upper) {
				rotateAround(poseStack, group, -5.0F * platePhase,
						direction * -7.0F * platePhase,
						direction * -8.0F * platePhase);
			} else if (name.contains("forearm")) {
				rotateAround(poseStack, group, 3.0F * platePhase,
						direction * 4.0F * platePhase,
						direction * -5.0F * platePhase);
			}
		} else if (zone == SentinelVisualZone.LEG) {
			float legPhase = smoothstep(0.24F, 0.86F, streamline);
			float lift = Mth.sin(legPhase * Mth.PI);
			boolean knee = name.contains("_knee_");
			poseStack.translate(direction * ((knee ? 0.12F : 0.20F) * legPhase + 0.18F * lift),
					(knee ? -0.18F : -0.06F) * legPhase,
					zOut * ((knee ? 0.24F : 0.16F) * legPhase + 0.22F * lift));
			scaleAround(poseStack, group,
					1.0F - 0.025F * legPhase,
					1.0F - 0.020F * legPhase,
					1.0F + 0.025F * legPhase);
			if (knee) {
				rotateAround(poseStack, group, -7.0F * legPhase,
						direction * -4.0F * legPhase,
						direction * 4.0F * legPhase);
			}
		} else if (zone == SentinelVisualZone.BOOT) {
			float bootPhase = smoothstep(0.32F, 0.94F, streamline);
			float lift = Mth.sin(bootPhase * Mth.PI);
			boolean toe = name.contains("toe_ram");
			poseStack.translate(direction * (0.16F * bootPhase + 0.15F * lift),
					(toe ? 0.14F : 0.04F) * bootPhase,
					(toe ? -0.36F * bootPhase - 0.18F * lift : zOut * (0.16F * bootPhase + 0.20F * lift)));
			scaleAround(poseStack, group,
					1.0F - 0.020F * bootPhase,
					1.0F - 0.015F * bootPhase,
					1.0F + 0.020F * bootPhase);
			if (toe) {
				rotateAround(poseStack, group, 5.0F * bootPhase, 0.0F,
						direction * -4.0F * bootPhase);
			}
		}
	}

	private static float sentinelOverdriveProgress(LivingEntity entity, float animationTime) {
		if (entity == null) return 1.0F;
		ArmorConfigurationData.Snapshot configuration =
				ArmorConfigurationAccess.configuredSentinel(entity)
						.map(ArmorConfigurationAccess.ConfiguredSentinel::snapshot)
						.orElseGet(() -> ArmorConfigurationData.read(
								entity.getItemBySlot(EquipmentSlot.CHEST)));
		float partialTick = Mth.clamp(animationTime - entity.tickCount, 0.0F, 1.0F);
		double elapsed = entity.level().getGameTime() + partialTick
				- configuration.transitionStartTick();
		float transition = configuration.revision() <= 0L ? 1.0F : smoothstep(
				0.0F, ArmorConfigurationAccess.TRANSITION_DURATION_TICKS, (float) elapsed);
		return configuration.mode() == SentinelArmorMode.OVERDRIVE_FLIGHT
				? transition : 1.0F - transition;
	}

	/**
	 * The outer vane, tip, pod and cannon are one physical module before and after H.
	 *
	 * <p>This method returns binary render ownership. While the worn suit owns the module it uses
	 * the same group-by-group hinge transform as the wingman entity. At the hand-off tick it stops
	 * drawing those exact OBJ groups and the entity starts drawing them at full size and alpha.
	 * There is no cross-fade and no whole-object scale.</p>
	 */
	private static boolean applySentinelDronePodTransform(
			PoseStack poseStack, ArmorObjMesh.Group group, ArmorVariant variant,
			LivingEntity entity, float animationTime) {
		String name = group.name().toLowerCase(Locale.ROOT);
		boolean detachableModule = isSentinelDetachableModule(name);
		if (variant != ArmorVariant.SENTINEL || !detachableModule) return true;
		// Cruise armor stands use the pod groups as their actual suit silhouette. Only a worn
		// player can hand those copies off to the two independently tracked wingman entities.
		if (!(entity instanceof Player)) return true;
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(entity).orElse(null);
		if (configured == null) return true;
		ArmorConfigurationData.Snapshot configuration = configured.snapshot();
		float partialTick = Mth.clamp(animationTime - entity.tickCount, 0.0F, 1.0F);
		double visualTime = entity.level().getGameTime() + partialTick;
		if (ArmorConfigurationAccess.wingmenEntityOwnsGeometry(
				configuration, visualTime)) {
			return false;
		}

		float fold = sentinelWingmanFoldProgress(configuration, visualTime);
		SentinelWingModuleMesh.applyWornSocketArticulation(
				poseStack, group, name, name.contains("_left_"), fold);
		return true;
	}

	private static boolean isSentinelDetachableModule(String name) {
		return name.contains("sentinel_drone_pod")
				|| name.contains("sentinel_wing_secondary")
				|| name.contains("sentinel_wing_tip")
				|| name.contains("sentinel_wing_cannon");
	}

	private static float sentinelWingmanFoldProgress(
			LivingEntity entity, float animationTime) {
		if (entity == null) return 0.0F;
		// Fold animation is visual chest metadata; exact identity validation remains in the
		// ownership method. Avoid re-walking all four armor slots once for every OBJ wing group.
		ArmorConfigurationData.Snapshot configuration = ArmorConfigurationData.read(
				entity.getItemBySlot(EquipmentSlot.CHEST));
		float partialTick = Mth.clamp(animationTime - entity.tickCount, 0.0F, 1.0F);
		return sentinelWingmanFoldProgress(configuration,
				entity.level().getGameTime() + partialTick);
	}

	private static float sentinelWingmanFoldProgress(
			ArmorConfigurationData.Snapshot configuration, double visualTime) {
		if (configuration == null) return 0.0F;
		if (configuration.wingmenRevision() <= 0L) {
			return configuration.wingmenDeployed() ? 1.0F : 0.0F;
		}
		float elapsed = (float) Math.max(0.0D,
				visualTime - configuration.wingmenTransitionStartTick());
		return configuration.wingmenDeployed()
				? smoothstep(4.0F,
						ArmorConfigurationAccess.WINGMEN_HANDOFF_START_TICKS, elapsed)
				: 1.0F - smoothstep(22.0F,
						ArmorConfigurationAccess.WINGMEN_TRANSITION_DURATION_TICKS, elapsed);
	}

	/** 武器变形与飞行展开解耦，站立蓄力和接地冲击时也能工作。 */
	private static void applyCombatTransform(PoseStack poseStack, ArmorObjMesh.Group group,
			LivingEntity entity, float animationTime) {
		LivingEntity player = entity;
		float partialTick = Mth.clamp(animationTime - player.tickCount, 0.0F, 1.0F);
		String name = group.name();
		applyBladeDeploymentTransform(poseStack, group, player, partialTick);
		if ((ArmorCombatClient.attackType(player) == dev.codex.flightarmor.combat.ArmorAttackType.CORE_BEAM
				|| ArmorCombatClient.attackType(player) == dev.codex.flightarmor.combat.ArmorAttackType.CRUISE_CORE_BEAM)) {
			float phaseProgress = ArmorCombatClient.phaseProgress(player, partialTick);
			float release = ArmorCombatClient.coreReleaseImpulse(player, partialTick);
			float strength = switch (ArmorCombatClient.phase(player)) {
				case CHARGING, WINDUP -> phaseProgress;
				case FIRING -> 1.0F;
				case RECOVERY -> 1.0F - phaseProgress;
				case IDLE -> release;
			};
			strength = Mth.clamp(Math.max(strength, release), 0.0F, 1.0F);
			if (name.contains("core_reactor")) {
				float breath = 0.006F * Mth.sin(animationTime * 0.72F) * strength;
				scaleAroundPoint(poseStack, group.centerX(), group.centerY(), group.centerZ(),
						1.0F + 0.050F * strength + breath,
						1.0F + 0.050F * strength + breath, 1.0F);
			} else if (name.contains("core_housing")) {
				scaleAroundPoint(poseStack, group.centerX(), group.centerY(), group.centerZ(),
						1.0F + 0.015F * strength, 1.0F + 0.015F * strength, 1.0F);
			}
		}
		float impact = ArmorCombatClient.diveImpactImpulse(player, partialTick);
		if (impact > 0.001F && (name.contains("boot_") || name.contains("_boot_"))) {
			if (name.contains("sole_field") || name.contains("gravity_ring")) {
				scaleAroundPoint(poseStack, group.centerX(), group.centerY(), group.centerZ(),
						1.0F + 0.22F * impact, 1.0F + 0.03F * impact, 1.0F + 0.22F * impact);
			}
			if (name.contains("gravity_ring")) {
				rotateAround(poseStack, group, 0.0F, 30.0F * impact, 0.0F);
			}
		}
	}

	private static void applyCruiseCombatAnchorOffset(PoseStack poseStack,
			Anchor anchor, FlightPoseFrame pose) {
		float pitch;
		float yaw;
		float roll;
		switch (anchor) {
			case HEAD -> {
				pitch = pose.headPitchOffset();
				yaw = 0.0F;
				roll = pose.headRollOffset();
			}
			case BODY -> {
				pitch = pose.bodyPitch();
				yaw = 0.0F;
				roll = pose.bodyRoll();
			}
			case LEFT_ARM -> {
				pitch = pose.leftArmPitch();
				yaw = pose.leftArmYaw();
				roll = pose.leftArmRoll();
			}
			case RIGHT_ARM -> {
				pitch = pose.rightArmPitch();
				yaw = pose.rightArmYaw();
				roll = pose.rightArmRoll();
			}
			case LEFT_LEG -> {
				pitch = pose.leftLegPitch();
				yaw = pose.leftLegYaw();
				roll = pose.leftLegRoll();
			}
			case RIGHT_LEG -> {
				pitch = pose.rightLegPitch();
				yaw = pose.rightLegYaw();
				roll = pose.rightLegRoll();
			}
			default -> {
				return;
			}
		}
		if (Math.abs(roll) > 0.0001F) poseStack.mulPose(Axis.ZP.rotation(roll));
		if (Math.abs(yaw) > 0.0001F) poseStack.mulPose(Axis.YP.rotation(yaw));
		if (Math.abs(pitch) > 0.0001F) poseStack.mulPose(Axis.XP.rotation(pitch));
	}

	/**
	 * 光刃不是凭空贴到手上：前臂外壳先解锁，外轨向两侧张开，尾板沿手腕方向滑动，
	 * 最后露出内部发射器。第一、第三人称共用同一进度，因此臂甲与光刃不会各播各的。
	 */
	private static void applyBladeDeploymentTransform(PoseStack poseStack, ArmorObjMesh.Group group,
			LivingEntity player, float partialTick) {
		float progress = ArmorCombatClient.bladeProgress(player, partialTick);
		if (progress <= 0.0001F) return;
		String name = group.name();
		if (!isArmBladeHardwareGroup(name)) return;
		boolean left = name.contains("arm_left_") || name.contains("left_arm_");
		boolean right = name.contains("arm_right_") || name.contains("right_arm_");
		if (!left && !right) return;

		ArmorVariant variant = ArmorCombatClient.bladeVariant(player);
		if (left && variant != ArmorVariant.SWIFT) return;
		if (variant == ArmorVariant.SENTINEL) {
			applySentinelBladeDeploymentTransform(poseStack, group, name, progress);
			return;
		}
		float side = left ? 1.0F : -1.0F;
		float unlock = staged(progress, 0.02F, 0.26F);
		float open = staged(progress, 0.12F, 0.52F);
		float emitter = staged(progress, 0.32F, 0.74F);
		float variantOpen = switch (variant) {
			case VANGUARD -> 1.0F;
			case SWIFT -> 0.82F;
			case SENTINEL -> 1.22F;
		};

		if (name.contains("forearm_plate") || name.contains("forearm_bulwark")) {
			poseStack.translate(side * 0.62F * open * variantOpen,
					0.22F * emitter, -0.46F * open);
			rotateAround(poseStack, group, -4.0F * unlock,
					side * 8.0F * open, side * 11.0F * open * variantOpen);
		} else if (name.contains("forearm_outer_rail")
				|| name.contains("forearm_outer_brace")) {
			poseStack.translate(side * 1.18F * open * variantOpen,
					0.54F * emitter, -0.78F * open);
			rotateAround(poseStack, group, -7.0F * unlock,
					side * 12.0F * open, side * 20.0F * open * variantOpen);
		} else if (name.contains("forearm_rear_plate")
				|| name.contains("swift_forearm_rear_blade")) {
			float swiftSlide = variant == ArmorVariant.SWIFT ? 1.32F : 0.92F;
			poseStack.translate(side * 0.34F * open,
					swiftSlide * emitter, -0.58F * emitter);
			rotateAround(poseStack, group, -10.0F * emitter,
					side * 6.0F * open, side * 7.0F * open);
		} else if (name.contains("forearm_energy_trace")
				|| name.contains("forearm_node")) {
			poseStack.translate(side * 0.18F * open, 0.72F * emitter, -0.50F * emitter);
			float traceScale = 1.0F + 0.10F * emitter;
			scaleAround(poseStack, group, traceScale, 1.0F + 0.18F * emitter, traceScale);
		} else if (name.contains("forearm_underlayer")) {
			// 内层承力骨架只做轻微前移，外壳打开后能看到它但不会脱离手臂。
			poseStack.translate(0.0F, 0.20F * emitter, -0.16F * emitter);
		}
	}

	private static boolean isArmBladeHardwareGroup(String name) {
		return name.contains("forearm")
				|| name.contains("cover_outer")
				|| name.contains("cover_inner")
				|| name.contains("hand_shell")
				|| name.contains("wrist_lock")
				|| name.contains("emitter_track");
	}

	/**
	 * Sentinel's bilateral breaker begins as a closed forearm/fist mechanism. The load-bearing
	 * sleeve stays fixed while paired forearm covers and hand-back shell leaves open in sequence;
	 * the shutter and emitter carriage then advance before the energy edge appears.
	 */
	private static void applySentinelBladeDeploymentTransform(PoseStack poseStack,
			ArmorObjMesh.Group group, String name, float progress) {
		SentinelBreakerTimeline.Sample deployment =
				SentinelBreakerTimeline.sample(progress);
		float unlock = deployment.unlock();
		float jawOpen = deployment.jawOpen();
		float carriage = deployment.carriage();
		boolean left = name.contains("arm_left_") || name.contains("left_arm_");
		float side = left ? 1.0F : -1.0F;

		if (name.contains("forearm_underlayer")
				|| name.contains("forearm_outer_brace")) {
			// The load-bearing sleeve and side brace stay welded to the arm.
			return;
		}
		if (name.contains("cover_outer")) {
			poseStack.translate(side * 0.22F * jawOpen, 0.08F * carriage,
					0.16F * jawOpen);
			rotateAroundFixedPivot(poseStack,
					left ? 3.08F : -3.08F, 6.35F, 3.30F,
					0.0F, -side * 18.0F * jawOpen, side * 3.0F * unlock);
			return;
		}
		if (name.contains("cover_inner")) {
			poseStack.translate(-side * 0.18F * jawOpen, 0.10F * carriage,
					0.20F * jawOpen);
			rotateAround(poseStack, group, -3.0F * unlock,
					side * 12.0F * jawOpen, -side * 2.0F * jawOpen);
			return;
		}
		if (name.contains("forearm_bulwark") || name.contains("forearm_plate")) {
			poseStack.translate(0.0F, 0.04F * carriage, 0.08F * unlock);
			return;
		}
		if (name.contains("wrist_lock")) {
			poseStack.translate(0.0F, 0.14F * unlock, 0.18F * unlock);
			rotateAround(poseStack, group, -9.0F * unlock,
					0.0F, side * 3.0F * unlock);
			return;
		}
		if (name.contains("hand_shell_outer")) {
			poseStack.translate(side * 0.34F * jawOpen, 0.38F * carriage,
					0.14F * jawOpen);
			rotateAround(poseStack, group, -4.0F * unlock,
					-side * 16.0F * jawOpen, side * 5.0F * jawOpen);
			return;
		}
		if (name.contains("hand_shell_inner")) {
			poseStack.translate(-side * 0.24F * jawOpen, 0.34F * carriage,
					0.12F * jawOpen);
			rotateAround(poseStack, group, -3.0F * unlock,
					side * 12.0F * jawOpen, -side * 4.0F * jawOpen);
			return;
		}
		if (name.contains("emitter_shutter")) {
			poseStack.translate(0.0F, 0.58F * carriage, 0.24F * jawOpen);
			scaleAround(poseStack, group, 0.82F + 0.18F * unlock,
					1.0F + 0.10F * carriage, 1.0F);
			return;
		}
		if (name.contains("emitter_track")) {
			poseStack.translate(0.0F, 0.78F * carriage, 0.22F * jawOpen);
			scaleAround(poseStack, group, 1.0F,
					1.0F + 0.16F * carriage, 1.0F);
			return;
		}
		if (name.contains("forearm_outer_rail")) {
			poseStack.translate(side * 0.42F * jawOpen,
					0.56F * carriage, -0.42F * carriage);
			return;
		}
		if (name.contains("forearm_energy_trace")) {
			poseStack.translate(0.0F, 0.52F * carriage, 0.16F * jawOpen);
			float nodeScale = 1.0F + 0.10F * carriage;
			scaleAround(poseStack, group, nodeScale,
					1.0F + 0.16F * carriage, nodeScale);
			return;
		}
		if (name.contains("forearm_rear_plate")) {
			poseStack.translate(0.0F, 0.12F * carriage, 0.16F * jawOpen);
		}
	}

	private static float staged(float progress, float start, float end) {
		float value = Mth.clamp((progress - start) / (end - start), 0.0F, 1.0F);
		return value * value * (3.0F - 2.0F * value);
	}

	private static void rotateAroundFixedPivot(PoseStack poseStack,
			float pivotX, float pivotY, float pivotZ,
			float xDegrees, float yDegrees, float zDegrees) {
		poseStack.translate(pivotX, pivotY, pivotZ);
		if (xDegrees != 0.0F) {
			poseStack.mulPose(Axis.XP.rotationDegrees(xDegrees));
		}
		if (yDegrees != 0.0F) {
			poseStack.mulPose(Axis.YP.rotationDegrees(yDegrees));
		}
		if (zDegrees != 0.0F) {
			poseStack.mulPose(Axis.ZP.rotationDegrees(zDegrees));
		}
		poseStack.translate(-pivotX, -pivotY, -pivotZ);
	}

	private static void rotateAround(PoseStack poseStack, ArmorObjMesh.Group group,
			float xDegrees, float yDegrees, float zDegrees) {
		poseStack.translate(group.centerX(), group.centerY(), group.centerZ());
		if (xDegrees != 0.0F) {
			poseStack.mulPose(Axis.XP.rotationDegrees(xDegrees));
		}
		if (yDegrees != 0.0F) {
			poseStack.mulPose(Axis.YP.rotationDegrees(yDegrees));
		}
		if (zDegrees != 0.0F) {
			poseStack.mulPose(Axis.ZP.rotationDegrees(zDegrees));
		}
		poseStack.translate(-group.centerX(), -group.centerY(), -group.centerZ());
	}

	private static void rotateAroundPoint(PoseStack poseStack, float pivotX, float pivotY, float pivotZ,
			float xDegrees, float yDegrees, float zDegrees) {
		poseStack.translate(pivotX, pivotY, pivotZ);
		if (xDegrees != 0.0F) poseStack.mulPose(Axis.XP.rotationDegrees(xDegrees));
		if (yDegrees != 0.0F) poseStack.mulPose(Axis.YP.rotationDegrees(yDegrees));
		if (zDegrees != 0.0F) poseStack.mulPose(Axis.ZP.rotationDegrees(zDegrees));
		poseStack.translate(-pivotX, -pivotY, -pivotZ);
	}

	private static void scaleAround(PoseStack poseStack, ArmorObjMesh.Group group,
			float x, float y, float z) {
		poseStack.translate(group.centerX(), group.centerY(), group.centerZ());
		poseStack.scale(x, y, z);
		poseStack.translate(-group.centerX(), -group.centerY(), -group.centerZ());
	}

	private static void scaleAroundPoint(PoseStack poseStack, float pivotX, float pivotY, float pivotZ,
			float x, float y, float z) {
		poseStack.translate(pivotX, pivotY, pivotZ);
		poseStack.scale(x, y, z);
		poseStack.translate(-pivotX, -pivotY, -pivotZ);
	}

	private static float flightProgress(LivingEntity entity, float partialTick) {
		if (entity instanceof Player player) {
			return FlightArmorClient.flightVisualProgress(player, partialTick);
		}
		return entity.isFallFlying() ? 1.0F : 0.0F;
	}

	private static float flightSpeedProgress(LivingEntity entity, float partialTick) {
		// 玩家灯色与 HUD、飞行声共用同一条真实位移遥测；巡航载体仍使用自身同步速度。
		if (entity instanceof Player player) {
			return (float) FlightSpeedTelemetry.profileEffectProgress(player, partialTick);
		}
		return (float) FlightPhysics.effectSpeedProgress(entity, entity.getDeltaMovement().length());
	}

	private static boolean isLed(String name) {
		return name.endsWith("_led") || name.contains("_led_") || name.startsWith("led_")
				|| name.contains("emissive") || name.contains("_glow") || name.startsWith("glow_");
	}

	static ArmorObjMesh.Color previewBaseColor(ArmorVariant variant, String groupName) {
		return variantBaseColor(variant, groupName, colorFor(groupName, 1.0F));
	}

	private static ArmorObjMesh.Color colorFor(String name, float intensity) {
		if (isLed(name)) {
			int alpha = Math.round(255.0F * Mth.clamp(intensity, 0.0F, 1.0F));
			if (name.contains("chin_chevron") || name.contains("command_node_led")) {
				return new ArmorObjMesh.Color(218, 48, 54, alpha);
			}
			return new ArmorObjMesh.Color(80, 226, 255, alpha);
		}
		if (name.contains("visor_lens") || name.contains("glass")) {
			return VISOR;
		}
		if (name.contains("visor_lower_frame") || name.contains("visor_sentinel_lower_lock")
				|| name.contains("shadow") || name.contains("under") || name.contains("black")
				|| name.contains("_seal_")) {
			return SHADOW;
		}
		if (name.contains("abdomen_sternum") || name.contains("chest_frame_gunmetal")) {
			return CRIMSON;
		}
		if (name.contains("visor_upper_frame") || name.contains("visor_sentinel_upper_lock")
				|| name.contains("faceplate_spine") || name.contains("faceplate_sentinel_lock")
				|| name.contains("calf_crest_light_titanium") || name.contains("body_waist_titanium")
				|| name.contains("waist_apron") || name.contains("body_abdomen_titanium")
				|| name.contains("abdominal_bulwark") || name.contains("upper_pauldron_titanium")
				|| name.contains("upper_bastion")) {
			return TITANIUM;
		}
		if (name.contains("dark") || name.contains("gunmetal") || name.contains("frame")
				|| name.contains("joint") || name.contains("barrel") || name.contains("shutter")
				|| name.contains("wrist_lock") || name.contains("rail") || name.contains("brace")
				|| name.contains("heel") || name.contains("load_frame") || name.contains("yoke")
				|| name.contains("collar") || name.contains("temple") || name.contains("cradle")) {
			return GUNMETAL;
		}
		if (name.contains("head_brow") || name.contains("brow_shield")
				|| name.contains("crown_crest") || name.contains("crown_bastion")
				|| name.contains("chest_upper") || name.contains("chest_crown")
				|| name.contains("core_housing") || name.contains("thigh_knee")
				|| name.contains("knee_lock") || name.contains("forearm_cuff")
				|| name.contains("waist_buckle") || name.contains("pauldron_crest")
				|| name.contains("wing_root_shell") || name.contains("wing_tip_shell")) {
			return LIGHT_TITANIUM;
		}
		if (name.contains("cannon_housing") || name.contains("side_trim")
				|| name.contains("rear_left_fin") || name.contains("rear_right_fin")) {
			return CRIMSON;
		}
		if (name.contains("body_chest_titanium") || name.contains("chest_bastion")
				|| name.contains("body_backplate_titanium") || name.contains("backplate_sentinel_lock")
				|| name.contains("thigh_flank_trim") || name.contains("sentinel_hip_shield")
				|| name.contains("toe_light_titanium") || name.contains("toe_ram")
				|| name.contains("vane_left_shell") || name.contains("vane_right_shell")
				|| name.contains("wing_primary_shell") || name.contains("swift_longfin")
				|| name.contains("flank")) {
			return ACCENT;
		}
		return TITANIUM;
	}

	private static ArmorObjMesh.Color variantBaseColor(
			ArmorVariant variant, String groupName, ArmorObjMesh.Color base) {
		if (variant == ArmorVariant.VANGUARD) return base;
		if (variant == ArmorVariant.SWIFT) {
			if (base == VISOR) return SWIFT_VISOR;
			if (base == SHADOW) return SWIFT_SHADOW;
			if (base == GUNMETAL) return SWIFT_GUNMETAL;
			if (base == ACCENT) return SWIFT_ACCENT;
			if (base == CRIMSON) return SWIFT_CRIMSON;
			if (base == LIGHT_TITANIUM) return SWIFT_HIGHLIGHT;
			return SWIFT_PRIMARY;
		}
		if (variant == ArmorVariant.SENTINEL) {
			if (base == VISOR) return SENTINEL_VISOR;
			if (base == SHADOW) return SENTINEL_SHADOW;
			if (base == GUNMETAL) return SENTINEL_GUNMETAL;
			if (base == ACCENT) return SENTINEL_ACCENT;
			if (base == CRIMSON) return SENTINEL_CRIMSON;
			if (base == LIGHT_TITANIUM) return SENTINEL_HIGHLIGHT;
			return SENTINEL_PRIMARY;
		}
		return base;
	}

	private static ArmorObjMesh.Color variantLedColor(
			LivingEntity entity, ArmorVariant variant, String groupName,
			ArmorObjMesh.Color base, float speedProgress) {
		if (variant == ArmorVariant.VANGUARD) return base;
		if (variant == ArmorVariant.SENTINEL) {
			ArmorObjMesh.Color cyan = new ArmorObjMesh.Color(48, 236, 255, 255);
			ArmorObjMesh.Color blue = new ArmorObjMesh.Color(58, 148, 255, 255);
			ArmorObjMesh.Color violet = new ArmorObjMesh.Color(190, 76, 255, 255);
			ArmorObjMesh.Color amber = new ArmorObjMesh.Color(255, 178, 42, 255);
			ArmorObjMesh.Color overload = new ArmorObjMesh.Color(255, 54, 32, 255);
			float load = smoothstep(0.30F, 0.92F, speedProgress);
			String name = groupName.toLowerCase(Locale.ROOT);
			ArmorObjMesh.Color flightIdle;
			ArmorObjMesh.Color flightLoaded;
			if (name.contains("spine_sentinel_segment")) {
				flightIdle = cyan;
				flightLoaded = mixColor(cyan, new ArmorObjMesh.Color(232, 252, 255, 255), 0.72F);
			} else if (name.contains("wing_root_node")) {
				flightIdle = cyan;
				flightLoaded = amber;
			} else if (name.contains("wing_primary_node")) {
				flightIdle = blue;
				flightLoaded = mixColor(amber, overload, 0.34F);
			} else if (name.contains("wing_secondary_node")) {
				flightIdle = violet;
				flightLoaded = mixColor(amber, overload, 0.62F);
			} else if (name.contains("wing_tip_node")) {
				flightIdle = mixColor(blue, violet, 0.48F);
				flightLoaded = overload;
			} else if (name.contains("wing_cannon_muzzle")) {
				flightIdle = new ArmorObjMesh.Color(236, 252, 255, 255);
				flightLoaded = new ArmorObjMesh.Color(255, 246, 202, 255);
			} else if (name.contains("drone_pod_status")) {
				flightIdle = violet;
				flightLoaded = new ArmorObjMesh.Color(255, 238, 255, 255);
			} else if (name.contains("drone_pod_node")) {
				flightIdle = blue;
				flightLoaded = overload;
			} else {
				flightIdle = cyan;
				flightLoaded = load < 0.68F
						? amber : mixColor(amber, overload, (load - 0.68F) / 0.32F);
			}
			ArmorObjMesh.Color breachIdle = name.contains("core_reactor")
					? new ArmorObjMesh.Color(255, 214, 74, 255)
					: name.contains("weapon") || name.contains("forearm")
							? new ArmorObjMesh.Color(255, 92, 38, 255)
							: new ArmorObjMesh.Color(255, 164, 42, 255);
			ArmorObjMesh.Color breachLoaded = name.contains("core_reactor")
					? new ArmorObjMesh.Color(255, 246, 188, 255)
					: mixColor(overload, new ArmorObjMesh.Color(255, 224, 102, 255), 0.34F);
			float overdrive = sentinelOverdriveProgress(entity,
					entity == null ? 0.0F
							: entity.tickCount + Minecraft.getInstance().getTimer()
									.getGameTimeDeltaPartialTick(true));
			ArmorObjMesh.Color idle = mixColor(breachIdle, flightIdle, overdrive);
			ArmorObjMesh.Color loaded = mixColor(breachLoaded, flightLoaded, overdrive);
			// Preserve docking, recall and weapon phase colors supplied by the
			// caller. Sentinel's old implementation replaced them with cyan at
			// speed zero, so its lights appeared frozen during transformation.
			ArmorObjMesh.Color phaseAwareIdle = mixColor(base, idle, 0.34F);
			ArmorObjMesh.Color result = mixColor(phaseAwareIdle, loaded, load);
			float transActivity = Mth.sin(Mth.clamp(overdrive, 0.0F, 1.0F) * Mth.PI);
			if (transActivity > 0.02F) {
				ArmorObjMesh.Color surgeGold = new ArmorObjMesh.Color(255, 232, 148, 255);
				result = mixColor(result, surgeGold, 0.65F * transActivity);
			}
			return result;
		}
		float moving = smoothstep(0.08F, 0.42F, speedProgress);
		float tint = Mth.lerp(moving, 0.68F, 0.24F);
		return mixColor(base, new ArmorObjMesh.Color(
				variant.lightRed(), variant.lightGreen(), variant.lightBlue(), 255), tint);
	}

	/**
	 * 装甲交接时一个槽位可能已从巡航载体移除、但还没同步到玩家。
	 * 优先读当前分区，随后读取同一实体的其他战甲槽，最后读取交接另一端，
	 * 确保召回、着装覆盖层和第一人称不会在中途跳回先锋型轮廓。
	 */
	private static ArmorVariant resolveArmorVariant(LivingEntity entity, EquipmentSlot preferredSlot) {
		ArmorVariant local = variantFromEquipment(entity, preferredSlot);
		if (local != null) return local;

		if (entity instanceof Player player) {
			ArmorCruiseClient.CruiseState state = ArmorCruiseClient.stateForOwner(player.getUUID());
			Entity carrier = state == null ? null
					: ArmorCruiseClient.resolveCarrier(Minecraft.getInstance(), state);
			if (carrier instanceof LivingEntity livingCarrier) {
				ArmorVariant carried = variantFromEquipment(livingCarrier, preferredSlot);
				if (carried != null) return carried;
			}
		} else {
			Player owner = ArmorCruiseClient.ownerOfCarrier(entity);
			if (owner != null) {
				ArmorVariant worn = variantFromEquipment(owner, preferredSlot);
				if (worn != null) return worn;
			}
		}
		return ArmorVariant.DEFAULT;
	}

	private static ArmorVariant variantFromEquipment(LivingEntity entity, EquipmentSlot preferredSlot) {
		ItemStack preferred = entity.getItemBySlot(preferredSlot);
		if (isFlightArmorItem(preferred)) return ArmorVariantData.get(preferred);
		for (EquipmentSlot slot : new EquipmentSlot[] {
				EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack candidate = entity.getItemBySlot(slot);
			if (isFlightArmorItem(candidate)) return ArmorVariantData.get(candidate);
		}
		return null;
	}

	private static boolean isFlightArmorItem(ItemStack stack) {
		return stack.is(ModItems.FLIGHT_HELMET) || stack.is(ModItems.FLIGHT_CHESTPLATE)
				|| stack.is(ModItems.FLIGHT_LEGGINGS) || stack.is(ModItems.FLIGHT_BOOTS);
	}


	private static ArmorObjMesh.Color ledColor(float speedProgress) {
		FlightLightColorCurve.Rgb actualSpeedColor = FlightLightColorCurve.colorAt(speedProgress);
		return new ArmorObjMesh.Color(actualSpeedColor.red(), actualSpeedColor.green(), actualSpeedColor.blue(), 255);

	}
	/** 按攻击类型只强化对应硬件分区，全部保持 FULL_BRIGHT，不用降亮度表达阶段。 */
	private static ArmorObjMesh.Color weaponLedColor(LivingEntity entity, String groupName,
			float partialTick, ArmorObjMesh.Color baseColor) {
		LivingEntity player = entity;
		boolean forearmWeaponLed = groupName.contains("forearm_energy_trace")
				|| groupName.contains("forearm_node")
				|| groupName.contains("emitter_track");
		if (forearmWeaponLed) {
			float blade = ArmorCombatClient.bladeProgress(player, partialTick);
			boolean leftGroup = groupName.contains("arm_left_") || groupName.contains("left_arm_");
			ArmorVariant bladeVariant = ArmorCombatClient.bladeVariant(player);
			if (blade > 0.001F && (!leftGroup || bladeVariant == ArmorVariant.SWIFT)) {
				ArmorObjMesh.Color active = switch (bladeVariant) {
					case VANGUARD -> new ArmorObjMesh.Color(255, 112, 26, 255);
					case SWIFT -> new ArmorObjMesh.Color(255, 42, 224, 255);
					case SENTINEL -> new ArmorObjMesh.Color(38, 238, 255, 255);
				};
				ArmorObjMesh.Color hot = new ArmorObjMesh.Color(236, 255, 255, 255);
				float hotBlend = smoothstep(0.68F, 1.0F, blade)
						* (bladeVariant == ArmorVariant.SENTINEL ? 0.72F : 0.42F);
				active = mixColor(active, hot, hotBlend);
				return mixColor(baseColor, active, smoothstep(0.08F, 0.58F, blade));
			}
		}
		dev.codex.flightarmor.combat.ArmorAttackType attackType = ArmorCombatClient.attackType(player);
		if (attackType == dev.codex.flightarmor.combat.ArmorAttackType.CORE_BEAM) {
			boolean core = groupName.contains("core_reactor") || groupName.contains("body_chest_trace");
			boolean face = groupName.contains("faceplate_core") || groupName.contains("temple_");
			boolean stableSentinelOverdrive =
					resolveArmorVariant(player, EquipmentSlot.CHEST) == ArmorVariant.SENTINEL
					&& ArmorConfigurationAccess.configuredSentinel(player).isPresent()
					&& sentinelOverdriveProgress(player, player.tickCount + partialTick) >= 0.999F;
			boolean wingCannon = stableSentinelOverdrive
					&& (groupName.contains("wing_cannon_muzzle")
					|| groupName.contains("wing_root_node")
					|| groupName.contains("wing_primary_node")
					|| groupName.contains("wing_secondary_node")
					|| groupName.contains("wing_tip_node"));
			if (!core && !face && !wingCannon) return baseColor;
			float progress = ArmorCombatClient.phaseProgress(player, partialTick);
			float release = ArmorCombatClient.coreReleaseImpulse(player, partialTick);
			float strength = switch (ArmorCombatClient.phase(player)) {
				case CHARGING, WINDUP -> progress;
				case FIRING -> 1.0F;
				case RECOVERY -> 1.0F - progress;
				case IDLE -> release;
			};
			strength = Mth.clamp(Math.max(strength, release), 0.0F, 1.0F);
			if (face) strength = smoothstep(0.72F, 1.0F, strength);
			ArmorObjMesh.Color cyan = new ArmorObjMesh.Color(38, 238, 255, 255);
			ArmorObjMesh.Color violet = new ArmorObjMesh.Color(166, 58, 255, 255);
			ArmorObjMesh.Color white = new ArmorObjMesh.Color(255, 240, 255, 255);
			ArmorObjMesh.Color charged = mixColor(cyan, violet, smoothstep(0.18F, 0.70F, strength));
			charged = mixColor(charged, white, smoothstep(0.74F, 1.0F, strength));
			if (wingCannon) {
				// Mechanical wing nodes share the chest-cannon charge bus, while
				// retaining a violet edge at full charge instead of flattening to white.
				ArmorObjMesh.Color whiteViolet =
						mixColor(violet, white, 0.72F);
				charged = mixColor(charged, whiteViolet,
						smoothstep(0.88F, 1.0F, strength));
			}
			return mixColor(baseColor, charged, smoothstep(0.02F, 0.42F, strength));
		}
		if (attackType == dev.codex.flightarmor.combat.ArmorAttackType.GRAVITY_DIVE_IMPACT) {
			boolean boot = groupName.contains("boot_") && (groupName.contains("sole_field")
					|| groupName.contains("gravity_ring") || groupName.contains("energy_trace"));
			boolean thigh = groupName.contains("leg_") && groupName.contains("thigh_energy_trace");
			if (!boot && !thigh) return baseColor;
			float impact = ArmorCombatClient.diveImpactImpulse(player, partialTick);
			ArmorObjMesh.Color impactOrange = new ArmorObjMesh.Color(255, 92, 24, 255);
			ArmorObjMesh.Color impactMagenta = new ArmorObjMesh.Color(255, 32, 112, 255);
			ArmorObjMesh.Color impactWhite = new ArmorObjMesh.Color(255, 246, 255, 255);
			ArmorObjMesh.Color impactColor = mixColor(impactOrange, impactMagenta,
					smoothstep(0.18F, 0.72F, impact));
			impactColor = mixColor(impactColor, impactWhite, smoothstep(0.76F, 1.0F, impact));
			return mixColor(baseColor, impactColor, smoothstep(0.02F, 0.55F, impact));
		}
		if (!forearmWeaponLed) return baseColor;
		boolean leftGroup = groupName.contains("arm_left_") || groupName.contains("left_arm_");
		boolean rightGroup = groupName.contains("arm_right_") || groupName.contains("right_arm_");
		float pulse = leftGroup
				? ArmorCombatClient.leftRecoil(player, partialTick)
				: rightGroup ? ArmorCombatClient.rightRecoil(player, partialTick) : 0.0F;
		if (pulse <= 0.001F) return baseColor;
		ArmorObjMesh.Color chargedViolet = new ArmorObjMesh.Color(190, 76, 255, 255);
		ArmorObjMesh.Color releaseWhite = new ArmorObjMesh.Color(255, 246, 255, 255);
		ArmorObjMesh.Color pulseColor = mixColor(chargedViolet, releaseWhite,
				smoothstep(0.38F, 0.92F, pulse));
		return mixColor(baseColor, pulseColor, smoothstep(0.04F, 0.72F, pulse));
	}

	/**
	 * 独立守卫使用常亮色相表达状态：搜索蓝、瞄准紫、开火白红、冷却青绿。
	 * 亮度始终由 FULL_BRIGHT 保证，阶段变化只改变颜色，不用变暗伪装冷却。
	 */
	private static ArmorObjMesh.Color cruiseCombatLedColor(LivingEntity entity, String groupName,
			float partialTick, ArmorObjMesh.Color baseColor) {
		boolean cruiseGuard = CruiseCombatClient.modeOfCarrier(entity) == CruiseCombatMode.GUARD;
		boolean escortGuard = ArmorEscortClient.isEscortCarrier(entity)
				&& ArmorEscortClient.phaseOf(entity) == ArmorEscortStatePayload.EscortPhase.ESCORTING
				&& ArmorEscortClient.guardEnabled(entity);
		if (!cruiseGuard && !escortGuard) return baseColor;
		CruiseCombatBehavior behavior = escortGuard && !cruiseGuard
				? CruiseCombatBehavior.SEARCHING
				: CruiseCombatClient.behaviorOfCarrier(entity);
		float progress = CruiseCombatClient.behaviorProgress(entity, partialTick);
		float pulse = 0.5F + 0.5F * Mth.sin((entity.tickCount + partialTick) * 0.52F);
		String name = groupName.toLowerCase(Locale.ROOT);
		boolean sensor = name.contains("core_reactor") || name.contains("body_chest_trace")
				|| name.contains("faceplate_core") || name.contains("temple_");
		boolean leftArm = name.contains("arm_left_") || name.contains("left_arm_");
		boolean rightArm = name.contains("arm_right_") || name.contains("right_arm_");

		return switch (behavior) {
			case SEARCHING -> {
				ArmorObjMesh.Color scanCyan = new ArmorObjMesh.Color(54, 244, 255, 255);
				ArmorObjMesh.Color scanBlue = new ArmorObjMesh.Color(46, 142, 255, 255);
				ArmorObjMesh.Color scan = mixColor(scanBlue, scanCyan, 0.20F + pulse * 0.62F);
				yield sensor
						? mixColor(scan, new ArmorObjMesh.Color(210, 250, 255, 255), 0.20F + pulse * 0.18F)
						: scan;
			}
			case AIMING -> {
				ArmorObjMesh.Color aimViolet = new ArmorObjMesh.Color(204, 70, 255, 255);
				ArmorObjMesh.Color lockPink = new ArmorObjMesh.Color(255, 92, 218, 255);
				ArmorObjMesh.Color aim = mixColor(aimViolet, lockPink,
						smoothstep(0.12F, 0.90F, progress));
				yield sensor
						? mixColor(aim, new ArmorObjMesh.Color(255, 232, 255, 255),
								0.28F + smoothstep(0.55F, 1.0F, progress) * 0.42F)
						: aim;
			}
			case FIRING -> {
				boolean firingLeft = CruiseCombatClient.leftHandOfCarrier(entity);
				boolean firingArm = firingLeft ? leftArm : rightArm;
				ArmorObjMesh.Color releaseMagenta = new ArmorObjMesh.Color(255, 46, 126, 255);
				ArmorObjMesh.Color releaseWhite = new ArmorObjMesh.Color(255, 250, 255, 255);
				yield firingArm || sensor
						? releaseWhite
						: mixColor(releaseMagenta, releaseWhite, 0.22F + pulse * 0.20F);
			}
			case COOLDOWN -> {
				ArmorObjMesh.Color coolGreen = new ArmorObjMesh.Color(64, 255, 178, 255);
				ArmorObjMesh.Color coolCyan = new ArmorObjMesh.Color(54, 238, 255, 255);
				yield mixColor(coolGreen, coolCyan,
						0.18F + smoothstep(0.10F, 1.0F, progress) * 0.72F);
			}
			case PASSIVE, INVALID -> baseColor;
		};
	}

	/**
	 * 新整甲贴合时间线：tick 0-2 延续动势，2-3 对正，3-6 从背后整体包覆，
	 * 6-8 分区闭合，8-10 核心封锁。全程不接入分件组装控制器。
	 */
	private static float dockingMechanicalProgress(float progress) {
		float brakeAndAlign = smoothstep(0.0F, DockingTimeline.FRAME_START, progress);
		float frameUnlock = smoothstep(DockingTimeline.FRAME_START, DockingTimeline.WRAP_START, progress);
		float wrap = smoothstep(DockingTimeline.WRAP_START, DockingTimeline.ZONE_LOCK_START, progress);
		float zoneLock = smoothstep(DockingTimeline.ZONE_LOCK_START, DockingTimeline.CORE_START, progress);
		float coreSeal = smoothstep(DockingTimeline.CORE_START, DockingTimeline.END, progress);
		return Mth.clamp(brakeAndAlign * 0.05F + frameUnlock * 0.10F + wrap * 0.50F
				+ zoneLock * 0.30F + coreSeal * 0.05F, 0.0F, 1.0F);
	}

	/** 相位前半收束、后半展开；在载体 UUID 切换的中点保持同一极值。 */
	private static float phasingFoldProgress(float progress) {
		return progress < 0.5F
				? smoothstep(0.0F, 0.5F, progress)
				: 1.0F - smoothstep(0.5F, 1.0F, progress);
	}

	private static float phasingFlightProgress(float progress) {
		if (progress < 0.5F) {
			return Mth.lerp(smoothstep(0.0F, 0.5F, progress), 0.68F, 0.0F);
		}
		// 重入末帧恢复完整悬停结构，匹配下一阶段 RECALLING 的 ENTRY_POSE_TICKS 切入起点。
		return Mth.lerp(smoothstep(0.5F, 1.0F, progress), 0.0F, 0.68F);
	}

	/** 相位色只改变色相；渲染层始终使用 FULL_BRIGHT 与不透明 alpha。 */
	private static ArmorObjMesh.Color phasingLedColor(float progress) {
		ArmorObjMesh.Color phaseBlue = new ArmorObjMesh.Color(32, 116, 255, 255);
		ArmorObjMesh.Color jumpViolet = new ArmorObjMesh.Color(201, 74, 255, 255);
		ArmorObjMesh.Color jumpWhite = new ArmorObjMesh.Color(250, 240, 255, 255);
		ArmorObjMesh.Color reentryCyan = new ArmorObjMesh.Color(38, 238, 255, 255);
		if (progress < 0.42F) {
			return mixColor(phaseBlue, jumpViolet, smoothstep(0.0F, 0.42F, progress));
		}
		if (progress < 0.58F) {
			float flash = 1.0F - Math.abs(progress - 0.50F) / 0.08F;
			return mixColor(jumpViolet, jumpWhite, smoothstep(0.0F, 1.0F, flash));
		}
		return mixColor(jumpViolet, reentryCyan, smoothstep(0.58F, 1.0F, progress));
	}

	/** 持有者阵亡后使用守护琥珀色；仅色相缓慢呼吸，alpha 永远为 255。 */
	private static ArmorObjMesh.Color ownerDownLedColor(float animationTime) {
		ArmorObjMesh.Color guardAmber = new ArmorObjMesh.Color(255, 174, 38, 255);
		ArmorObjMesh.Color guardGold = new ArmorObjMesh.Color(255, 218, 92, 255);
		return mixColor(guardAmber, guardGold, 0.5F + 0.5F * Mth.sin(animationTime * 0.055F));
	}

	/**
	 * 整甲贴合始终使用 FULL_BRIGHT，只以颜色表达能量从返航、锁定到核心接管的过程。
	 * 近距悬停青先压缩成紫白锁定闪光，最终回到常态高亮青色，不以降低亮度伪装进度。
	 */
	/** 高速段保持白紫灼热，急刹切回青蓝，锁定末端用白色磁吸闪光。 */
	private static ArmorObjMesh.Color platformAssembledRecallLedColor(
			PlatformRecallClient.Sample recall, String groupName) {
		ArmorObjMesh.Color launchCyan = new ArmorObjMesh.Color(38, 244, 255, 255);
		ArmorObjMesh.Color sonicViolet = new ArmorObjMesh.Color(185, 58, 255, 255);
		ArmorObjMesh.Color sonicWhite = new ArmorObjMesh.Color(255, 240, 255, 255);
		ArmorObjMesh.Color brakeGold = new ArmorObjMesh.Color(255, 190, 54, 255);
		float rush = recall.hypersonicStrength();
		float brake = recall.brakingStrength();
		ArmorObjMesh.Color color = mixColor(launchCyan, sonicViolet,
				smoothstep(0.05F, 0.68F, rush));
		color = mixColor(color, sonicWhite, smoothstep(0.70F, 1.0F, rush));
		color = mixColor(color, brakeGold, smoothstep(0.06F, 0.84F, brake));
		float settle = smoothstep(3.55F, 4.15F, recall.elapsedTicks());
		return mixColor(color, dockingLedColor(recall.wrapProgress(), groupName), settle);
	}

	private static ArmorObjMesh.Color platformRecallLedColor(PlatformRecallClient.Sample recall,
			EquipmentSlot slot, ArmorAssemblyPart assemblyPart) {
		PlatformRecallClient.PartTrajectory trajectory = recall.partTrajectory(slot, assemblyPart);
		ArmorObjMesh.Color rushViolet = new ArmorObjMesh.Color(176, 54, 255, 255);
		ArmorObjMesh.Color rushWhite = new ArmorObjMesh.Color(255, 246, 255, 255);
		ArmorObjMesh.Color brakeBlue = new ArmorObjMesh.Color(38, 126, 255, 255);
		ArmorObjMesh.Color hoverCyan = new ArmorObjMesh.Color(54, 244, 255, 255);
		ArmorObjMesh.Color color = mixColor(rushViolet, rushWhite,
				smoothstep(0.08F, 0.82F, trajectory.hypersonicStrength()));
		ArmorObjMesh.Color braking = mixColor(brakeBlue, hoverCyan,
				smoothstep(0.12F, 0.92F, trajectory.brakingStrength()));
		color = mixColor(color, braking, smoothstep(0.04F, 0.72F, trajectory.brakingStrength()));
		// 磁吸完成后立即稳定为高亮青色，白色只作为短促锁定闪光，不能一直挂到召回状态过期。
		color = mixColor(color, hoverCyan, smoothstep(0.985F, 1.0F, trajectory.travel()));
		return mixColor(color, rushWhite,
				0.72F * smoothstep(0.08F, 1.0F, trajectory.lockStrength()));
	}

	private static ArmorObjMesh.Color dockingLedColor(float progress, String groupName) {
		// RECALLING 在捕获点已经回到悬停青；DOCKING 第 0 tick 必须保持同色，不能再跳回高速蓝。
		ArmorObjMesh.Color approachCyan = new ArmorObjMesh.Color(54, 244, 255, 255);
		ArmorObjMesh.Color lockViolet = new ArmorObjMesh.Color(188, 92, 255, 255);
		ArmorObjMesh.Color contactWhite = new ArmorObjMesh.Color(255, 240, 198, 255);
		ArmorObjMesh.Color onlineCyan = new ArmorObjMesh.Color(54, 244, 255, 255);
		ArmorObjMesh.Color color = mixColor(approachCyan, lockViolet,
				smoothstep(DockingTimeline.FRAME_START, DockingTimeline.WRAP_START, progress));
		color = mixColor(color, contactWhite,
				smoothstep(DockingTimeline.ZONE_LOCK_START, DockingTimeline.CORE_START, progress));
		boolean core = groupName.contains("core_reactor") || groupName.contains("core_housing")
				|| groupName.contains("core_cradle");
		if (core && progress >= DockingTimeline.CORE_START) {
			// 棱形核心先整面白闪，再由中心青色接管；全程 FULL_BRIGHT。
			float whiteFlash = 1.0F - smoothstep(DockingTimeline.CORE_START,
					DockingTimeline.CORE_TRANSFER, progress);
			color = mixColor(onlineCyan, contactWhite, whiteFlash);
		}
		return mixColor(color, onlineCyan, smoothstep(core ? DockingTimeline.CORE_TRANSFER
				: DockingTimeline.CORE_START, DockingTimeline.END, progress));
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static ArmorObjMesh.Color mixColor(ArmorObjMesh.Color from, ArmorObjMesh.Color to, float progress) {
		float blend = Mth.clamp(progress, 0.0F, 1.0F);
		return new ArmorObjMesh.Color(
				Math.round(Mth.lerp(blend, from.red(), to.red())),
				Math.round(Mth.lerp(blend, from.green(), to.green())),
				Math.round(Mth.lerp(blend, from.blue(), to.blue())),
				255);
	}

	/**
	 * 视觉 tick 8 起，闭合完成的整甲分区直接挂在真实 PlayerModel 上。这样移动、转头、蹲姿和步态都由玩家
	 * 当前骨骼天然驱动；载体端按同一分区阈值退出，最终装备包到达后普通 ArmorRenderer 无缝接管。
	 */
	/**
	 * Player-owned half of cruise deployment. Every OBJ region uses the same
	 * opening clock, so the suit opens as one mechanism instead of four slots.
	 */
	private static final class DeploymentArmorOverlay
			extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
		private DeploymentArmorOverlay(
				RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
			super(parent);
		}

		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
				AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
				float ageInTicks, float netHeadYaw, float headPitch) {
			CruiseDeploymentTimeline.Sample sample =
					CruiseDeploymentClient.playerSample(player, partialTick);
			if (sample == null) return;
			ArmorVariant armorVariant = resolveArmorVariant(player, EquipmentSlot.CHEST);
			ArmorObjMesh mesh = FlightArmorObjResources.mesh(armorVariant);
			if (mesh.groups().isEmpty()) return;

			HumanoidModel<?> model = getParentModel();
			float animationTime = player.tickCount + partialTick;
			float openProgress = Mth.lerp(sample.open(),
					DockingTimeline.FRAME_START, DockingTimeline.WRAP_START);
			VertexConsumer base = ItemRenderer.getArmorFoilBuffer(buffers,
					RenderType.armorCutoutNoCull(BASE_TEXTURE), false);
			for (ArmorObjMesh.Group group : mesh.groups()) {
				GroupBinding binding = GroupBinding.from(group.name());
				if (binding == null || isLed(group.name())) continue;
				renderGroup(mesh, group, binding, model, poseStack, base, player, packedLight,
						variantBaseColor(armorVariant, group.name(), colorFor(group.name(), 1.0F)),
						0.0F, 0.0F, 0.0F, animationTime,
						FlightPoseFrame.ZERO, openProgress, null, 1.0F, false);
			}

			VertexConsumer emissive = buffers.getBuffer(
					RenderType.entityTranslucentEmissive(EMISSIVE_TEXTURE));
			for (ArmorObjMesh.Group group : mesh.groups()) {
				GroupBinding binding = GroupBinding.from(group.name());
				if (binding == null || !isLed(group.name())) continue;
				renderGroup(mesh, group, binding, model, poseStack, emissive, player,
						LightTexture.FULL_BRIGHT, variantLedColor(
								player, armorVariant, group.name(),
								dockingLedColor(openProgress, group.name()), 0.0F),
						0.0F, 0.0F, 0.0F, animationTime, FlightPoseFrame.ZERO,
						openProgress, null, 1.0F, false);
			}
		}
	}
	private static final class DockingArmorOverlay
			extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
		private DockingArmorOverlay(
				RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
			super(parent);
		}

		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
				AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
				float ageInTicks, float netHeadYaw, float headPitch) {
			float progress = ArmorCruiseClient.dockingOverlayProgress(player, partialTick);
			if (progress < 0.0F) return;
			ArmorVariant armorVariant = resolveArmorVariant(player, EquipmentSlot.CHEST);
			ArmorObjMesh mesh = FlightArmorObjResources.mesh(armorVariant);
			if (mesh.groups().isEmpty()) return;

			HumanoidModel<?> model = getParentModel();
			FlightPoseFrame flightPose = FlightJointAnimation.poseFor(player, partialTick);
			float animationTime = player.tickCount + partialTick;
			VertexConsumer base = ItemRenderer.getArmorFoilBuffer(buffers,
					RenderType.armorCutoutNoCull(BASE_TEXTURE), false);
			for (ArmorObjMesh.Group group : mesh.groups()) {
				GroupBinding binding = GroupBinding.from(group.name());
				if (binding == null || isLed(group.name())
						|| hasSyncedArmorSlot(player, binding.slot())
						|| !dockingRegionTransferredToOwner(group.name(), binding, progress)) continue;
				renderGroup(mesh, group, binding, model, poseStack, base, player, packedLight,
						variantBaseColor(armorVariant, group.name(), colorFor(group.name(), 1.0F)),
						0.0F, 0.0F, 0.0F, animationTime,
						flightPose, progress, null, 1.0F, false);
			}

			VertexConsumer emissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(EMISSIVE_TEXTURE));
			for (ArmorObjMesh.Group group : mesh.groups()) {
				GroupBinding binding = GroupBinding.from(group.name());
				if (binding == null || !isLed(group.name())
						|| hasSyncedArmorSlot(player, binding.slot())
						|| !dockingRegionTransferredToOwner(group.name(), binding, progress)) continue;
				renderGroup(mesh, group, binding, model, poseStack, emissive, player, LightTexture.FULL_BRIGHT,
						variantLedColor(player, armorVariant, group.name(),
								dockingLedColor(progress, group.name()), 0.0F),
						0.0F, 0.0F, 0.0F, animationTime,
						flightPose, progress, null, 1.0F, false);
			}
		}

		private static boolean hasSyncedArmorSlot(AbstractClientPlayer player, EquipmentSlot slot) {
			ItemStack stack = player.getItemBySlot(slot);
			return switch (slot) {
				case HEAD -> stack.is(ModItems.FLIGHT_HELMET);
				case CHEST -> stack.is(ModItems.FLIGHT_CHESTPLATE);
				case LEGS -> stack.is(ModItems.FLIGHT_LEGGINGS);
				case FEET -> stack.is(ModItems.FLIGHT_BOOTS);
				default -> false;
			};
		}
	}

	/** Full-size deployed blades follow the rendered player arm and lower-arm bend. */
	private static final class ArmMountedBladeLayer
			extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
		private ArmMountedBladeLayer(
				RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
			super(parent);
		}

		@Override
		public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
				AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
				float ageInTicks, float netHeadYaw, float headPitch) {
			if (player == Minecraft.getInstance().player
					&& Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
				return;
			}
			PlayerModel<AbstractClientPlayer> model = getParentModel();
			ArmMountedBladeRenderer.renderThirdPerson(
					poseStack, buffers, player, model, false, partialTick);
			if (ArmorCombatClient.bladeVariant(player) == ArmorVariant.SWIFT) {
				ArmMountedBladeRenderer.renderThirdPerson(
						poseStack, buffers, player, model, true, partialTick);
			}
		}
	}

	private enum Anchor {
		HEAD,
		BODY,
		LEFT_ARM,
		RIGHT_ARM,
		LEFT_LEG,
		RIGHT_LEG;

		ModelPart part(HumanoidModel<?> model) {
			return switch (this) {
				case HEAD -> model.head;
				case BODY -> model.body;
				case LEFT_ARM -> model.leftArm;
				case RIGHT_ARM -> model.rightArm;
				case LEFT_LEG -> model.leftLeg;
				case RIGHT_LEG -> model.rightLeg;
			};
		}
	}

	private enum LimbSegment {
		RIGID,
		UPPER_ARM,
		ELBOW,
		FOREARM,
		THIGH,
		KNEE,
		SHIN
	}

	private record GroupBinding(Anchor anchor, EquipmentSlot slot, LimbSegment segment) {
		private static GroupBinding from(String rawName) {
			return BINDING_CACHE.computeIfAbsent(rawName, GroupBinding::computeFrom);
		}

		private static GroupBinding computeFrom(String rawName) {
			String name = rawName.toLowerCase(Locale.ROOT);
			if (hasAny(name, "left_boot", "boot_left", "left_ankle", "ankle_left")) {
				return new GroupBinding(Anchor.LEFT_LEG, EquipmentSlot.FEET, LimbSegment.SHIN);
			}
			if (hasAny(name, "right_boot", "boot_right", "right_ankle", "ankle_right")) {
				return new GroupBinding(Anchor.RIGHT_LEG, EquipmentSlot.FEET, LimbSegment.SHIN);
			}
			if (hasAny(name, "left_leg", "leg_left")) {
				return new GroupBinding(Anchor.LEFT_LEG, EquipmentSlot.LEGS,
						name.contains("_knee_") ? LimbSegment.KNEE : name.contains("_shin_") ? LimbSegment.SHIN : LimbSegment.THIGH);
			}
			if (hasAny(name, "right_leg", "leg_right")) {
				return new GroupBinding(Anchor.RIGHT_LEG, EquipmentSlot.LEGS,
						name.contains("_knee_") ? LimbSegment.KNEE : name.contains("_shin_") ? LimbSegment.SHIN : LimbSegment.THIGH);
			}
			if (hasAny(name, "left_arm", "arm_left")) {
				return new GroupBinding(Anchor.LEFT_ARM, EquipmentSlot.CHEST,
						name.contains("_elbow_") ? LimbSegment.ELBOW
								: name.contains("_forearm_") ? LimbSegment.FOREARM : LimbSegment.UPPER_ARM);
			}
			if (hasAny(name, "right_arm", "arm_right")) {
				return new GroupBinding(Anchor.RIGHT_ARM, EquipmentSlot.CHEST,
						name.contains("_elbow_") ? LimbSegment.ELBOW
								: name.contains("_forearm_") ? LimbSegment.FOREARM : LimbSegment.UPPER_ARM);
			}
			if (hasAny(name, "head", "helmet", "visor", "faceplate", "jaw")) {
				return new GroupBinding(Anchor.HEAD, EquipmentSlot.HEAD, LimbSegment.RIGID);
			}
			if (hasAny(name, "body", "chest", "torso", "core", "collar", "spine", "back", "vane", "wing")) {
				return new GroupBinding(Anchor.BODY, EquipmentSlot.CHEST, LimbSegment.RIGID);
			}
			return null;
		}

		private ModelPart part(HumanoidModel<?> model) {
			return anchor.part(model);
		}

		private static boolean hasAny(String value, String... fragments) {
			for (String fragment : fragments) {
				if (value.contains(fragment)) {
					return true;
				}
			}
			return false;
		}
	}
}
