package dev.codex.flightarmor.client.platform;

import dev.codex.flightarmor.client.cruise.DockingTimeline;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyPart;
import dev.codex.flightarmor.client.audio.RadialDopplerTracker;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.network.ArmorPlatformRecallPayload;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import dev.codex.flightarmor.sound.ModSoundEvents;
import dev.codex.flightarmor.sound.RadialDopplerModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Client-only trajectory sampler for a platform-to-owner recall. */
public final class PlatformRecallClient {
	public static final float DURATION_TICKS = ArmorPlatformRecallPayload.DURATION_TICKS;
	private static final float EXPIRY_TICKS = ArmorPlatformRecallPayload.EXPIRY_TICKS;
	private static final double MAX_VISIBLE_ORIGIN_DISTANCE = 96.0D;
	private static final float VERTICAL_LAUNCH_TICKS = 0.85F;
	private static final float VERTICAL_LAUNCH_HEIGHT = 3.20F;
	private static final float HYPERSONIC_RUSH_END_TICKS = 2.65F;
	private static final float ASSEMBLED_VISUAL_ARRIVAL_TICKS =
			ArmorPlatformRecallPayload.ASSEMBLED_ARRIVAL_TICK;
	private static final float ASSEMBLED_FIRST_PERSON_CHEST_READY_TICKS =
			ArmorPlatformRecallPayload.ASSEMBLED_ARRIVAL_TICK + 3.25F;
	private static final float BRAKE_DURATION_TICKS = 0.82F;
	private static final Map<UUID, RecallState> STATES = new HashMap<>();
	private static final Map<UUID, SoundCueState> SOUND_CUES = new HashMap<>();
	private static final Map<UUID, Long> LAST_SEQUENCES = new HashMap<>();
	private static final int CUE_LAUNCH = 1;
	private static final int CUE_FLYBY = 1 << 1;
	private static final int CUE_PARTS_SECOND_FLYBY = 1 << 2;
	private static final int CUE_BRAKE = 1 << 3;
	private static final int CUE_BRAKE_TICK = ArmorPlatformRecallPayload.ASSEMBLED_ARRIVAL_TICK - 1;

	private PlatformRecallClient() {
	}

	public static void initialize() {
		PlatformRecallFxRenderer.initialize();
		ClientPlayNetworking.registerGlobalReceiver(ArmorPlatformRecallPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receive(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(PlatformRecallClient::tickSounds);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			STATES.clear();
			SOUND_CUES.clear();
			LAST_SEQUENCES.clear();
		});
	}

	private static void receive(ArmorPlatformRecallPayload payload) {
		long knownSequence = LAST_SEQUENCES.getOrDefault(payload.playerId(), Long.MIN_VALUE);
		if (payload.recallSequence() <= knownSequence) return;
		LAST_SEQUENCES.put(payload.playerId(), payload.recallSequence());
		Minecraft client = Minecraft.getInstance();
		long receivedAt = client.level == null
				? payload.startGameTime() : client.level.getGameTime();
		int animationOffset = Mth.clamp(payload.animationOffsetTicks(),
				0, ArmorPlatformRecallPayload.DURATION_TICKS);
		long clientStartGameTime = receivedAt - animationOffset;
		STATES.put(payload.playerId(), new RecallState(
				payload.recallSequence(), payload.originDimension(), payload.origin(),
				payload.displayMode(), clientStartGameTime));
		SOUND_CUES.put(payload.playerId(), new SoundCueState(payload.recallSequence(),
				passedCueMask(animationOffset, payload.displayMode())));
	}

