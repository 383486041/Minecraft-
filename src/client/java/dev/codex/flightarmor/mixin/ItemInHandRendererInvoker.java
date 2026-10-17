package dev.codex.flightarmor.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's complete mirrored first-person arm transform for synthetic dual blades. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public interface ItemInHandRendererInvoker {
	@Invoker("renderPlayerArm")
	void flightArmor$renderPlayerArm(PoseStack poseStack, SubmitNodeCollector collector,
			int packedLight, float equipProgress, float swingProgress, HumanoidArm arm,
			PlayerRenderState playerState);
}
