package dev.codex.flightarmor.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.codex.flightarmor.client.render.obj.ArmMountedBladeRenderer;
import dev.codex.flightarmor.client.render.obj.FlightObjArmorRenderer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** 为原版第一人称左右手补上对应的 OBJ 战甲外壳。 */
@Mixin(PlayerRenderer.class)
public abstract class FirstPersonArmorArmMixin {
	@WrapMethod(method = "renderRightHand")
	private void flightArmor$renderRightArmWithArmor(PoseStack poseStack, MultiBufferSource buffers,
			int packedLight, AbstractClientPlayer player, Operation<Void> original) {
		poseStack.pushPose();
		try {
			original.call(poseStack, buffers, packedLight, player);
			PlayerRenderer renderer = (PlayerRenderer) (Object) this;
			@SuppressWarnings("unchecked")
			PlayerModel<AbstractClientPlayer> model = renderer.getModel();
			FlightObjArmorRenderer.renderFirstPersonArm(
					poseStack, buffers, packedLight, player, model, false);
			ArmMountedBladeRenderer.renderFirstPerson(
					poseStack, buffers, player, model, false);
		} finally {
			poseStack.popPose();
		}
	}

	@WrapMethod(method = "renderLeftHand")
	private void flightArmor$renderLeftArmWithArmor(PoseStack poseStack, MultiBufferSource buffers,
			int packedLight, AbstractClientPlayer player, Operation<Void> original) {
		poseStack.pushPose();
		try {
			original.call(poseStack, buffers, packedLight, player);
			PlayerRenderer renderer = (PlayerRenderer) (Object) this;
			@SuppressWarnings("unchecked")
			PlayerModel<AbstractClientPlayer> model = renderer.getModel();
			FlightObjArmorRenderer.renderFirstPersonArm(
					poseStack, buffers, packedLight, player, model, true);
			ArmMountedBladeRenderer.renderFirstPerson(
					poseStack, buffers, player, model, true);
		} finally {
			poseStack.popPose();
		}
	}
}
