package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Public rendering facade for the Sentinel's detachable outer-wing module.
 *
 * <p>The facade reads the exact same OBJ mesh used by the worn armor and selects one physical
 * side's secondary vane, tip, drone pod and cannon groups. The selected groups are recentered and
 * rotated into an entity-local flight frame; no substitute procedural aircraft is generated.
 * {@code foldProgress} only articulates the individual plates and weapon carriage. It never fades
 * or uniformly scales the module, so ownership can move between the armor and wingman renderers
 * without the old holographic shrink/fade impression.</p>
 */
public final class SentinelWingModuleMesh {
	private static final float LEFT_CENTER_X = 4.16F;
	private static final float RIGHT_CENTER_X = -4.16F;
	private static final float MODULE_CENTER_Y = 12.10F;
	private static final float MODULE_CENTER_Z = 4.92F;
	private static final float FLIGHT_CENTER_X = 5.10F;
	private static final float FLIGHT_CENTER_Y = 12.41F;
	private static final float FLIGHT_CENTER_Z = 5.73F;
	private static final float SECONDARY_HINGE_X = 3.51F;
	private static final float SECONDARY_HINGE_Y = 0.66F;
	private static final float SECONDARY_HINGE_Z = 4.41F;
	private static final float TIP_HINGE_X = 3.51F;
	private static final float TIP_HINGE_Y = 2.36F;
	private static final float TIP_HINGE_Z = 4.73F;
	private static final float POD_HINGE_X = 3.78F;
	private static final float POD_HINGE_Y = 1.97F;
	private static final float POD_HINGE_Z = 5.02F;
	private static final float CANNON_HINGE_X = 4.42F;
	private static final float CANNON_HINGE_Y = 1.06F;
	private static final float CANNON_HINGE_Z = 4.854F;

	private static final ArmorObjMesh.Color SHELL =
			new ArmorObjMesh.Color(96, 113, 126, 255);
	private static final ArmorObjMesh.Color FRAME =
			new ArmorObjMesh.Color(29, 38, 46, 255);
	private static final ArmorObjMesh.Color CYAN =
			new ArmorObjMesh.Color(48, 236, 255, 255);
	private static final ArmorObjMesh.Color BLUE =
			new ArmorObjMesh.Color(58, 148, 255, 255);
	private static final ArmorObjMesh.Color VIOLET =
			new ArmorObjMesh.Color(190, 76, 255, 255);
	private static final ArmorObjMesh.Color WHITE =
			new ArmorObjMesh.Color(238, 252, 255, 255);

	private SentinelWingModuleMesh() {
	}

	/**
	 * Renders one complete detachable module.
	 *
	 * @param side negative selects the player's left OBJ module; positive selects the right
	 * @param foldProgress zero is the autonomous flight shape, one is the compact docking shape
	 * @param energyPulse zero to one weapon/drive pulse, used only for emissive colour
	 */
	public static void render(PoseStack poses, MultiBufferSource buffers, int packedLight,
			int side, float foldProgress, float energyPulse) {
		ArmorObjMesh mesh = FlightArmorObjResources.mesh(ArmorVariant.SENTINEL);
		if (mesh.groups().isEmpty()) {
			return;
		}

		boolean left = side < 0;
		float fold = smoothstep(Mth.clamp(foldProgress, 0.0F, 1.0F));
		float pulse = Mth.clamp(energyPulse, 0.0F, 1.0F);
		VertexConsumer shell = buffers.getBuffer(
				RenderType.armorCutoutNoCull(FlightObjArmorRenderer.BASE_TEXTURE));

		for (ArmorObjMesh.Group group : mesh.groups()) {
			String name = group.name().toLowerCase(Locale.ROOT);
			if (!isSelectedModuleGroup(name, left) || isLed(name)) {
				continue;
			}
			poses.pushPose();
			applyEntityLocalFrame(poses, left, fold);
			applyModuleArticulation(poses, group, name, left, fold);
			mesh.render(group, poses.last(), shell, packedLight,
					isDark(name) ? FRAME : SHELL);
			poses.popPose();
		}

		/*
		 * MultiBufferSource.BufferSource closes the current builder when getBuffer switches
		 * RenderType. Never obtain the emissive consumer before the shell pass is complete:
		 * retaining the old shell consumer and writing through it afterwards throws
		 * IllegalStateException("Not building!") on the render thread.
		 */
		VertexConsumer emissive = buffers.getBuffer(
				RenderType.entityTranslucentEmissive(FlightObjArmorRenderer.EMISSIVE_TEXTURE));
		for (ArmorObjMesh.Group group : mesh.groups()) {
			String name = group.name().toLowerCase(Locale.ROOT);
			if (!isSelectedModuleGroup(name, left) || !isLed(name)) {
				continue;
			}
			poses.pushPose();
			applyEntityLocalFrame(poses, left, fold);
			applyModuleArticulation(poses, group, name, left, fold);
			mesh.render(group, poses.last(), emissive, LightTexture.FULL_BRIGHT,
					emissiveColor(name, pulse));
			poses.popPose();
		}
	}

