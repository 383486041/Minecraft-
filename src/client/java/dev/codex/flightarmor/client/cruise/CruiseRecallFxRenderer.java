package dev.codex.flightarmor.client.cruise;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.mixin.RenderTypeInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 独立巡航战甲的无纹理空气压缩场。
 *
 * <p>强度只由载体在本客户端 tick 的真实可见位移决定。阶段仅用于找到仍然存在的
 * 召回载体，不参与速度或透明度计算；位移归零后不保存尾迹状态，因此同帧至多下一
 * 个客户端 tick 就会完全消失。</p>
 */
final class CruiseRecallFxRenderer {
	/** 与玩家测速 HUD 使用同一游戏性音速：5 格/秒，即 0.25 格/tick。 */
	private static final double MACH_ONE_BLOCKS_PER_TICK = FlightPhysics.APPROXIMATE_SOUND_SPEED_BLOCKS_PER_TICK;
	private static final double TRANSONIC_START_MACH = 0.72D;
	private static final double FULL_COMPRESSION_MACH = 1.35D;
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);
	private static final Rgba AIR_WHITE = new Rgba(232, 250, 255, 255);
	private static final Rgba AIR_CYAN = new Rgba(112, 224, 244, 255);
	private static final Rgba ION_VIOLET = new Rgba(184, 142, 246, 255);
	private static boolean initialized;

	private CruiseRecallFxRenderer() {
	}

	static void initialize() {
		if (initialized) return;
		initialized = true;
		RecallRenderType.bootstrap();
		WorldRenderEvents.AFTER_ENTITIES.register(CruiseRecallFxRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		if (!FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) return;
		if (context.world() == null || context.consumers() == null) return;
		Minecraft client = Minecraft.getInstance();
		Vec3 camera = context.camera().getPosition();
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
		VertexConsumer vertices = context.consumers().getBuffer(RecallRenderType.INSTANCE);

		for (ArmorCruiseClient.CruiseState state : ArmorCruiseClient.states()) {
			// 这里只做载体生命周期过滤；音障强度绝不读取 phase、距离或目标速度。
			if (state.phase() != ArmorCruisePhase.RECALLING) continue;
			Entity resolved = ArmorCruiseClient.resolveCarrier(client, state);
			if (!(resolved instanceof LivingEntity carrier)
					|| ArmorCruiseClient.isRetiredCarrier(carrier)) continue;

			Vec3 tickStart = carrier.getPosition(0.0F);
			Vec3 tickEnd = carrier.getPosition(1.0F);
			Vec3 tickMotion = tickEnd.subtract(tickStart);
			double blocksPerTick = tickMotion.length();
			if (!Double.isFinite(blocksPerTick) || blocksPerTick < 1.0E-4D) continue;

			double mach = blocksPerTick / MACH_ONE_BLOCKS_PER_TICK;
			float compression = smoothstep((float) TRANSONIC_START_MACH,
					(float) FULL_COMPRESSION_MACH, (float) mach);
			if (compression <= 0.002F) continue;

			Vec3 current = carrier.getPosition(partialTick).add(0.0D, 0.92D, 0.0D);
			if (camera.distanceToSqr(current) > 180.0D * 180.0D) continue;
			Vec3 direction = tickMotion.scale(1.0D / blocksPerTick);
			Player owner = ArmorCruiseClient.ownerOfCarrier(carrier);
			boolean ownerFirstPerson = owner == client.player
					&& client.options.getCameraType().isFirstPerson();

			double length = Mth.lerp(compression, 8.0D, 25.0D);
			Vec3 tail = current.subtract(direction.scale(length));
			float cameraVisibility = firstPersonVisibility(camera, current, tail,
					ownerFirstPerson);
			float strength = compression * cameraVisibility;
			if (strength <= 0.002F) continue;

			renderAirField(vertices, camera, current, direction, mach, length,
					strength, carrier.tickCount + partialTick);
		}
	}

	private static void renderAirField(VertexConsumer vertices, Vec3 camera,
			Vec3 apex, Vec3 direction, double mach, double length,
			float strength, double flowTime) {
		// 高马赫数时压缩锥更长；宽度限制在角色周围，避免变成巨大的实心几何罩。
		double coneRadius = Mth.clamp(1.45D + length * 0.095D, 1.45D, 3.85D);
		Rgba speedTint = mix(AIR_CYAN, ION_VIOLET,
				smoothstep(1.0F, 2.8F, (float) mach));

		// 四层低 alpha、不同长度和相位的扰动壳相互穿插，形成压缩雾而非固定棱柱。
		renderTurbulentShell(vertices, camera, apex, direction,
				length * 1.00D, coneRadius, 12, 20, flowTime * 0.34D,
				AIR_WHITE, 17.0F * strength);
		renderTurbulentShell(vertices, camera, apex, direction,
				length * 0.92D, coneRadius * 0.82D, 11, 18, -flowTime * 0.49D + 1.7D,
				speedTint, 22.0F * strength);
		renderTurbulentShell(vertices, camera, apex, direction,
				length * 0.78D, coneRadius * 0.59D, 10, 16, flowTime * 0.67D + 3.1D,
				AIR_WHITE, 28.0F * strength);
		renderTurbulentShell(vertices, camera, apex, direction,
				length * 0.62D, coneRadius * 0.37D, 8, 14, -flowTime * 0.82D + 0.8D,
				speedTint, 31.0F * strength);

		renderFlowWisps(vertices, camera, apex, direction, length,
				coneRadius, flowTime, speedTint, strength);
		renderCondensationBands(vertices, camera, apex, direction, length,
				coneRadius, flowTime, strength);
	}

	private static void renderTurbulentShell(VertexConsumer vertices, Vec3 camera,
			Vec3 apex, Vec3 direction, double length, double maxRadius,
			int segments, int sides, double phase, Rgba color, float maxAlpha) {
		Basis basis = basis(direction);
		for (int segment = 0; segment < segments; segment++) {
			double t0 = segment / (double) segments;
			double t1 = (segment + 1) / (double) segments;
			float alpha0 = maxAlpha * mistEnvelope((float) t0);
			float alpha1 = maxAlpha * mistEnvelope((float) t1);
			for (int side = 0; side < sides; side++) {
				double angle0 = Math.PI * 2.0D * side / sides;
				double angle1 = Math.PI * 2.0D * (side + 1) / sides;
				Vec3 v00 = shellPoint(apex, direction, basis, length, maxRadius,
						t0, angle0, phase);
				Vec3 v01 = shellPoint(apex, direction, basis, length, maxRadius,
						t0, angle1, phase);
				Vec3 v11 = shellPoint(apex, direction, basis, length, maxRadius,
						t1, angle1, phase);
				Vec3 v10 = shellPoint(apex, direction, basis, length, maxRadius,
						t1, angle0, phase);
				emitQuad(vertices, camera, v00, v01, v11, v10,
						color.alpha(alpha0), color.alpha(alpha0),
						color.alpha(alpha1), color.alpha(alpha1));
			}
		}
	}

	private static Vec3 shellPoint(Vec3 apex, Vec3 direction, Basis basis,
			double length, double maxRadius, double t, double angle, double phase) {
		double axialWave = Math.sin(t * 21.0D - phase * 2.6D + angle * 2.0D);
		double angularWave = Math.sin(angle * 5.0D + t * 13.0D + phase * 1.7D);
		double radius = 0.09D + maxRadius * t
				* (1.0D + 0.055D * axialWave + 0.040D * angularWave);
		double swirlAngle = phase * 0.23D + t * 7.0D;
		Vec3 swirl = basis.radial(swirlAngle)
				.scale(maxRadius * t * 0.028D * Math.sin(t * 11.0D + phase));
		return apex.subtract(direction.scale(0.12D + length * t))
				.add(swirl).add(basis.radial(angle).scale(radius));
	}

	private static void renderFlowWisps(VertexConsumer vertices, Vec3 camera,
			Vec3 apex, Vec3 direction, double length, double maxRadius,
			double flowTime, Rgba color, float strength) {
		Basis basis = basis(direction);
		int ribbons = 9;
		int segments = 9;
		for (int ribbon = 0; ribbon < ribbons; ribbon++) {
			double baseAngle = Math.PI * 2.0D * ribbon / ribbons + flowTime * 0.035D;
			for (int segment = 0; segment < segments; segment++) {
				double t0 = 0.07D + 0.90D * segment / segments;
				double t1 = 0.07D + 0.90D * (segment + 1) / segments;
				double noise = Math.sin(ribbon * 2.37D + segment * 1.91D - flowTime * 0.42D);
				if (noise < -0.32D) continue;
				double angle0 = baseAngle + 0.22D * Math.sin(t0 * 10.0D - flowTime * 0.31D);
				double angle1 = baseAngle + 0.22D * Math.sin(t1 * 10.0D - flowTime * 0.31D);
				double width = 0.045D + 0.035D * (noise + 1.0D);
				Vec3 v00 = wispPoint(apex, direction, basis, length, maxRadius, t0, angle0 - width);
				Vec3 v01 = wispPoint(apex, direction, basis, length, maxRadius, t0, angle0 + width);
				Vec3 v11 = wispPoint(apex, direction, basis, length, maxRadius, t1, angle1 + width);
				Vec3 v10 = wispPoint(apex, direction, basis, length, maxRadius, t1, angle1 - width);
				float alpha0 = 18.0F * strength * mistEnvelope((float) t0);
				float alpha1 = 18.0F * strength * mistEnvelope((float) t1);
				emitQuad(vertices, camera, v00, v01, v11, v10,
						color.alpha(alpha0), color.alpha(alpha0),
						color.alpha(alpha1), color.alpha(alpha1));
			}
		}
	}

	private static Vec3 wispPoint(Vec3 apex, Vec3 direction, Basis basis,
			double length, double maxRadius, double t, double angle) {
		double radius = 0.12D + maxRadius * t
				* (0.72D + 0.08D * Math.sin(t * 17.0D + angle * 3.0D));
		return apex.subtract(direction.scale(0.16D + length * t))
				.add(basis.radial(angle).scale(radius));
	}

	private static void renderCondensationBands(VertexConsumer vertices, Vec3 camera,
			Vec3 apex, Vec3 direction, double length, double maxRadius,
			double flowTime, float strength) {
		Basis basis = basis(direction);
		for (int band = 0; band < 6; band++) {
			double t = 0.12D + 0.76D * fract(band * 0.173D + flowTime * 0.028D);
			double radius = 0.16D + maxRadius * t
					* (0.82D + 0.07D * Math.sin(flowTime * 0.43D + band));
			Vec3 center = apex.subtract(direction.scale(0.20D + length * t));
			float envelope = mistEnvelope((float) t);
			renderBrokenRing(vertices, camera, center, direction, basis, radius,
					0.08D + 0.08D * t, 24, flowTime * 0.11D + band * 0.71D,
					AIR_WHITE, 38.0F * strength * envelope);
		}
	}

	private static void renderBrokenRing(VertexConsumer vertices, Vec3 camera,
			Vec3 center, Vec3 normal, Basis basis, double radius, double thickness,
			int sides, double phase, Rgba color, float maxAlpha) {
		double inner = Math.max(0.001D, radius - thickness * 0.5D);
		double outer = radius + thickness * 0.5D;
		for (int side = 0; side < sides; side++) {
			double angle0 = Math.PI * 2.0D * side / sides;
			double angle1 = Math.PI * 2.0D * (side + 1) / sides;
			double noise = Math.sin(angle0 * 3.0D + phase)
					+ 0.55D * Math.sin(angle0 * 7.0D - phase * 1.7D);
			if (noise < -0.42D) continue;
			float alpha = maxAlpha * (float) Mth.clamp(0.50D + noise * 0.30D, 0.12D, 1.0D);
			Vec3 radial0 = basis.radial(angle0);
			Vec3 radial1 = basis.radial(angle1);
			emitQuad(vertices, camera,
					center.add(radial0.scale(inner)),
					center.add(radial0.scale(outer)),
					center.add(radial1.scale(outer)),
					center.add(radial1.scale(inner)),
					color.alpha(alpha), color.alpha(alpha),
					color.alpha(alpha * 0.72F), color.alpha(alpha * 0.72F));
		}
	}

	private static float firstPersonVisibility(Vec3 camera, Vec3 apex, Vec3 tail,
			boolean ownerFirstPerson) {
		if (!ownerFirstPerson) return 1.0F;
		float carrierFade = smoothstep(3.5F, 8.0F, (float) camera.distanceTo(apex));
		float axisFade = smoothstep(1.8F, 4.2F,
				(float) distanceToSegment(camera, apex, tail));
		return Math.min(carrierFade, axisFade);
	}

	private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		double lengthSquared = segment.lengthSqr();
		if (lengthSquared < 1.0E-8D) return point.distanceTo(start);
		double t = Mth.clamp(point.subtract(start).dot(segment) / lengthSquared, 0.0D, 1.0D);
		return point.distanceTo(start.add(segment.scale(t)));
	}

	private static float mistEnvelope(float t) {
		return smoothstep(0.02F, 0.18F, t)
				* (1.0F - smoothstep(0.62F, 1.0F, t));
	}

	private static double fract(double value) {
		return value - Math.floor(value);
	}

	private static void emitQuad(VertexConsumer vertices, Vec3 camera,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			Rgba c0, Rgba c1, Rgba c2, Rgba c3) {
		emitVertex(vertices, camera, v0, c0);
		emitVertex(vertices, camera, v1, c1);
		emitVertex(vertices, camera, v2, c2);
		emitVertex(vertices, camera, v3, c3);
	}

	private static void emitVertex(VertexConsumer vertices, Vec3 camera,
			Vec3 position, Rgba color) {
		vertices.addVertex((float) (position.x - camera.x),
				(float) (position.y - camera.y), (float) (position.z - camera.z))
				.setColor(color.r, color.g, color.b, color.a);
	}

	private static Basis basis(Vec3 axis) {
		Vec3 direction = axis.lengthSqr() > 1.0E-8D ? axis.normalize() : WORLD_UP;
		Vec3 reference = Math.abs(direction.y) < 0.86D ? WORLD_UP : WORLD_RIGHT;
		Vec3 first = direction.cross(reference).normalize();
		return new Basis(first, direction.cross(first).normalize());
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = Mth.clamp((value - edge0) / Math.max(1.0E-5F, edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static Rgba mix(Rgba from, Rgba to, float progress) {
		float t = Mth.clamp(progress, 0.0F, 1.0F);
		return new Rgba(Math.round(Mth.lerp(t, from.r, to.r)),
				Math.round(Mth.lerp(t, from.g, to.g)),
				Math.round(Mth.lerp(t, from.b, to.b)),
				Math.round(Mth.lerp(t, from.a, to.a)));
	}

	private record Basis(Vec3 first, Vec3 second) {
		private Vec3 radial(double angle) {
			return first.scale(Math.cos(angle)).add(second.scale(Math.sin(angle)));
		}
	}

	private record Rgba(int r, int g, int b, int a) {
		private Rgba alpha(float nextAlpha) {
			return new Rgba(r, g, b, Math.round(Mth.clamp(nextAlpha, 0.0F, 255.0F)));
		}
	}

	private static final class RecallRenderType extends RenderStateShard {
		/** 四层扰动壳峰值约 4,400 顶点；预留 256 KiB 供多载体共用。 */
		private static final int BUFFER_SIZE = 262144;
		private static final RenderType INSTANCE = RenderTypeInvoker.flightArmor$create(
				"flight_armor_cruise_recall_air",
				DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
				BUFFER_SIZE, false, true,
				RenderType.CompositeState.builder()
						.setShaderState(POSITION_COLOR_SHADER)
						.setTextureState(NO_TEXTURE)
						.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
						.setDepthTestState(LEQUAL_DEPTH_TEST)
						.setCullState(NO_CULL)
						.setLightmapState(NO_LIGHTMAP)
						.setOverlayState(NO_OVERLAY)
						.setWriteMaskState(COLOR_WRITE)
						.setOutputState(MAIN_TARGET)
						.createCompositeState(false));

		private RecallRenderType() {
			super("flight_armor_cruise_recall_air_state", () -> { }, () -> { });
		}

		private static void bootstrap() {
			if (INSTANCE == null) throw new IllegalStateException("Cruise recall air unavailable");
		}
	}
}