	/**
	 * 每个逻辑 tick 只消费一次平台召回声场，避免渲染器按装备槽和发光层重复播放。
	 * 远距平台只把声源投影到玩家附近的来向上，既能听清掠入方向，也不会让 96 格外的启动声完全衰减。
	 */
	private static void tickSounds(Minecraft client) {
		if (client.level == null) return;
		Iterator<Map.Entry<UUID, SoundCueState>> iterator = SOUND_CUES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, SoundCueState> entry = iterator.next();
			RecallState recall = STATES.get(entry.getKey());
			SoundCueState cues = entry.getValue();
			if (recall == null || recall.sequence != cues.sequence) {
				iterator.remove();
				continue;
			}
			Player player = client.level.getPlayerByUUID(entry.getKey());
			int elapsed = (int) Math.floor(client.level.getGameTime() - recall.clientStartGameTime);
			if (elapsed > ArmorPlatformRecallPayload.EXPIRY_TICKS) {
				iterator.remove();
				STATES.remove(entry.getKey(), recall);
				continue;
			}
			if (elapsed < 0) continue;
			if (player == null) {
				// 新追踪者尚未创建玩家实体时直接跳过已经错过的声点，避免实体出现后四个 cue 同帧爆发。
				cues.consumedMask |= passedCueMask(elapsed, recall.displayMode);
				continue;
			}
			Sample sample = sample(player, 0.0F);
			if (!sample.active()) {
				iterator.remove();
				continue;
			}

			Vec3 incoming = sample.entryOffset().lengthSqr() > 1.0E-6D
					? sample.entryOffset().normalize() : sample.approachForward().scale(-1.0D);
			Vec3 playerCenter = player.position().add(0.0D, 1.05D, 0.0D);
			Vec3 listenerPosition = client.player == null ? null : client.player.getEyePosition();
			Vec3 listenerMotion = client.player == null ? null : realizedPlayerMotion(client.player);
			Vec3 visualSource = visualSoundSource(playerCenter, sample);
			Vec3 sourceMotion = realizedPlayerMotion(player).add(
					visualSoundOffset(sample, sample.elapsedTicks() + 1.0F)
							.subtract(visualSoundOffset(sample, sample.elapsedTicks())));
			float dopplerFactor = cues.doppler.sampleWithMotionWarmup(client.level,
					visualSource, listenerPosition, sourceMotion, listenerMotion, true);
			if (elapsed >= 0 && cues.consume(CUE_LAUNCH)) {
				double launchDistance = Mth.clamp(sample.entryOffset().length(), 5.0D, 12.0D);
				playAt(client, playerCenter.add(incoming.scale(launchDistance)),
						ModSoundEvents.FLIGHT_BOOST_ENTER, 0.90F, 0.92F, dopplerFactor);
			}
			if (elapsed >= 2 && cues.consume(CUE_FLYBY)) {
				playAt(client, playerCenter.add(sample.approachSide().scale(1.15D)),
						ModSoundEvents.FLIGHT_FLYBY, 1.15F, 0.96F, dopplerFactor);
			}
			if (!sample.assembled() && elapsed >= 3 && cues.consume(CUE_PARTS_SECOND_FLYBY)) {
				playAt(client, playerCenter.add(sample.approachSide().scale(-0.95D)).add(0.0D, 0.22D, 0.0D),
						ModSoundEvents.FLIGHT_FLYBY, 0.68F, 1.12F, dopplerFactor);
			}
			if (elapsed >= CUE_BRAKE_TICK && cues.consume(CUE_BRAKE)) {
				playAt(client, playerCenter.subtract(sample.approachForward().scale(2.2D)),
						ModSoundEvents.FLIGHT_BOOST_RELEASE, 0.82F, 0.82F, dopplerFactor);
			}
		}
	}

	private static void playAt(Minecraft client, Vec3 position,
			SoundEvent sound, float volume, float pitch, float dopplerFactor) {
		if (client.level == null) return;
		client.level.playLocalSound(position.x, position.y, position.z,
				sound, SoundSource.PLAYERS,
				volume * FlightArmorClientSettingsStore.masterVolume(),
				(float) RadialDopplerModel.applyToPitch(pitch, dopplerFactor), false);
	}

	private static Vec3 realizedPlayerMotion(Player player) {
		Vec3 interpolatedStep = player.getPosition(1.0F).subtract(player.getPosition(0.0F));
		return interpolatedStep.lengthSqr() > 1.0E-8D
				? interpolatedStep : player.getDeltaMovement();
	}

	private static Vec3 visualSoundSource(Vec3 playerCenter, Sample sample) {
		return playerCenter.add(visualSoundOffset(sample, sample.elapsedTicks()));
	}

	private static Vec3 visualSoundOffset(Sample sample, float elapsedTicks) {
		if (sample.assembled()) return sample.assembledOffsetAt(elapsedTicks);
		Vec3 average = Vec3.ZERO;
		EquipmentSlot[] slots = {
				EquipmentSlot.FEET, EquipmentSlot.LEGS,
				EquipmentSlot.CHEST, EquipmentSlot.HEAD
		};
		for (EquipmentSlot slot : slots) {
			average = average.add(sample.partTrajectoryAt(
					slot, null, elapsedTicks).offset());
		}
		return average.scale(1.0D / slots.length);
	}

	private static int passedCueMask(int animationOffset, ChargingPlatformDisplayMode mode) {
		int mask = 0;
		if (animationOffset > 0) mask |= CUE_LAUNCH;
		if (animationOffset >= 2) mask |= CUE_FLYBY;
		if (mode == ChargingPlatformDisplayMode.PARTS && animationOffset >= 3) {
			mask |= CUE_PARTS_SECOND_FLYBY;
		}
		if (animationOffset >= CUE_BRAKE_TICK) mask |= CUE_BRAKE;
		return mask;
	}

	public static Sample sample(Player player, float partialTick) {
		RecallState state = STATES.get(player.getUUID());
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.level == null) return Sample.NONE;
		float elapsed = Math.max(0.0F, client.level.getGameTime()
				+ Mth.clamp(partialTick, 0.0F, 1.0F) - state.clientStartGameTime);
		if (elapsed > EXPIRY_TICKS) {
			STATES.remove(player.getUUID(), state);
			return Sample.NONE;
		}

		float normalized = Mth.clamp(elapsed / DURATION_TICKS, 0.0F, 1.0F);
		Vec3 target = player.getPosition(partialTick);
		boolean sameDimension = client.level.dimension().location().equals(state.originDimension);
		Vec3 entry = sameDimension ? state.origin : phaseEntry(player, target);
		Vec3 delta = entry.subtract(target);
		if (delta.lengthSqr() > MAX_VISIBLE_ORIGIN_DISTANCE * MAX_VISIBLE_ORIGIN_DISTANCE) {
			entry = target.add(delta.normalize().scale(MAX_VISIBLE_ORIGIN_DISTANCE));
		}

		boolean assembled = state.displayMode == ChargingPlatformDisplayMode.ASSEMBLED;
		Vec3 entryOffset = entry.subtract(target);
		Vec3 horizontal = target.subtract(entry).multiply(1.0D, 0.0D, 1.0D);
		Vec3 forward = horizontal.lengthSqr() > 1.0E-6D
				? horizontal.normalize() : yawForward(player);
		Vec3 side = new Vec3(forward.z, 0.0D, -forward.x);
		Vec3 rootOffset = assembled
				? stagedApproachOffset(entryOffset, side, forward, elapsed,
						0.0F, ASSEMBLED_VISUAL_ARRIVAL_TICKS,
						0.75D, 0.12D, 2.15D)
				: Vec3.ZERO;
		float rootPitch = assembled
				? approachPitchDegrees(elapsed, 0.0F, ASSEMBLED_VISUAL_ARRIVAL_TICKS,
						entryOffset.length())
				: 0.0F;

		float wrapProgress = 0.0F;
		if (assembled && elapsed >= ASSEMBLED_VISUAL_ARRIVAL_TICKS) {
			// The hypersonic approach still reaches the owner at tick 6. From that exact
			// pose, keep one render owner and spend the rest of the shared 36-tick
			// assembly timeline opening, wrapping, and locking the individual zones.
			float wrap = smoothstep(ASSEMBLED_VISUAL_ARRIVAL_TICKS, DURATION_TICKS, elapsed);
			wrapProgress = Mth.lerp(wrap, DockingTimeline.FRAME_START, DockingTimeline.END);
		}
		return new Sample(true, assembled, rootOffset, rootPitch, wrapProgress, normalized, elapsed,
				entryOffset, side, forward);
	}

	private static Vec3 phaseEntry(Player player, Vec3 target) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 behind = new Vec3(Math.sin(yaw), 0.0D, -Math.cos(yaw));
		return target.add(behind.scale(88.0D)).add(0.0D, 22.0D, 0.0D);
	}
	private static Vec3 yawForward(Player player) {
		double yaw = Math.toRadians(player.getYRot());
		return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
	}

	/** 垂直离台阶段不计算水平进度；远距俯冲结束后才进入直立近进和急刹锁定。 */
	private static float hypersonicTravel(float elapsed, float launchDelay, float arrivalTick) {
		if (elapsed <= launchDelay) return 0.0F;
		if (elapsed >= arrivalTick) return 1.0F;
		float launchEnd = launchDelay + VERTICAL_LAUNCH_TICKS;
		if (elapsed <= launchEnd) return 0.0F;
		float rushEnd = rushEndTick(launchDelay, arrivalTick);
		if (elapsed <= rushEnd) {
			float rush = Mth.clamp((elapsed - launchEnd)
					/ Math.max(0.1F, rushEnd - launchEnd), 0.0F, 1.0F);
			return 0.90F * easeInCubic(rush);
		}
		float brakeStart = Math.max(rushEnd + 0.20F, arrivalTick - BRAKE_DURATION_TICKS);
		if (elapsed < brakeStart) {
			return Mth.lerp(smoothstep(rushEnd, brakeStart, elapsed), 0.90F, 0.975F);
		}
		float brake = Mth.clamp((elapsed - brakeStart)
				/ Math.max(0.1F, arrivalTick - brakeStart), 0.0F, 1.0F);
		return Mth.lerp(easeOutCubic(brake), 0.975F, 1.0F);
	}

	private static float rushEndTick(float launchDelay, float arrivalTick) {
		return Math.min(arrivalTick - 1.65F,
				HYPERSONIC_RUSH_END_TICKS + launchDelay * 0.85F);
	}

	private static float rushStrength(float elapsed, float launchDelay, float arrivalTick) {
		float rushEnd = rushEndTick(launchDelay, arrivalTick);
		float launchEnd = launchDelay + VERTICAL_LAUNCH_TICKS;
		return smoothstep(launchEnd, launchEnd + 0.24F, elapsed)
				* (1.0F - smoothstep(rushEnd - 1.25F, rushEnd + 0.20F, elapsed));
	}

	private static float brakeStrength(float elapsed, float launchDelay, float arrivalTick) {
		float rushEnd = rushEndTick(launchDelay, arrivalTick);
		return smoothstep(rushEnd - 0.20F, arrivalTick - BRAKE_DURATION_TICKS * 0.35F, elapsed)
				* (1.0F - smoothstep(arrivalTick - 0.45F, arrivalTick, elapsed));
	}

	private static Vec3 stagedApproachOffset(Vec3 source, Vec3 side, Vec3 forward,
			float elapsed, float launchDelay, float arrivalTick,
			double arcSide, double arcForward, double arcHeight) {
		if (elapsed <= launchDelay) return source;
		float launchEnd = launchDelay + VERTICAL_LAUNCH_TICKS;
		Vec3 liftedSource = source.add(0.0D, VERTICAL_LAUNCH_HEIGHT + arcHeight * 0.18D, 0.0D);
		if (elapsed < launchEnd) {
			return source.lerp(liftedSource, smoothstep(launchDelay, launchEnd, elapsed));
		}

		float rushEnd = rushEndTick(launchDelay, arrivalTick);
		float farDistance = farDistanceFactor(source.length());
		double horizontalDistance = source.multiply(1.0D, 0.0D, 1.0D).length();
		double stagingDistance = Math.min(5.20D, Math.max(0.35D, horizontalDistance * 0.55D));
		double arcScale = 0.18D + farDistance * 0.82D;
		Vec3 uprightEntry = forward.scale(-stagingDistance)
				.add(side.scale(arcSide * 0.22D * arcScale))
				.add(0.0D, 0.72D + arcHeight * 0.10D, 0.0D);
		if (elapsed < rushEnd) {
			float rawRush = Mth.clamp((elapsed - launchEnd)
					/ Math.max(0.1F, rushEnd - launchEnd), 0.0F, 1.0F);
			float rush = easeInCubic(rawRush);
			// 弧线必须跟随实际冲刺位移建立；若直接使用 rawRush，近距平台会在 cubic
			// 主位移尚未起步时先被弧线推离玩家，形成一小段反向飞行。
			double arc = Math.sin(Math.PI * rush);
			return liftedSource.lerp(uprightEntry, rush)
					.add(side.scale(arc * arcSide * arcScale))
					.add(forward.scale(arc * arcForward * arcScale))
					.add(0.0D, arc * arcHeight * arcScale, 0.0D);
		}

		float brakeStart = Math.max(rushEnd + 0.20F, arrivalTick - BRAKE_DURATION_TICKS);
		double brakeDistance = Math.min(2.15D, Math.max(0.12D, stagingDistance * 0.42D));
		Vec3 brakePoint = forward.scale(-brakeDistance)
				.add(side.scale(arcSide * 0.10D))
				.add(0.0D, 0.26D, 0.0D);
		if (elapsed < brakeStart) {
			return uprightEntry.lerp(brakePoint, smoothstep(rushEnd, brakeStart, elapsed));
		}
		float brake = Mth.clamp((elapsed - brakeStart)
				/ Math.max(0.1F, arrivalTick - brakeStart), 0.0F, 1.0F);
		return brakePoint.scale(1.0F - easeOutCubic(brake));
	}

	private static float approachPitchDegrees(float elapsed, float launchDelay,
			float arrivalTick, double sourceDistance) {
		float launchEnd = launchDelay + VERTICAL_LAUNCH_TICKS;
		float rushEnd = rushEndTick(launchDelay, arrivalTick);
		float diveIn = smoothstep(launchEnd, launchEnd + 0.30F, elapsed);
		float upright = smoothstep(rushEnd - 0.34F, rushEnd + 0.38F, elapsed);
		float pitch = Mth.lerp(upright, 72.0F, 8.0F)
				* diveIn * farDistanceFactor(sourceDistance);
		return Mth.lerp(smoothstep(arrivalTick - BRAKE_DURATION_TICKS,
				arrivalTick, elapsed), pitch, 0.0F);
	}

	private static float easeOutCubic(float value) {
		float inverse = 1.0F - value;
		return 1.0F - inverse * inverse * inverse;
	}

	private static float easeInCubic(float value) {
		return value * value * value;
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = Mth.clamp((value - edge0) / Math.max(1.0E-5F, edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static float farDistanceFactor(double distance) {
		return smoothstep(8.0F, 28.0F, (float) distance);
	}
	private static PartRoute partRoute(EquipmentSlot slot) {
		return switch (slot) {
			case FEET -> new PartRoute(0.00F, ArmorPlatformRecallPayload.PARTS_ARRIVAL_TICK,
					-0.24D, 0.24D, -0.42D, 0.12D, 0.28D);
			case LEGS -> new PartRoute(0.12F, 6.60F, 0.24D, 0.24D, 0.50D, -0.18D, 0.52D);
			case CHEST -> new PartRoute(0.24F, 7.20F, 0.24D, -0.24D, -0.62D, 0.22D, 0.82D);
			case HEAD -> new PartRoute(0.36F, 7.80F, -0.24D, -0.24D, 0.70D, -0.24D, 1.02D);
			default -> throw new IllegalArgumentException("不是装甲槽位: " + slot);
		};
	}

	private record PartRoute(float launchDelay, float arrivalTick,
			double mountSide, double mountForward, double arcSide,
			double arcForward, double arcHeight) {
	}

	private static final class SoundCueState {
		private final long sequence;
		private final RadialDopplerTracker doppler = new RadialDopplerTracker();
		private int consumedMask;

		private SoundCueState(long sequence, int consumedMask) {
			this.sequence = sequence;
			this.consumedMask = consumedMask;
		}

		private boolean consume(int cue) {
			if ((consumedMask & cue) != 0) return false;
			consumedMask |= cue;
			return true;
		}
	}

	private record RecallState(long sequence, ResourceLocation originDimension, Vec3 origin,
			ChargingPlatformDisplayMode displayMode, long clientStartGameTime) {
	}

	public record Sample(boolean active, boolean assembled, Vec3 rootOffset,
			float rootPitchDegrees, float wrapProgress, float normalizedProgress, float elapsedTicks,
			Vec3 entryOffset, Vec3 approachSide, Vec3 approachForward) {
		public static final Sample NONE = new Sample(false, false, Vec3.ZERO,
				0.0F, 0.0F, 1.0F, 0.0F, Vec3.ZERO,
				new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D));

		/** 四个槽位从平台托位分别飞向对应身体区域；同槽内部只保留很小的机械错峰。 */
		public PartTrajectory partTrajectory(EquipmentSlot slot, ArmorAssemblyPart part) {
			return partTrajectoryAt(slot, part, elapsedTicks);
		}

		/**
		 * Samples the assembled root at an arbitrary visual time so the procedural
		 * afterimage follows the exact same curve as the armor model.
		 */
		public Vec3 assembledOffsetAt(float sampleElapsed) {
			if (!active || !assembled) return Vec3.ZERO;
			return stagedApproachOffset(entryOffset, approachSide, approachForward,
					Math.max(0.0F, sampleElapsed), 0.0F, ASSEMBLED_VISUAL_ARRIVAL_TICKS,
					0.75D, 0.12D, 2.15D);
		}

		/** Keeps the first recall frame continuous with the 0.70 platform preview. */
		public float assembledVisualScale() {
			if (!active || !assembled) return 1.0F;
			return Mth.lerp(smoothstep(0.0F, 0.72F, elapsedTicks), 0.70F, 1.0F);
		}

		/** Keeps each part continuous with its own platform cradle scale. */
		public float partVisualScale(EquipmentSlot slot, ArmorAssemblyPart part) {
			if (!active || assembled) return 1.0F;
			PartRoute route = partRoute(slot);
			int phase = part == null ? 0 : part.ordinal() % 3;
			float launchDelay = route.launchDelay() + phase * 0.055F;
			float sourceScale = switch (slot) {
				case FEET -> 0.47F;
				case LEGS -> 0.42F;
				case CHEST -> 0.37F;
				case HEAD -> 0.41F;
				default -> 1.0F;
			};
			return Mth.lerp(smoothstep(launchDelay, launchDelay + 0.72F, elapsedTicks),
					sourceScale, 1.0F);
		}

		/** Samples an individual platform part for the dedicated trail renderer. */
		public PartTrajectory partTrajectoryAt(EquipmentSlot slot, ArmorAssemblyPart part,
				float sampleElapsed) {
			if (!active || assembled) return PartTrajectory.NONE;
			PartRoute route = partRoute(slot);
			int phase = part == null ? 0 : part.ordinal() % 3;
			float launchDelay = route.launchDelay() + phase * 0.055F;
			float arrivalTick = route.arrivalTick();
			if (slot != EquipmentSlot.FEET) {
				arrivalTick = Math.min(DURATION_TICKS - 0.05F, arrivalTick + phase * 0.045F);
			}
			float time = Math.max(0.0F, sampleElapsed);
			float travel = hypersonicTravel(time, launchDelay, arrivalTick);
			double microSide = part == null ? 0.0D : (phase - 1) * 0.045D;
			Vec3 source = entryOffset
					.add(approachSide.scale(route.mountSide()))
					.add(approachForward.scale(route.mountForward()));
			Vec3 offset = stagedApproachOffset(source, approachSide, approachForward,
					time, launchDelay, arrivalTick, route.arcSide() + microSide,
					route.arcForward(), route.arcHeight());
			float lockIn = smoothstep(arrivalTick - 0.85F, arrivalTick, time);
			float lockOut = 1.0F - smoothstep(arrivalTick, arrivalTick + 0.90F, time);
			return new PartTrajectory(offset, travel,
					rushStrength(time, launchDelay, arrivalTick) * farDistanceFactor(source.length()),
					brakeStrength(time, launchDelay, arrivalTick),
					lockIn * lockOut);
		}

		public float slotTravel(EquipmentSlot slot) {
			return partTrajectory(slot, null).travel();
		}

		/**
		 * First-person armor is never translated along the world-space recall path.
		 * Preserve the previous absolute reveal window when the visual lifetime grows
		 * from 16 to 36 ticks, so the local camera does not stare through flying parts.
		 */
		public boolean firstPersonChestReady() {
			if (!active) return true;
			return assembled
					? elapsedTicks >= ASSEMBLED_FIRST_PERSON_CHEST_READY_TICKS
					: slotTravel(EquipmentSlot.CHEST) >= 0.995F;
		}

		/** 供自定义残影与音频控制器使用；不生成原版粒子。 */
		public float hypersonicStrength() {
			float arrival = assembled ? ASSEMBLED_VISUAL_ARRIVAL_TICKS : partRoute(EquipmentSlot.HEAD).arrivalTick();
			return active ? rushStrength(elapsedTicks, 0.0F, arrival)
					* farDistanceFactor(entryOffset.length()) : 0.0F;
		}

		public float brakingStrength() {
			float arrival = assembled ? ASSEMBLED_VISUAL_ARRIVAL_TICKS : partRoute(EquipmentSlot.HEAD).arrivalTick();
			return active ? brakeStrength(elapsedTicks, 0.0F, arrival) : 0.0F;
		}
	}

	public record PartTrajectory(Vec3 offset, float travel, float hypersonicStrength,
			float brakingStrength, float lockStrength) {
		public static final PartTrajectory NONE = new PartTrajectory(Vec3.ZERO, 1.0F, 0.0F, 0.0F, 1.0F);
	}
}
