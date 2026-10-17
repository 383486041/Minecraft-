package dev.codex.flightarmor.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 仅用于建立本模组自己的 POSITION_COLOR 能量几何层。 */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {
	@Invoker("create")
	static RenderType.CompositeRenderType flightArmor$create(
			String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
			boolean affectsCrumbling, boolean sortOnUpload, RenderType.CompositeState state) {
		throw new AssertionError("mixin invoker 未应用");
	}
}
