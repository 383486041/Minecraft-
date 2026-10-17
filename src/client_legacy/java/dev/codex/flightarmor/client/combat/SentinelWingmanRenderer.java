package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.client.render.obj.SentinelWingModuleMesh;
import dev.codex.flightarmor.combat.SentinelWingmanEntity;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Renders one physical outer-wing module after it separates from the Sentinel back assembly.
 *
 * <p>The geometry comes from the same Sentinel OBJ groups used by the worn armor. Deployment and
 * recall only rotate the real secondary vane, wing tip, pod and cannon around their hinges; this
 * renderer never grows a substitute aircraft and never fades or uniformly scales the module.</p>
 */
public final class SentinelWingmanRenderer extends EntityRenderer<SentinelWingmanEntity> {
	private static final ResourceLocation UNUSED_TEXTURE =
			FlightArmorMod.id("textures/entity/sentinel_wingman.png");

	public SentinelWingmanRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.18F;
		shadowStrength = 0.28F;
	}

	@Override
	public void render(SentinelWingmanEntity wingman, float yaw, float partialTick,
			PoseStack poses, MultiBufferSource buffers, int packedLight) {
		float phaseElapsed = Math.max(0.0F, wingman.level().getGameTime() + partialTick
				- wingman.getPhaseStartTick());
		Player owner = wingman.getOwnerId() == null ? null
				: wingman.level().players().stream()
						.filter(player -> wingman.getOwnerId().equals(player.getUUID()))
						.findFirst()
						.orElse(null);
		ArmorConfigurationAccess.ConfiguredSentinel configured = owner == null ? null
				: ArmorConfigurationAccess.configuredSentinel(owner).orElse(null);
		boolean sharedOwnershipClock = configured != null
				&& wingman.getSuitId() != null
				&& wingman.getSuitId().equals(configured.suitId());
		if (sharedOwnershipClock) {
			if (!ArmorConfigurationAccess.wingmenEntityOwnsGeometry(
					configured.snapshot(),
					wingman.level().getGameTime() + partialTick)) {
				return;
			}
		} else {
			// Identity may arrive one packet before the player's equipment snapshot. Until both
			// agree, retain the phase fallback instead of drawing a duplicate module.
			if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_DEPLOYING
					&& phaseElapsed < SentinelWingmanEntity.DEPLOY_HANDOFF_START_TICKS) {
				return;
			}
			if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_RETURNING
					&& phaseElapsed >= SentinelWingmanEntity.RECOMBINE_SHRINK_START_TICKS) {
				return;
			}
		}

		Minecraft client = Minecraft.getInstance();
		Entity camera = client.getCameraEntity();
		if (camera != null && client.options.getCameraType().isFirstPerson()
				&& wingman.getOwnerId() != null
				&& wingman.getOwnerId().equals(camera.getUUID())
				&& camera.distanceToSqr(wingman) < 0.36D) {
			// Binary near clipping prevents a docking plate crossing the first-person camera.
			// Third person is unaffected and there is deliberately no distance-based alpha.
			return;
		}

		poses.pushPose();
		Vec3 visualOffset = wingman.getClientVisualRenderOffset(partialTick);
		poses.translate(visualOffset.x, visualOffset.y, visualOffset.z);
		float interpolatedYaw = wingman.getClientVisualYaw(partialTick);
		poses.mulPose(Axis.YP.rotationDegrees(180.0F - interpolatedYaw));
		poses.mulPose(Axis.XP.rotationDegrees(wingman.getClientVisualPitch(partialTick)));
		/*
		 * 侧倾：模块的机首是模型局部 -Z（OBJ 的翼根→翼尖映射到 -Z），所以 Z 就是纵轴，
		 * 横滚必须加在 yaw/pitch 之后（链式展开后 Z 最先作用于顶点）—— 顺序错了会变成绕世界 Z 打转。
		 */
		poses.mulPose(Axis.ZP.rotationDegrees(wingman.getClientVisualRoll(partialTick)));

		float fold;
		if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_DEPLOYING) {
			fold = 1.0F - smoothstep(SentinelWingmanEntity.DEPLOY_HANDOFF_END_TICKS,
					SentinelWingmanEntity.DEPLOY_EJECT_END_TICKS, phaseElapsed);
		} else if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_RETURNING) {
			fold = smoothstep(10.0F,
					SentinelWingmanEntity.RECOMBINE_SHRINK_START_TICKS, phaseElapsed);
		} else {
			fold = 0.0F;
		}
		double pulse = 0.5D + 0.5D * Math.sin(
				(wingman.tickCount + partialTick) * 0.32D + wingman.getShotSequence() * 1.7D);
		SentinelWingModuleMesh.render(poses, buffers, packedLight,
				wingman.getSide(), fold, (float) pulse);
		poses.popPose();
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / Math.max(0.001F, end - start),
				0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	@Override
	public ResourceLocation getTextureLocation(SentinelWingmanEntity entity) {
		return UNUSED_TEXTURE;
	}
}
