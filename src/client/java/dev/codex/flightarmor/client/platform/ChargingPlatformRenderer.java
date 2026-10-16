package dev.codex.flightarmor.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.codex.flightarmor.mixin.RenderTypeInvoker;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.platform.ModPlatformContent;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.Rotations;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 充能平台的动态战甲陈列。
 *
 * <p>使用不可交互、不可见的客户端 ArmorStand 作为骨骼预览载体，因此平台复用现有 OBJ
 * 装甲渲染器和常亮纹路，不产生第二套贴片模型。预览实体从不加入世界，也不保存或同步。</p>
 */
public final class ChargingPlatformRenderer implements BlockEntityRenderer<ChargingPlatformBlockEntity> {
	private static final EquipmentSlot[] SLOT_BY_INDEX = {
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD
	};
	private static boolean initialized;

	private static final int ASSEMBLED_PREVIEW_ID = -94100;
	private static final int PART_PREVIEW_ID_BASE = -94110;
	private static final float ASSEMBLED_DISPLAY_YAW = 180.0F;
	/** 平台预览不是世界实体，年龄始终固定，避免装甲渲染层读取时间产生旋转或呼吸动画。 */
	private static final int STATIC_PREVIEW_TICK = 0;
	private static final int PLATFORM_VIEW_DISTANCE = 48;
	private static final double LOCK_DETAIL_DISTANCE_SQUARED = 24.0D * 24.0D;
	private static final float[] LOCK_DX = {0.0F, 1.0F, 0.0F, -1.0F};
	private static final float[] LOCK_DZ = {1.0F, 0.0F, -1.0F, 0.0F};
	private static final float[] LOCK_YAW = {0.0F, 90.0F, 180.0F, 270.0F};
	private static final LockColor LOCK_RAIL = new LockColor(58, 65, 74, 255);
	private static final LockColor LOCK_BODY = new LockColor(148, 168, 181, 255);
	private static final LockColor LOCK_CAP = new LockColor(196, 218, 228, 255);
	private static final LockColor LOCK_LIGHT = new LockColor(80, 242, 255, 255);

	private ArmorStand assembledPreview;
	private final Map<EquipmentSlot, ArmorStand> partPreviews = new EnumMap<>(EquipmentSlot.class);
	private Level previewLevel;

	public ChargingPlatformRenderer(BlockEntityRendererProvider.Context context) {
	}

	/** 在客户端入口调用一次；保持中央入口只承担一行接线。 */
	public static void initialize() {
		if (initialized) return;
		initialized = true;
		BlockEntityRenderers.register(ModPlatformContent.CHARGING_PLATFORM_BLOCK_ENTITY,
				ChargingPlatformRenderer::new);
	}

	@Override
	public void render(ChargingPlatformBlockEntity platform, float partialTick, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		Level level = platform.getLevel();
		if (level == null) return;
		List<ItemStack> items = platform.getArmorItems();
		if (items.stream().allMatch(ItemStack::isEmpty)) return;

		ChargingPlatformVisualState.Sample sample = ChargingPlatformVisualState.sample(platform, partialTick);
		if (sample.assembledScale() > 0.001F) {
			renderAssembled(level, items, sample.assembledScale(), poseStack, buffers,
					packedLight, partialTick);
		}
		if (sample.partsScale() > 0.001F) {
			renderParts(level, items, sample.partsScale(), poseStack, buffers,
					packedLight, partialTick);
		}
		if (items.stream().noneMatch(ItemStack::isEmpty)) {
			boolean detailedLocks = sample.transitioning() || isNearLockDetailRange(platform);
			renderMechanicalLocks(sample.lockProgress(), detailedLocks, poseStack, buffers);
		}
	}

	@Override
	public int getViewDistance() {
		return PLATFORM_VIEW_DISTANCE;
	}

