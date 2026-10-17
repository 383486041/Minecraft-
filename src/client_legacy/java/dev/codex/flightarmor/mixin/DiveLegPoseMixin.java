package dev.codex.flightarmor.mixin;

import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.animation.AttackPoseController;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyPart;
import dev.codex.flightarmor.client.cruise.ArmorCruiseClient;
import dev.codex.flightarmor.item.ModItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class DiveLegPoseMixin<T extends LivingEntity> {
	@Shadow public ModelPart rightLeg;
	@Shadow public ModelPart leftLeg;
	@Shadow public ModelPart body;
	@Shadow public ModelPart rightArm;
	@Shadow public ModelPart leftArm;
	@Shadow public ModelPart head;
	@Shadow public ModelPart hat;

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void flightArmor$applyFlightAndAssemblyPose(T entity, float limbSwing, float limbSwingAmount,
			float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
		if (!(entity instanceof Player player)) return;

		boolean wearingFlightHelmet = player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.FLIGHT_HELMET);
		boolean assembling = ArmorAssemblyClient.isAnimating(player);
		boolean headAnimating = assembling && ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.HEAD);
		float partialTick = ageInTicks - player.tickCount;
		float helmetProgress = headAnimating
				? ArmorAssemblyClient.partProgress(player, ArmorAssemblyPart.HELMET_SHELL, partialTick)
				: 1.0F;
		// Keep the player head visible until the recalled helmet shell has aligned.
		boolean hidePlayerHead = wearingFlightHelmet && (!headAnimating || helmetProgress >= 0.68F);
		head.visible = !hidePlayerHead;
		hat.visible = !hidePlayerHead;

		boolean flightActive = (ModItems.hasFlightGear(player) || ArmorCruiseClient.isBootHoverReady(player))
				&& FlightArmorClient.isFlightEffectActive(player);
		boolean attackActive = AttackPoseController.isActive(player);
		if (!assembling && !flightActive && !attackActive) return;

		float assemblyProgress = assembling
				? ArmorAssemblyClient.assemblyProgress(player, partialTick)
				: 1.0F;
		float acceptingStance = assembling ? 1.0F - smoothstep(0.86F, 1.0F, assemblyProgress) : 0.0F;
		float armStance = ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.CHEST) ? acceptingStance : 0.0F;
		float legStance = ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.FEET)
				|| ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.LEGS) ? acceptingStance : 0.0F;

		// Reset vanilla model pivots; Player Animator owns all animated rotations.
		body.setPos(0.0F, 0.0F, 0.0F);
		head.setPos(0.0F, 0.0F, 0.0F);
		leftArm.setPos(5.0F + 0.18F * armStance, 2.0F, 0.0F);
		rightArm.setPos(-5.0F - 0.12F * armStance, 2.0F, 0.0F);
		// Separate the feet slightly during assembly, then settle without a pose pop.
		leftLeg.setPos(1.9F + 0.45F * legStance, 12.0F, 0.0F);
		rightLeg.setPos(-1.9F - 0.45F * legStance, 12.0F, 0.0F);

		/* Joint rotations and bends are owned by FlightJointAnimation's Player Animator layer. */
		hat.copyFrom(head);
		head.visible = !hidePlayerHead;
		hat.visible = !hidePlayerHead;
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}
}
