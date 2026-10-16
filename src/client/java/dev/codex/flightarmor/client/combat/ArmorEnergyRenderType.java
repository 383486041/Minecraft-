package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.codex.flightarmor.mixin.RenderTypeInvoker;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 战甲专用无纹理能量层：主目标、深度测试、只写颜色、不写深度，
 * 避免多层半透明棱柱互相遮挡，也不借用任何原版特效几何。
 */
public final class ArmorEnergyRenderType extends RenderStateShard {
	public static final RenderType INSTANCE = RenderTypeInvoker.flightArmor$create(
			"flight_armor_energy_mesh",
			DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS,
			8192,
				false,
				false,
			RenderType.CompositeState.builder()
					.setShaderState(POSITION_COLOR_SHADER)
					.setTextureState(NO_TEXTURE)
					.setTransparencyState(LIGHTNING_TRANSPARENCY)
					.setDepthTestState(LEQUAL_DEPTH_TEST)
					.setCullState(NO_CULL)
					.setLightmapState(NO_LIGHTMAP)
					.setOverlayState(NO_OVERLAY)
					.setWriteMaskState(COLOR_WRITE)
					.setOutputState(MAIN_TARGET)
					.createCompositeState(false));
	public static final RenderType FIELD_ALPHA = RenderTypeInvoker.flightArmor$create(
			"flight_armor_energy_field_alpha",
			DefaultVertexFormat.POSITION_COLOR,
			VertexFormat.Mode.QUADS,
			8192,
				false,
				false,
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

	private ArmorEnergyRenderType() {
		super("flight_armor_energy_state", () -> { }, () -> { });
	}

	/** 由客户端初始化阶段显式触发类加载，尽早暴露 mixin/invoker 配置错误。 */
	public static void bootstrap() {
		if (FIELD_ALPHA == null) {
			throw new IllegalStateException("Armor energy alpha-field render layer unavailable");
		}
		if (INSTANCE == null) throw new IllegalStateException("战甲能量渲染层初始化失败");
	}
}