	private static boolean isSelectedModuleGroup(String name, boolean left) {
		String sideToken = left ? "vane_left_" : "vane_right_";
		if (!name.startsWith(sideToken)) {
			return false;
		}
		return name.contains("sentinel_wing_secondary")
				|| name.contains("sentinel_wing_tip")
				|| name.contains("sentinel_drone_pod")
				|| name.contains("sentinel_wing_cannon");
	}

	/**
	 * Armor OBJ coordinates are body-model pixels: +Y runs from the wing root to its tip.
	 * Rotating -90 degrees around X maps that physical span to entity-local -Z (the flight nose).
	 */
	private static void applyEntityLocalFrame(PoseStack poses, boolean left, float fold) {
		// Armor OBJ data uses HumanoidModel's +X-left/+Y-down basis. A standalone entity does
		// not receive LivingEntityRenderer's fixed model-basis flip, so reproduce it here.
		poses.scale(-1.0F, -1.0F, 1.0F);
		poses.mulPose(Axis.XP.rotationDegrees(-90.0F));
		poses.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
		float direction = left ? 1.0F : -1.0F;
		float centerX = Mth.lerp(fold, direction * FLIGHT_CENTER_X,
				left ? LEFT_CENTER_X : RIGHT_CENTER_X);
		float centerY = Mth.lerp(fold, FLIGHT_CENTER_Y, MODULE_CENTER_Y);
		float centerZ = Mth.lerp(fold, FLIGHT_CENTER_Z, MODULE_CENTER_Z);
		poses.translate(-centerX, -centerY, -centerZ);
	}

	/**
	 * Applies the socket-side half of the shared transformation.
	 *
	 * <p>At {@code fold == 1} this produces the same {@code -90 degree} module frame used by
	 * {@code applyEntityLocalFrame}. The entity origin is placed at this pivot, so the
	 * ownership switch changes only which renderer supplies the matrix, not the geometry or its
	 * hinge pose.</p>
	 */
	static void applyWornSocketArticulation(PoseStack poses, ArmorObjMesh.Group group,
			String name, boolean left, float foldProgress) {
		float fold = smoothstep(Mth.clamp(foldProgress, 0.0F, 1.0F));
		rotateAroundPoint(poses,
				left ? LEFT_CENTER_X : RIGHT_CENTER_X,
				MODULE_CENTER_Y, MODULE_CENTER_Z,
				-90.0F * fold, 0.0F, 0.0F);
		applyModuleArticulation(poses, group, name, left, fold);
	}

