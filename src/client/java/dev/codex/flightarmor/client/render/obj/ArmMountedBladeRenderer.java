package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.codex.flightarmor.client.animation.FlightJointAnimation;
import dev.codex.flightarmor.client.animation.FlightPoseFrame;
import dev.codex.flightarmor.client.combat.ArmorCombatClient;
import dev.codex.flightarmor.client.combat.ArmorEnergyRenderType;
import dev.codex.flightarmor.combat.SentinelBreakerTimeline;
import dev.codex.flightarmor.mixin.RenderTypeInvoker;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the deployed blade in arm-local space.
 *
 * <p>The first-person view model intentionally uses a shorter, more readable
 * silhouette. The third-person weapon is a full-size world model and inherits
 * the real arm rotation plus the same lower-arm bend used by the OBJ armor.
 * Mechanical parts write depth; only the energy field uses the emissive,
 * color-only render layer.</p>
 */
public final class ArmMountedBladeRenderer {
	private static final Rgba GUNMETAL = new Rgba(42, 52, 66, 255);
	private static final Rgba TITANIUM = new Rgba(240, 244, 250, 255);
	private static final Rgba BREAKER_ALLOY = new Rgba(38, 52, 82, 255);
	private static final Rgba BREAKER_PLATE = new Rgba(238, 188, 54, 255);
	private static final Rgba VANGUARD = new Rgba(36, 232, 255, 236);
	private static final Rgba SWIFT = new Rgba(242, 54, 255, 236);
	private static final Rgba VANGUARD_CORE = new Rgba(212, 252, 255, 236);
	private static final Rgba SWIFT_CORE = new Rgba(255, 176, 255, 236);
	private static final Rgba BREAKER_FIELD = new Rgba(42, 238, 218, 136);
	private static final Rgba BREAKER_EDGE = new Rgba(136, 255, 238, 224);
	private static final Rgba BREAKER_LOCK = new Rgba(220, 252, 255, 236);
	private static final double BREAKER_EDGE_BAND_WIDTH = 0.14D;

