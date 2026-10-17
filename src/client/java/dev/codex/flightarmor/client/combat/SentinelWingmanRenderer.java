package dev.codex.flightarmor.client.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.codex.flightarmor.client.render.obj.SentinelWingModuleMesh;
import dev.codex.flightarmor.combat.SentinelWingmanEntity;
import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Renders one physical outer-wing module after it separates from the Sentinel back assembly (MC 26.3).
 */
public final class SentinelWingmanRenderer extends EntityRenderer<SentinelWingmanEntity, SentinelWingmanRenderer.WingmanRenderState> {

	public SentinelWingmanRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.18F;
		shadowStrength = 0.28F;
	}

	@Override
	public WingmanRenderState createRenderState() {
		return new WingmanRenderState();
	}

	@Override
	public void extractRenderState(SentinelWingmanEntity wingman, WingmanRenderState state, float partialTick) {
		super.extractRenderState(wingman, state, partialTick);

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
				state.skipRender = true;
				return;
			}
		} else {
			if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_DEPLOYING
					&& phaseElapsed < SentinelWingmanEntity.DEPLOY_HANDOFF_START_TICKS) {
				state.skipRender = true;
				return;
			}
			if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_RETURNING
					&& phaseElapsed >= SentinelWingmanEntity.RECOMBINE_SHRINK_START_TICKS) {
				state.skipRender = true;
				return;
			}
		}

		Minecraft client = Minecraft.getInstance();
		Entity camera = client.getCameraEntity();
		if (camera != null && client.options.getCameraType().isFirstPerson()
				&& wingman.getOwnerId() != null
				&& wingman.getOwnerId().equals(camera.getUUID())
				&& camera.distanceToSqr(wingman) < 0.36D) {
			state.skipRender = true;
			return;
		}

		state.skipRender = false;
		state.visualOffset = wingman.getClientVisualRenderOffset(partialTick);
		state.yaw = wingman.getClientVisualYaw(partialTick);
		state.pitch = wingman.getClientVisualPitch(partialTick);
		state.roll = wingman.getClientVisualRoll(partialTick);
		state.side = wingman.getSide();

		if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_DEPLOYING) {
			state.fold = 1.0F - smoothstep(SentinelWingmanEntity.DEPLOY_HANDOFF_END_TICKS,
					SentinelWingmanEntity.DEPLOY_EJECT_END_TICKS, phaseElapsed);
		} else if (wingman.getDeploymentPhase() == SentinelWingmanEntity.PHASE_RETURNING) {
			state.fold = smoothstep(10.0F,
					SentinelWingmanEntity.RECOMBINE_SHRINK_START_TICKS, phaseElapsed);
		} else {
			state.fold = 0.0F;
		}
		double pulse = 0.5D + 0.5D * Math.sin(
				(wingman.tickCount + partialTick) * 0.32D + wingman.getShotSequence() * 1.7D);
		state.pulse = (float) pulse;
		state.packedLight = state.lightCoords;
	}

	@Override
	public void submit(WingmanRenderState state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.skipRender) return;

		poses.pushPose();
		Vec3 visualOffset = state.visualOffset;
		poses.translate(visualOffset.x, visualOffset.y, visualOffset.z);
		poses.rotate(Axis.YP.rotationDegrees(180.0F - state.yaw));
		poses.rotate(Axis.XP.rotationDegrees(state.pitch));
		poses.rotate(Axis.ZP.rotationDegrees(state.roll));

		SentinelWingModuleMesh.render(poses, collector, state.packedLight,
				state.side, state.fold, state.pulse);
		poses.popPose();
	}

	private static float smoothstep(float start, float end, float value) {
		float progress = Mth.clamp((value - start) / Math.max(0.001F, end - start),
				0.0F, 1.0F);
		return progress * progress * (3.0F - 2.0F * progress);
	}

	public static final class WingmanRenderState extends EntityRenderState {
		boolean skipRender;
		Vec3 visualOffset = Vec3.ZERO;
		float yaw;
		float pitch;
		float roll;
		int side;
		float fold;
		float pulse;
		int packedLight;
	}
}
