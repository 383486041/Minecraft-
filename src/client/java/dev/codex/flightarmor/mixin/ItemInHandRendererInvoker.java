package dev.codex.flightarmor.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's complete mirrored first-person arm transform for synthetic dual blades. */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererInvoker {
	@Invoker("renderPlayerArm")
	void flightArmor$renderPlayerArm(PoseStack poseStack, MultiBufferSource buffers,
			int packedLight, float equipProgress, float swingProgress, HumanoidArm arm);
}