	/*
	 * Every light blade is centered by construction: BladeShape has no lateral-curve channel,
	 * so a variant may change color and proportions without turning the weapon into a held,
	 * one-sided knife. The narrow root grows into a flat dorsal blade and then converges on the
	 * arm/hand centerline at the tip.
	 */
	private static final BladeShape FIRST_PERSON_VANGUARD = new BladeShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.34D, 0.68D, 0.86D, 1.0D},
			new double[]{0.14D, 0.58D, 0.86D, 0.86D, 0.54D, 0.24D, 0.0D},
			0.048D);
	/* Short stepped back-blade: broad enough to read in first person, never a held knife. */
	private static final BladeShape FIRST_PERSON_SWIFT = new BladeShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.44D, 0.58D, 0.76D, 0.90D, 1.0D},
			new double[]{0.16D, 0.48D, 0.86D, 0.86D, 0.68D, 0.68D, 0.34D, 0.0D},
			0.038D);
	private static final BladeShape WORLD_VANGUARD = new BladeShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.34D, 0.68D, 0.86D, 1.0D},
			new double[]{0.18D, 0.76D, 1.24D, 1.24D, 0.78D, 0.34D, 0.0D},
			0.064D);
	private static final BladeShape WORLD_SWIFT = new BladeShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.44D, 0.58D, 0.76D, 0.90D, 1.0D},
			new double[]{0.18D, 0.58D, 1.05D, 1.05D, 0.82D, 0.82D, 0.40D, 0.0D},
			0.044D);
	private static final Rgba VANGUARD_ALLOY = new Rgba(36, 92, 186, 255);
	private static final Rgba VANGUARD_PLATE = new Rgba(242, 246, 252, 255);
	private static final Rgba SWIFT_ALLOY = new Rgba(72, 48, 148, 255);
	private static final Rgba SWIFT_PLATE = new Rgba(232, 228, 244, 255);

	/* Vanguard: Sleek Gundam Beam Saber / Beam Tonfa blade profile with a cylindrical hilt emitter. */
	private static final BreakerShape FIRST_PERSON_VANGUARD_TRANSFORM_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.06D, 0.16D, 0.52D, 0.80D, 0.94D, 1.0D},
			new double[]{0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D},
			new double[]{0.12D, 0.28D, 0.46D, 0.48D, 0.38D, 0.20D, 0.0D},
			0.042D);
	private static final BreakerShape WORLD_VANGUARD_TRANSFORM_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.06D, 0.16D, 0.52D, 0.80D, 0.94D, 1.0D},
			new double[]{0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D},
			new double[]{0.15D, 0.34D, 0.56D, 0.58D, 0.46D, 0.24D, 0.0D},
			0.050D);
	/* Swift: Sleek Exia / 00 GN Twin Beam Tonfa Katana profile. */
	private static final BreakerShape FIRST_PERSON_SWIFT_TRANSFORM_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.08D, 0.22D, 0.58D, 0.82D, 0.94D, 1.0D},
			new double[]{0.0D, 0.02D, 0.05D, 0.08D, 0.06D, 0.03D, 0.0D},
			new double[]{0.10D, 0.22D, 0.38D, 0.42D, 0.32D, 0.16D, 0.0D},
			0.034D);
	private static final BreakerShape WORLD_SWIFT_TRANSFORM_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.08D, 0.22D, 0.58D, 0.82D, 0.94D, 1.0D},
			new double[]{0.0D, 0.03D, 0.07D, 0.11D, 0.08D, 0.04D, 0.0D},
			new double[]{0.12D, 0.26D, 0.46D, 0.50D, 0.38D, 0.19D, 0.0D},
			0.040D);
	private static final BreakerProfile FIRST_PERSON_VANGUARD_TRANSFORM = new BreakerProfile(
			14.8D, 0.42D, 1.42D, 0.28D, 0.32D, 0.12D, -0.72D,
			6.35D, 3.02D, 9.75D, 3.12D, 0.972D, -0.235D, 16.0D, 0.26D,
			FIRST_PERSON_VANGUARD_TRANSFORM_SHAPE);
	private static final BreakerProfile WORLD_VANGUARD_TRANSFORM = new BreakerProfile(
			20.4D, 0.48D, 1.54D, 0.32D, 0.36D, 0.14D, -0.74D,
			6.22D, 3.08D, 9.95D, 3.18D, 0.985D, -0.172D, 4.0D, 0.28D,
			WORLD_VANGUARD_TRANSFORM_SHAPE);
	private static final BreakerProfile FIRST_PERSON_SWIFT_TRANSFORM = new BreakerProfile(
			13.2D, 0.44D, 1.24D, 0.24D, 0.38D, 0.10D, -0.54D,
			6.48D, 2.96D, 9.52D, 3.04D, 0.980D, -0.198D, 20.0D, 0.30D,
			FIRST_PERSON_SWIFT_TRANSFORM_SHAPE);
	private static final BreakerProfile WORLD_SWIFT_TRANSFORM = new BreakerProfile(
			17.8D, 0.50D, 1.36D, 0.26D, 0.44D, 0.12D, -0.58D,
			6.34D, 3.02D, 9.72D, 3.10D, 0.990D, -0.141D, 8.0D, 0.32D,
			WORLD_SWIFT_TRANSFORM_SHAPE);

	private static final BreakerShape FIRST_PERSON_BREAKER_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.28D,
					0.68D, 0.82D, 0.94D, 1.0D},
			new double[]{0.0D, 0.0D, 0.0D, 0.0D,
					0.0D, 0.0D, 0.0D, 0.0D},
			new double[]{0.16D, 0.34D, 0.72D, 1.00D,
					1.00D, 0.74D, 0.32D, 0.0D},
			0.046D);
	private static final BreakerShape WORLD_BREAKER_SHAPE = new BreakerShape(
			new double[]{0.0D, 0.08D, 0.18D, 0.28D,
					0.68D, 0.82D, 0.94D, 1.0D},
			new double[]{0.0D, 0.0D, 0.0D, 0.0D,
					0.0D, 0.0D, 0.0D, 0.0D},
			new double[]{0.176D, 0.374D, 0.792D, 1.100D,
					1.100D, 0.814D, 0.352D, 0.0D},
			0.054D);
	private static final BreakerProfile FIRST_PERSON_BREAKER = new BreakerProfile(
			12.8D, 0.25D,
			2.15D, 0.34D, 0.38D, 0.14D,
			-1.0D,
			6.25D, 3.10D, 9.65D, 3.20D,
			0.930D, -0.367D, 24.0D, 0.42D,
			FIRST_PERSON_BREAKER_SHAPE);
	private static final BreakerProfile WORLD_BREAKER = new BreakerProfile(
			15.8D, 0.25D,
			2.25D, 0.38D, 0.42D, 0.16D,
			-1.0D,
			6.20D, 3.15D, 9.90D, 3.30D,
			0.985D, -0.174D, -1.0D, 0.44D,
			WORLD_BREAKER_SHAPE);

	private ArmMountedBladeRenderer() {
	}

	public static void bootstrap() {
		ArmorEnergyRenderType.bootstrap();
		if (MechanicalBladeRenderType.INSTANCE == null) {
			throw new IllegalStateException("Arm-mounted blade metal render type unavailable");
		}
	}

	public static void renderFirstPerson(PoseStack poseStack, MultiBufferSource buffers,
			AbstractClientPlayer player, PlayerModel<AbstractClientPlayer> model, boolean leftArm) {
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
		float progress = ArmorCombatClient.bladeProgress(player, partialTick);
		if (!shouldRender(player, leftArm, progress)) return;

		poseStack.pushPose();
		ModelPart arm = leftArm ? model.leftArm : model.rightArm;
		arm.translateAndRotate(poseStack);
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		renderLocal(poseStack, buffers, ArmorCombatClient.bladeVariant(player),
				leftArm, BladeView.FIRST_PERSON, progress, player.tickCount + partialTick);
		poseStack.popPose();
	}

	public static void renderThirdPerson(PoseStack poseStack, MultiBufferSource buffers,
			AbstractClientPlayer player, PlayerModel<AbstractClientPlayer> model,
			boolean leftArm, float partialTick) {
		float progress = ArmorCombatClient.bladeProgress(player, partialTick);
		if (!shouldRender(player, leftArm, progress)) return;

		poseStack.pushPose();
		ModelPart arm = leftArm ? model.leftArm : model.rightArm;
		arm.translateAndRotate(poseStack);
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		FlightPoseFrame pose = FlightJointAnimation.poseFor(player, partialTick);
		float elbow = leftArm ? pose.leftElbowBend() : pose.rightElbowBend();
		if (Math.abs(elbow) > 0.0001F) {
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(elbow));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		}
		renderLocal(poseStack, buffers, ArmorCombatClient.bladeVariant(player),
				leftArm, BladeView.WORLD, progress, player.tickCount + partialTick);
		poseStack.popPose();
	}

	public static void renderAutonomous(PoseStack poseStack, MultiBufferSource buffers,
			LivingEntity carrier, HumanoidModel<LivingEntity> model, ArmorVariant variant,
			boolean leftArm, float partialTick, FlightPoseFrame pose) {
		float progress = ArmorCombatClient.bladeProgress(carrier, partialTick);
		if (!shouldRender(carrier, leftArm, progress)) return;
		poseStack.pushPose();
		ModelPart arm = leftArm ? model.leftArm : model.rightArm;
		arm.translateAndRotate(poseStack);
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		float elbow = leftArm ? pose.leftElbowBend() : pose.rightElbowBend();
		if (Math.abs(elbow) > 0.0001F) {
			poseStack.translate(0.0F, 6.0F, 0.0F);
			poseStack.mulPose(Axis.XP.rotation(elbow));
			poseStack.translate(0.0F, -6.0F, 0.0F);
		}
		renderLocal(poseStack, buffers, variant, leftArm, BladeView.WORLD,
				progress, carrier.tickCount + partialTick);
		poseStack.popPose();
	}
	private static boolean shouldRender(LivingEntity player, boolean leftArm, float progress) {
		return progress > 0.001F && !player.isInvisible()
				&& (!leftArm || ArmorCombatClient.bladeVariant(player) == ArmorVariant.SWIFT);
	}

	private static void renderLocal(PoseStack poseStack, MultiBufferSource buffers,
			ArmorVariant variant, boolean leftArm, BladeView view, float progress, double time) {
		if (variant == ArmorVariant.SENTINEL) {
			renderSentinelBreaker(poseStack, buffers, leftArm, view, progress, time);
			return;
		}
		if (variant == ArmorVariant.VANGUARD) {
			renderVanguardTransformingBlade(poseStack, buffers, leftArm, view, progress, time);
			return;
		}
		renderSwiftTransformingBlades(poseStack, buffers, leftArm, view, progress, time);
	}

	private static void renderDorsalBlade(PoseStack poseStack, MultiBufferSource buffers,
			ArmorVariant variant, boolean leftArm, BladeView view, float progress, double time) {
		MountProfile mount = MountProfile.resolve(view, variant);
		BladeShape shape = BladeShape.resolve(view, variant);
		float panelOpen = smoothstep(0.04F, 0.38F, progress);
		float emitterProgress = smoothstep(0.20F, 0.62F, progress);
		float bladeProgress = smoothstep(0.42F, 0.94F, progress);

		Vec3 direction = new Vec3(0.0D, mount.directionY(), mount.directionZ()).normalize();
		// The blade is geometrically symmetric, so both arms use one stable local +X axis.
		// The two arm bones provide the world-space mirror. Keeping the basis winding identical
		// also prevents a future cull/normal-enabled material from hiding only the left blade.
		Vec3 bladeSide = new Vec3(1.0D, 0.0D, 0.0D);
		Vec3 bladeNormal = direction.cross(new Vec3(1.0D, 0.0D, 0.0D)).normalize();
		/*
		 * The gauntlet stays flush with the arm bone, while the Swift energy face rolls slightly
		 * toward the camera/world viewer. This preserves the hand-back root but avoids presenting
		 * only the paper-thin edge of the field.
		 */
		double energyRollDegrees = variant == ArmorVariant.SWIFT
				? (view == BladeView.FIRST_PERSON ? 24.0D : 10.0D) : 0.0D;
		Vec3 energySide = rotateAroundAxis(bladeSide, direction,
				Math.toRadians(energyRollDegrees * (leftArm ? -1.0D : 1.0D))).normalize();
		Vec3 energyNormal = direction.cross(energySide).normalize();
		Vec3 wrist = new Vec3(0.0D, mount.startY(), mount.startZ());
		Vec3 housingRear = wrist.subtract(direction.scale(mount.housingLength() * 0.58D));
		Vec3 housingFront = wrist.add(direction.scale(mount.housingLength() * 0.42D));
		double panelGap = mount.panelTravel() * panelOpen;
		double halfPanelWidth = mount.housingHalfWidth() * 0.46D;

		VertexConsumer metal = buffers.getBuffer(MechanicalBladeRenderType.INSTANCE);
		// Low dorsal spine remains tight to the glove while two cover plates split by
		// only a few pixels. This reads as armor transformation without becoming a guard.
		renderOrientedBox(metal, poseStack.last(),
				housingRear, housingFront, bladeSide, bladeNormal,
				mount.housingHalfWidth() * 0.18D, mount.housingHalfDepth() * 0.74D,
				GUNMETAL, TITANIUM);
		for (int panelSide = -1; panelSide <= 1; panelSide += 2) {
			Vec3 lateral = bladeSide.scale(panelSide
					* (halfPanelWidth + panelGap * 0.52D));
			Vec3 panelRear = housingRear.add(direction.scale(0.16D)).add(lateral);
			Vec3 panelFront = housingFront.subtract(direction.scale(0.18D)).add(lateral);
			renderOrientedBox(metal, poseStack.last(),
					panelRear, panelFront, bladeSide, bladeNormal,
					halfPanelWidth, mount.housingHalfDepth(),
					TITANIUM, GUNMETAL);
		}

		double emitterLength = Mth.lerp(emitterProgress, 0.38D, mount.emitterLength());
		Vec3 emitterRear = housingFront.subtract(direction.scale(0.12D));
		Vec3 emitterFront = emitterRear.add(direction.scale(emitterLength));
		renderOrientedBox(metal, poseStack.last(), emitterRear, emitterFront,
				bladeSide, bladeNormal,
				mount.housingHalfWidth() * 0.24D,
				mount.housingHalfDepth() * 0.62D,
				GUNMETAL, TITANIUM);
		for (int railSide = -1; railSide <= 1; railSide += 2) {
			Vec3 railOffset = bladeSide.scale(railSide
					* mount.housingHalfWidth() * (0.48D + panelOpen * 0.08D));
			Vec3 railRear = housingFront.subtract(direction.scale(0.05D)).add(railOffset);
			Vec3 railFront = emitterFront.subtract(direction.scale(0.10D)).add(railOffset);
			renderOrientedBox(metal, poseStack.last(), railRear, railFront,
					bladeSide, bladeNormal,
					mount.housingHalfWidth() * 0.075D,
					mount.housingHalfDepth() * 0.38D,
					TITANIUM, GUNMETAL);
		}

		Rgba field = switch (variant) {
			case VANGUARD -> VANGUARD;
			case SWIFT -> SWIFT;
			case SENTINEL -> throw new IllegalArgumentException(
					"Sentinel uses its dedicated breaker renderer");
		};
		Rgba core = switch (variant) {
			case VANGUARD -> VANGUARD_CORE;
			case SWIFT -> SWIFT_CORE;
			case SENTINEL -> throw new IllegalArgumentException(
					"Sentinel uses its dedicated breaker renderer");
		};
		double bladeLength = mount.bladeLength() * easeOut(bladeProgress);
		Vec3 bladeStart = emitterFront.add(direction.scale(0.12D));

		/*
		 * The field does not float directly out of the glove. A three-stage metal spar
		 * telescopes through the emitter first, keeping the root visibly connected to
		 * the dorsal forearm housing in both cameras.
		 */
		if (bladeProgress > 0.001F) {
			double sparProgress = easeOut(smoothstep(0.02F, 0.72F, bladeProgress));
			double sparLength = bladeLength * mount.sparLengthRatio() * sparProgress;
			Vec3 sparStart = bladeStart.subtract(direction.scale(0.08D));
			Vec3 sparEnd = sparStart.add(direction.scale(sparLength));
			renderOrientedBox(metal, poseStack.last(), sparStart, sparEnd,
					bladeSide, bladeNormal,
					mount.housingHalfWidth() * 0.105D,
					mount.housingHalfDepth() * 0.42D,
					TITANIUM, GUNMETAL);
			Vec3 sparTipStart = sparStart.add(direction.scale(sparLength * 0.58D));
			Vec3 sparTipEnd = sparStart.add(direction.scale(sparLength * 1.06D));
			renderOrientedBox(metal, poseStack.last(), sparTipStart, sparTipEnd,
					bladeSide, bladeNormal,
					mount.housingHalfWidth() * 0.065D,
					mount.housingHalfDepth() * 0.30D,
					GUNMETAL, TITANIUM);
		}

		/*
		 * Immediate buffer sources finish the previous RenderType as soon as another one is
		 * requested. All mechanical vertices must therefore be emitted before acquiring the
		 * emissive consumer; reusing the old metal consumer afterwards throws "Not building!".
		 */
		VertexConsumer energy = buffers.getBuffer(ArmorEnergyRenderType.INSTANCE);
		renderEmitterSlot(energy, poseStack.last(), emitterFront, direction,
				energySide, energyNormal,
				mount.housingHalfWidth() * (0.58D + panelOpen * 0.06D),
				mount.housingHalfDepth() * 0.34D, field, core);

		if (bladeProgress <= 0.001F) return;
		double pulse = 0.96D + Math.sin(time * 0.42D + (leftArm ? 1.7D : 0.0D)) * 0.04D;

		renderBladeInsetStrip(energy, poseStack.last(), bladeStart, direction, energySide,
				energyNormal, bladeLength, shape, pulse, field.alpha(188));
		renderBladeEdgeStrip(energy, poseStack.last(), bladeStart, direction, energySide,
				energyNormal, bladeLength, shape, pulse, core);
	}

	/**
	 * Sentinel uses one integrated dorsal weapon rather than a held hilt. Its wide receiver
	 * follows the forearm, while the blade has an independent camera-authored axis. The shell,
	 * carriage and three short spine sections are the only solid parts; the full-width blade is
	 * one translucent cyan surface with two narrow luminous edges.
	 */
	private static void renderSentinelBreaker(PoseStack poseStack, MultiBufferSource buffers,
			boolean leftArm, BladeView view, float progress, double time) {
		BreakerProfile profile = view == BladeView.FIRST_PERSON
				? FIRST_PERSON_BREAKER : WORLD_BREAKER;
		SentinelBreakerTimeline.Sample deployment = SentinelBreakerTimeline.sample(progress);
		double centerX = leftArm ? -profile.centerX() : profile.centerX();

		Vec3 receiverRear = new Vec3(centerX,
				profile.receiverRearY(), profile.receiverRearZ());
		Vec3 receiverFront = new Vec3(centerX,
				profile.receiverFrontY(), profile.receiverFrontZ());
		Vec3 receiverDirection = receiverFront.subtract(receiverRear).normalize();
		Vec3 receiverSide = new Vec3(1.0D, 0.0D, 0.0D);
		Vec3 receiverNormal = receiverSide.cross(receiverDirection).normalize();

		Vec3 bladeDirection = new Vec3(
				0.0D, profile.bladeDirectionY(), profile.bladeDirectionZ()).normalize();
		Vec3 mechanicalSide = receiverSide;
		Vec3 mechanicalNormal = mechanicalSide.cross(bladeDirection).normalize();
		Vec3 energySide = rotateAroundAxis(mechanicalSide, bladeDirection,
				Math.toRadians(profile.rollDegrees() * (leftArm ? -1.0D : 1.0D))).normalize();
		Vec3 energyNormal = energySide.cross(bladeDirection).normalize();

		VertexConsumer metal = buffers.getBuffer(MechanicalBladeRenderType.INSTANCE);
		float receiverRootReveal = smoothstep(0.00F, 0.52F, deployment.unlock());
		float receiverMiddleReveal = smoothstep(0.20F, 0.78F, deployment.unlock());
		float receiverFrontReveal = smoothstep(0.46F, 1.00F, deployment.unlock());
		renderReceiverSleeveSegment(metal, poseStack.last(),
				receiverRear, receiverFront, receiverSide, receiverNormal,
				profile, 0.00D, 0.30D, receiverRootReveal,
				BREAKER_ALLOY, BREAKER_PLATE);
		renderReceiverSleeveSegment(metal, poseStack.last(),
				receiverRear, receiverFront, receiverSide, receiverNormal,
				profile, 0.35D, 0.64D, receiverMiddleReveal,
				BREAKER_PLATE, BREAKER_ALLOY);
		renderReceiverSleeveSegment(metal, poseStack.last(),
				receiverRear, receiverFront, receiverSide, receiverNormal,
				profile, 0.69D, 1.00D, receiverFrontReveal,
				BREAKER_ALLOY, BREAKER_PLATE);

		double open = deployment.jawOpen() * (1.0D - deployment.lock() * 0.56D);
		float jawReveal = smoothstep(0.02F, 0.82F,
				Math.max(deployment.unlock(), deployment.jawOpen()));
		double jawAngle = Math.toRadians(
				18.0D * deployment.unlock() - 5.0D * deployment.lock());
		if (jawReveal > 0.001F) {
			for (int jawSide = -1; jawSide <= 1; jawSide += 2) {
				Vec3 panelSide = rotateAroundAxis(
						receiverSide, receiverDirection, jawSide * jawAngle).normalize();
				Vec3 panelNormal = receiverDirection.cross(panelSide).normalize();
				Vec3 lateral = receiverSide.scale(jawSide
						* (profile.receiverHalfWidth() * 0.70D + profile.jawTravel() * open));
				Vec3 lift = receiverNormal.scale(profile.jawLift() * open);
				Vec3 jawRear = receiverRear.add(receiverDirection.scale(0.20D))
						.add(lateral).add(lift);
				Vec3 jawTarget = receiverFront.subtract(receiverDirection.scale(0.18D))
						.add(lateral).add(lift);
				Vec3 jawFront = jawRear.lerp(jawTarget, easeOut(jawReveal));
				renderOrientedBox(metal, poseStack.last(), jawRear, jawFront,
						panelSide, panelNormal,
						profile.receiverHalfWidth() * 0.25D,
						profile.receiverHalfDepth() * 0.92D,
						BREAKER_PLATE, BREAKER_ALLOY);
			}
		}

		double carriageTravel = profile.carriageTravel() * deployment.carriage();
		float carriageReveal = deployment.carriage();
		double carriageEase = easeOut(carriageReveal);
		Vec3 carriageRear = receiverFront.subtract(
				receiverDirection.scale(0.20D * carriageEase));
		Vec3 carriageFront = receiverFront.add(
				receiverDirection.scale((0.46D + carriageTravel) * carriageEase));
		if (carriageReveal > 0.001F) {
			renderOrientedBox(metal, poseStack.last(), carriageRear, carriageFront,
					mechanicalSide, mechanicalNormal,
					profile.receiverHalfWidth() * 0.28D,
					profile.receiverHalfDepth() * 0.62D,
					TITANIUM, BREAKER_ALLOY);
			for (int railSide = -1; railSide <= 1; railSide += 2) {
				Vec3 railOffset = mechanicalSide.scale(
						railSide * profile.receiverHalfWidth() * 0.58D);
				renderOrientedBox(metal, poseStack.last(),
						carriageRear.add(railOffset), carriageFront.add(railOffset),
						mechanicalSide, mechanicalNormal,
						profile.receiverHalfWidth() * 0.075D,
						profile.receiverHalfDepth() * 0.36D,
						BREAKER_PLATE, BREAKER_ALLOY);
			}
		}

		Vec3 breakerStart = carriageFront.add(bladeDirection.scale(0.10D));
		double mechanicalSpineLength =
				profile.bladeLength() * profile.mechanicalSpineRatio();
		renderBreakerSpineSegment(metal, poseStack.last(), breakerStart,
				bladeDirection, mechanicalSide, mechanicalNormal, mechanicalSpineLength,
				0.00D, 0.37D, deployment.spineBase(),
				profile.receiverHalfWidth() * 0.17D,
				profile.receiverHalfDepth() * 0.52D);
		renderBreakerSpineSegment(metal, poseStack.last(), breakerStart,
				bladeDirection, mechanicalSide, mechanicalNormal, mechanicalSpineLength,
				0.28D, 0.70D, deployment.spineMiddle(),
				profile.receiverHalfWidth() * 0.13D,
				profile.receiverHalfDepth() * 0.43D);
		renderBreakerSpineSegment(metal, poseStack.last(), breakerStart,
				bladeDirection, mechanicalSide, mechanicalNormal, mechanicalSpineLength,
				0.60D, 1.00D, deployment.spineTip(),
				profile.receiverHalfWidth() * 0.09D,
				profile.receiverHalfDepth() * 0.34D);

		/*
		 * Crash invariant: every sleeve, jaw, rail and spine metal vertex is submitted
		 * before the immediate buffer source is switched to either energy RenderType.
		 * Once the translucent field begins, this method must never write metal again.
		 */
		double pulse = 0.94D + Math.sin(time * 0.34D) * 0.06D;
		Rgba field = mix(BREAKER_FIELD, BREAKER_EDGE,
				deployment.lock() * 0.12F)
				.alpha((int) Math.round(88.0D + pulse * 24.0D));
		Rgba edge = mix(BREAKER_EDGE, BREAKER_LOCK, deployment.lock() * 0.34F)
				.alpha((int) Math.round(198.0D + pulse * 18.0D));
		if (deployment.breakerHead() > 0.001F) {
			VertexConsumer fieldEnergy =
					buffers.getBuffer(ArmorEnergyRenderType.FIELD_ALPHA);
			renderBreakerEnergyField(fieldEnergy, poseStack.last(), breakerStart,
					bladeDirection, energySide, energyNormal, profile.bladeLength(),
					profile.shape(), deployment.breakerHead(), field);
		}
		VertexConsumer energy = buffers.getBuffer(ArmorEnergyRenderType.INSTANCE);
		renderBreakerNode(energy, poseStack.last(), carriageFront,
				bladeDirection, mechanicalSide, mechanicalNormal,
				profile.receiverHalfWidth() * 0.42D,
				profile.receiverHalfDepth() * 0.74D, carriageReveal, edge);
		if (deployment.energyEdge() > 0.001F) {
			renderBreakerEdgeInset(energy, poseStack.last(), breakerStart,
					bladeDirection, energySide, energyNormal, profile.bladeLength(),
					profile.shape(), deployment.energyEdge(), edge);
		}
	}

	private static void renderReceiverSleeveSegment(VertexConsumer vertices,
			PoseStack.Pose pose, Vec3 rear, Vec3 front, Vec3 side, Vec3 normal,
			BreakerProfile profile, double startStation, double endStation,
			float reveal, Rgba topColor, Rgba sideColor) {
		if (reveal <= 0.001F) return;
		Vec3 segmentRear = rear.lerp(front, startStation);
		double revealedEnd = Mth.lerp(easeOut(reveal), startStation, endStation);
		Vec3 segmentFront = rear.lerp(front, revealedEnd);
		renderOrientedBox(vertices, pose, segmentRear, segmentFront, side, normal,
				profile.receiverHalfWidth() * 0.82D,
				profile.receiverHalfDepth() * 0.72D,
				topColor, sideColor);
	}

	private static void renderBreakerSpineSegment(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double fullLength,
			double startStation, double endStation, float progress,
			double halfWidth, double halfDepth) {
		if (progress <= 0.001F) return;
		Vec3 segmentStart = start.add(direction.scale(fullLength * startStation));
		Vec3 segmentEnd = segmentStart.add(direction.scale(
				fullLength * (endStation - startStation) * easeOut(progress)));
		renderOrientedBox(vertices, pose, segmentStart, segmentEnd,
				side, normal, halfWidth, halfDepth, TITANIUM, BREAKER_ALLOY);
	}

	private static void renderBreakerEnergyField(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double fullLength,
			BreakerShape shape, float deployment, Rgba color) {
		double deployedStation = Mth.clamp(deployment, 0.0F, 1.0F);
		if (deployedStation <= 0.001D) return;
		Vec3 surface = normal.scale(shape.surfaceOffset());
		for (int index = 0; index < shape.stations().length - 1; index++) {
			double station0 = shape.stations()[index];
			double station1 = shape.stations()[index + 1];
			if (deployedStation <= station0) break;
			double clippedStation = Math.min(station1, deployedStation);
			BreakerSection section0 = breakerSectionAt(shape, station0);
			BreakerSection section1 = breakerSectionAt(shape, clippedStation);
			Vec3 center0 = start.add(direction.scale(fullLength * section0.station()))
					.add(side.scale(section0.centerOffset()));
			Vec3 center1 = start.add(direction.scale(fullLength * section1.station()))
					.add(side.scale(section1.centerOffset()));
			Vec3 inner0 = center0.subtract(side.scale(section0.halfWidth()));
			Vec3 outer0 = center0.add(side.scale(section0.halfWidth()));
			Vec3 inner1 = center1.subtract(side.scale(section1.halfWidth()));
			Vec3 outer1 = center1.add(side.scale(section1.halfWidth()));
			emitQuad(vertices, pose,
					inner0.add(surface), outer0.add(surface),
					outer1.add(surface), inner1.add(surface),
					color, color);
			if (clippedStation >= deployedStation - 1.0E-6D) break;
		}
	}

	/**
	 * Two exact 0.14-model-pixel bands trace the translucent face. There is no
	 * back-face volume or near-white core, so the blade remains a thin armor energy
	 * projection rather than an opaque held wedge.
	 */
	private static void renderBreakerEdgeInset(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double fullLength,
			BreakerShape shape, float deployment, Rgba color) {
		double deployedStation = Mth.clamp(deployment, 0.0F, 1.0F);
		Vec3 surface = normal.scale(shape.surfaceOffset() + 0.012D);
		for (int index = 0; index < shape.stations().length - 1; index++) {
			double station0 = shape.stations()[index];
			double station1 = shape.stations()[index + 1];
			if (deployedStation <= station0) break;
			double clippedStation = Math.min(station1, deployedStation);
			BreakerSection section0 = breakerSectionAt(shape, station0);
			BreakerSection section1 = breakerSectionAt(shape, clippedStation);
			Vec3 center0 = start.add(direction.scale(fullLength * section0.station()))
					.add(side.scale(section0.centerOffset()));
			Vec3 center1 = start.add(direction.scale(fullLength * section1.station()))
					.add(side.scale(section1.centerOffset()));
			double band0 = Math.min(BREAKER_EDGE_BAND_WIDTH, section0.halfWidth());
			double band1 = Math.min(BREAKER_EDGE_BAND_WIDTH, section1.halfWidth());
			Vec3 leftOuter0 = center0.subtract(side.scale(section0.halfWidth())).add(surface);
			Vec3 leftInner0 = center0.subtract(
					side.scale(Math.max(0.0D, section0.halfWidth() - band0))).add(surface);
			Vec3 leftOuter1 = center1.subtract(side.scale(section1.halfWidth())).add(surface);
			Vec3 leftInner1 = center1.subtract(
					side.scale(Math.max(0.0D, section1.halfWidth() - band1))).add(surface);
			Vec3 rightInner0 = center0.add(
					side.scale(Math.max(0.0D, section0.halfWidth() - band0))).add(surface);
			Vec3 rightOuter0 = center0.add(side.scale(section0.halfWidth())).add(surface);
			Vec3 rightInner1 = center1.add(
					side.scale(Math.max(0.0D, section1.halfWidth() - band1))).add(surface);
			Vec3 rightOuter1 = center1.add(side.scale(section1.halfWidth())).add(surface);
			emitQuad(vertices, pose,
					leftOuter0, leftInner0, leftInner1, leftOuter1, color, color);
			emitQuad(vertices, pose,
					rightInner0, rightOuter0, rightOuter1, rightInner1, color, color);
			if (clippedStation >= deployedStation - 1.0E-6D) break;
		}
	}

	private static void renderBreakerNode(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 center, Vec3 direction, Vec3 side, Vec3 normal,
			double halfWidth, double normalOffset, float reveal, Rgba color) {
		if (reveal <= 0.001F) return;
		double revealScale = easeOut(reveal);
		Vec3 nodeCenter = center.add(normal.scale(normalOffset + 0.018D));
		Vec3 width = side.scale(halfWidth * revealScale);
		Vec3 length = direction.scale(0.22D * revealScale);
		emitQuad(vertices, pose,
				nodeCenter.subtract(width).subtract(length),
				nodeCenter.add(width).subtract(length),
				nodeCenter.add(width).add(length),
				nodeCenter.subtract(width).add(length),
				color.alpha(164), color);
	}

	private static BreakerSection breakerSectionAt(BreakerShape shape, double station) {
		double clamped = Mth.clamp(station, 0.0D, 1.0D);
		for (int index = 0; index < shape.stations().length - 1; index++) {
			double start = shape.stations()[index];
			double end = shape.stations()[index + 1];
			if (clamped > end && index < shape.stations().length - 2) continue;
			double progress = end <= start ? 0.0D : (clamped - start) / (end - start);
			progress = Mth.clamp(progress, 0.0D, 1.0D);
			return new BreakerSection(clamped,
					Mth.lerp(progress,
							shape.centerOffsets()[index], shape.centerOffsets()[index + 1]),
					Mth.lerp(progress, shape.halfWidths()[index], shape.halfWidths()[index + 1]));
		}
		int last = shape.stations().length - 1;
		return new BreakerSection(clamped,
				shape.centerOffsets()[last], shape.halfWidths()[last]);
	}

	/**
	 * The light blade is centered over the hand-back axis but remains one thin dorsal surface
	 * rather than a glowing volume. The no-cull render type keeps that surface visible from
	 * either side without a second coincident face that would over-brighten it.
	 */
	private static void renderBladeInsetStrip(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double length,
			BladeShape shape, double widthScale, Rgba color) {
		for (int index = 0; index < shape.stations().length - 1; index++) {
			Vec3 center0 = start.add(direction.scale(length * shape.stations()[index]));
			Vec3 center1 = start.add(direction.scale(length * shape.stations()[index + 1]));
			double width0 = shape.widths()[index] * widthScale;
			double width1 = shape.widths()[index + 1] * widthScale;
			Vec3 left0 = center0.subtract(side.scale(width0));
			Vec3 right0 = center0.add(side.scale(width0));
			Vec3 left1 = center1.subtract(side.scale(width1));
			Vec3 right1 = center1.add(side.scale(width1));
			Vec3 surface = normal.scale(shape.thickness());
			emitQuad(vertices, pose,
					left0.add(surface), right0.add(surface),
					right1.add(surface), left1.add(surface),
					color, color);
		}
	}

	private static void renderBladeEdgeStrip(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double length,
			BladeShape shape, double widthScale, Rgba color) {
		Vec3 surface = normal.scale(shape.thickness() + 0.006D);
		for (int index = 0; index < shape.stations().length - 1; index++) {
			Vec3 center0 = start.add(direction.scale(length * shape.stations()[index]));
			Vec3 center1 = start.add(direction.scale(length * shape.stations()[index + 1]));
			double width0 = shape.widths()[index] * widthScale;
			double width1 = shape.widths()[index + 1] * widthScale;
			double band0 = Math.min(0.080D, width0 * 0.24D);
			double band1 = Math.min(0.080D, width1 * 0.24D);
			Vec3 leftOuter0 = center0.subtract(side.scale(width0)).add(surface);
			Vec3 leftInner0 = center0.subtract(side.scale(Math.max(0.0D, width0 - band0)))
					.add(surface);
			Vec3 leftOuter1 = center1.subtract(side.scale(width1)).add(surface);
			Vec3 leftInner1 = center1.subtract(side.scale(Math.max(0.0D, width1 - band1)))
					.add(surface);
			Vec3 rightInner0 = center0.add(side.scale(Math.max(0.0D, width0 - band0)))
					.add(surface);
			Vec3 rightOuter0 = center0.add(side.scale(width0)).add(surface);
			Vec3 rightInner1 = center1.add(side.scale(Math.max(0.0D, width1 - band1)))
					.add(surface);
			Vec3 rightOuter1 = center1.add(side.scale(width1)).add(surface);
			emitQuad(vertices, pose,
					leftOuter0, leftInner0, leftInner1, leftOuter1, color, color);
			emitQuad(vertices, pose,
					rightInner0, rightOuter0, rightOuter1, rightInner1, color, color);
		}
	}

	private static void renderOrientedBox(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 end, Vec3 side, Vec3 normal,
			double halfWidth, double halfDepth, Rgba topColor, Rgba sideColor) {
		if (end.subtract(start).lengthSqr() < 1.0E-8D) return;
		Vec3 width = side.normalize().scale(halfWidth);
		Vec3 depth = normal.normalize().scale(halfDepth);
		Vec3 startLeftBottom = start.subtract(width).subtract(depth);
		Vec3 startRightBottom = start.add(width).subtract(depth);
		Vec3 startRightTop = start.add(width).add(depth);
		Vec3 startLeftTop = start.subtract(width).add(depth);
		Vec3 endLeftBottom = end.subtract(width).subtract(depth);
		Vec3 endRightBottom = end.add(width).subtract(depth);
		Vec3 endRightTop = end.add(width).add(depth);
		Vec3 endLeftTop = end.subtract(width).add(depth);

		emitQuad(vertices, pose,
				startLeftTop, startRightTop, endRightTop, endLeftTop,
				topColor, topColor);
		emitQuad(vertices, pose,
				startRightBottom, startLeftBottom, endLeftBottom, endRightBottom,
				sideColor, sideColor);
		emitQuad(vertices, pose,
				startLeftBottom, startLeftTop, endLeftTop, endLeftBottom,
				sideColor, topColor);
		emitQuad(vertices, pose,
				startRightTop, startRightBottom, endRightBottom, endRightTop,
				topColor, sideColor);
		emitQuad(vertices, pose,
				startLeftBottom, startRightBottom, startRightTop, startLeftTop,
				sideColor, topColor);
		emitQuad(vertices, pose,
				endLeftTop, endRightTop, endRightBottom, endLeftBottom,
				topColor, sideColor);
	}

	private static void renderEmitterSlot(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 center, Vec3 direction, Vec3 side, Vec3 normal,
			double halfWidth, double halfDepth, Rgba field, Rgba core) {
		Vec3 faceCenter = center.add(direction.normalize().scale(0.035D));
		Vec3 outerWidth = side.normalize().scale(halfWidth);
		Vec3 outerDepth = normal.normalize().scale(halfDepth);
		Vec3 innerWidth = outerWidth.scale(0.58D);
		Vec3 innerDepth = outerDepth.scale(0.32D);

		Vec3 outerLeftBottom = faceCenter.subtract(outerWidth).subtract(outerDepth);
		Vec3 outerRightBottom = faceCenter.add(outerWidth).subtract(outerDepth);
		Vec3 outerRightTop = faceCenter.add(outerWidth).add(outerDepth);
		Vec3 outerLeftTop = faceCenter.subtract(outerWidth).add(outerDepth);
		Vec3 innerLeftBottom = faceCenter.subtract(innerWidth).subtract(innerDepth);
		Vec3 innerRightBottom = faceCenter.add(innerWidth).subtract(innerDepth);
		Vec3 innerRightTop = faceCenter.add(innerWidth).add(innerDepth);
		Vec3 innerLeftTop = faceCenter.subtract(innerWidth).add(innerDepth);

		emitQuad(vertices, pose, outerLeftBottom, outerRightBottom,
				innerRightBottom, innerLeftBottom, field.alpha(176), field);
		emitQuad(vertices, pose, innerLeftTop, innerRightTop,
				outerRightTop, outerLeftTop, field, field.alpha(176));
		emitQuad(vertices, pose, outerLeftBottom, innerLeftBottom,
				innerLeftTop, outerLeftTop, field.alpha(176), field);
		emitQuad(vertices, pose, innerRightBottom, outerRightBottom,
				outerRightTop, innerRightTop, field, field.alpha(176));
		emitQuad(vertices, pose, innerLeftBottom, innerRightBottom,
				innerRightTop, innerLeftTop, core.alpha(218), core);
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 first, Vec3 second, Vec3 third, Vec3 fourth,
			Rgba startColor, Rgba endColor) {
		emitVertex(vertices, pose, first, startColor);
		emitVertex(vertices, pose, second, startColor);
		emitVertex(vertices, pose, third, endColor);
		emitVertex(vertices, pose, fourth, endColor);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 position, Rgba color) {
		vertices.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
				.setColor(color.red(), color.green(), color.blue(), color.alpha());
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	private static double easeOut(float value) {
		double clamped = Mth.clamp(value, 0.0F, 1.0F);
		return 1.0D - Math.pow(1.0D - clamped, 3.0D);
	}

	private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 rawAxis, double radians) {
		Vec3 axis = rawAxis.normalize();
		double cosine = Math.cos(radians);
		double sine = Math.sin(radians);
		return vector.scale(cosine)
				.add(axis.cross(vector).scale(sine))
				.add(axis.scale(axis.dot(vector) * (1.0D - cosine)));
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float value = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(
				Math.round(Mth.lerp(value, from.red(), to.red())),
				Math.round(Mth.lerp(value, from.green(), to.green())),
				Math.round(Mth.lerp(value, from.blue(), to.blue())),
				Math.round(Mth.lerp(value, from.alpha(), to.alpha())));
	}

	private static void renderVanguardTransformingBlade(PoseStack poseStack,
			MultiBufferSource buffers, boolean leftArm, BladeView view,
			float progress, double time) {
		BreakerProfile profile = view == BladeView.FIRST_PERSON
				? FIRST_PERSON_VANGUARD_TRANSFORM : WORLD_VANGUARD_TRANSFORM;
		renderVariantTransformingBlade(poseStack, buffers, leftArm, profile,
				progress, time, VANGUARD_ALLOY, VANGUARD_PLATE,
				VANGUARD.alpha(118), VANGUARD_CORE, 15.0D, 0.02D);
	}

	private static void renderSwiftTransformingBlades(PoseStack poseStack,
			MultiBufferSource buffers, boolean leftArm, BladeView view,
			float progress, double time) {
		BreakerProfile profile = view == BladeView.FIRST_PERSON
				? FIRST_PERSON_SWIFT_TRANSFORM : WORLD_SWIFT_TRANSFORM;
		renderVariantTransformingBlade(poseStack, buffers, leftArm, profile,
				progress, time, SWIFT_ALLOY, SWIFT_PLATE,
				SWIFT.alpha(112), SWIFT_CORE, 23.0D, leftArm ? 1.7D : 0.0D);
	}

	/**
	 * Sentinel-derived transformation architecture used by the new Vanguard and Swift weapons.
	 * The silhouettes, receiver dimensions and colors remain variant-specific, but both now
	 * unfold from armor plate instead of appearing as a held knife or a growing flat decal.
	 */
	private static void renderVariantTransformingBlade(PoseStack poseStack,
			MultiBufferSource buffers, boolean leftArm, BreakerProfile profile,
			float progress, double time, Rgba alloy, Rgba plate,
			Rgba fieldBase, Rgba edgeBase, double jawDegrees, double pulseOffset) {
		SentinelBreakerTimeline.Sample deployment = SentinelBreakerTimeline.sample(progress);
		double centerX = leftArm ? -profile.centerX() : profile.centerX();
		Vec3 receiverRear = new Vec3(centerX,
				profile.receiverRearY(), profile.receiverRearZ());
		Vec3 receiverFront = new Vec3(centerX,
				profile.receiverFrontY(), profile.receiverFrontZ());
		Vec3 receiverDirection = receiverFront.subtract(receiverRear).normalize();
		Vec3 receiverSide = new Vec3(1.0D, 0.0D, 0.0D);
		Vec3 receiverNormal = receiverSide.cross(receiverDirection).normalize();
		Vec3 bladeDirection = new Vec3(
				0.0D, profile.bladeDirectionY(), profile.bladeDirectionZ()).normalize();
		Vec3 mechanicalNormal = receiverSide.cross(bladeDirection).normalize();
		Vec3 energySide = rotateAroundAxis(receiverSide, bladeDirection,
				Math.toRadians(profile.rollDegrees() * (leftArm ? -1.0D : 1.0D))).normalize();
		Vec3 energyNormal = energySide.cross(bladeDirection).normalize();

		VertexConsumer metal = buffers.getBuffer(MechanicalBladeRenderType.INSTANCE);
		float rootReveal = smoothstep(0.00F, 0.48F, deployment.unlock());
		float middleReveal = smoothstep(0.18F, 0.76F, deployment.unlock());
		float frontReveal = smoothstep(0.42F, 1.00F, deployment.unlock());
		renderReceiverSleeveSegment(metal, poseStack.last(), receiverRear, receiverFront,
				receiverSide, receiverNormal, profile, 0.00D, 0.31D, rootReveal,
				alloy, plate);
		renderReceiverSleeveSegment(metal, poseStack.last(), receiverRear, receiverFront,
				receiverSide, receiverNormal, profile, 0.35D, 0.66D, middleReveal,
				plate, alloy);
		renderReceiverSleeveSegment(metal, poseStack.last(), receiverRear, receiverFront,
				receiverSide, receiverNormal, profile, 0.70D, 1.00D, frontReveal,
				alloy, plate);

		double open = deployment.jawOpen() * (1.0D - deployment.lock() * 0.52D);
		float jawReveal = smoothstep(0.04F, 0.82F,
				Math.max(deployment.unlock(), deployment.jawOpen()));
		double jawAngle = Math.toRadians(
				jawDegrees * deployment.unlock() - 4.0D * deployment.lock());
		if (jawReveal > 0.001F) {
			for (int jawSide = -1; jawSide <= 1; jawSide += 2) {
				Vec3 panelSide = rotateAroundAxis(receiverSide, receiverDirection,
						jawSide * jawAngle).normalize();
				Vec3 panelNormal = receiverDirection.cross(panelSide).normalize();
				Vec3 lateral = receiverSide.scale(jawSide
						* (profile.receiverHalfWidth() * 0.68D
						+ profile.jawTravel() * open));
				Vec3 lift = receiverNormal.scale(profile.jawLift() * open);
				Vec3 jawRear = receiverRear.add(receiverDirection.scale(0.18D))
						.add(lateral).add(lift);
				Vec3 jawTarget = receiverFront.subtract(receiverDirection.scale(0.16D))
						.add(lateral).add(lift);
				Vec3 jawFront = jawRear.lerp(jawTarget, easeOut(jawReveal));
				renderOrientedBox(metal, poseStack.last(), jawRear, jawFront,
						panelSide, panelNormal,
						profile.receiverHalfWidth() * 0.23D,
						profile.receiverHalfDepth() * 0.90D, plate, alloy);
			}
		}

		double carriageEase = easeOut(deployment.carriage());
		double carriageTravel = profile.carriageTravel() * deployment.carriage();
		Vec3 carriageRear = receiverFront.subtract(
				receiverDirection.scale(0.18D * carriageEase));
		Vec3 carriageFront = receiverFront.add(receiverDirection.scale(
				(0.42D + carriageTravel) * carriageEase));
		if (deployment.carriage() > 0.001F) {
			renderOrientedBox(metal, poseStack.last(), carriageRear, carriageFront,
					receiverSide, mechanicalNormal,
					profile.receiverHalfWidth() * 0.27D,
					profile.receiverHalfDepth() * 0.60D, plate, alloy);
			for (int railSide = -1; railSide <= 1; railSide += 2) {
				Vec3 railOffset = receiverSide.scale(
						railSide * profile.receiverHalfWidth() * 0.55D);
				renderOrientedBox(metal, poseStack.last(), carriageRear.add(railOffset),
						carriageFront.add(railOffset), receiverSide, mechanicalNormal,
						profile.receiverHalfWidth() * 0.068D,
						profile.receiverHalfDepth() * 0.34D, plate, alloy);
			}
		}

		Vec3 bladeStart = carriageFront.add(bladeDirection.scale(0.08D));
		double spineLength = profile.bladeLength() * profile.mechanicalSpineRatio();
		renderVariantSpineSegment(metal, poseStack.last(), bladeStart, bladeDirection,
				receiverSide, mechanicalNormal, spineLength, 0.00D, 0.38D,
				deployment.spineBase(), profile.receiverHalfWidth() * 0.16D,
				profile.receiverHalfDepth() * 0.48D, plate, alloy);
		renderVariantSpineSegment(metal, poseStack.last(), bladeStart, bladeDirection,
				receiverSide, mechanicalNormal, spineLength, 0.29D, 0.72D,
				deployment.spineMiddle(), profile.receiverHalfWidth() * 0.12D,
				profile.receiverHalfDepth() * 0.39D, alloy, plate);
		renderVariantSpineSegment(metal, poseStack.last(), bladeStart, bladeDirection,
				receiverSide, mechanicalNormal, spineLength, 0.63D, 1.00D,
				deployment.spineTip(), profile.receiverHalfWidth() * 0.078D,
				profile.receiverHalfDepth() * 0.30D, plate, alloy);

		/* Never submit another metal vertex after requesting either energy buffer. */
		double pulse = 0.94D + Math.sin(time * 0.48D + pulseOffset) * 0.06D;
		Rgba field = fieldBase.alpha((int) Math.round(92.0D + pulse * 26.0D));
		Rgba edge = edgeBase.alpha((int) Math.round(202.0D + pulse * 24.0D));
		if (deployment.breakerHead() > 0.001F) {
			VertexConsumer fieldEnergy = buffers.getBuffer(ArmorEnergyRenderType.FIELD_ALPHA);
			renderBreakerEnergyField(fieldEnergy, poseStack.last(), bladeStart,
					bladeDirection, energySide, energyNormal, profile.bladeLength(),
					profile.shape(), deployment.breakerHead(), field);
		}
		VertexConsumer energy = buffers.getBuffer(ArmorEnergyRenderType.INSTANCE);
		renderBreakerNode(energy, poseStack.last(), carriageFront, bladeDirection,
				receiverSide, mechanicalNormal,
				profile.receiverHalfWidth() * 0.38D,
				profile.receiverHalfDepth() * 0.70D, deployment.carriage(), edge);
		if (deployment.energyEdge() > 0.001F) {
			renderBreakerEdgeInset(energy, poseStack.last(), bladeStart,
					bladeDirection, energySide, energyNormal, profile.bladeLength(),
					profile.shape(), deployment.energyEdge(), edge);
		}
	}

	private static void renderVariantSpineSegment(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 start, Vec3 direction, Vec3 side, Vec3 normal, double fullLength,
			double startStation, double endStation, float progress,
			double halfWidth, double halfDepth, Rgba topColor, Rgba sideColor) {
		if (progress <= 0.001F) return;
		Vec3 segmentStart = start.add(direction.scale(fullLength * startStation));
		Vec3 segmentEnd = segmentStart.add(direction.scale(
				fullLength * (endStation - startStation) * easeOut(progress)));
		renderOrientedBox(vertices, pose, segmentStart, segmentEnd,
				side, normal, halfWidth, halfDepth, topColor, sideColor);
	}
	private enum BladeView {
		FIRST_PERSON,
		WORLD
	}

	private record MountProfile(double bladeLength, double housingLength, double emitterLength,
			double housingHalfWidth, double housingHalfDepth, double panelTravel,
			double startY, double startZ, double directionY, double directionZ,
			double sparLengthRatio) {
		private static MountProfile resolve(BladeView view, ArmorVariant variant) {
			if (view == BladeView.FIRST_PERSON) {
				return switch (variant) {
					case VANGUARD -> new MountProfile(13.6D, 4.10D, 2.90D,
							1.15D, 0.22D, 0.26D,
							8.72D, -3.52D, 0.999D, -0.045D, 0.56D);
					case SWIFT -> new MountProfile(12.4D, 3.50D, 2.15D,
							1.18D, 0.24D, 0.42D,
							10.25D, -2.20D, 0.996D, -0.090D, 0.55D);
					case SENTINEL -> throw new IllegalArgumentException(
							"Sentinel uses its dedicated breaker profile");
				};
			}
			return switch (variant) {
				case VANGUARD -> new MountProfile(21.0D, 4.55D, 3.05D,
						1.28D, 0.24D, 0.32D,
						8.94D, -3.48D, 0.999D, -0.032D, 0.58D);
				case SWIFT -> new MountProfile(18.0D, 3.80D, 2.40D,
						1.14D, 0.22D, 0.38D,
						10.35D, -2.22D, 0.997D, -0.075D, 0.56D);
				case SENTINEL -> throw new IllegalArgumentException(
						"Sentinel uses its dedicated breaker profile");
			};
		}
	}

	private record BladeShape(double[] stations, double[] widths, double thickness) {
		private static BladeShape resolve(BladeView view, ArmorVariant variant) {
			return switch (view) {
				case FIRST_PERSON -> switch (variant) {
					case VANGUARD -> FIRST_PERSON_VANGUARD;
					case SWIFT -> FIRST_PERSON_SWIFT;
					case SENTINEL -> throw new IllegalArgumentException(
							"Sentinel uses its dedicated breaker shape");
				};
				case WORLD -> switch (variant) {
					case VANGUARD -> WORLD_VANGUARD;
					case SWIFT -> WORLD_SWIFT;
					case SENTINEL -> throw new IllegalArgumentException(
							"Sentinel uses its dedicated breaker shape");
				};
			};
		}
	}

	private record BreakerProfile(double bladeLength, double carriageTravel,
			double receiverHalfWidth, double receiverHalfDepth,
			double jawTravel, double jawLift,
			double centerX,
			double receiverRearY, double receiverRearZ,
			double receiverFrontY, double receiverFrontZ,
			double bladeDirectionY, double bladeDirectionZ,
			double rollDegrees, double mechanicalSpineRatio,
			BreakerShape shape) {
	}

	private record BreakerShape(double[] stations, double[] centerOffsets, double[] halfWidths,
			double surfaceOffset) {
	}

	private record BreakerSection(double station, double centerOffset, double halfWidth) {
	}

	private record Rgba(int red, int green, int blue, int alpha) {
		private Rgba alpha(int nextAlpha) {
			return new Rgba(red, green, blue, Mth.clamp(nextAlpha, 0, 255));
		}
	}

	private static final class MechanicalBladeRenderType extends RenderStateShard {
		private static final RenderType INSTANCE = RenderTypeInvoker.flightArmor$create(
				"flight_armor_arm_blade_mechanical",
				DefaultVertexFormat.POSITION_COLOR,
				VertexFormat.Mode.QUADS,
				4096,
				false,
				false,
				RenderType.CompositeState.builder()
						.setShaderState(POSITION_COLOR_SHADER)
						.setTextureState(NO_TEXTURE)
						.setTransparencyState(NO_TRANSPARENCY)
						.setDepthTestState(LEQUAL_DEPTH_TEST)
						.setCullState(NO_CULL)
						.setLightmapState(NO_LIGHTMAP)
						.setOverlayState(NO_OVERLAY)
						.setWriteMaskState(COLOR_DEPTH_WRITE)
						.setOutputState(MAIN_TARGET)
						.createCompositeState(false));

		private MechanicalBladeRenderType() {
			super("flight_armor_arm_blade_mechanical_state", () -> { }, () -> { });
		}
	}
}