	private void renderAssembled(Level level, List<ItemStack> items,
			float modeScale, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, float partialTick) {
		ArmorStand stand = assembledPreview(level);
		syncEquipment(stand, items, null);
		stand.setHeadPose(new Rotations(-2.0F, 0.0F, 0.0F));
		stand.setBodyPose(new Rotations(0.0F, 0.0F, 0.0F));
		stand.setLeftArmPose(new Rotations(-3.0F, 0.0F, -7.0F));
		stand.setRightArmPose(new Rotations(-3.0F, 0.0F, 7.0F));
		stand.setLeftLegPose(new Rotations(0.0F, 0.0F, -1.5F));
		stand.setRightLegPose(new Rotations(0.0F, 0.0F, 1.5F));

		float scale = 0.70F * modeScale;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.625F, 0.5F);
		poseStack.scale(scale, scale, scale);
		renderPreview(stand, ASSEMBLED_DISPLAY_YAW, partialTick, poseStack, buffers, packedLight);
		poseStack.popPose();
	}

	private void renderParts(Level level, List<ItemStack> items,
			float modeScale, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, float partialTick) {

		for (int index = 0; index < SLOT_BY_INDEX.length; index++) {
			if (index >= items.size() || items.get(index).isEmpty()) continue;
			EquipmentSlot slot = SLOT_BY_INDEX[index];
			// 每个槽位固定使用一个独立实体，避免同帧换槽污染盔甲层缓存。
			ArmorStand stand = partPreview(level, slot, index);
			syncEquipment(stand, items, slot);
			PartPlacement placement = PartPlacement.forSlot(slot);
			float scale = placement.scale() * modeScale;

			poseStack.pushPose();
			poseStack.translate(placement.x(), placement.y(), placement.z());
			poseStack.scale(scale, scale, scale);
			// 四个托位保持固定朝向；切换模式时只缩放，不改变部件位置与姿态。
			renderPreview(stand, placement.yawOffset(), partialTick, poseStack, buffers, packedLight);
			poseStack.popPose();
		}
	}


	/**
	 * 四组锁臂只随模式切换收放；稳定待机时矩阵恒定，装甲和锁臂都不会循环运动。
	 */
	private static boolean isNearLockDetailRange(ChargingPlatformBlockEntity platform) {
		var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		var pos = platform.getBlockPos();
		double dx = pos.getX() + 0.5D - camera.x;
		double dy = pos.getY() + 0.8D - camera.y;
		double dz = pos.getZ() + 0.5D - camera.z;
		return dx * dx + dy * dy + dz * dz <= LOCK_DETAIL_DISTANCE_SQUARED;
	}

	private static void renderMechanicalLocks(float rawProgress, boolean detailed,
			PoseStack poseStack, MultiBufferSource buffers) {
		float progress = Math.max(0.0F, Math.min(1.0F, rawProgress));
		VertexConsumer vertices = buffers.getBuffer(MechanicalLockRenderType.INSTANCE);
		for (int index = 0; index < 4; index++) {
			float yaw = LOCK_YAW[index];
			float dx = LOCK_DX[index];
			float dz = LOCK_DZ[index];
			float outerDistance = 0.39F;
			float innerDistance = outerDistance - 0.21F * progress;
			float railDistance = (outerDistance + innerDistance) * 0.5F;
			float railLength = 0.14F + 0.22F * progress;
			float strutHeight = 0.09F + 0.26F * progress;
			float strutBottom = 0.345F;

			if (detailed) {
				renderCuboid(vertices, poseStack, 0.5F + dx * railDistance, 0.335F,
						0.5F + dz * railDistance, 0.105F, 0.065F, railLength, yaw, LOCK_RAIL);
				renderCuboid(vertices, poseStack, 0.5F + dx * innerDistance, strutBottom,
						0.5F + dz * innerDistance, 0.13F, strutHeight, 0.13F, yaw, LOCK_BODY);
				renderCuboid(vertices, poseStack, 0.5F + dx * innerDistance,
						strutBottom + strutHeight - 0.015F,
						0.5F + dz * innerDistance, 0.19F, 0.10F, 0.12F, yaw, LOCK_CAP);
			} else {
				// 稳定远景把三段锁臂合成一段，不再重复提交完整方块模型。
				renderCuboid(vertices, poseStack, 0.5F + dx * innerDistance, strutBottom,
						0.5F + dz * innerDistance, 0.15F, strutHeight + 0.075F,
						0.14F, yaw, LOCK_BODY);
			}
			renderCuboid(vertices, poseStack, 0.5F + dx * (innerDistance - 0.007F),
					strutBottom + strutHeight + 0.072F,
					0.5F + dz * (innerDistance - 0.007F), 0.075F, 0.018F, 0.075F,
					yaw, LOCK_LIGHT);
		}
	}

	private static void renderCuboid(VertexConsumer vertices, PoseStack poseStack,
			float x, float y, float z, float width, float height, float depth,
			float yaw, LockColor color) {
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
		poseStack.scale(width, height, depth);
		poseStack.translate(-0.5F, 0.0F, -0.5F);
		PoseStack.Pose pose = poseStack.last();
		emitFace(vertices, pose, color.shade(0.62F),
				0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0);
		emitFace(vertices, pose, color,
				0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1);
		emitFace(vertices, pose, color.shade(0.76F),
				0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
		emitFace(vertices, pose, color.shade(0.88F),
				0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1);
		emitFace(vertices, pose, color.shade(0.70F),
				0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1);
		emitFace(vertices, pose, color.shade(0.94F),
				1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0);
		poseStack.popPose();
	}

	private static void emitFace(VertexConsumer vertices, PoseStack.Pose pose, LockColor color,
			float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3) {
		emitVertex(vertices, pose, x0, y0, z0, color);
		emitVertex(vertices, pose, x1, y1, z1, color);
		emitVertex(vertices, pose, x2, y2, z2, color);
		emitVertex(vertices, pose, x3, y3, z3, color);
	}

	private static void emitVertex(VertexConsumer vertices, PoseStack.Pose pose,
			float x, float y, float z, LockColor color) {
		vertices.addVertex(pose, x, y, z)
				.setColor(color.red(), color.green(), color.blue(), color.alpha());
	}

	private ArmorStand assembledPreview(Level level) {
		ensurePreviewLevel(level);
		if (assembledPreview == null) {
			assembledPreview = createPreview(level, ASSEMBLED_PREVIEW_ID);
		}
		freezePreview(assembledPreview, level);
		return assembledPreview;
	}

	private ArmorStand partPreview(Level level, EquipmentSlot slot, int index) {
		ensurePreviewLevel(level);
		ArmorStand stand = partPreviews.computeIfAbsent(slot,
				ignored -> createPreview(level, PART_PREVIEW_ID_BASE - index));
		freezePreview(stand, level);
		return stand;
	}

	private void ensurePreviewLevel(Level level) {
		if (previewLevel == level) return;
		previewLevel = level;
		assembledPreview = null;
		partPreviews.clear();
	}

	private static ArmorStand createPreview(Level level, int entityId) {
		ArmorStand stand = new ArmorStand(level, 0.0D, 0.0D, 0.0D);
		stand.setId(entityId);
		stand.setInvisible(true);
		stand.setNoBasePlate(true);
		stand.setShowArms(true);
		stand.setNoGravity(true);
		stand.setSilent(true);
		stand.setHeadPose(new Rotations(0.0F, 0.0F, 0.0F));
		stand.setBodyPose(new Rotations(0.0F, 0.0F, 0.0F));
		stand.setLeftArmPose(new Rotations(0.0F, 0.0F, -4.0F));
		stand.setRightArmPose(new Rotations(0.0F, 0.0F, 4.0F));
		stand.setLeftLegPose(new Rotations(0.0F, 0.0F, 0.0F));
		stand.setRightLegPose(new Rotations(0.0F, 0.0F, 0.0F));
		freezePreview(stand, level);
		return stand;
	}

	private static void freezePreview(ArmorStand stand, Level level) {
		stand.tickCount = STATIC_PREVIEW_TICK;
		stand.lastHit = level.getGameTime() - 1000L;
		stand.setDeltaMovement(0.0D, 0.0D, 0.0D);
	}

	private static void syncEquipment(ArmorStand stand, List<ItemStack> items, EquipmentSlot onlySlot) {
		for (int index = 0; index < SLOT_BY_INDEX.length; index++) {
			EquipmentSlot slot = SLOT_BY_INDEX[index];
			ItemStack desired = index < items.size() && (onlySlot == null || onlySlot == slot)
					? items.get(index) : ItemStack.EMPTY;
			if (!ItemStack.matches(stand.getItemBySlot(slot), desired)) {
				stand.setItemSlot(slot, desired.copy());
			}
		}
	}

	private static void renderPreview(ArmorStand stand, float yaw, float partialTick,
			PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		stand.setYRot(yaw);
		stand.yRotO = yaw;
		stand.setYBodyRot(yaw);
		stand.yBodyRotO = yaw;
		stand.setYHeadRot(yaw);
		stand.yHeadRotO = yaw;
		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		// 固定 partial tick，隔离平台陈列与实体年龄、客户端帧插值。
		dispatcher.render(stand, 0.0D, 0.0D, 0.0D, yaw, 0.0F,
				poseStack, buffers, packedLight);
	}

	private record LockColor(int red, int green, int blue, int alpha) {
		private LockColor shade(float multiplier) {
			return new LockColor(
					Math.round(red * multiplier),
					Math.round(green * multiplier),
					Math.round(blue * multiplier),
					alpha);
		}
	}

	private static final class MechanicalLockRenderType extends RenderStateShard {
		private static final RenderType INSTANCE = RenderTypeInvoker.flightArmor$create(
				"flight_armor_platform_mechanical_locks",
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

		private MechanicalLockRenderType() {
			super("flight_armor_platform_mechanical_lock_state", () -> { }, () -> { });
		}
	}

	private record PartPlacement(float x, float y, float z, float scale, float yawOffset) {
		private static PartPlacement forSlot(EquipmentSlot slot) {
			return switch (slot) {
				case FEET -> new PartPlacement(0.25F, 0.41F, 0.75F, 0.47F, -20.0F);
				case LEGS -> new PartPlacement(0.75F, 0.03F, 0.75F, 0.42F, 20.0F);
				case CHEST -> new PartPlacement(0.75F, -0.18F, 0.25F, 0.37F, -16.0F);
				case HEAD -> new PartPlacement(0.25F, -0.42F, 0.25F, 0.41F, 16.0F);
				default -> throw new IllegalArgumentException("不是装甲槽：" + slot);
			};
		}
	}
}
