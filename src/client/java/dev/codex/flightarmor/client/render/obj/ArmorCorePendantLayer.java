package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.client.core.ArmorCoreClient;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 装在战甲胸口的导航核心实体挂件。
 *
 * <p>装甲核心原先完全不可见，只能靠面板里的文字确认它在不在。这一层把它做成真实几何，
 * 挂在 {@code HumanoidModel.body} 上，因此第三人称、其它玩家视角、以及随身体姿态摆动都能
 * 自然跟随，不需要额外的骨骼同步。</p>
 *
 * <p>几何直接用顶点写出，不新增 OBJ 资源：外壳走 {@link FlightObjArmorRenderer#BASE_TEXTURE}
 * 的裁剪通道，核心本体走自发光通道并随 tick 轻微脉动，用来区分「认主生效」与「未认主」。
 * 顶点写法刻意与 {@code ArmorObjMesh#render} 保持同一套 API，避免与工程既有的渲染约定分叉。</p>
 *
 * <p><b>坐标口径（踩过）</b>：{@code body.translateAndRotate} 之后必须执行
 * {@code poseStack.scale(1/16, 1/16, 1/16)} 才能进入模型像素空间（1 单位 = 1/16 格），
 * 且 {@code y = 0} 是<b>肩</b>、{@code y = 12} 是胯、{@code y = -8} 才是头顶。所以胸口是<b>正值</b>
 * {@code y 2.4~5.0}，与 OBJ 里 {@code core_cradle/housing/reactor} 的区间一致。</p>
 */
public final class ArmorCorePendantLayer
		extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	/** 挂件外壳：与战甲的 gunmetal 同色系，保证核心看起来属于这套甲。 */
	private static final int SHELL = 0xFF2A3540;
	/** 接缝金属条，只在核心四周露出一圈，避免整块看起来像贴纸。 */
	private static final int RIM = 0xFF8DA6B4;
	/** 核心自发光基色。 */
	private static final int CORE_RGB = 0x0030ECFF;
	/** 接缝相对核心槽的收缩系数（比 OBJ 的 core_housing 大 0.05，恰好露出一圈）。 */
	private static final float RIM_SCALE = 0.78F;
	/** 发光芯相对核心槽的收缩系数（落在 OBJ 的 core_reactor 之内，贴在它前面）。 */
	private static final float CORE_SCALE = 0.45F;

	public ArmorCorePendantLayer(
			RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
			AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTick,
			float ageInTicks, float netHeadYaw, float headPitch) {
		Minecraft client = Minecraft.getInstance();
		if (player == client.player && client.options.getCameraType().isFirstPerson()) {
			return;
		}
		// 核心是装在胸甲上的组件：没有胸甲就没有可挂载的基座，宁可不画也不要浮在裸身上。
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		if (!chest.is(ModItems.FLIGHT_CHESTPLATE)) return;
		if (!ArmorCoreClient.snapshot().coreEquipped()) return;

		poseStack.pushPose();
		getParentModel().body.translateAndRotate(poseStack);
		poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);

		// 挂件尺寸随型号走：三个型号的核心槽差得很远（疾影半宽 1.09 / 先锋 1.37 / 守望 1.78），
		// 固定尺寸在任一型号上不是撑破槽就是塞不满 —— 现在三层同心叠在槽面之前，由大到小各让出一圈。
		CoreExtent core = coreExtent(chest);
		float centerY = (core.y0() + core.y1()) * 0.5F;
		float halfHeight = (core.y1() - core.y0()) * 0.5F;
		float front = core.zFront();

		// 纯白纹理 + 顶点色：u/v 取固定点即可得到平色，与 OBJ 通道完全一致。
		VertexConsumer shell = buffers.getBuffer(
				RenderType.armorCutoutNoCull(FlightObjArmorRenderer.BASE_TEXTURE));
		// 外壳：罩住整个槽，四周各让 0.04、前板让 0.10，避免与 OBJ 的 core_cradle 共面闪烁。
		box(shell, poseStack.last(), packedLight, SHELL,
				-core.halfWidth() - 0.04F, core.y0() - 0.04F, front - 0.10F,
				core.halfWidth() + 0.04F, core.y1() + 0.04F, core.zBack() + 0.06F);
		// 接缝：缩到 0.78，在核心四周露出一圈金属边。
		box(shell, poseStack.last(), packedLight, RIM,
				-core.halfWidth() * RIM_SCALE, centerY - halfHeight * RIM_SCALE, front - 0.30F,
				core.halfWidth() * RIM_SCALE, centerY + halfHeight * RIM_SCALE, front - 0.16F);

		/*
		 * 不能先取自发光 consumer 再画外壳：MultiBufferSource 在切换 RenderType 时会关闭上一个
		 * builder，继续用旧 consumer 写顶点会抛 IllegalStateException("Not building!")。
		 */
		float pulse = 0.72F + 0.28F * Mth.sin((player.tickCount + partialTick) * 0.12F);
		int coreColor = (Mth.clamp((int) (pulse * 255.0F), 0, 255) << 24) | CORE_RGB;
		VertexConsumer emissive = buffers.getBuffer(
				RenderType.entityTranslucentEmissive(FlightObjArmorRenderer.EMISSIVE_TEXTURE));
		box(emissive, poseStack.last(), LightTexture.FULL_BRIGHT, coreColor,
				-core.halfWidth() * CORE_SCALE, centerY - halfHeight * CORE_SCALE, front - 0.42F,
				core.halfWidth() * CORE_SCALE, centerY + halfHeight * CORE_SCALE, front - 0.30F);

		poseStack.popPose();
	}

	/** 核心槽在 {@code body} 局部空间的实测尺寸：半宽、y 起止、z 起止（z 越小越靠前）。 */
	private record CoreExtent(float halfWidth, float y0, float y1, float zFront, float zBack) {
	}

	/**
	 * 尺寸取自各型号 OBJ 里 {@code core_cradle} 的包围盒（1 单位 = 1/16 格）。
	 *
	 * <p>三个型号的核心槽并不一样大，固定尺寸必然在某个型号上失准；型号由胸甲的
	 * CustomData 解析，缺省回退先锋型（与 {@link ArmorVariantData#get} 的语义一致）。</p>
	 */
	private static CoreExtent coreExtent(ItemStack chest) {
		return switch (ArmorVariantData.get(chest)) {
			case SWIFT -> new CoreExtent(1.37F, 2.18F, 5.22F, -3.25F, -2.70F);
			case SENTINEL -> new CoreExtent(1.84F, 1.96F, 5.30F, -3.68F, -3.10F);
			case VANGUARD -> new CoreExtent(1.61F, 2.18F, 5.22F, -3.25F, -2.70F);
		};
	}

	/** 以 body 的局部像素坐标（1 单位 = 1/16 格）画一个长方体。 */
	private static void box(VertexConsumer consumer, PoseStack.Pose pose, int light, int argb,
			float x0, float y0, float z0, float x1, float y1, float z1) {
		// 模型空间里 -Z 是正面，挂件因此从胸口朝前凸出。
		face(consumer, pose, light, argb, 0.0F, 0.0F, -1.0F,
				x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
		face(consumer, pose, light, argb, 0.0F, 0.0F, 1.0F,
				x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		face(consumer, pose, light, argb, -1.0F, 0.0F, 0.0F,
				x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		face(consumer, pose, light, argb, 1.0F, 0.0F, 0.0F,
				x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
		face(consumer, pose, light, argb, 0.0F, -1.0F, 0.0F,
				x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
		face(consumer, pose, light, argb, 0.0F, 1.0F, 0.0F,
				x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
	}

	/**
	 * 正反两面各写一遍。工程里不同 RenderType 的背面剔除策略并不一致（外壳通道带 NoCull，
	 * 自发光通道不带），双面写出可以保证挂件在任何通道里都不会因为绕序而缺面。
	 */
	private static void face(VertexConsumer consumer, PoseStack.Pose pose, int light, int argb,
			float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		vertex(consumer, pose, light, argb, ax, ay, az, nx, ny, nz);
		vertex(consumer, pose, light, argb, bx, by, bz, nx, ny, nz);
		vertex(consumer, pose, light, argb, cx, cy, cz, nx, ny, nz);
		vertex(consumer, pose, light, argb, dx, dy, dz, nx, ny, nz);
		vertex(consumer, pose, light, argb, dx, dy, dz, -nx, -ny, -nz);
		vertex(consumer, pose, light, argb, cx, cy, cz, -nx, -ny, -nz);
		vertex(consumer, pose, light, argb, bx, by, bz, -nx, -ny, -nz);
		vertex(consumer, pose, light, argb, ax, ay, az, -nx, -ny, -nz);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, int argb,
			float x, float y, float z, float nx, float ny, float nz) {
		consumer.addVertex(pose, x, y, z)
				.setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
				.setUv(0.5F, 0.5F)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, nx, ny, nz);
	}
}
