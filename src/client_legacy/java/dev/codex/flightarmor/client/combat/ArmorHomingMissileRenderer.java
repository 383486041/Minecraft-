package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorHomingMissileEntity;
import dev.codex.flightarmor.flight.FlightLightColorCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Custom untextured missile shell and emissive trail; no vanilla particles are used. */
public final class ArmorHomingMissileRenderer extends EntityRenderer<ArmorHomingMissileEntity> {
	private static final ResourceLocation UNUSED_TEXTURE = FlightArmorMod.id("textures/entity/homing_missile.png");
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final Vec3 WORLD_RIGHT = new Vec3(1.0D, 0.0D, 0.0D);

	public ArmorHomingMissileRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.0F;
	}

	@Override
	public void render(ArmorHomingMissileEntity missile, float yaw, float partialTick,
			PoseStack poses, MultiBufferSource buffers, int packedLight) {
		Minecraft client = Minecraft.getInstance();
		Entity camera = client.getCameraEntity();
		if (camera != null && client.options.getCameraType().isFirstPerson()
				&& camera.distanceToSqr(missile) < 3.25D) {
			return;
		}
		Vec3 velocity = missile.getDeltaMovement();
		if (velocity.lengthSqr() < 1.0E-8D) return;
		Vec3 forward = velocity.normalize();
		double speed = Math.min(1.0D, velocity.length() / 1.72D);
        FlightLightColorCurve.Rgb light = FlightLightColorCurve.colorAt(speed);
		Vec3 reference = Math.abs(forward.y) < 0.86D ? WORLD_UP : WORLD_RIGHT;
		Vec3 right = forward.cross(reference).normalize();
		Vec3 up = forward.cross(right).normalize();
		VertexConsumer vertices = buffers.getBuffer(ArmorEnergyRenderType.INSTANCE);
		PoseStack.Pose pose = poses.last();
		double rotation = (missile.tickCount + partialTick) * 0.34D;

		Vec3 nose = forward.scale(0.34D);
		Vec3 rear = forward.scale(-0.23D);
		Vec3[] ring = new Vec3[4];
		for (int i = 0; i < ring.length; i++) {
			double angle = rotation + i * Math.PI * 0.5D;
			ring[i] = forward.scale(0.015D)
					.add(right.scale(Math.cos(angle) * 0.105D))
					.add(up.scale(Math.sin(angle) * 0.105D));
		}
		for (int i = 0; i < ring.length; i++) {
			Vec3 a = ring[i];
			Vec3 b = ring[(i + 1) & 3];
			emitQuad(vertices, pose, nose, a, b, nose,
                    light.red(), light.green(), light.blue(), 250, 68, 18, 82, 242);
			emitQuad(vertices, pose, rear, b, a, rear,
					86, 20, 104, 238, 34, 8, 48, 220);
		}

		// A smaller white-hot diamond prevents the body from looking like a flat billboard.
		Vec3 coreNose = forward.scale(0.25D);
		Vec3 coreRear = forward.scale(-0.13D);
		for (int i = 0; i < 4; i++) {
			double a0 = -rotation * 0.72D + i * Math.PI * 0.5D;
			double a1 = -rotation * 0.72D + (i + 1) * Math.PI * 0.5D;
			Vec3 p0 = right.scale(Math.cos(a0) * 0.045D).add(up.scale(Math.sin(a0) * 0.045D));
			Vec3 p1 = right.scale(Math.cos(a1) * 0.045D).add(up.scale(Math.sin(a1) * 0.045D));
			emitQuad(vertices, pose, coreNose, p0, p1, coreNose,
					255, 246, 255, 255, 255, 122, 248, 248);
			emitQuad(vertices, pose, coreRear, p1, p0, coreRear,
					255, 88, 232, 238, 255, 40, 210, 214);
		}

		double tailLength = 0.48D + speed * 0.92D;
		Vec3 tailNear = forward.scale(-0.18D);
		Vec3 tailFar = forward.scale(-tailLength);
		emitTrailBlade(vertices, pose, tailNear, tailFar, right, 0.078D,
                light.red(), light.green(), light.blue(), 224);
		emitTrailBlade(vertices, pose, tailNear, tailFar, up, 0.068D,
                (light.red() + 170) / 2, (light.green() + 80) / 2, 255, 202);
		emitTrailBlade(vertices, pose, forward.scale(-0.14D), forward.scale(-tailLength * 0.72D),
				right.add(up).normalize(), 0.026D, 255, 242, 255, 244);
	}

	private static void emitTrailBlade(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 near, Vec3 far, Vec3 widthAxis, double halfWidth,
			int red, int green, int blue, int alpha) {
		Vec3 nearWidth = widthAxis.scale(halfWidth);
		Vec3 farWidth = widthAxis.scale(halfWidth * 0.08D);
		emitVertex(vertices, pose, near.subtract(nearWidth), red, green, blue, alpha);
		emitVertex(vertices, pose, near.add(nearWidth), red, green, blue, alpha);
		emitVertex(vertices, pose, far.add(farWidth), red, green, blue, 0);
		emitVertex(vertices, pose, far.subtract(farWidth), red, green, blue, 0);
	}

	private static void emitQuad(VertexConsumer vertices, PoseStack.Pose pose,
			Vec3 v0, Vec3 v1, Vec3 v2, Vec3 v3,
			int hotRed, int hotGreen, int hotBlue, int hotAlpha,
			int coolRed, int coolGreen, int coolBlue, int coolAlpha) {
		emitVertex(vertices, pose, v0, hotRed, hotGreen, hotBlue, hotAlpha);
		emitVertex(vertices, pose, v1, coolRed, coolGreen, coolBlue, coolAlpha);
		emitVertex(vertices, pose, v2, coolRed, coolGreen, coolBlue, coolAlpha);
		emitVertex(vertices, pose, v3, hotRed, hotGreen, hotBlue, hotAlpha);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 point,
			int red, int green, int blue, int alpha) {
		vertices.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
				.setColor(red, green, blue, alpha);
	}

	@Override
	public ResourceLocation getTextureLocation(ArmorHomingMissileEntity entity) {
		return UNUSED_TEXTURE;
	}
}