	/**
	 * Package-visible so the worn-armor renderer can use the exact same plate articulation before
	 * the entity hand-off. Its caller is responsible only for choosing the body or entity frame.
	 */
	static void applyModuleArticulation(PoseStack poses, ArmorObjMesh.Group group,
			String name, boolean left, float fold) {
		float direction = left ? 1.0F : -1.0F;
		if (name.contains("wing_tip")) {
			// The tip is swept and raised into a separate upper plane in flight, then closes
			// without crossing the body centreline.
			rotateAroundPoint(poses, direction * TIP_HINGE_X, TIP_HINGE_Y, TIP_HINGE_Z,
					Mth.lerp(fold, 6.5F, -4.0F),
					direction * Mth.lerp(fold, -4.0F, 3.0F),
					direction * Mth.lerp(fold, -9.0F, 8.0F));
			poses.translate(direction * Mth.lerp(fold, 0.42F, 0.0F),
					Mth.lerp(fold, 0.42F, -0.75F),
					Mth.lerp(fold, 0.30F, 0.05F));
		} else if (name.contains("wing_secondary")) {
			// The main vane occupies the middle plane and shares one physical root across shell,
			// frame and LEDs.
			rotateAroundPoint(poses, direction * SECONDARY_HINGE_X,
					SECONDARY_HINGE_Y, SECONDARY_HINGE_Z,
					Mth.lerp(fold, 2.0F, -3.0F),
					direction * Mth.lerp(fold, -2.0F, 3.0F),
					direction * Mth.lerp(fold, -4.0F, 6.0F));
			poses.translate(direction * Mth.lerp(fold, 0.20F, -0.10F),
					Mth.lerp(fold, 0.05F, -0.55F),
					Mth.lerp(fold, -0.25F, 0.0F));
		} else if (name.contains("wing_cannon")) {
			// A fixed carriage hinge keeps the barrel, housing and muzzle locked together.
			poses.translate(direction * Mth.lerp(fold, 0.24F, -0.20F),
					Mth.lerp(fold, 0.55F, -0.70F),
					Mth.lerp(fold, -0.55F, 0.0F));
			rotateAroundPoint(poses, direction * CANNON_HINGE_X,
					CANNON_HINGE_Y, CANNON_HINGE_Z,
					Mth.lerp(fold, 2.0F, -5.0F),
					direction * Mth.lerp(fold, -2.0F, 3.0F), 0.0F);
		} else if (name.contains("drone_pod")) {
			// The pod sits below and behind the vane as a readable fuselage, with its status
			// lights sharing the same hinge instead of orbiting their individual mesh centres.
			poses.translate(direction * Mth.lerp(fold, -0.28F, -0.30F),
					Mth.lerp(fold, -0.55F, -0.45F),
					Mth.lerp(fold, 0.55F, 0.0F));
			rotateAroundPoint(poses, direction * POD_HINGE_X, POD_HINGE_Y, POD_HINGE_Z,
					Mth.lerp(fold, 0.0F, -6.0F),
					direction * Mth.lerp(fold, -2.0F, -4.0F),
					direction * Mth.lerp(fold, 2.0F, -6.0F));
		}
	}

	private static boolean isLed(String name) {
		return name.contains("_led");
	}

	private static boolean isDark(String name) {
		return name.contains("frame") || name.contains("gunmetal")
				|| name.contains("barrel") || name.contains("dark");
	}

	private static ArmorObjMesh.Color emissiveColor(String name, float pulse) {
		ArmorObjMesh.Color base = name.contains("wing_cannon_muzzle") ? WHITE
				: name.contains("wing_tip_node") ? VIOLET
				: name.contains("wing_secondary_node") ? VIOLET
				: name.contains("drone_pod_status") ? CYAN : BLUE;
		return mix(base, WHITE, 0.10F + pulse * 0.42F);
	}

	private static ArmorObjMesh.Color mix(ArmorObjMesh.Color from,
			ArmorObjMesh.Color to, float progress) {
		float t = Mth.clamp(progress, 0.0F, 1.0F);
		return new ArmorObjMesh.Color(
				Math.round(Mth.lerp(t, from.red(), to.red())),
				Math.round(Mth.lerp(t, from.green(), to.green())),
				Math.round(Mth.lerp(t, from.blue(), to.blue())),
				255);
	}

	private static void rotateAroundPoint(PoseStack poses,
			float pivotX, float pivotY, float pivotZ,
			float xDegrees, float yDegrees, float zDegrees) {
		poses.translate(pivotX, pivotY, pivotZ);
		if (xDegrees != 0.0F) poses.mulPose(Axis.XP.rotationDegrees(xDegrees));
		if (yDegrees != 0.0F) poses.mulPose(Axis.YP.rotationDegrees(yDegrees));
		if (zDegrees != 0.0F) poses.mulPose(Axis.ZP.rotationDegrees(zDegrees));
		poses.translate(-pivotX, -pivotY, -pivotZ);
	}

	private static float smoothstep(float value) {
		return value * value * (3.0F - 2.0F * value);
	}
}
