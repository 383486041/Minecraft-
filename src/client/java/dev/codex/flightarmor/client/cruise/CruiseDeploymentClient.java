package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Client view of the explicit whole-suit deployment phase.
 *
 * <p>Because the server owns DEPLOYING, reconnecting to an existing CRUISING
 * carrier and rolling DOCKING back to CRUISING cannot replay this animation.</p>
 */
public final class CruiseDeploymentClient {
	private static final Map<UUID, HandoffAnchor> HANDOFF_ANCHORS = new HashMap<>();

	private CruiseDeploymentClient() {
	}

	public static CruiseDeploymentTimeline.Sample playerSample(Player player, float partialTick) {
		ArmorCruiseClient.CruiseState state = stateForOwner(player.getUUID());
		CruiseDeploymentTimeline.Sample sample = sample(state, partialTick);
		if (sample == null) return null;
		rememberHandoffAnchor(state, player, sample, partialTick);
		return sample.playerOwnsVisual() || !carrierReady(state) ? sample : null;
	}

	public static boolean suppressWornArmor(Player player, float partialTick) {
		return sample(stateForOwner(player.getUUID()), partialTick) != null;
	}

	public static CruiseDeploymentTimeline.Sample carrierSample(LivingEntity carrier, float partialTick) {
		ArmorCruiseClient.CruiseState state = stateForCarrier(carrier.getUUID());
		CruiseDeploymentTimeline.Sample sample = sample(state, partialTick);
		return sample != null && sample.carrierOwnsVisual() && ModItems.hasFullSet(carrier) ? sample : null;
	}

	public static boolean hideCarrierBeforeHandoff(LivingEntity carrier, float partialTick) {
		ArmorCruiseClient.CruiseState state = stateForCarrier(carrier.getUUID());
		CruiseDeploymentTimeline.Sample sample = sample(state, partialTick);
		return sample != null && (sample.playerOwnsVisual() || !ModItems.hasFullSet(carrier));
	}

	public static Player ownerOfCarrier(LivingEntity carrier) {
		ArmorCruiseClient.CruiseState state = stateForCarrier(carrier.getUUID());
		Minecraft client = Minecraft.getInstance();
		return state == null || client.level == null ? null : client.level.getPlayerByUUID(state.ownerId());
	}

	/** 交接后冻结脱离原点，玩家继续高速移动时不会把整甲轨迹拖弯。 */
	public static Vec3 handoffOrigin(LivingEntity carrier, float partialTick) {
		ArmorCruiseClient.CruiseState state = stateForCarrier(carrier.getUUID());
		CruiseDeploymentTimeline.Sample sample = sample(state, partialTick);
		if (state == null || sample == null) return null;
		Player owner = ownerOfCarrier(carrier);
		if (owner != null) rememberHandoffAnchor(state, owner, sample, partialTick);
		HandoffAnchor anchor = HANDOFF_ANCHORS.get(state.ownerId());
		return anchor != null && anchor.sequenceId() == state.sequenceId() ? anchor.position() : null;
	}

	private static boolean carrierReady(ArmorCruiseClient.CruiseState state) {
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.level == null) return false;
		var entity = client.level.getEntity(state.carrierEntityId());
		return entity instanceof LivingEntity carrier
				&& carrier.getUUID().equals(state.carrierId()) && ModItems.hasFullSet(carrier);
	}

	private static void rememberHandoffAnchor(ArmorCruiseClient.CruiseState state, Player owner,
			CruiseDeploymentTimeline.Sample sample, float partialTick) {
		HandoffAnchor current = HANDOFF_ANCHORS.get(state.ownerId());
		if (current == null || current.sequenceId() != state.sequenceId() || sample.playerOwnsVisual()) {
			HANDOFF_ANCHORS.put(state.ownerId(),
					new HandoffAnchor(state.sequenceId(), owner.getPosition(partialTick)));
		}
	}

	public static void clearOwner(UUID ownerId) {
		HANDOFF_ANCHORS.remove(ownerId);
	}

	public static void clear() {
		HANDOFF_ANCHORS.clear();
	}

	private record HandoffAnchor(long sequenceId, Vec3 position) {
	}

	private static CruiseDeploymentTimeline.Sample sample(
			ArmorCruiseClient.CruiseState state, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		if (state == null || state.phase() != ArmorCruisePhase.DEPLOYING || client.level == null) {
			return null;
		}
		float elapsed = client.level.getGameTime() + Mth.clamp(partialTick, 0.0F, 1.0F)
				- state.phaseStartGameTime();
		return CruiseDeploymentTimeline.sample(
				elapsed / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS);
	}

	private static ArmorCruiseClient.CruiseState stateForOwner(UUID ownerId) {
		return ArmorCruiseClient.stateForOwner(ownerId);
	}

	private static ArmorCruiseClient.CruiseState stateForCarrier(UUID carrierId) {
		for (ArmorCruiseClient.CruiseState state : ArmorCruiseClient.states()) {
			if (state.carrierId().equals(carrierId)) return state;
		}
		return null;
	}
}
