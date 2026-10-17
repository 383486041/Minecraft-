package dev.codex.flightarmor.client.environment;

import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.flight.LowAltitudeFeedbackModel;
import dev.codex.flightarmor.item.ModItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Directional high-speed ground coupling telemetry for sound modifiers and environmental feedback.
 */
public final class LowAltitudeFeedbackClient {
	private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
	private static final double MAX_RENDER_DISTANCE_SQUARED =
			LowAltitudeFeedbackModel.MAX_RENDER_DISTANCE_BLOCKS
					* LowAltitudeFeedbackModel.MAX_RENDER_DISTANCE_BLOCKS;
	private static final Map<UUID, FeedbackRig> RIGS = new HashMap<>();
	private static ResourceKey<Level> sampledDimension;
	private static boolean initialized;

	private LowAltitudeFeedbackClient() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(LowAltitudeFeedbackClient::samplePlayers);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/**
	 * Thin read-only bridge used by the existing three-layer flight mixer.
	 * Settings remain owned by FlightArmorClientSettingsStore.
	 */
	public static LowAltitudeFeedbackModel.SoundModifier soundModifier(Player player) {
		if (player == null
				|| !FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) {
			return LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}
		FeedbackRig rig = RIGS.get(player.getUUID());
		if (rig == null || rig.player != player) {
			return LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}
		long age = player.level().getGameTime() - rig.soundSampleGameTime;
		return age >= 0L && age <= LowAltitudeFeedbackModel.SAMPLE_INTERVAL_TICKS + 1L
				? rig.soundModifier
				: LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
	}

	private static void samplePlayers(Minecraft client) {
		if (client.level == null || client.player == null
				|| !FlightArmorClientSettingsStore.get().environmentFeedbackEnabled()) {
			clear();
			return;
		}
		ResourceKey<Level> dimension = client.level.dimension();
		if (sampledDimension != null && !sampledDimension.equals(dimension)) RIGS.clear();
		sampledDimension = dimension;
		long now = client.level.getGameTime();
		if (Math.floorMod(now, LowAltitudeFeedbackModel.SAMPLE_INTERVAL_TICKS) != 0) return;

		for (FeedbackRig rig : RIGS.values()) rig.silenceSound();
		Vec3 listener = client.player.position();
		List<Candidate> candidates = new ArrayList<>();
		Set<UUID> loadedPlayers = new HashSet<>();
		for (Player player : client.level.players()) {
			loadedPlayers.add(player.getUUID());
			if (player.isRemoved() || !player.isAlive()
					|| player.isUnderWater() || player.isInWater()
					|| !ModItems.hasFlightGear(player)
					|| !FlightArmorClient.isFlightEffectActive(player)) {
				continue;
			}
			double distanceSquared = listener.distanceToSqr(player.position());
			if (distanceSquared > MAX_RENDER_DISTANCE_SQUARED) continue;
			double speed = FlightSpeedTelemetry.currentBlocksPerTick(player);
			if (speed < LowAltitudeFeedbackModel.MINIMUM_SPEED_BLOCKS_PER_TICK) continue;
			candidates.add(new Candidate(player, distanceSquared, speed));
		}
		candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));

		int count = Math.min(LowAltitudeFeedbackModel.MAX_ACTIVE_RIGS, candidates.size());
		for (int index = 0; index < count; index++) {
			sampleCandidate(client.level, candidates.get(index), now);
		}

		Iterator<Map.Entry<UUID, FeedbackRig>> iterator = RIGS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, FeedbackRig> entry = iterator.next();
			FeedbackRig rig = entry.getValue();
			rig.expire(now);
			if (!loadedPlayers.contains(entry.getKey()) || rig.player.isRemoved()
					|| (rig.samples.isEmpty() && rig.soundModifier
					== LowAltitudeFeedbackModel.SoundModifier.NEUTRAL)) {
				iterator.remove();
			}
		}
	}

	private static void sampleCandidate(Level level, Candidate candidate, long now) {
		Player player = candidate.player;
		Vec3 from = player.position().add(0.0D, 0.16D, 0.0D);
		Vec3 to = from.add(0.0D,
				-(LowAltitudeFeedbackModel.MAXIMUM_CLEARANCE_BLOCKS + 0.75D), 0.0D);
		BlockHitResult hit = level.clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
		if (hit.getType() != HitResult.Type.BLOCK) return;

		double clearance = player.getY() - hit.getLocation().y;
		LowAltitudeFeedbackModel.Sample policy = LowAltitudeFeedbackModel.sample(
				true, true, false, candidate.speed, clearance);
		if (!policy.active()) return;

		Vec3 direction = horizontalDirection(player);
		Vec3 normal = new Vec3(hit.getDirection().getStepX(),
				hit.getDirection().getStepY(), hit.getDirection().getStepZ());
		if (normal.y < 0.45D) normal = WORLD_UP;
		boolean water = !level.getFluidState(hit.getBlockPos()).isEmpty();
		Vec3 surface = hit.getLocation().add(normal.scale(water ? 0.045D : 0.025D));

		FeedbackRig rig = RIGS.get(player.getUUID());
		if (rig == null || rig.player != player) {
			rig = new FeedbackRig(player);
			RIGS.put(player.getUUID(), rig);
		}
		rig.add(new WakeSample(surface, normal, direction, water, now, policy));
	}

	private static Vec3 horizontalDirection(Player player) {
		Vec3 motion = player.getDeltaMovement();
		Vec3 horizontal = new Vec3(motion.x, 0.0D, motion.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			Vec3 look = player.getLookAngle();
			horizontal = new Vec3(look.x, 0.0D, look.z);
		}
		return horizontal.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 0.0D, 1.0D) : horizontal.normalize();
	}

	private static void clear() {
		RIGS.clear();
		sampledDimension = null;
	}

	private record Candidate(Player player, double distanceSquared, double speed) {
	}

	private record WakeSample(Vec3 surface, Vec3 normal, Vec3 direction,
			boolean water, long createdAt, LowAltitudeFeedbackModel.Sample policy) {
	}

	private static final class FeedbackRig {
		private final Player player;
		private final ArrayDeque<WakeSample> samples = new ArrayDeque<>();
		private LowAltitudeFeedbackModel.SoundModifier soundModifier =
				LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		private long soundSampleGameTime = Long.MIN_VALUE;

		private FeedbackRig(Player player) {
			this.player = player;
		}

		private void add(WakeSample sample) {
			samples.addLast(sample);
			soundModifier = sample.policy.sound();
			soundSampleGameTime = sample.createdAt;
			while (samples.size() > LowAltitudeFeedbackModel.MAX_SAMPLES_PER_RIG) {
				samples.removeFirst();
			}
		}

		private void silenceSound() {
			soundModifier = LowAltitudeFeedbackModel.SoundModifier.NEUTRAL;
		}

		private void expire(long now) {
			while (!samples.isEmpty()
					&& now - samples.peekFirst().createdAt
					> LowAltitudeFeedbackModel.SAMPLE_LIFETIME_TICKS) {
				samples.removeFirst();
			}
		}
	}
}
