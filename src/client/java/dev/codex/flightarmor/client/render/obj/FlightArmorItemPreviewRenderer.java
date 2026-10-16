package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Stable inventory previews backed by the same variant OBJ meshes as worn armor.
 * The renderer is deliberately clock-free: GUI items never rotate, pulse or flicker.
 */
public final class FlightArmorItemPreviewRenderer
		implements BuiltinItemRendererRegistry.DynamicItemRenderer {
	private static final FlightArmorItemPreviewRenderer INSTANCE =
			new FlightArmorItemPreviewRenderer();
	private static final Offset ZERO = new Offset(0.0F, 0.0F, 0.0F);
	private static final Offset LEFT_ARM_OFFSET = new Offset(5.0F, 2.0F, 0.0F);
	private static final Offset RIGHT_ARM_OFFSET = new Offset(-5.0F, 2.0F, 0.0F);
	private static final Offset LEFT_LEG_OFFSET = new Offset(2.05F, 12.0F, 0.0F);
	private static final Offset RIGHT_LEG_OFFSET = new Offset(-2.05F, 12.0F, 0.0F);
	private static final Offset LEFT_BOOT_OFFSET = new Offset(2.15F, 12.0F, -0.35F);
	private static final Offset RIGHT_BOOT_OFFSET = new Offset(-2.15F, 12.0F, 0.35F);

	private FlightArmorItemPreviewRenderer() {}

	public static void initialize() {
		BuiltinItemRendererRegistry registry = BuiltinItemRendererRegistry.INSTANCE;
		registry.register(ModItems.FLIGHT_HELMET, INSTANCE);
		registry.register(ModItems.FLIGHT_CHESTPLATE, INSTANCE);
		registry.register(ModItems.FLIGHT_LEGGINGS, INSTANCE);
		registry.register(ModItems.FLIGHT_BOOTS, INSTANCE);
		registry.register(ModItems.ARMOR_NAVIGATION_CORE, INSTANCE);
	}

	@Override
	public void render(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		EquipmentSlot armorSlot = armorSlot(stack);
		if (armorSlot != null) {
			renderArmorPart(stack, armorSlot, context, poseStack, buffers, packedLight);
		} else if (stack.is(ModItems.ARMOR_NAVIGATION_CORE)) {
			renderNavigationCore(stack, context, poseStack, buffers, packedLight);
		}
	}

	private static void renderArmorPart(ItemStack stack, EquipmentSlot slot,
			ItemDisplayContext context, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight) {
		ArmorVariant variant = ArmorVariantData.get(stack);
		ArmorObjMesh mesh = FlightArmorObjResources.mesh(variant);
		if (mesh.groups().isEmpty()) return;
		PreviewLayout layout = PreviewLayout.forSlot(slot);

		poseStack.pushPose();
		applyContextTransform(poseStack, context, layout.pixelScale());
		VertexConsumer base = ItemRenderer.getArmorFoilBuffer(buffers,
				RenderType.armorCutoutNoCull(FlightObjArmorRenderer.BASE_TEXTURE), stack.hasFoil());
		for (ArmorObjMesh.Group group : mesh.groups()) {
			if (slotForGroup(group.name()) != slot || isEmissive(group.name())) continue;
			renderGroup(mesh, group, poseStack, base, packedLight,
					baseColor(variant, group.name()), layout);
		}

		VertexConsumer emissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(
				FlightObjArmorRenderer.EMISSIVE_TEXTURE));
		for (ArmorObjMesh.Group group : mesh.groups()) {
			if (slotForGroup(group.name()) != slot || !isEmissive(group.name())) continue;
			renderGroup(mesh, group, poseStack, emissive, LightTexture.FULL_BRIGHT,
					emissiveColor(variant, group.name()), layout);
		}
		poseStack.popPose();
	}

	private static void renderNavigationCore(ItemStack stack, ItemDisplayContext context,
			PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		ArmorObjMesh mesh = FlightArmorObjResources.mesh(ArmorVariant.VANGUARD);
		if (mesh.groups().isEmpty()) return;
		PreviewLayout layout = PreviewLayout.NAVIGATION_CORE;

		poseStack.pushPose();
		applyContextTransform(poseStack, context, layout.pixelScale());
		VertexConsumer base = ItemRenderer.getArmorFoilBuffer(buffers,
				RenderType.armorCutoutNoCull(FlightObjArmorRenderer.BASE_TEXTURE), stack.hasFoil());
		for (ArmorObjMesh.Group group : mesh.groups()) {
			if (!group.name().startsWith("core_") || isEmissive(group.name())) continue;
			renderGroup(mesh, group, poseStack, base, packedLight,
					baseColor(ArmorVariant.VANGUARD, group.name()), layout);
		}

		// GUI item rendering may use an immediate MultiBufferSource. Requesting a second
		// RenderType closes the first builder, so never interleave both consumers.
		VertexConsumer emissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(
				FlightObjArmorRenderer.EMISSIVE_TEXTURE));
		ArmorObjMesh.Color light = new ArmorObjMesh.Color(70, 244, 255, 255);
		for (ArmorObjMesh.Group group : mesh.groups()) {
			if (!group.name().startsWith("core_") || !isEmissive(group.name())) continue;
			renderGroup(mesh, group, poseStack, emissive, LightTexture.FULL_BRIGHT, light, layout);
		}
		poseStack.popPose();
	}

	private static void applyContextTransform(PoseStack poseStack, ItemDisplayContext context,
			float pixelScale) {
		poseStack.translate(0.5F, 0.5F, 0.5F);
		float contextScale;
		switch (context) {
			case GUI -> {
				poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(212.0F));
				contextScale = 1.0F;
			}
			case FIXED -> {
				poseStack.mulPose(Axis.XP.rotationDegrees(12.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(205.0F));
				contextScale = 0.82F;
			}
			case GROUND -> {
				poseStack.translate(0.0F, 0.08F, 0.0F);
				poseStack.mulPose(Axis.XP.rotationDegrees(8.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(205.0F));
				contextScale = 0.58F;
			}
			case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> {
				poseStack.translate(0.0F, -0.08F, 0.0F);
				poseStack.mulPose(Axis.XP.rotationDegrees(8.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(190.0F));
				contextScale = 0.28F;
			}
			case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> {
				poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(195.0F));
				contextScale = 0.44F;
			}
			case HEAD -> {
				poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
				contextScale = 0.42F;
			}
			case NONE -> {
				poseStack.mulPose(Axis.XP.rotationDegrees(12.0F));
				poseStack.mulPose(Axis.YP.rotationDegrees(205.0F));
				contextScale = 0.72F;
			}
			default -> contextScale = 0.60F;
		}
		float scale = pixelScale * contextScale;
		// OBJ coordinates follow HumanoidModel: +Y points down and front points toward -Z.
		poseStack.scale(scale, -scale, scale);
	}

	private static void renderGroup(ArmorObjMesh mesh, ArmorObjMesh.Group group,
			PoseStack poseStack, VertexConsumer consumer, int packedLight,
			ArmorObjMesh.Color color, PreviewLayout layout) {
		String name = group.name();
		Offset anchor = anchorForGroup(name);
		poseStack.pushPose();
		poseStack.translate(anchor.x() - layout.centerX(), anchor.y() - layout.centerY(),
				anchor.z() - layout.centerZ());
		if (name.contains("vane_") && name.contains("_sentinel_")) {
			// Fold the long Sentinel overdrive wings into a compact, readable V-silhouette
			// in item previews so they frame the chestplate rather than hanging below the slot.
			float side = name.contains("_left_") ? 1.0F : -1.0F;
			poseStack.translate(side * 1.35F, -1.85F, 0.65F);
			poseStack.translate(side * 3.62F, 2.55F, 2.52F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(side * -34.0F));
			poseStack.mulPose(Axis.YP.rotationDegrees(side * -18.0F));
			poseStack.scale(0.76F, 0.52F, 0.78F);
			poseStack.translate(-side * 3.62F, -2.55F, -2.52F);
		} else if (hasAny(name, "left_arm", "arm_left", "right_arm", "arm_right")) {
			float side = hasAny(name, "left_arm", "arm_left") ? 1.0F : -1.0F;
			poseStack.translate(side * 0.35F, 0.0F, 0.0F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(side * -9.0F));
			poseStack.mulPose(Axis.XP.rotationDegrees(-6.0F));
		}
		if (name.contains("underlayer_shadow") || name.contains("_seal_shadow")
				|| name.equals("head_shell_shadow")) {
			// Slightly inset inner shadow blocks in item previews so the outer wrap-around
			// armor plates stand out in crisp relief at 16x16 / 32x32 GUI resolution.
			poseStack.translate(group.centerX(), group.centerY(), group.centerZ());
			poseStack.scale(0.95F, 0.96F, 0.95F);
			poseStack.translate(-group.centerX(), -group.centerY(), -group.centerZ());
		}
		mesh.render(group, poseStack.last(), consumer, packedLight, color);
		poseStack.popPose();
	}

	private static EquipmentSlot armorSlot(ItemStack stack) {
		if (stack.is(ModItems.FLIGHT_HELMET)) return EquipmentSlot.HEAD;
		if (stack.is(ModItems.FLIGHT_CHESTPLATE)) return EquipmentSlot.CHEST;
		if (stack.is(ModItems.FLIGHT_LEGGINGS)) return EquipmentSlot.LEGS;
		if (stack.is(ModItems.FLIGHT_BOOTS)) return EquipmentSlot.FEET;
		return null;
	}

	private static EquipmentSlot slotForGroup(String rawName) {
		String name = rawName.toLowerCase(Locale.ROOT);
		if (hasAny(name, "left_boot", "boot_left", "right_boot", "boot_right", "ankle")) {
			return EquipmentSlot.FEET;
		}
		if (hasAny(name, "left_leg", "leg_left", "right_leg", "leg_right")) {
			return EquipmentSlot.LEGS;
		}
		if (hasAny(name, "left_arm", "arm_left", "right_arm", "arm_right")) {
			return EquipmentSlot.CHEST;
		}
		// Head wins before generic "core/spine/back" fragments; several faceplate groups
		// intentionally contain those material names but still belong to the helmet item.
		if (hasAny(name, "head", "helmet", "visor", "faceplate", "jaw", "temple", "brow")) {
			return EquipmentSlot.HEAD;
		}
		if (hasAny(name, "body", "chest", "torso", "core", "collar", "spine", "back", "vane", "wing")) {
			return EquipmentSlot.CHEST;
		}
		return null;
	}

	private static Offset anchorForGroup(String rawName) {
		String name = rawName.toLowerCase(Locale.ROOT);
		if (hasAny(name, "left_boot", "boot_left", "left_ankle", "ankle_left")) {
			return LEFT_BOOT_OFFSET;
		}
		if (hasAny(name, "right_boot", "boot_right", "right_ankle", "ankle_right")) {
			return RIGHT_BOOT_OFFSET;
		}
		if (hasAny(name, "left_arm", "arm_left")) return LEFT_ARM_OFFSET;
		if (hasAny(name, "right_arm", "arm_right")) return RIGHT_ARM_OFFSET;
		if (hasAny(name, "left_leg", "leg_left")) {
			return LEFT_LEG_OFFSET;
		}
		if (hasAny(name, "right_leg", "leg_right")) {
			return RIGHT_LEG_OFFSET;
		}
		return ZERO;
	}

	private static boolean isEmissive(String name) {
		return name.endsWith("_led") || name.contains("_led_") || name.startsWith("led_")
				|| name.contains("emissive") || name.contains("_glow") || name.startsWith("glow_");
	}

	private static ArmorObjMesh.Color baseColor(ArmorVariant variant, String name) {
		return FlightObjArmorRenderer.previewBaseColor(variant, name);
	}

	private static ArmorObjMesh.Color emissiveColor(ArmorVariant variant, String name) {
		if (name.contains("chin_chevron") || name.contains("command_node_led")) {
			return variant == ArmorVariant.SWIFT
					? new ArmorObjMesh.Color(196, 38, 112, 255)
					: variant == ArmorVariant.SENTINEL
					? new ArmorObjMesh.Color(178, 42, 44, 255)
					: new ArmorObjMesh.Color(212, 46, 52, 255);
		}
		if (name.contains("faceplate_v_edge")) {
			return new ArmorObjMesh.Color(24, 28, 36, 255);
		}
		if (variant == ArmorVariant.SWIFT) {
			if (name.contains("core_reactor") || name.contains("visor") || name.contains("blade")) {
				return new ArmorObjMesh.Color(255, 118, 250, 255);
			}
			return new ArmorObjMesh.Color(214, 72, 255, 255);
		}
		if (variant == ArmorVariant.SENTINEL) {
			if (name.contains("core_reactor") || name.contains("visor") || name.contains("muzzle")) {
				return new ArmorObjMesh.Color(255, 232, 138, 255);
			}
			if (name.contains("spine_sentinel") || name.contains("drone_pod_status")) {
				return new ArmorObjMesh.Color(64, 238, 255, 255);
			}
			return new ArmorObjMesh.Color(255, 184, 46, 255);
		}
		if (name.contains("core_reactor") || name.contains("visor")) {
			return new ArmorObjMesh.Color(148, 248, 255, 255);
		}
		return new ArmorObjMesh.Color(
				variant.lightRed(), variant.lightGreen(), variant.lightBlue(), 255);
	}

	private static boolean hasAny(String value, String... fragments) {
		for (String fragment : fragments) if (value.contains(fragment)) return true;
		return false;
	}

	private record Offset(float x, float y, float z) {}

	private record PreviewLayout(float centerX, float centerY, float centerZ, float pixelScale) {
		private static final PreviewLayout NAVIGATION_CORE =
				new PreviewLayout(0.0F, 3.70F, -3.02F, 0.32F);

		private static PreviewLayout forSlot(EquipmentSlot slot) {
			return switch (slot) {
				case HEAD -> new PreviewLayout(0.0F, -4.95F, -0.15F, 0.068F);
				case CHEST -> new PreviewLayout(0.0F, 5.85F, 0.10F, 0.048F);
				case LEGS -> new PreviewLayout(0.0F, 15.15F, 0.0F, 0.080F);
				case FEET -> new PreviewLayout(0.0F, 21.35F, 0.0F, 0.084F);
				default -> throw new IllegalArgumentException("Unsupported armor preview slot: " + slot);
			};
		}
	}
}
