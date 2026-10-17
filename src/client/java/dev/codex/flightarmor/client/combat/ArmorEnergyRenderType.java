package dev.codex.flightarmor.client.combat;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * 战甲专用无纹理能量层：在 MC 26.3 中映射至 RenderTypes.lightning() 与 debugQuads()，
 * 避免反射与 invoker，具备相同的加法混合、深度测试与无裁剪特性。
 */
public final class ArmorEnergyRenderType {
	public static final RenderType INSTANCE = RenderTypes.lightning();
	public static final RenderType FIELD_ALPHA = RenderTypes.debugQuads();

	private ArmorEnergyRenderType() {
	}

	public static void bootstrap() {
	}
}
