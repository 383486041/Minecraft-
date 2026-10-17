package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.combat.ArmorAnchorPose;
import dev.codex.flightarmor.combat.ArmorAnchorResolver;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatTuning;
import dev.codex.flightarmor.combat.ArmorSemanticAnchor;
import dev.codex.flightarmor.combat.EnhancedBeamMath;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * 原创世界空间武器特效。所有形状均由动态顶点组成，不使用 ParticleTypes、原版纹理或粒子实体。
 */
public final class ArmorCombatFxRenderer {
	private static final double NEAR_DISTANCE_SQR = 24.0D * 24.0D;
	private static final double MID_DISTANCE_SQR = 56.0D * 56.0D;
	/**
	 * 绘制距离上限，与 {@link EnhancedBeamMath#MAX_LANCE_FX_RANGE} 同源：
	 * 灭世光束的最远命中点就在那个距离上，若这里再各自写一份 96，满蓄力一炮的爆坑
	 * 会因为 {@code quality(...)} 返回 -1 而整片不画——「山被挖掉了，却看不见爆炸」。
	 * 更远的距离只会落到最低细节档（quality 2），不会回到高模。
	 */
	private static final double MAX_DISTANCE_SQR =
			EnhancedBeamMath.MAX_LANCE_FX_RANGE * EnhancedBeamMath.MAX_LANCE_FX_RANGE;
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);

	private static final Rgba AQUA = new Rgba(54, 255, 228, 255);
	private static final Rgba VIOLET = new Rgba(190, 76, 255, 255);
	private static final Rgba WHITE = new Rgba(255, 246, 255, 255);
	private static final Rgba VANGUARD_ORANGE = new Rgba(255, 118, 32, 255);
	private static final Rgba VANGUARD_GOLD = new Rgba(255, 210, 86, 255);
	private static final Rgba SWIFT_MAGENTA = new Rgba(255, 48, 224, 255);
	private static final Rgba SENTINEL_GOLD = new Rgba(255, 190, 59, 255);
	private static final Rgba SENTINEL_BLUE = new Rgba(58, 148, 255, 255);
	private static final Rgba WEAPON_GUNMETAL = new Rgba(42, 55, 64, 255);
	private static final Rgba WEAPON_TITANIUM = new Rgba(156, 177, 188, 255);
	private static boolean initialized;

	private ArmorCombatFxRenderer() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ArmorEnergyRenderType.bootstrap();
		LevelRenderEvents.COLLECT_SUBMITS.register(ArmorCombatFxRenderer::render);
	}

	private static void render(LevelRenderContext context) {
		if (context.levelRenderer() == null) return;
		boolean hasFx = !ArmorCombatFxController.activeBeams().isEmpty()
				|| !ArmorCombatFxController.activeImpacts().isEmpty()
				|| !ArmorCombatFxController.activeShockwaves().isEmpty()
				|| !ArmorCombatFxController.activeMissiles().isEmpty();
		// 蓄力阶段服务端只广播权威 visual、不发 FX，所以手部变形必须另有入口，
		// 否则「按住时前臂展开」这件事在整段蓄力里都看不见。
		boolean charging = anyHandBeamCharging();
		if (!hasFx && !charging) return;

		LevelRenderState levelState = context.levelState();
		if (levelState == null || levelState.cameraRenderState == null) return;
		Vec3 camera = levelState.cameraRenderState.pos;
		if (camera == null) return;
		float partialTick = levelState.worldPartialTicks;
		long gameTime = levelState.gameTime;
		Frustum frustum = levelState.cameraRenderState.cullFrustum;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();

		collector.submitCustomGeometry(poseStack, ArmorEnergyRenderType.INSTANCE, (pose, vertices) -> {
			if (charging) renderHandBeamCharging(context, vertices, pose, camera, frustum, partialTick);
			for (ArmorCombatFxController.ActiveBeam beam : ArmorCombatFxController.activeBeams()) {
				renderBeam(context, vertices, pose, camera, frustum, beam, gameTime, partialTick);
			}
			for (ArmorCombatFxController.ActiveImpact impact : ArmorCombatFxController.activeImpacts()) {
				renderImpact(context, vertices, pose, camera, frustum, impact, gameTime, partialTick);
			}
			for (ArmorCombatFxController.ActiveShockwave shockwave : ArmorCombatFxController.activeShockwaves()) {
				renderShockwave(context, vertices, pose, camera, frustum, shockwave, gameTime, partialTick);
			}
			for (ArmorCombatFxController.ActiveMissile missile : ArmorCombatFxController.activeMissiles()) {
				renderMissile(context, vertices, pose, camera, frustum, missile, gameTime, partialTick);
			}
		});
	}

	private static boolean anyHandBeamCharging() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return false;
		for (Player player : client.level.players()) {
			if (ArmorCombatClient.handBeamCharging(player)) return true;
		}
		return false;
	}

	/**
	 * 蓄力期的「手部变形」：双手前臂发射器随蓄力进度展开，展开量就是进度，所以
	 * 玩家不用看 HUD 也能从自己的手读出「还差多少」。蓄满时枪口已经全开、只等松手。
	 */
	private static void renderHandBeamCharging(LevelRenderContext context, VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 camera, Frustum frustum, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		for (Player player : client.level.players()) {
			if (!ArmorCombatClient.handBeamCharging(player)) continue;
			float charge = Mth.clamp(ArmorCombatClient.phaseProgress(player, partialTick), 0.0F, 1.0F);
			ArmorVariant variant = ModItems.fullSetVariant(player).orElse(ArmorVariant.VANGUARD);
			Rgba field = handBeamField(variant, player.isInWater());
			Rgba core = mix(WHITE, variant == ArmorVariant.SENTINEL ? SENTINEL_BLUE : AQUA, 0.10F);
			double open = 0.28D + charge * 0.72D;
			// 灭世档是双手托举，只展开一只手会让蓄力姿势读不出来。
			for (int side = 0; side < 2; side++) {
				HandBasis hand = resolveHandBasis(context, camera, player, variant, side == 0);
				if (hand.origin().distanceToSqr(camera) > MAX_DISTANCE_SQR) continue;
				if (!visible(frustum, AABB.ofSize(hand.origin(), 2.6D, 2.6D, 2.6D))) continue;
				Vec3 muzzle = hand.forward().scale(0.85D)
						.add(hand.up().scale(0.42D)).normalize();
				renderHandEmitter(vertices, pose, camera, hand.origin(), muzzle, variant,
						open, charge, field, core);
			}
		}
	}

	private static void renderBeam(LevelRenderContext context, VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Frustum frustum, ArmorCombatFxController.ActiveBeam beam, long gameTime, float partialTick) {
		float age = effectAge(gameTime, partialTick, beam.startGameTime);
		if (age < 0.0F || age >= beam.lifetimeTicks) return;
		Vec3 origin = firstPersonOrigin(context, beam);
		Vec3 fullDelta = beam.endpoint.subtract(origin);
		double fullLength = fullDelta.length();
		if (fullLength < 0.02D) return;
		double distanceSqr = distanceToSegmentSqr(camera, origin, beam.endpoint);
		int quality = quality(distanceSqr);
		if (quality < 0) return;

		float progress = Mth.clamp(age / beam.lifetimeTicks, 0.0F, 1.0F);
		boolean bladeSlash = beam.type == ArmorAttackType.ENERGY_BLADE_SLASH;
		boolean handLance = beam.type == ArmorAttackType.HAND_BEAM_LANCE;
		float reveal;
		if (bladeSlash) {
			float travelTicks = switch (beam.variant) {
				case VANGUARD -> 4.25F;
				case SWIFT -> 3.35F;
				case SENTINEL -> 4.85F;
			};
			reveal = easeOut(Mth.clamp((age - 0.08F) / travelTicks, 0.0F, 1.0F));
		} else if (handLance) {
			// 灭世档刻意让光束「长出去」而不是瞬间铺满：削山的重量感来自这几 tick 的推进。
			reveal = Mth.clamp(0.06F + age * 0.24F, 0.0F, 1.0F);
		} else {
			reveal = Mth.clamp(0.30F + age * (isCoreBeam(beam.type) ? 0.48F : 0.62F),
					0.0F, 1.0F);
		}
		Vec3 endpoint = origin.add(fullDelta.scale(reveal));
		double cullInflation = bladeSlash ? bladeSlashCullInflation(beam.variant)
				: handLance ? 2.6D : 0.45D;
		if (!visible(frustum, new AABB(origin, endpoint).inflate(cullInflation))) return;
		float fade = (float) Math.pow(1.0F - progress, 0.72D);
		double seed = phaseSeed(beam.attackId);
		double rotation = seed + age * (isCoreBeam(beam.type) ? 0.52D : 0.72D);

		if (bladeSlash) {
			renderBladeSlash(vertices, pose, camera, origin, endpoint, beam.variant,
					beam.leftHand, age, fade, quality, isLocalFirstPerson(beam));
		} else if (isCoreBeam(beam.type)) {
			renderCoreBeam(vertices, pose, camera, origin, endpoint, beam.variant, beam.charge,
					beam.underwater, rotation, age, fade, quality, beam.eventIndex);
		} else if (handLance) {
			renderHandLanceBeam(vertices, pose, camera, origin, endpoint, beam.variant,
					beam.charge, beam.underwater, rotation, age, fade, quality);
		} else if (beam.type == ArmorAttackType.HAND_BEAM_PULSE) {
			renderHandPulseBeam(vertices, pose, camera, origin, endpoint, beam.variant,
					beam.underwater, rotation, age, fade, quality);
		} else {
			renderWristBeam(vertices, pose, camera, origin, endpoint, beam.variant,
					beam.charge, beam.underwater, rotation, age, fade, quality);
		}
	}

	/** 手部光束的型号色：与腕炮同色系，保证三种能量武器读起来是同一套装甲的动力源。 */
	private static Rgba handBeamField(ArmorVariant variant, boolean underwater) {
		Rgba base = switch (variant) {
			case VANGUARD -> VANGUARD_ORANGE;
			case SWIFT -> SWIFT_MAGENTA;
			case SENTINEL -> SENTINEL_GOLD;
		};
		return underwater ? mix(base, AQUA, 0.46F) : base;
	}

	/**
	 * 「手部可以变形」这一条就落在这里：前臂发射器沿光轴滑出、两侧护轨张开、末端光环点亮，
	 * 枪口再吐一段短促的能量焰。几何语言刻意复用光刃那套前臂件（深色承力骨架 + 钛合金护轨 +
	 * 光环），所以手炮和光刃看起来是同一副装甲长出来的，而不是两个互不相干的模型。
	 *
	 * @param open 0 = 收纳，1 = 完全展开。
	 * @param glow 0..1 的枪口余焰强度，随光束淡出一起收。
	 */
	private static void renderHandEmitter(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 axis, ArmorVariant variant, double open, double glow,
			Rgba field, Rgba core) {
		if (axis.lengthSqr() < 1.0E-8D) return;
		Vec3 direction = axis.normalize();
		Basis frame = basis(direction);
		double openClamped = Mth.clamp(open, 0.0D, 1.0D);
		double housingRadius = switch (variant) {
			case VANGUARD -> 0.112D;
			case SWIFT -> 0.082D;
			case SENTINEL -> 0.152D;
		};
		Vec3 rear = origin.subtract(direction.scale(0.30D + 0.10D * openClamped));
		Vec3 front = origin.add(direction.scale(0.10D + 0.26D * openClamped));
		renderPrism(vertices, pose, camera, rear, front, housingRadius,
				housingRadius * (0.72D + 0.24D * openClamped), 6, openClamped * 0.9D,
				WEAPON_GUNMETAL.alpha(248.0F), WEAPON_TITANIUM.alpha(248.0F));
		double guard = switch (variant) {
			case VANGUARD -> 0.30D;
			case SWIFT -> 0.22D;
			case SENTINEL -> 0.40D;
		} * openClamped;
		for (int side = -1; side <= 1; side += 2) {
			Vec3 railStart = front.subtract(direction.scale(0.05D))
					.add(frame.first.scale(guard * side * 0.30D));
			Vec3 railEnd = front.add(direction.scale(0.10D)).add(frame.first.scale(guard * side));
			renderPrism(vertices, pose, camera, railStart, railEnd,
					housingRadius * 0.26D, housingRadius * 0.16D, 4, side * 0.5D,
					WEAPON_TITANIUM.alpha(252.0F), field.alpha(240.0F));
		}
		renderRing(vertices, pose, camera, front, direction,
				housingRadius * (1.05D + 0.35D * openClamped), housingRadius * 0.26D, 6,
				openClamped * 0.7D, field.alpha(240.0F));
		if (glow <= 0.001D) return;
		float muzzle = (float) Mth.clamp(glow, 0.0D, 1.0D);
		renderPrism(vertices, pose, camera, front, front.add(direction.scale(0.10D + 0.24D * openClamped)),
				housingRadius * 0.60D, housingRadius * 0.22D, 6, -openClamped * 1.4D,
				core.alpha(255.0F * muzzle), field.alpha(126.0F * muzzle));
	}

	/** 点射：细、亮、快，一帧打出去立刻收——读起来是「一枪」而不是一道持续的光。 */
	private static void renderHandPulseBeam(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 endpoint, ArmorVariant variant, boolean underwater,
			double rotation, float age, float fade, int quality) {
		Vec3 axis = endpoint.subtract(origin);
		if (axis.lengthSqr() < 1.0E-8D) return;
		Rgba field = handBeamField(variant, underwater);
		Rgba core = mix(WHITE, variant == ArmorVariant.SENTINEL ? SENTINEL_BLUE : AQUA,
				underwater ? 0.34F : 0.10F);
		double pulse = 0.95D + Math.sin(age * 3.1D + rotation) * 0.05D;
		renderPrism(vertices, pose, camera, origin, endpoint,
				0.055D * pulse, 0.020D, 4, rotation,
				field.alpha(214.0F * fade), field.alpha(28.0F * fade));
		renderPrism(vertices, pose, camera, origin, endpoint,
				0.020D * pulse, 0.008D, 3, -rotation * 1.7D,
				core.alpha(255.0F * fade), core.alpha(150.0F * fade));
		if (quality <= 1) {
			renderHandEmitter(vertices, pose, camera, origin, axis, variant,
					Mth.clamp(0.40D + age * 0.34D, 0.0D, 1.0D), fade, field, core);
		}
	}

	/** 灭世档：三层同轴力场 + 加速环 + 螺旋约束 + 全开的前臂发射器。 */
	private static void renderHandLanceBeam(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 endpoint, ArmorVariant variant, float charge, boolean underwater,
			double rotation, float age, float fade, int quality) {
		Vec3 axis = endpoint.subtract(origin);
		if (axis.lengthSqr() < 1.0E-8D) return;
		double strength = Mth.clamp(charge, 0.0F, 1.0F);
		Rgba field = handBeamField(variant, underwater);
		Rgba core = mix(WHITE, variant == ArmorVariant.SENTINEL ? SENTINEL_BLUE : AQUA,
				underwater ? 0.30F : 0.08F);
		double pulse = 0.97D + Math.sin(age * 1.15D + rotation) * 0.03D;
		double outer = (0.30D + strength * 0.34D) * pulse;
		renderPrism(vertices, pose, camera, origin, endpoint, outer, outer * 0.52D, 8, rotation,
				field.alpha(88.0F * fade), field.alpha(30.0F * fade));
		renderPrism(vertices, pose, camera, origin, endpoint, outer * 0.56D, outer * 0.30D, 8,
				-rotation * 0.34D, field.alpha(196.0F * fade),
				mix(field, core, 0.35F).alpha(140.0F * fade));
		renderPrism(vertices, pose, camera, origin, endpoint,
				0.075D + strength * 0.050D, 0.038D + strength * 0.022D, 6, rotation * 0.2D,
				core.alpha(255.0F * fade), core.alpha(238.0F * fade));
		if (quality > 1) return;
		int rings = quality == 0 ? 5 : 3;
		for (int index = 0; index < rings; index++) {
			double travel = positiveModulo(age * 0.20D + index / (double) rings, 1.0D);
			renderRing(vertices, pose, camera, origin.add(axis.scale(0.08D + travel * 0.88D)), axis,
					outer * 0.86D, 0.040D, 8, rotation * 0.3D + index * 0.5D,
					field.alpha(206.0F * fade));
		}
		renderHelix(vertices, pose, camera, origin, endpoint, outer * 0.74D, 0.022D,
				quality == 0 ? 40 : 22, rotation * 1.3D, core.alpha(190.0F * fade));
		// 前臂发射器开到最大：这是「手部变形」最该被看见的一帧。
		renderHandEmitter(vertices, pose, camera, origin, axis, variant, 1.0D, fade, field, core);
	}

	/**
	 * 手掌语义锚点的唯一解算入口（第一人称走相机基，其余视角走实体基）。
	 * 光刃与手炮共用它——两份各自解算的锚点一旦漂移，就会出现「变形件长在手腕外侧」这类
	 * 只有实机才看得出来的错位。
	 */
	private static HandBasis resolveHandBasis(LevelRenderContext context, Vec3 camera,
			Player player, ArmorVariant variant, boolean leftHand) {
		Minecraft client = Minecraft.getInstance();
		Vec3 forward;
		Vec3 right;
		Vec3 up;
		Vec3 origin;
		if (client.player != null && client.player.getUUID().equals(player.getUUID())
				&& context.levelState().cameraRenderState.isFirstPerson) {
			var mainCamera = client.gameRenderer.mainCamera();
			forward = vector(mainCamera.forwardVector()).normalize();
			right = vector(mainCamera.leftVector()).normalize().scale(-1.0D);
			up = vector(mainCamera.upVector()).normalize();
			origin = ArmorAnchorResolver.resolveFirstPerson(camera, forward, right, up,
					variant, ArmorAnchorResolver.inferPose(player),
					ArmorSemanticAnchor.palm(leftHand));
		} else {
			forward = player.getLookAngle();
			if (forward.lengthSqr() < 1.0E-8D) forward = new Vec3(0.0D, 0.0D, 1.0D);
			forward = forward.normalize();
			up = WORLD_UP;
			right = up.cross(forward);
			if (right.lengthSqr() < 1.0E-8D) right = WORLD_RIGHT;
			right = right.normalize();
			origin = ArmorAnchorResolver.resolve(player, variant,
					ArmorAnchorResolver.inferPose(player), ArmorSemanticAnchor.palm(leftHand), forward);
		}
		return new HandBasis(forward, right, up, origin);
	}

	private static void renderBladeSlash(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 endpoint, ArmorVariant variant, boolean leftHand,
			float age, float fade, int quality, boolean localFirstPerson) {
		Vec3 travel = endpoint.subtract(origin);
		double travelled = travel.length();
		if (travelled < 0.12D) return;
		Vec3 direction = travel.scale(1.0D / travelled);

		if (localFirstPerson) {
			double axialClearance = endpoint.subtract(camera).dot(direction);
			if (axialClearance < 1.78D || endpoint.distanceToSqr(camera) < 1.90D * 1.90D) return;
		}

		BladeWaveStyle style = bladeWaveStyle(variant);
		Vec3 side = direction.cross(WORLD_UP);
		if (side.lengthSqr() < 1.0E-7D) side = basis(direction).first;
		else side = side.normalize();
		Vec3 up = side.cross(direction).normalize();
		double handedness = leftHand ? -1.0D : 1.0D;
		double roll = style.rollRadians * handedness;
		Vec3 waveSide = side.scale(Math.cos(roll)).add(up.scale(Math.sin(roll))).normalize();
		Vec3 waveUp = up.scale(Math.cos(roll)).subtract(side.scale(Math.sin(roll))).normalize();

		float formation = smoothstep(0.12F, 0.78F, age);
		double pulse = 0.975D + Math.sin(age * style.pulseRate) * 0.025D;
		double widthScale = (0.70D + formation * 0.30D) * pulse;
		double desiredTrail = style.trailLength * smoothstep(0.30F, 1.15F, age);
		if (localFirstPerson) {
			double safeBehindWave = Math.max(0.0D,
					endpoint.subtract(camera).dot(direction) - 1.72D);
			desiredTrail = Math.min(desiredTrail, safeBehindWave);
		}
		int segments = quality == 0 ? style.nearSegments
				: quality == 1 ? style.midSegments : style.farSegments;

		renderTravellingCrescent(vertices, pose, camera, endpoint, direction, waveSide, waveUp,
				style, handedness, widthScale, desiredTrail, age, fade, segments);

		if (quality <= 1 && variant == ArmorVariant.SWIFT && desiredTrail > 0.18D) {
			Vec3 echoCenter = endpoint.subtract(direction.scale(Math.min(0.42D, desiredTrail * 0.42D)))
					.add(waveUp.scale(-0.11D * handedness));
			renderCrescentAccent(vertices, pose, camera, echoCenter, direction, waveSide, waveUp,
					style, handedness, widthScale * 0.88D, segments,
					VIOLET.alpha(52.0F * fade), WHITE.alpha(176.0F * fade));
		} else if (quality <= 1 && variant == ArmorVariant.SENTINEL) {
			Vec3 braceCenter = endpoint.subtract(direction.scale(0.075D));
			renderCrescentAccent(vertices, pose, camera, braceCenter, direction, waveSide, waveUp,
					style, handedness, widthScale * 0.72D, segments,
					SENTINEL_BLUE.alpha(44.0F * fade), WHITE.alpha(192.0F * fade));
		}
	}

	private static void renderTravellingCrescent(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 direction, Vec3 side, Vec3 up, BladeWaveStyle style,
			double handedness, double widthScale, double trailLength,
			float age, float fade, int segments) {
		Vec3 tailCenter = center.subtract(direction.scale(trailLength));
		CrescentSection previousFront = null;
		CrescentSection previousTail = null;
		for (int index = 0; index <= segments; index++) {
			double normalized = index / (double) segments;
			double across = normalized * 2.0D - 1.0D;
			CrescentSection front = crescentSection(center, direction, side, up, style,
					handedness, across, widthScale, 1.0D);
			CrescentSection tail = crescentSection(tailCenter, direction, side, up, style,
					handedness, across, widthScale * 0.78D, 0.78D);
			if (previousFront != null) {
				float tipEnvelope = (float) Math.pow(Math.max(0.0D,
						Math.sin(normalized * Math.PI)), 0.42D);
				float localFade = fade * (0.48F + tipEnvelope * 0.52F);
				Rgba fieldInner = style.field.alpha(style.fieldAlpha * 0.24F * localFade);
				Rgba fieldOuter = style.field.alpha(style.fieldAlpha * localFade);
				emitNoCullQuad(vertices, pose, camera,
						previousFront.inner, previousFront.outer, front.outer, front.inner,
						fieldInner, fieldOuter, fieldOuter, fieldInner);

				Rgba core = style.core.alpha(style.coreAlpha * localFade);
				Rgba coreSoft = style.edge.alpha(style.coreAlpha * 0.42F * localFade);
				emitNoCullQuad(vertices, pose, camera,
						previousFront.coreInner, previousFront.coreOuter,
						front.coreOuter, front.coreInner,
						coreSoft, core, core, coreSoft);

				renderPrism(vertices, pose, camera, previousFront.outer, front.outer,
						style.edgeRadius, style.edgeRadius * 0.72D, 4, age * 0.24D,
						style.core.alpha(246.0F * localFade),
						style.edge.alpha(214.0F * localFade));
				renderPrism(vertices, pose, camera, previousFront.inner, front.inner,
						style.edgeRadius * 0.46D, style.edgeRadius * 0.34D, 3, -age * 0.18D,
						style.edge.alpha(126.0F * localFade),
						style.field.alpha(74.0F * localFade));

				if (trailLength > 0.025D) {
					Rgba trailFront = style.field.alpha(style.trailAlpha * localFade);
					Rgba trailBack = style.field.alpha(0.0F);
					emitNoCullQuad(vertices, pose, camera,
							previousFront.outer, front.outer, tail.outer, previousTail.outer,
							trailFront, trailFront, trailBack, trailBack);
					emitNoCullQuad(vertices, pose, camera,
							front.inner, previousFront.inner, previousTail.inner, tail.inner,
							trailFront.alpha(trailFront.a * 0.58F),
							trailFront.alpha(trailFront.a * 0.58F),
							trailBack, trailBack);
				}
			}
			previousFront = front;
			previousTail = tail;
		}
	}

	private static void renderCrescentAccent(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 direction, Vec3 side, Vec3 up, BladeWaveStyle style,
			double handedness, double widthScale, int segments,
			Rgba field, Rgba core) {
		CrescentSection previous = null;
		for (int index = 0; index <= segments; index++) {
			double across = index / (double) segments * 2.0D - 1.0D;
			CrescentSection current = crescentSection(center, direction, side, up, style,
					handedness, across, widthScale, 0.42D);
			if (previous != null) {
				Vec3 previousMid = previous.inner.add(previous.outer).scale(0.5D);
				Vec3 currentMid = current.inner.add(current.outer).scale(0.5D);
				renderPrism(vertices, pose, camera, previousMid, currentMid,
						style.edgeRadius * 0.52D, style.edgeRadius * 0.34D, 3, 0.0D,
						core, field);
			}
			previous = current;
		}
	}

	private static CrescentSection crescentSection(Vec3 center, Vec3 direction,
			Vec3 side, Vec3 up, BladeWaveStyle style, double handedness,
			double across, double widthScale, double bandScale) {
		double clampedAcross = Mth.clamp(across, -1.0D, 1.0D);
		double arch = Math.max(0.0D, 1.0D - clampedAcross * clampedAcross);
		double tip = Math.sqrt(arch);
		double sideOffset = clampedAcross * style.halfWidth * widthScale;
		double verticalOffset = style.camber * arch
				+ style.tilt * clampedAcross * handedness;
		double sweptBack = style.tipSweepBack
				* Math.pow(Math.abs(clampedAcross), style.tipSweepPower);
		Vec3 spine = center.subtract(direction.scale(sweptBack))
				.add(side.scale(sideOffset)).add(up.scale(verticalOffset));
		double band = style.bandThickness * bandScale
				* (0.10D + tip * 0.90D);
		Vec3 inner = spine.subtract(up.scale(band * 0.50D));
		Vec3 outer = spine.add(up.scale(band * 0.50D));
		Vec3 coreInner = inner.add(up.scale(band * 0.61D));
		Vec3 coreOuter = inner.add(up.scale(band * 0.82D));
		return new CrescentSection(inner, outer, coreInner, coreOuter);
	}

	private static BladeWaveStyle bladeWaveStyle(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> new BladeWaveStyle(
					2.85D, 0.62D, 0.52D, 0.18D, 0.64D, 1.55D,
					1.28D, Math.toRadians(10.0D), 0.041D, 2.15D,
					14, 10, 7, VANGUARD_ORANGE, VANGUARD_GOLD, WHITE,
					76.0F, 40.0F, 246.0F);
			case SWIFT -> new BladeWaveStyle(
					2.18D, 0.34D, 0.24D, 0.34D, 0.92D, 1.30D,
					2.10D, Math.toRadians(24.0D), 0.026D, 3.20D,
					12, 9, 6, VIOLET, SWIFT_MAGENTA, WHITE,
					58.0F, 32.0F, 238.0F);
			case SENTINEL -> new BladeWaveStyle(
					3.65D, 0.86D, 0.68D, 0.08D, 0.48D, 1.82D,
					1.02D, Math.toRadians(4.0D), 0.052D, 1.55D,
					16, 12, 8, SENTINEL_GOLD, SENTINEL_BLUE, WHITE,
					88.0F, 46.0F, 250.0F);
		};
	}

	private static double bladeSlashCullInflation(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 3.9D;
			case SWIFT -> 3.2D;
			case SENTINEL -> 4.8D;
		};
	}

	private static boolean isLocalFirstPerson(ArmorCombatFxController.ActiveBeam beam) {
		Minecraft client = Minecraft.getInstance();
		return client.player != null
				&& client.player.getUUID().equals(beam.attackerId)
				&& client.options.getCameraType().isFirstPerson();
	}

	private static void renderWristBeam(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 endpoint, ArmorVariant variant, float charge, boolean underwater,
			double rotation, float age, float fade, int quality) {
		Vec3 axis = endpoint.subtract(origin);
		double pulse = 0.94D + Math.sin(age * 2.2D + rotation) * 0.06D;
		double chargeScale = Mth.lerp(Mth.clamp(charge, 0.0F, 1.0F), 0.82D, 1.85D);
		switch (variant) {
			case VANGUARD -> {
				Rgba field = underwater ? mix(VANGUARD_ORANGE, AQUA, 0.48F) : VANGUARD_ORANGE;
				Rgba core = underwater ? mix(WHITE, AQUA, 0.22F) : VANGUARD_GOLD;
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.115D * pulse * chargeScale, 0.052D * pulse * chargeScale, 6, rotation,
						field.alpha(70.0F * fade), field.alpha(26.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.043D * chargeScale, 0.022D * chargeScale, 4, rotation + Math.PI * 0.25D,
						core.alpha(246.0F * fade), WHITE.alpha(184.0F * fade));
				if (quality <= 1) {
					int rings = quality == 0 ? 3 : 2;
					for (int index = 0; index < rings; index++) {
						double travel = positiveModulo(age * 0.22D + index / (double) rings, 1.0D);
						Vec3 center = origin.add(axis.scale(0.10D + travel * 0.84D));
						renderRing(vertices, pose, camera, center, axis, 0.13D, 0.022D,
								6, rotation + index * 0.34D, field.alpha(172.0F * fade));
					}
				}
			}
			case SWIFT -> {
				Rgba field = underwater ? mix(SWIFT_MAGENTA, AQUA, 0.42F) : SWIFT_MAGENTA;
				Rgba core = underwater ? mix(WHITE, AQUA, 0.20F) : WHITE;
				Basis frame = basis(axis);
				double railOffset = 0.052D;
				for (int side = -1; side <= 1; side += 2) {
					Vec3 offset = frame.first.scale(railOffset * side);
					renderPrism(vertices, pose, camera, origin.add(offset), endpoint.add(offset.scale(0.22D)),
							0.018D * chargeScale, 0.006D * chargeScale, 3, -rotation * 1.8D,
							field.alpha(152.0F * fade), field.alpha(28.0F * fade));
				}
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.030D * pulse * chargeScale, 0.007D * chargeScale, 3, rotation * 2.2D,
						core.alpha(252.0F * fade), field.alpha(210.0F * fade));
				if (quality == 0) {
					for (int index = 0; index < 2; index++) {
						double travel = positiveModulo(age * 0.38D + index * 0.5D, 1.0D);
						renderRing(vertices, pose, camera, origin.add(axis.scale(0.16D + travel * 0.78D)), axis,
								0.072D, 0.010D, 4, rotation * 2.0D,
								field.alpha(174.0F * fade));
					}
				}
			}
			case SENTINEL -> {
				Rgba field = underwater ? mix(SENTINEL_GOLD, AQUA, 0.52F) : SENTINEL_GOLD;
				Rgba core = underwater ? AQUA : SENTINEL_BLUE;
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.145D * pulse * chargeScale, 0.084D * pulse * chargeScale, 6, rotation * 0.32D,
						field.alpha(62.0F * fade), field.alpha(30.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.050D * chargeScale, 0.031D * chargeScale, 6, -rotation * 0.24D,
						core.alpha(232.0F * fade), WHITE.alpha(178.0F * fade));
				if (quality <= 1) {
					int rings = quality == 0 ? 4 : 2;
					for (int index = 0; index < rings; index++) {
						double travel = positiveModulo(age * 0.12D + index / (double) rings, 1.0D);
						Vec3 center = origin.add(axis.scale(0.10D + travel * 0.84D));
						renderRing(vertices, pose, camera, center, axis, 0.17D, 0.024D,
								6, rotation * 0.3D, field.alpha(190.0F * fade));
					}
				}
			}
		}
	}

	private static void renderCoreBeam(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 origin, Vec3 endpoint, ArmorVariant variant, float charge, boolean underwater,
			double rotation, float age, float fade, int quality, int eventIndex) {
		float strength = Mth.clamp(charge, 0.0F, 1.0F);
		double pulse = 0.96D + Math.sin(age * 1.55D + rotation) * 0.055D;
		Vec3 axis = endpoint.subtract(origin);
		switch (variant) {
			case VANGUARD -> {
				Rgba primary = underwater ? mix(VANGUARD_ORANGE, AQUA, 0.46F)
						: mix(VANGUARD_ORANGE, VANGUARD_GOLD, strength * 0.55F);
				Rgba hot = mix(WHITE, primary, 0.16F);
				double outerRadius = (0.18D + strength * 0.13D) * pulse;
				renderPrism(vertices, pose, camera, origin, endpoint,
						outerRadius, outerRadius * 0.45D, 6, rotation,
						primary.alpha(82.0F * fade), primary.alpha(24.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						outerRadius * 0.56D, outerRadius * 0.22D, 6, -rotation * 0.38D,
						primary.alpha(168.0F * fade), hot.alpha(92.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.052D + strength * 0.022D, 0.026D + strength * 0.010D, 4,
						rotation * 0.22D, hot.alpha(255.0F * fade), hot.alpha(220.0F * fade));
				if (quality <= 1) {
					int rings = quality == 0 ? 4 : 2;
					for (int index = 0; index < rings; index++) {
						double travel = positiveModulo(age * 0.16D + index / (double) rings, 1.0D);
						renderRing(vertices, pose, camera, origin.add(axis.scale(0.10D + travel * 0.86D)), axis,
								outerRadius * 0.80D, 0.028D, 6, rotation + index * 0.22D,
								primary.alpha(190.0F * fade));
					}
				}
			}
			case SWIFT -> {
				Rgba primary = underwater ? mix(SWIFT_MAGENTA, AQUA, 0.40F)
						: mix(VIOLET, SWIFT_MAGENTA, 0.42F + strength * 0.40F);
				Rgba hot = mix(WHITE, primary, 0.10F);
				Basis frame = basis(axis);
				double railOffset = 0.070D + strength * 0.026D;
				for (int index = 0; index < 3; index++) {
					double angle = rotation + index * Math.PI * 2.0D / 3.0D;
					Vec3 offset = frame.radial(angle).scale(railOffset);
					renderPrism(vertices, pose, camera, origin.add(offset), endpoint.add(offset.scale(0.08D)),
							0.020D + strength * 0.005D, 0.004D, 3, -rotation * 2.0D,
							primary.alpha(182.0F * fade), primary.alpha(24.0F * fade));
				}
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.044D + strength * 0.018D, 0.008D, 3, rotation * 2.6D,
						hot.alpha(255.0F * fade), primary.alpha(232.0F * fade));
				if (quality <= 1) {
					renderHelix(vertices, pose, camera, origin, endpoint, railOffset * 0.72D,
							0.009D, quality == 0 ? 30 : 18, rotation * 1.8D,
							primary.alpha(148.0F * fade));
				}
			}
			case SENTINEL -> {
				Rgba primary = underwater ? mix(SENTINEL_GOLD, AQUA, 0.50F) : SENTINEL_GOLD;
				Rgba accent = underwater ? AQUA : SENTINEL_BLUE;
				Rgba hot = mix(WHITE, primary, 0.18F);
				// Index 0 is the chest cannon. Indexes 1/2 are the mechanical
				// left/right wing cannons and deliberately read as narrower rails.
				boolean cataclysm = strength >= 0.96F;
				double laneScale = eventIndex == 0
						? (cataclysm ? 1.34D : 1.0D)
						: (cataclysm ? 0.82D : 0.62D);
				double outerRadius = (0.22D + strength * 0.15D) * pulse * laneScale;
				renderPrism(vertices, pose, camera, origin, endpoint,
						outerRadius, outerRadius * 0.72D, 6, rotation * 0.24D,
						primary.alpha(66.0F * fade), primary.alpha(34.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						outerRadius * 0.48D, outerRadius * 0.34D, 6, -rotation * 0.18D,
						accent.alpha(176.0F * fade), accent.alpha(96.0F * fade));
				renderPrism(vertices, pose, camera, origin, endpoint,
						0.060D + strength * 0.020D, 0.040D, 6, rotation * 0.12D,
						hot.alpha(250.0F * fade), WHITE.alpha(210.0F * fade));
				if (quality <= 1) {
					int cages = quality == 0 ? 4 : 2;
					for (int index = 0; index < cages; index++) {
						double travel = positiveModulo(age * 0.095D + index / (double) cages, 1.0D);
						Vec3 center = origin.add(axis.scale(0.12D + travel * 0.82D));
						renderRing(vertices, pose, camera, center, axis, outerRadius * 0.92D,
								0.032D, 6, rotation * 0.25D, primary.alpha(210.0F * fade));
					}
					if (cataclysm) {
						renderHelix(vertices, pose, camera, origin, endpoint,
								outerRadius * 0.74D, 0.016D,
								quality == 0 ? 34 : 20, -rotation * 1.45D,
								accent.alpha(206.0F * fade));
					}
				}
			}
		}
	}

	/** Swift missiles are fully custom curved geometry; the target entity only adjusts the visual endpoint. */
	private static void renderMissile(LevelRenderContext context, VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Frustum frustum, ArmorCombatFxController.ActiveMissile missile, long gameTime, float partialTick) {
		float age = effectAge(gameTime, partialTick, missile.startGameTime);
		if (age < 0.0F || age >= missile.lifetimeTicks) return;
		Vec3 endpoint = missile.endpoint;
		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			net.minecraft.world.entity.Entity tracked = client.level.getEntity(missile.targetEntityId);
			if (tracked != null && tracked.isAlive()) endpoint = tracked.getBoundingBox().getCenter();
		}
		Vec3 axis = endpoint.subtract(missile.origin);
		double length = axis.length();
		if (length < 0.05D) return;
		double distanceSqr = distanceToSegmentSqr(camera, missile.origin, endpoint);
		int quality = quality(distanceSqr);
		if (quality < 0 || !visible(frustum, new AABB(missile.origin, endpoint).inflate(7.5D))) return;

		Vec3 lateral = new Vec3(axis.z, 0.0D, -axis.x);
		if (lateral.lengthSqr() < 1.0E-6D) lateral = basis(axis).first;
		else lateral = lateral.normalize();
		double side = (missile.missileIndex & 1) == 0 ? -1.0D : 1.0D;
		double spread = side * (0.85D + (missile.missileIndex % 3) * 0.42D);
		double arcHeight = 2.4D + Math.min(4.8D, length * 0.115D)
				+ (missile.missileIndex / 2) * 0.20D;
		Vec3 control = missile.origin.add(endpoint).scale(0.5D)
				.add(0.0D, arcHeight, 0.0D).add(lateral.scale(spread));
		float linearProgress = Mth.clamp(age / missile.lifetimeTicks, 0.0F, 1.0F);
		float progress = linearProgress * linearProgress * (3.0F - 2.0F * linearProgress);
		float tailStart = Math.max(0.0F, progress - (quality == 2 ? 0.16F : 0.28F));
		int segments = quality == 0 ? 9 : quality == 1 ? 6 : 3;
		Vec3 previous = quadraticBezier(missile.origin, control, endpoint, tailStart);
		double rotation = phaseSeed(missile.attackId + missile.missileIndex * 31L) + age * 0.82D;
		for (int segment = 1; segment <= segments; segment++) {
			float t = Mth.lerp(segment / (float) segments, tailStart, progress);
			Vec3 next = quadraticBezier(missile.origin, control, endpoint, t);
			float tail = segment / (float) segments;
			renderPrism(vertices, pose, camera, previous, next,
					0.012D + tail * 0.030D, 0.018D + tail * 0.038D, 4,
					rotation + segment * 0.18D,
					SWIFT_MAGENTA.alpha(34.0F + tail * 142.0F),
					mix(WHITE, SWIFT_MAGENTA, 0.22F).alpha(78.0F + tail * 168.0F));
			previous = next;
		}
		float rearT = Math.max(0.0F, progress - 0.035F);
		Vec3 rear = quadraticBezier(missile.origin, control, endpoint, rearT);
		Vec3 head = quadraticBezier(missile.origin, control, endpoint, progress);
		Vec3 direction = head.subtract(rear);
		if (direction.lengthSqr() > 1.0E-7D) {
			renderPrism(vertices, pose, camera, rear, head, 0.105D, 0.038D, 4, rotation,
					SWIFT_MAGENTA.alpha(232.0F), WHITE.alpha(255.0F));
			renderRing(vertices, pose, camera, rear, direction, 0.115D, 0.025D, 4,
					rotation + Math.PI * 0.25D, VIOLET.alpha(210.0F));
		}
	}

	private static Vec3 quadraticBezier(Vec3 start, Vec3 control, Vec3 end, float progress) {
		double t = Mth.clamp(progress, 0.0F, 1.0F);
		double inverse = 1.0D - t;
		return start.scale(inverse * inverse)
				.add(control.scale(2.0D * inverse * t)).add(end.scale(t * t));
	}

	private static void renderImpact(LevelRenderContext context, VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Frustum frustum, ArmorCombatFxController.ActiveImpact impact, long gameTime, float partialTick) {
		float age = effectAge(gameTime, partialTick, impact.startGameTime);
		if (age < 0.0F || age >= impact.lifetimeTicks) return;
		double distanceSqr = camera.distanceToSqr(impact.center);
		int quality = quality(distanceSqr);
		boolean lanceImpact = impact.type == ArmorAttackType.HAND_BEAM_LANCE;
		boolean handPulseImpact = impact.type == ArmorAttackType.HAND_BEAM_PULSE;
		double cullRadius = lanceImpact ? 27.0D : 15.0D;
		if (quality < 0
				|| !visible(frustum, AABB.ofSize(impact.center, cullRadius, cullRadius, cullRadius))) {
			return;
		}

		float progress = Mth.clamp(age / impact.lifetimeTicks, 0.0F, 1.0F);
		float fade = (float) Math.pow(1.0F - progress, 1.18D);
		boolean coreImpact = isCoreBeam(impact.type);
		boolean diveImpact = impact.type == ArmorAttackType.GRAVITY_DIVE_IMPACT;
		Rgba primary = switch (impact.variant) {
			case VANGUARD -> mix(VANGUARD_ORANGE, VANGUARD_GOLD,
					coreImpact ? impact.strength * 0.65F : 0.18F);
			case SWIFT -> mix(VIOLET, SWIFT_MAGENTA,
					coreImpact ? 0.54F + impact.strength * 0.36F : 0.72F);
			case SENTINEL -> mix(SENTINEL_GOLD, SENTINEL_BLUE,
					coreImpact ? 0.18F + impact.strength * 0.22F : 0.08F);
		};
		if (impact.underwater) primary = mix(primary, AQUA, 0.46F);
		Rgba hot = mix(WHITE, primary, 0.18F);
		Vec3 center = impact.center.add(impact.normal.scale(0.022D + progress * 0.035D));
		double maxRadius;
		if (lanceImpact) {
			maxRadius = Mth.lerp(impact.strength, 4.5D, 11.5D);
		} else if (handPulseImpact) {
			maxRadius = 0.75D + impact.strength * 0.55D;
		} else {
			maxRadius = switch (impact.variant) {
				case VANGUARD -> coreImpact ? Mth.lerp(impact.strength, 2.15D, 3.75D)
						: diveImpact ? Mth.lerp(impact.strength, 3.5D, 8.0D)
						: Mth.lerp(impact.strength, ArmorCombatTuning.WRIST_BLAST_RADIUS,
								ArmorCombatTuning.WRIST_MAX_BLAST_RADIUS);
				case SWIFT -> coreImpact ? 0.60D + impact.strength * 0.30D
						: diveImpact ? 1.15D + impact.strength * 1.25D : 0.38D + impact.strength * 0.28D;
				case SENTINEL -> coreImpact ? Mth.lerp(impact.strength, 3.30D, 6.80D)
						: diveImpact ? Mth.lerp(impact.strength, 5.0D, 9.5D)
						: Mth.lerp(impact.strength, 3.8D, 6.2D);
			};
		}
		double radius = maxRadius * (0.16D + easeOut(progress) * 0.84D);
		double rotation = phaseSeed(impact.attackId) - age * 0.24D;
		int sides = quality == 2 ? 4 : lanceImpact ? 14 : switch (impact.variant) {
			case VANGUARD -> coreImpact ? 12 : 6;
			case SWIFT -> 4;
			case SENTINEL -> 6;
		};

		renderRing(vertices, pose, camera, center, impact.normal, radius,
				Math.max(0.018D, radius * 0.075D), sides, rotation,
				primary.alpha(190.0F * fade));
		renderRing(vertices, pose, camera, center, impact.normal, radius * 0.62D,
				Math.max(0.012D, radius * 0.040D),
				impact.variant == ArmorVariant.SENTINEL ? 6 : 4, -rotation,
				hot.alpha(218.0F * fade));

		if (quality <= 1) {
			switch (impact.variant) {
				case VANGUARD -> renderRadialBlades(vertices, pose, camera, center, impact.normal,
						radius * 0.16D, radius * 1.02D, coreImpact ? 12 : 6,
						rotation, primary.alpha(132.0F * fade));
				case SWIFT -> {
					renderRadialBlades(vertices, pose, camera, center, impact.normal,
							radius * 0.08D, radius * 1.20D, coreImpact ? 4 : 2,
							rotation, primary.alpha(178.0F * fade));
					renderRing(vertices, pose, camera, center, impact.normal, radius * 0.86D,
							Math.max(0.008D, radius * 0.025D), 4, rotation + Math.PI * 0.25D,
							hot.alpha(190.0F * fade));
				}
				case SENTINEL -> {
					renderCage(vertices, pose, camera, center, impact.normal,
							radius * (0.66D + (1.0D - progress) * 0.34D),
							rotation * 0.38D, hot.alpha(176.0F * fade));
					renderRadialBlades(vertices, pose, camera, center, impact.normal,
							radius * 0.42D, radius * 0.96D, 6,
							rotation, primary.alpha(92.0F * fade));
				}
			}
		}
	}

	private static void renderShockwave(LevelRenderContext context, VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Frustum frustum, ArmorCombatFxController.ActiveShockwave shockwave, long gameTime, float partialTick) {
		float age = effectAge(gameTime, partialTick, shockwave.startGameTime);
		if (age < 0.0F || age >= shockwave.lifetimeTicks) return;
		double distanceSqr = camera.distanceToSqr(shockwave.center);
		int quality = quality(distanceSqr);
		if (quality < 0 || !visible(frustum, AABB.ofSize(shockwave.center, 34.0D, 12.0D, 34.0D))) {
			return;
		}

		float progress = Mth.clamp(age / shockwave.lifetimeTicks, 0.0F, 1.0F);
		float fade = (float) Math.pow(1.0F - progress, 1.10D);
		float tier = Mth.clamp((shockwave.strength - 0.35F) / 0.65F, 0.0F, 1.0F);
		Rgba primary = switch (shockwave.variant) {
			case VANGUARD -> mix(VANGUARD_ORANGE, VANGUARD_GOLD, tier * 0.68F);
			case SWIFT -> mix(VIOLET, SWIFT_MAGENTA, 0.58F + tier * 0.34F);
			case SENTINEL -> mix(SENTINEL_GOLD, SENTINEL_BLUE, tier * 0.24F);
		};
		if (shockwave.underwater) primary = mix(primary, AQUA, 0.48F);
		Rgba hot = mix(WHITE, primary, 0.24F);
		double rotation = phaseSeed(shockwave.attackId) + age * 0.045D;
		Vec3 center = shockwave.center.add(shockwave.normal.scale(0.035D));
		if (shockwave.type == ArmorAttackType.CRUISE_RAM) {
			renderCruiseRamShockwave(vertices, pose, camera, shockwave, center,
					progress, fade, tier, rotation, primary, hot, quality);
			return;
		}

		switch (shockwave.variant) {
			case VANGUARD -> {
				double maxRadius = diveBaseRadius(shockwave.strength);
				double radius = maxRadius * easeOut(progress);
				int ringSides = quality == 0 ? 30 : quality == 1 ? 18 : 10;
				renderRing(vertices, pose, camera, center, shockwave.normal, Math.max(0.08D, radius),
						0.13D + tier * 0.065D, ringSides, rotation,
						primary.alpha(218.0F * fade));
				renderRing(vertices, pose, camera, center, shockwave.normal, Math.max(0.05D, radius * 0.58D),
						0.060D + tier * 0.030D, 6, -rotation,
						hot.alpha(196.0F * fade));
				if (quality <= 1) {
					renderRadialBlades(vertices, pose, camera, center, shockwave.normal,
							Math.max(0.08D, radius * 0.18D), Math.max(0.16D, radius * 0.94D),
							12, rotation, primary.alpha(118.0F * fade));
					if (quality == 0) {
						renderGravityFins(vertices, pose, camera, center, shockwave.normal,
								Math.max(0.20D, radius * 0.72D),
								(0.32D + tier * 0.54D) * (1.0D - progress),
								8, rotation, hot.alpha(128.0F * fade));
					}
				}
			}
			case SWIFT -> {
				double encodedLength = shockwave.travelDirection.length();
				double maxLength = Mth.clamp(encodedLength, 3.0D, 16.0D);
				double length = maxLength * easeOut(progress);
				double width = 0.44D + tier * 0.38D;
				renderGroundCorridor(vertices, pose, camera, center, shockwave.normal,
						shockwave.travelDirection, Math.max(0.10D, length), width,
						primary.alpha(122.0F * fade), hot.alpha(228.0F * fade));
				Vec3 forward = planarDirection(shockwave.travelDirection, shockwave.normal);
				Vec3 front = center.add(forward.scale(Math.max(0.10D, length)));
				renderRing(vertices, pose, camera, front, shockwave.normal,
						width * (0.56D + progress * 0.22D), 0.045D, 4,
						rotation * 2.4D + Math.PI * 0.25D, hot.alpha(205.0F * fade));
				if (quality == 0) {
					renderRadialBlades(vertices, pose, camera, front, shockwave.normal,
							0.05D, width * 0.92D, 4, rotation * 2.0D,
							primary.alpha(148.0F * fade));
				}
			}
			case SENTINEL -> {
				double maxRadius = diveBaseRadius(shockwave.strength) * 1.22D;
				double radius = maxRadius * easeOut(progress);
				renderRing(vertices, pose, camera, center, shockwave.normal, Math.max(0.10D, radius),
						0.17D + tier * 0.075D, 12, rotation * 0.24D,
						primary.alpha(224.0F * fade));
				renderRing(vertices, pose, camera, center, shockwave.normal, Math.max(0.08D, radius * 0.70D),
						0.080D + tier * 0.035D, 6, -rotation * 0.18D,
						hot.alpha(194.0F * fade));
				if (quality <= 1) {
					double wallHeight = (0.70D + tier * 1.05D)
							* (1.0D - smoothstep(0.18F, 1.0F, progress));
					renderFieldWall(vertices, pose, camera, center, shockwave.normal,
							Math.max(0.12D, radius * 0.62D), Math.max(0.10D, radius * 0.48D),
							wallHeight, quality == 0 ? 18 : 12, rotation * 0.20D,
							primary.alpha(112.0F * fade), SENTINEL_BLUE.alpha(24.0F * fade));
					renderCage(vertices, pose, camera,
							center.add(shockwave.normal.scale(Math.max(0.10D, wallHeight * 0.42D))),
							shockwave.normal, Math.max(0.24D, radius * 0.20D),
							rotation * 0.18D, hot.alpha(164.0F * fade));
				}
			}
		}
	}

	private static void renderCruiseRamShockwave(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			ArmorCombatFxController.ActiveShockwave shockwave, Vec3 center,
			float progress, float fade, float tier, double rotation,
			Rgba primary, Rgba hot, int quality) {
		Vec3 axis = shockwave.travelDirection.lengthSqr() > 1.0E-8D
				? shockwave.travelDirection.normalize()
				: shockwave.normal.scale(-1.0D);
		double expansion = easeOut(progress);
		double radius = (0.42D + tier * 0.34D) + expansion * (1.45D + tier * 1.10D);
		double advance = expansion * (0.85D + tier * 1.65D);
		Vec3 leading = center.add(axis.scale(advance));
		int sides = quality == 0 ? 20 : quality == 1 ? 12 : 8;
		renderRing(vertices, pose, camera, leading, axis, radius,
				0.095D + tier * 0.045D, sides, rotation * 2.2D,
				primary.alpha(230.0F * fade));
		renderRing(vertices, pose, camera, center.add(axis.scale(advance * 0.34D)), axis,
				radius * 0.58D, 0.045D + tier * 0.022D,
				quality == 0 ? 12 : 8, -rotation * 2.8D,
				hot.alpha(205.0F * fade));
		if (quality <= 1) {
			renderRadialBlades(vertices, pose, camera, leading, axis,
					Math.max(0.08D, radius * 0.24D), radius * 1.08D,
					quality == 0 ? 12 : 8, rotation,
					primary.alpha(126.0F * fade));
		}
	}

	private static boolean isCoreBeam(ArmorAttackType type) {
		return type == ArmorAttackType.CORE_BEAM || type == ArmorAttackType.CRUISE_CORE_BEAM;
	}

	/** 将 0.42/0.70/1.00 三档冲击强度还原为服务端 3.5/5.5/8.0 格基础半径。 */
	private static double diveBaseRadius(float strength) {
		if (strength <= 0.42F) return 3.5D;
		if (strength <= 0.70F) {
			return Mth.lerp(Mth.clamp((strength - 0.42F) / 0.28F, 0.0F, 1.0F), 3.5D, 5.5D);
		}
		return Mth.lerp(Mth.clamp((strength - 0.70F) / 0.30F, 0.0F, 1.0F),
				5.5D, 8.0D);
	}

	private static Vec3 firstPersonOrigin(LevelRenderContext context,
			ArmorCombatFxController.ActiveBeam beam) {
		if (beam.visualOrigin != null) return beam.visualOrigin;
		if (isCoreBeam(beam.type) && beam.eventIndex > 0) {
			beam.visualOrigin = beam.origin;
			return beam.visualOrigin;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !client.player.getUUID().equals(beam.attackerId)
				|| !context.levelState().cameraRenderState.isFirstPerson) {
			beam.visualOrigin = beam.origin;
			return beam.visualOrigin;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		var mainCamera = client.gameRenderer.mainCamera();
		Vec3 forward = vector(mainCamera.forwardVector()).normalize();
		Vec3 right = vector(mainCamera.leftVector()).normalize().scale(-1.0D);
		Vec3 up = vector(mainCamera.upVector()).normalize();
		ArmorAnchorPose pose = ArmorAnchorResolver.inferPose(client.player);
		if (pose != ArmorAnchorPose.SWIMMING) {
			if (FlightArmorClient.isDiveVisualActive(client.player)) pose = ArmorAnchorPose.FLIGHT;
			else if (FlightArmorClient.isHovering(client.player)) pose = ArmorAnchorPose.HOVER;
			else if (FlightArmorClient.isFlightEffectActive(client.player)) pose = ArmorAnchorPose.FLIGHT;
		}
		ArmorSemanticAnchor anchor = isCoreBeam(beam.type)
				? ArmorSemanticAnchor.CHEST_CORE : ArmorSemanticAnchor.palm(beam.leftHand);
		beam.visualOrigin = ArmorAnchorResolver.resolveFirstPerson(
				camera, forward, right, up, beam.variant, pose, anchor);
		return beam.visualOrigin;
	}

	private static void renderPrism(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 start, Vec3 end,
			double startRadius, double endRadius, int sides, double rotation,
			Rgba startColor, Rgba endColor) {
		Vec3 axis = end.subtract(start);
		if (axis.lengthSqr() < 1.0E-8D || startColor.a <= 0 && endColor.a <= 0) return;
		Basis basis = basis(axis);
		for (int side = 0; side < sides; side++) {
			double angle0 = rotation + Math.PI * 2.0D * side / sides;
			double angle1 = rotation + Math.PI * 2.0D * (side + 1) / sides;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			Vec3 v0 = start.add(radial0.scale(startRadius));
			Vec3 v1 = start.add(radial1.scale(startRadius));
			Vec3 v2 = end.add(radial1.scale(endRadius));
			Vec3 v3 = end.add(radial0.scale(endRadius));
			emitNoCullQuad(vertices, pose, camera, v0, v1, v2, v3,
					startColor, startColor, endColor, endColor);
		}
	}

	private static void renderRing(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 center, Vec3 normal,
			double radius, double thickness, int sides, double rotation, Rgba color) {
		if (radius <= 0.001D || thickness <= 0.001D || color.a <= 0) return;
		Basis basis = basis(normal);
		double inner = Math.max(0.001D, radius - thickness * 0.5D);
		double outer = radius + thickness * 0.5D;
		for (int side = 0; side < sides; side++) {
			double angle0 = rotation + Math.PI * 2.0D * side / sides;
			double angle1 = rotation + Math.PI * 2.0D * (side + 1) / sides;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			Vec3 v0 = center.add(radial0.scale(inner));
			Vec3 v1 = center.add(radial0.scale(outer));
			Vec3 v2 = center.add(radial1.scale(outer));
			Vec3 v3 = center.add(radial1.scale(inner));
			emitNoCullQuad(vertices, pose, camera, v0, v1, v2, v3, color, color, color, color);
		}
	}

	private static void renderHelix(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 start, Vec3 end,
			double radius, double width, int segments, double rotation, Rgba color) {
		Vec3 axis = end.subtract(start);
		double length = axis.length();
		if (length < 0.02D || color.a <= 0) return;
		Vec3 direction = axis.scale(1.0D / length);
		Basis basis = basis(axis);
		for (int segment = 0; segment < segments; segment++) {
			double t0 = segment / (double) segments;
			double t1 = (segment + 1) / (double) segments;
			double angle0 = rotation + t0 * Math.PI * 8.0D;
			double angle1 = rotation + t1 * Math.PI * 8.0D;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			Vec3 p0 = start.add(axis.scale(t0)).add(radial0.scale(radius));
			Vec3 p1 = start.add(axis.scale(t1)).add(radial1.scale(radius));
			Vec3 width0 = direction.cross(radial0).normalize().scale(width);
			Vec3 width1 = direction.cross(radial1).normalize().scale(width);
			emitNoCullQuad(vertices, pose, camera,
					p0.subtract(width0), p0.add(width0), p1.add(width1), p1.subtract(width1),
					color, color, color, color);
		}
	}

	private static void renderRadialBlades(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 normal, double innerRadius, double outerRadius,
			int count, double rotation, Rgba color) {
		if (outerRadius <= innerRadius || color.a <= 0) return;
		Basis basis = basis(normal);
		for (int index = 0; index < count; index++) {
			double angle = rotation + Math.PI * 2.0D * index / count;
			Vec3 radial = basis.radial(angle);
			Vec3 tangent = basis.radial(angle + Math.PI * 0.5D);
			Vec3 inner = center.add(radial.scale(innerRadius));
			Vec3 outer = center.add(radial.scale(outerRadius));
			double innerWidth = Math.max(0.012D, innerRadius * 0.12D);
			double outerWidth = Math.max(0.006D, outerRadius * 0.025D);
			emitNoCullQuad(vertices, pose, camera,
					inner.subtract(tangent.scale(innerWidth)), inner.add(tangent.scale(innerWidth)),
					outer.add(tangent.scale(outerWidth)), outer.subtract(tangent.scale(outerWidth)),
					color, color, color.alpha(color.a * 0.25F), color.alpha(color.a * 0.25F));
		}
	}

	private static void renderCage(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 center, Vec3 normal,
			double radius, double rotation, Rgba color) {
		Basis frame = basis(normal);
		renderRing(vertices, pose, camera, center, normal, radius, 0.018D, 8, rotation, color);
		renderRing(vertices, pose, camera, center, frame.first, radius, 0.015D, 8, -rotation * 0.72D,
				color.alpha(color.a * 0.72F));
		renderRing(vertices, pose, camera, center, frame.second, radius, 0.015D, 8, rotation * 0.58D,
				color.alpha(color.a * 0.72F));
	}

	private static void renderFieldWall(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 normal, double lowerRadius, double upperRadius,
			double height, int sides, double rotation, Rgba lowerColor, Rgba upperColor) {
		if (height <= 0.005D || lowerColor.a <= 0 && upperColor.a <= 0) return;
		Basis basis = basis(normal);
		Vec3 upperCenter = center.add(normal.normalize().scale(height));
		for (int side = 0; side < sides; side++) {
			double angle0 = rotation + Math.PI * 2.0D * side / sides;
			double angle1 = rotation + Math.PI * 2.0D * (side + 1) / sides;
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			Vec3 v0 = center.add(radial0.scale(lowerRadius));
			Vec3 v1 = center.add(radial1.scale(lowerRadius));
			Vec3 v2 = upperCenter.add(radial1.scale(upperRadius));
			Vec3 v3 = upperCenter.add(radial0.scale(upperRadius));
			emitNoCullQuad(vertices, pose, camera, v0, v1, v2, v3,
					lowerColor, lowerColor, upperColor, upperColor);
		}
	}

	private static void renderGroundCorridor(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 normal, Vec3 direction, double length, double width,
			Rgba fieldColor, Rgba coreColor) {
		if (length <= 0.005D || width <= 0.005D || fieldColor.a <= 0 && coreColor.a <= 0) return;
		Vec3 up = normal.lengthSqr() > 1.0E-8D ? normal.normalize() : WORLD_UP;
		Vec3 forward = planarDirection(direction, up);
		Vec3 side = up.cross(forward).normalize();
		Vec3 lift = up.scale(0.028D);
		Vec3 tail = center.subtract(forward.scale(0.20D)).add(lift);
		Vec3 front = center.add(forward.scale(length)).add(lift);
		double tailWidth = width * 0.10D;
		double frontWidth = width * 0.62D;
		emitNoCullQuad(vertices, pose, camera,
				tail.subtract(side.scale(tailWidth)), tail.add(side.scale(tailWidth)),
				front.add(side.scale(frontWidth)), front.subtract(side.scale(frontWidth)),
				fieldColor.alpha(fieldColor.a * 0.45F), fieldColor.alpha(fieldColor.a * 0.45F),
				fieldColor, fieldColor);

		double coreTailWidth = Math.max(0.018D, width * 0.030D);
		double coreFrontWidth = Math.max(0.025D, width * 0.075D);
		emitNoCullQuad(vertices, pose, camera,
				tail.subtract(side.scale(coreTailWidth)), tail.add(side.scale(coreTailWidth)),
				front.add(side.scale(coreFrontWidth)), front.subtract(side.scale(coreFrontWidth)),
				coreColor, coreColor, coreColor.alpha(coreColor.a * 0.72F),
				coreColor.alpha(coreColor.a * 0.72F));

		for (int sign = -1; sign <= 1; sign += 2) {
			Vec3 railStart = tail.add(side.scale(tailWidth * sign));
			Vec3 railEnd = front.add(side.scale(frontWidth * sign));
			renderPrism(vertices, pose, camera, railStart, railEnd,
					0.018D, 0.009D, 3, sign * Math.PI * 0.25D,
					coreColor.alpha(coreColor.a * 0.78F), fieldColor.alpha(fieldColor.a * 0.48F));
		}
	}

	private static Vec3 planarDirection(Vec3 direction, Vec3 normal) {
		Vec3 up = normal.lengthSqr() > 1.0E-8D ? normal.normalize() : WORLD_UP;
		Vec3 finiteDirection = direction != null && Double.isFinite(direction.x)
				&& Double.isFinite(direction.y) && Double.isFinite(direction.z)
				? direction : Vec3.ZERO;
		Vec3 planar = finiteDirection.subtract(up.scale(finiteDirection.dot(up)));
		return planar.lengthSqr() > 1.0E-8D ? planar.normalize() : basis(up).first;
	}

	private static void renderGravityFins(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 center, Vec3 normal, double radius, double height,
			int count, double rotation, Rgba color) {
		if (height <= 0.005D || color.a <= 0) return;
		Basis basis = basis(normal);
		Vec3 direction = normal.normalize();
		for (int index = 0; index < count; index++) {
			double angle = rotation + Math.PI * 2.0D * index / count;
			Vec3 radial = basis.radial(angle);
			Vec3 tangent = basis.radial(angle + Math.PI * 0.5D);
			Vec3 bottom = center.add(radial.scale(radius));
			Vec3 top = bottom.add(direction.scale(height)).add(radial.scale(-0.08D));
			double halfWidth = 0.035D + height * 0.025D;
			emitNoCullQuad(vertices, pose, camera,
					bottom.subtract(tangent.scale(halfWidth)), bottom.add(tangent.scale(halfWidth)),
					top.add(tangent.scale(halfWidth * 0.35D)), top.subtract(tangent.scale(halfWidth * 0.35D)),
					color, color, color.alpha(color.a * 0.16F), color.alpha(color.a * 0.16F));
		}
	}

	private static void emitNoCullQuad(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3) {
		emitQuad(vertices, pose, camera, v0, v1, v2, v3, c0, c1, c2, c3);
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3) {
		emitVertex(vertices, pose, camera, v0, c0);
		emitVertex(vertices, pose, camera, v1, c1);
		emitVertex(vertices, pose, camera, v2, c2);
		emitVertex(vertices, pose, camera, v3, c3);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera, Vec3 position, Rgba color) {
		vertices.addVertex(pose,
				(float) (position.x - camera.x),
				(float) (position.y - camera.y),
				(float) (position.z - camera.z))
				.setColor(color.r, color.g, color.b, color.a);
	}

	private static Basis basis(Vec3 axis) {
		Vec3 direction = axis.lengthSqr() > 1.0E-8D ? axis.normalize() : WORLD_UP;
		Vec3 reference = Math.abs(direction.y) < 0.86D ? WORLD_UP : WORLD_RIGHT;
		Vec3 first = direction.cross(reference).normalize();
		Vec3 second = direction.cross(first).normalize();
		return new Basis(direction, first, second);
	}

	private static int quality(double distanceSqr) {
		if (distanceSqr > MAX_DISTANCE_SQR) return -1;
		if (distanceSqr <= NEAR_DISTANCE_SQR) return 0;
		if (distanceSqr <= MID_DISTANCE_SQR) return 1;
		return 2;
	}

	private static boolean visible(Frustum frustum, AABB bounds) {
		return frustum == null || frustum.isVisible(bounds);
	}

	private static float effectAge(long gameTime, float partialTick, long startGameTime) {
		return gameTime - startGameTime + Mth.clamp(partialTick, 0.0F, 1.0F);
	}

	private static double distanceToSegmentSqr(Vec3 point, Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		double lengthSqr = segment.lengthSqr();
		if (lengthSqr < 1.0E-8D) return point.distanceToSqr(start);
		double t = Mth.clamp(point.subtract(start).dot(segment) / lengthSqr, 0.0D, 1.0D);
		return point.distanceToSqr(start.add(segment.scale(t)));
	}

	private static Vec3 vector(Vector3fc vector) {
		return new Vec3(vector.x(), vector.y(), vector.z());
	}

	private static double phaseSeed(long attackId) {
		long mixed = attackId * 0x9E3779B97F4A7C15L;
		return ((mixed >>> 40) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
	}

	private static double positiveModulo(double value, double modulus) {
		double result = value % modulus;
		return result < 0.0D ? result + modulus : result;
	}

	private static float easeOut(float value) {
		float t = Mth.clamp(value, 0.0F, 1.0F);
		return 1.0F - (1.0F - t) * (1.0F - t);
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = Mth.clamp((value - edge0) / Math.max(1.0E-5F, edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float t = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(
				Math.round(Mth.lerp(t, from.r, to.r)),
				Math.round(Mth.lerp(t, from.g, to.g)),
				Math.round(Mth.lerp(t, from.b, to.b)),
				Math.round(Mth.lerp(t, from.a, to.a)));
	}

	private record BladeWaveStyle(
			double halfWidth,
			double bandThickness,
			double camber,
			double tilt,
			double tipSweepBack,
			double tipSweepPower,
			double trailLength,
			double rollRadians,
			double edgeRadius,
			double pulseRate,
			int nearSegments,
			int midSegments,
			int farSegments,
			Rgba field,
			Rgba edge,
			Rgba core,
			float fieldAlpha,
			float trailAlpha,
			float coreAlpha) {
	}

	private record CrescentSection(
			Vec3 inner,
			Vec3 outer,
			Vec3 coreInner,
			Vec3 coreOuter) {
	}

	/** 手掌锚点解算结果：手部件（光刃发射器、手炮发射器）统一从这里取基与原点。 */
	private record HandBasis(Vec3 forward, Vec3 right, Vec3 up, Vec3 origin) {
	}

	private record Basis(Vec3 direction, Vec3 first, Vec3 second) {
		private Vec3 radial(double angle) {
			return first.scale(Math.cos(angle)).add(second.scale(Math.sin(angle)));
		}
	}

	private record Rgba(int r, int g, int b, int a) {
		private Rgba {
			r = Mth.clamp(r, 0, 255);
			g = Mth.clamp(g, 0, 255);
			b = Mth.clamp(b, 0, 255);
			a = Mth.clamp(a, 0, 255);
		}

		private Rgba alpha(float nextAlpha) {
			return new Rgba(r, g, b, Math.round(Mth.clamp(nextAlpha, 0.0F, 255.0F)));
		}
	}
}
