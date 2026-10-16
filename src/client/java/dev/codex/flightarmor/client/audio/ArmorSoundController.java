package dev.codex.flightarmor.client.audio;

import dev.codex.flightarmor.client.FlightArmorClient;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyClient;
import dev.codex.flightarmor.client.cruise.ArmorCruiseClient;
import dev.codex.flightarmor.client.cruise.DockingTimeline;
import dev.codex.flightarmor.client.cruise.RecallApproachTimeline;
import dev.codex.flightarmor.client.environment.LowAltitudeFeedbackClient;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.client.telemetry.FlightSpeedTelemetry;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import dev.codex.flightarmor.cruise.RecallApproachProfile;
import dev.codex.flightarmor.flight.FlightPhysics;
import dev.codex.flightarmor.flight.LowAltitudeFeedbackModel;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.sound.ArmorFlightSoundMix;
import dev.codex.flightarmor.sound.ArmorSoundEdgeGate;
import dev.codex.flightarmor.sound.CruiseSoundObservationRetention;
import dev.codex.flightarmor.sound.ModSoundEvents;
import dev.codex.flightarmor.sound.RadialDopplerModel;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 全部飞行持续声的唯一控制入口。
 *
 * <p>每名可听玩家只拥有低频悬浮、中频伺服和高速空气三层。真实位移速度、真实加速度与
 * 水下权重连续改变同一批实例；Ctrl/俯冲按键不创建额外发动机层。一次性启动/关闭声由
 * 边沿门禁去重。</p>
 */
public final class ArmorSoundController {
	private static final float LOOP_START_VOLUME = 0.012F;
	private static final float LOOP_STOP_VOLUME = 0.005F;
	private static final double REMOTE_PLAYER_RANGE_SQUARED = 48.0D * 48.0D;
	private static final double REMOTE_CRUISE_RANGE_SQUARED = 64.0D * 64.0D;
	private static final int MAX_REMOTE_PLAYER_RIGS = 8;
	private static final int MAX_REMOTE_CRUISE_RIGS = 6;
	private final Map<UUID, PlayerSoundRig> rigs = new HashMap<>();
	private final Map<UUID, CruiseSoundRig> cruiseRigs = new HashMap<>();
	private final Map<UUID, CruisePhaseObservation> cruisePhaseObservations = new HashMap<>();
	private static final int CUE_PHASE_JUMP = 1;
	private static final int CUE_DOCK_FRAME = 1 << 1;
	private static final int CUE_DOCK_WRAP = 1 << 2;
	private static final int CUE_DOCK_BOOTS = 1 << 3;
	private static final int CUE_DOCK_LEGS = 1 << 4;
	private static final int CUE_DOCK_TORSO = 1 << 5;
	private static final int CUE_DOCK_HELMET = 1 << 6;
	private static final int CUE_DOCK_CORE = 1 << 7;
	private static final int CUE_RECALL_LAUNCH = 1 << 8;
	private static final int CUE_RECALL_FLYBY = 1 << 9;
	private static final int CUE_RECALL_BRAKE = 1 << 10;

	public void tick(Minecraft client) {
		if (client.level == null) {
			clear();
			return;
		}

		Set<UUID> visiblePlayers = new HashSet<>();
		int remotePlayerRigs = 0;
		for (Player player : client.level.players()) {
			if (!player.isAlive() || player.isRemoved()) {
				continue;
			}
			FlightArmorClient.FlightAudioSnapshot flight = FlightArmorClient.flightAudioSnapshot(player);
			// 巡航贴合的战靴在视觉锁定后、最终 ItemStack 提交前也已经能产生反重力悬停声场。
			boolean hasGear = ModItems.hasFlightGear(player)
					|| ArmorAssemblyClient.isBootHoverReady(player)
					|| ArmorCruiseClient.isBootHoverReady(player);
			boolean coreTransition = ArmorAssemblyClient.isAnimating(player)
					&& ArmorAssemblyClient.affectsSlot(player, EquipmentSlot.CHEST);
			boolean coreAvailable = ModItems.fullSetVariant(player).isPresent() && !coreTransition;
			if (!hasGear && !flight.active() && !rigs.containsKey(player.getUUID())) {
				continue;
			}

			boolean local = client.player != null && client.player.getUUID().equals(player.getUUID());
			if (!local) {
				if (client.player != null && player.distanceToSqr(client.player) > REMOTE_PLAYER_RANGE_SQUARED) continue;
				if (remotePlayerRigs >= MAX_REMOTE_PLAYER_RIGS) continue;
				remotePlayerRigs++;
			}

			PlayerSoundRig rig = rigs.get(player.getUUID());
			// 跨维度或重生后 UUID 不变但实体实例已经替换，旧声音不能继续跟随旧坐标。
			if (rig == null || rig.player != player) {
				if (rig != null) {
					rig.stopImmediately();
				}
				rig = new PlayerSoundRig(player, local);
				rigs.put(player.getUUID(), rig);
			}
			rig.tick(client, flight, hasGear, coreAvailable);
			if (hasGear || flight.active() || !rig.isSilent()) {
				visiblePlayers.add(player.getUUID());
			}
		}

		Iterator<Map.Entry<UUID, PlayerSoundRig>> iterator = rigs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, PlayerSoundRig> entry = iterator.next();
			if (!visiblePlayers.contains(entry.getKey())) {
				entry.getValue().stopImmediately();
				iterator.remove();
			}
		}
		// Sample real carrier motion first so a phase-edge sonic cue can use a
		// safely warmed radial Doppler value on its very first audible tick.
		tickCruiseCarriers(client);
		tickCruisePhaseTransitions(client);
	}

	public void clear() {
		for (PlayerSoundRig rig : rigs.values()) {
			rig.stopImmediately();
		}
		rigs.clear();
		for (CruiseSoundRig rig : cruiseRigs.values()) {
			rig.stopImmediately();
		}
		cruiseRigs.clear();
		cruisePhaseObservations.clear();
	}

	/**
	 * 巡航阶段快照只触发整甲级声音，不调用 13 部件/36 tick 的分件着甲时间线。
	 */
	private void tickCruisePhaseTransitions(Minecraft client) {
		Set<UUID> retainedOwners = new HashSet<>();
		for (ArmorCruiseClient.CruiseState state : ArmorCruiseClient.states()) {
			CruisePhaseObservation previous = cruisePhaseObservations.get(state.ownerId());
			boolean carrierChangedInPhase = previous != null
					&& previous.sequenceId == state.sequenceId()
					&& previous.phase == ArmorCruisePhase.PHASING
					&& state.phase() == ArmorCruisePhase.PHASING
					&& !previous.carrierId.equals(state.carrierId());
			boolean sameStage = previous != null
					&& previous.sequenceId == state.sequenceId()
					&& previous.phase == state.phase()
					&& previous.phaseStartGameTime == state.phaseStartGameTime();
			boolean enteredStage = !sameStage && !carrierChangedInPhase;
			long age = client.level == null ? Long.MAX_VALUE
					: client.level.getGameTime() - state.phaseStartGameTime();
			boolean fresh = age >= 0L && age <= 2L;
			int cues = sameStage || carrierChangedInPhase ? previous.cueMask : 0;

			boolean respawnRecall = previous != null && previous.phase == ArmorCruisePhase.OWNER_DOWN
					&& (state.phase() == ArmorCruisePhase.PHASING || state.phase() == ArmorCruisePhase.RECALLING);
			if (respawnRecall) {
				playCruiseOneShot(client, state, ModSoundEvents.CORE_WAKE, 0.72F, 1.01F);
				playCruiseOneShot(client, state, ModSoundEvents.ASSEMBLY_RECALL_START, 0.68F, 1.00F);
			} else if (enteredStage && state.phase() == ArmorCruisePhase.PHASING
					&& (previous != null || fresh)) {
				playCruiseOneShot(client, state, ModSoundEvents.ASSEMBLY_CORE_CHARGE, 0.66F, 0.92F);
				playCruiseOneShot(client, state, ModSoundEvents.CORE_SHUTDOWN, 0.46F, 1.04F);
			}
			if (enteredStage && state.phase() == ArmorCruisePhase.OWNER_DOWN
					&& (previous != null || fresh)) {
				playCruiseOneShot(client, state, ModSoundEvents.CORE_SHUTDOWN, 0.58F, 0.88F);
			}

			if (enteredStage && state.phase() == ArmorCruisePhase.RECALLING
					&& (previous != null || fresh)) {
				playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_BOOST_ENTER, 0.78F, 0.96F);
				cues |= CUE_RECALL_LAUNCH;
			}
			if (state.phase() == ArmorCruisePhase.RECALLING) {
				Entity recallSource = ArmorCruiseClient.resolveCarrier(client, state);
				if (recallSource instanceof LivingEntity carrier) {
					RecallApproachTimeline.Sample approach = RecallApproachTimeline.sample(carrier, 1.0F);
					if (previous == null && !fresh) {
						if (approach.distance() <= RecallApproachProfile.BRAKING_START_DISTANCE + 0.5D)
							cues |= CUE_RECALL_FLYBY;
						if (approach.hoverProgress() >= 0.08F) cues |= CUE_RECALL_BRAKE;
					}
					if (approach.distance() <= RecallApproachProfile.BRAKING_START_DISTANCE + 0.5D
							&& (cues & CUE_RECALL_FLYBY) == 0) {
						playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_FLYBY, 0.92F, 1.04F);
						cues |= CUE_RECALL_FLYBY;
					}
					if (approach.hoverProgress() >= 0.08F && (cues & CUE_RECALL_BRAKE) == 0) {
						playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_BOOST_RELEASE, 0.76F, 0.86F);
						cues |= CUE_RECALL_BRAKE;
					}
				}
			}
			if (state.phase() == ArmorCruisePhase.PHASING) {
				float progress = ArmorCruiseClient.phasingProgress(state, 1.0F);
				if (previous == null && !fresh) cues |= progress >= 0.42F ? CUE_PHASE_JUMP : 0;
				if (progress >= 0.42F && (cues & CUE_PHASE_JUMP) == 0) {
					playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_FLYBY, 0.72F, 0.90F);
					cues |= CUE_PHASE_JUMP;
				}
				if (carrierChangedInPhase) {
					playCruiseOneShot(client, state, ModSoundEvents.ASSEMBLY_INBOUND_HEAVY, 0.72F, 1.02F);
					playCruiseOneShot(client, state, ModSoundEvents.CORE_WAKE, 0.56F, 1.04F);
				}
			}

			if (enteredStage && state.phase() == ArmorCruisePhase.DOCKING
					&& (previous != null || fresh)) {
				if (previous != null && previous.phase == ArmorCruisePhase.RECALLING) {
					if ((previous.cueMask & CUE_RECALL_FLYBY) == 0) {
						playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_FLYBY, 0.92F, 1.04F);
					}
					if ((previous.cueMask & CUE_RECALL_BRAKE) == 0) {
						playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_BOOST_RELEASE, 0.76F, 0.86F);
					}
				}
				// tick 0-3：整甲刹停；后续提示严格取自 DockingTimeline 的公共边界。
				playCruiseOneShot(client, state, ModSoundEvents.FLIGHT_DISENGAGE, 0.42F, 0.91F);
			}
			if (state.phase() == ArmorCruisePhase.DOCKING) {
				float progress = ArmorCruiseClient.dockingProgress(state, 1.0F);
				if (previous == null && !fresh) cues |= passedDockingCueMask(progress);
				Player owner = client.level.getPlayerByUUID(state.ownerId());
				if (owner != null && ArmorAssemblyClient.isAnimating(owner)) {
					// ArmorAssemblySoundController owns the same mechanical lock timeline.
					// Mark elapsed cues consumed so the cruise observer cannot double-play them.
					cues |= passedDockingCueMask(progress);
				} else {
					cues = playDockingCue(client, state, cues, CUE_DOCK_FRAME, progress, DockingTimeline.FRAME_START,
							ModSoundEvents.ASSEMBLY_INBOUND_MEDIUM, 0.56F, 1.04F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_WRAP, progress, DockingTimeline.WRAP_START,
							ModSoundEvents.ASSEMBLY_INBOUND_HEAVY, 0.74F, 0.92F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_BOOTS, progress, DockingTimeline.ZONE_LOCK_START,
							ModSoundEvents.ASSEMBLY_LOCK_BOOT, 0.62F, 0.98F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_LEGS, progress, DockingTimeline.LEGS_LOCK_START,
							ModSoundEvents.ASSEMBLY_LOCK_LIMB, 0.66F, 0.94F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_TORSO, progress, DockingTimeline.CHEST_LOCK_START,
							ModSoundEvents.ASSEMBLY_LOCK_TORSO, 0.76F, 0.96F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_HELMET, progress, DockingTimeline.HELMET_LOCK_START,
							ModSoundEvents.ASSEMBLY_HELMET_SEAL, 0.64F, 1.04F);
					cues = playDockingCue(client, state, cues, CUE_DOCK_CORE, progress, DockingTimeline.CORE_START,
							ModSoundEvents.ASSEMBLY_CORE_CHARGE, 0.70F, 1.03F);
				}
			}

			if (enteredStage && previous != null && previous.phase == ArmorCruisePhase.DOCKING
					&& state.phase() == ArmorCruisePhase.IDLE) {
				playCruiseOneShot(client, state, ModSoundEvents.CORE_ONLINE, 0.82F, 1.03F);
			}
			Entity observationCarrier = ArmorCruiseClient.resolveCarrier(client, state);
			boolean carrierPresent = observationCarrier != null
					&& !observationCarrier.isRemoved()
					&& !ArmorCruiseClient.isRetiredCarrier(observationCarrier);
			long phaseAge = client.level == null ? Long.MAX_VALUE
					: client.level.getGameTime() - state.phaseStartGameTime();
			if (CruiseSoundObservationRetention.shouldRetain(
					true, carrierPresent, state.phase(), phaseAge)) {
				cruisePhaseObservations.put(state.ownerId(), new CruisePhaseObservation(
						state.sequenceId(), state.phase(), state.carrierId(),
						state.phaseStartGameTime(), cues));
				retainedOwners.add(state.ownerId());
			} else {
				cruisePhaseObservations.remove(state.ownerId());
			}
		}
		// Also drops owners whose state vanished from the same ClientLevel without
		// waiting for disconnect or a dimension replacement.
		cruisePhaseObservations.keySet().removeIf(ownerId -> !retainedOwners.contains(ownerId));
	}

	private int playDockingCue(Minecraft client, ArmorCruiseClient.CruiseState state,
			int cues, int cue, float progress, float threshold, SoundEvent event, float volume, float pitch) {
		if (progress >= threshold && (cues & cue) == 0) {
			playCruiseOneShot(client, state, event, volume, pitch);
			return cues | cue;
		}
		return cues;
	}

	private static int passedDockingCueMask(float progress) {
		int mask = 0;
		if (progress >= DockingTimeline.FRAME_START) mask |= CUE_DOCK_FRAME;
		if (progress >= DockingTimeline.WRAP_START) mask |= CUE_DOCK_WRAP;
		if (progress >= DockingTimeline.ZONE_LOCK_START) mask |= CUE_DOCK_BOOTS;
		if (progress >= DockingTimeline.LEGS_LOCK_START) mask |= CUE_DOCK_LEGS;
		if (progress >= DockingTimeline.CHEST_LOCK_START) mask |= CUE_DOCK_TORSO;
		if (progress >= DockingTimeline.HELMET_LOCK_START) mask |= CUE_DOCK_HELMET;
		if (progress >= DockingTimeline.CORE_START) mask |= CUE_DOCK_CORE;
		return mask;
	}

	private void playCruiseOneShot(Minecraft client, ArmorCruiseClient.CruiseState state,
			SoundEvent event, float volume, float pitch) {
		if (client.level == null) return;
		Entity source = ArmorCruiseClient.resolveCarrier(client, state);
		if (source == null) {
			source = client.level.getPlayerByUUID(state.ownerId());
		}
		if (source == null) return;
		float spatialPitch = pitch;
		if (isSonicCue(event)) {
			CruiseSoundRig rig = cruiseRigs.get(source.getUUID());
			if (rig != null) {
				spatialPitch = (float) RadialDopplerModel.applyToPitch(
						pitch, rig.lastDopplerFactor);
			}
		}
		client.level.playLocalSound(source.getX(), source.getY() + source.getBbHeight() * 0.58D,
				source.getZ(), event, SoundSource.PLAYERS,
				volume * FlightArmorClientSettingsStore.masterVolume(), spatialPitch, false);
	}

	private static boolean isSonicCue(SoundEvent event) {
		return event == ModSoundEvents.FLIGHT_FLYBY
				|| event == ModSoundEvents.FLIGHT_BOOST_ENTER
				|| event == ModSoundEvents.FLIGHT_BOOST_RELEASE;
	}

	private void tickCruiseCarriers(Minecraft client) {
		Set<UUID> visibleCarriers = new HashSet<>();
		int remoteCruiseRigs = 0;
		for (ArmorCruiseClient.CruiseState state : ArmorCruiseClient.states()) {
			if (state.phase() == ArmorCruisePhase.IDLE) {
				continue;
			}
			Entity resolved = ArmorCruiseClient.resolveCarrier(client, state);
			if (!(resolved instanceof LivingEntity carrier)) {
				continue;
			}
			CruiseSoundRig rig = cruiseRigs.get(carrier.getUUID());
			if (client.player != null && carrier.distanceToSqr(client.player) > REMOTE_CRUISE_RANGE_SQUARED) continue;
			if (remoteCruiseRigs >= MAX_REMOTE_CRUISE_RIGS) continue;
			remoteCruiseRigs++;

			if (rig == null || rig.entity != carrier) {
				if (rig != null) {
					rig.stopImmediately();
				}
				rig = new CruiseSoundRig(carrier);
				cruiseRigs.put(carrier.getUUID(), rig);
			}
			rig.tick(client, state);
			visibleCarriers.add(carrier.getUUID());
		}

		Iterator<Map.Entry<UUID, CruiseSoundRig>> iterator = cruiseRigs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, CruiseSoundRig> entry = iterator.next();
			if (!visibleCarriers.contains(entry.getKey())) {
				entry.getValue().stopImmediately();
				iterator.remove();
			}
		}
	}

	private enum LoopLayer {
		CORE,
		HUM,
		SERVO,
		DRIVE,
		PRESSURE,
		AIR,
		WATER_HOVER,
		WATER_FIELD,
		WATER_DRIVE
	}

	private enum CruiseLayer {
		CORE,
		HUM,
		SERVO,
		DRIVE,
		PRESSURE,
		AIR,
		WATER_HOVER,
		WATER_FIELD,
		WATER_DRIVE
	}
	private static final class CruiseSoundRig {
		private final LivingEntity entity;
		private final EnumMap<CruiseLayer, ArmorLoopSound> loops = new EnumMap<>(CruiseLayer.class);
		private final RadialDopplerTracker doppler = new RadialDopplerTracker();
		private float underwaterWeight;
		private float lastDopplerFactor = 1.0F;
		private double previousSpeed;
		private Vec3 previousPosition;

		private CruiseSoundRig(LivingEntity entity) {
			this.entity = entity;
			this.previousPosition = entity.position();
		}

		private void tick(Minecraft client, ArmorCruiseClient.CruiseState state) {
			ArmorCruisePhase phase = state.phase();
			Vec3 currentPosition = entity.position();
			Vec3 sampledMotion = currentPosition.subtract(previousPosition);
			double sampledSpeed = sampledMotion.length();
			previousPosition = currentPosition;
			Vec3 sourceMotion = sampledMotion.lengthSqr() > 1.0E-8D
					? sampledMotion : realizedEntityMotion(entity);
			var profile = FlightPhysics.resolveFlightProfile(entity);
			double actualSpeed = Math.max(sampledSpeed, entity.getDeltaMovement().length());
			actualSpeed = Math.min(actualSpeed, profile.dive().hardSpeedCap() * 1.10D);
			// PHASING replaces the carrier entity; that position jump is not flight and
			// must not become a false supersonic air blast.
			if (phase == ArmorCruisePhase.PHASING) actualSpeed = 0.0D;
			double actualAcceleration = actualSpeed - previousSpeed;
			previousSpeed = actualSpeed;

			float waterTarget = entity.isUnderWater() ? 1.0F : 0.0F;
			underwaterWeight += (waterTarget - underwaterWeight) * 0.12F;
			ArmorVariant variant = ModItems.fullSetVariant(entity).orElse(ArmorVariant.DEFAULT);
			ArmorFlightSoundMix.Mix mix = ArmorFlightSoundMix.sample(true, false,
					actualSpeed, actualAcceleration, underwaterWeight,
					profile.cruise().targetSpeed(), profile.dive().targetSpeed(), variant);

			Vec3 listenerPosition = client.player == null ? null : client.player.getEyePosition();
			Vec3 listenerMotion = client.player == null ? null : realizedEntityMotion(client.player);
			float dopplerFactor = doppler.sampleWithMotionWarmup(client.level,
					entity.position().add(0.0D, entity.getBbHeight() * 0.62D, 0.0D),
					listenerPosition, sourceMotion, listenerMotion,
					phase != ArmorCruisePhase.PHASING
							&& phase != ArmorCruisePhase.IDLE);
			lastDopplerFactor = dopplerFactor;
			float phaseGain = switch (phase) {
				case CRUISING -> 0.82F;
				case RECALLING -> 1.0F;
				case DEPLOYING -> 0.72F;
				case PHASING -> 0.34F;
				case OWNER_DOWN -> 0.42F;
				case DOCKING -> 1.0F - smoothstep(DockingTimeline.FRAME_START,
						DockingTimeline.END, ArmorCruiseClient.dockingProgress(entity, 1.0F));
				case IDLE -> 0.0F;
			};
			phaseGain *= FlightArmorClientSettingsStore.masterVolume();
			setLoop(client, CruiseLayer.CORE, ModSoundEvents.CORE_IDLE_EXT,
					(float) mix.coreVolume() * phaseGain,
					(float) mix.corePitch());
			setLoop(client, CruiseLayer.HUM, ModSoundEvents.FLIGHT_HOVER_EXT_LOOP,
					(float) mix.humVolume() * phaseGain,
					(float) mix.humPitch());
			setLoop(client, CruiseLayer.SERVO, ModSoundEvents.FLIGHT_FIELD_LOOP,
					(float) mix.servoVolume() * phaseGain * 0.78F,
					(float) mix.servoPitch());
			setLoop(client, CruiseLayer.DRIVE, ModSoundEvents.FLIGHT_BOOST_LOOP,
					(float) mix.driveVolume() * phaseGain * 0.92F,
					(float) mix.drivePitch());
			setLoop(client, CruiseLayer.PRESSURE, ModSoundEvents.FLIGHT_DIVE_LOOP,
					(float) mix.pressureVolume() * phaseGain * 0.88F,
					(float) RadialDopplerModel.applyToPitch(
							mix.pressurePitch(), dopplerFactor));
			setLoop(client, CruiseLayer.AIR, ModSoundEvents.FLIGHT_AIR_EXT_LOOP,
					(float) mix.airVolume() * phaseGain * 0.82F,
					(float) RadialDopplerModel.applyToPitch(
							mix.airPitch(), dopplerFactor));
			setLoop(client, CruiseLayer.WATER_HOVER, ModSoundEvents.UNDERWATER_HOVER_LOOP,
					(float) mix.waterHoverVolume() * phaseGain,
					(float) mix.waterPitch());
			setLoop(client, CruiseLayer.WATER_FIELD, ModSoundEvents.UNDERWATER_FIELD_LOOP,
					(float) mix.waterFieldVolume() * phaseGain,
					(float) (mix.waterPitch() + 0.04D));
			setLoop(client, CruiseLayer.WATER_DRIVE, ModSoundEvents.UNDERWATER_DIVE_LOOP,
					(float) mix.waterDriveVolume() * phaseGain,
					(float) (mix.waterPitch() + 0.08D));
			loops.entrySet().removeIf(entry -> entry.getValue().isStopped());
		}

		private void setLoop(Minecraft client, CruiseLayer layer, SoundEvent event, float volume, float pitch) {
			ArmorLoopSound current = loops.get(layer);
			if (current != null && current.isStopped()) {
				loops.remove(layer);
				current = null;
			}
			if (volume >= LOOP_START_VOLUME && current == null) {
				current = new ArmorLoopSound(entity, event, false);
				loops.put(layer, current);
				client.getSoundManager().play(current);
			}
			if (current != null) {
				current.setTarget(volume <= LOOP_STOP_VOLUME ? 0.0F : volume, pitch);
				if (volume <= LOOP_STOP_VOLUME) current.fadeOut();
			}
		}

		private void stopImmediately() {
			for (ArmorLoopSound loop : loops.values()) loop.stopImmediately();
			loops.clear();
			doppler.reset();
			lastDopplerFactor = 1.0F;
		}
	}

	private static final class PlayerSoundRig {
		private final Player player;
		private final boolean local;
		private final ArmorSoundEdgeGate flightEdges;
		private final EnumMap<LoopLayer, ArmorLoopSound> loops = new EnumMap<>(LoopLayer.class);
		private final RadialDopplerTracker doppler = new RadialDopplerTracker();
		private float underwaterWeight;
		private float assemblyDuck = 1.0F;
		private float previousVerticalSpeed;
		private float previousMotionSpeed;
		private boolean previousBoosting;
		private boolean previousDiving;

		private PlayerSoundRig(Player player, boolean local) {
			this.player = player;
			this.local = local;
			this.flightEdges = new ArmorSoundEdgeGate(local);
		}

		private void tick(Minecraft client, FlightArmorClient.FlightAudioSnapshot flight, boolean hasGear,
				boolean coreAvailable) {
			boolean flightActive = flight.active() && hasGear;
			ArmorVariant variant = activeFlightVariant();
			double actualSpeed = FlightSpeedTelemetry.currentBlocksPerTick(player);
			double actualAcceleration = actualSpeed - FlightSpeedTelemetry.previousBlocksPerTick(player);
			float waterTarget = player.isUnderWater() || player.isInWaterOrBubble() ? 1.0F : 0.0F;
			underwaterWeight += (waterTarget - underwaterWeight) * 0.12F;
			float assemblyDuckTarget = ArmorAssemblyClient.isAnimating(player) ? 0.56F : 1.0F;
			assemblyDuck += (assemblyDuckTarget - assemblyDuck)
					* (assemblyDuckTarget < assemblyDuck ? 0.32F : 0.10F);

			playFlightEdge(client, flightEdges.observe(flightActive));
			playModeEdges(client, flightActive, flight);
			previousVerticalSpeed = (float) FlightSpeedTelemetry.currentVerticalBlocksPerTick(player);
			previousMotionSpeed = (float) actualSpeed;

			var profile = FlightPhysics.resolveFlightProfile(player);
			ArmorFlightSoundMix.Mix mix = ArmorFlightSoundMix.sample(flightActive, coreAvailable,
					actualSpeed, actualAcceleration, underwaterWeight,
					profile.cruise().targetSpeed(), profile.dive().targetSpeed(), variant);
			float listenerScale = (local ? 0.76F : 1.0F) * assemblyDuck
					* playerCruiseAuthorityScale(player)
					* FlightArmorClientSettingsStore.masterVolume();
			LowAltitudeFeedbackModel.SoundModifier surface =
					LowAltitudeFeedbackClient.soundModifier(player);
			Vec3 listenerPosition = client.player == null ? null : client.player.getEyePosition();
			Vec3 listenerMotion = client.player == null ? null : realizedEntityMotion(client.player);
			float dopplerFactor = doppler.sampleWithMotionWarmup(client.level,
					player.position().add(0.0D, player.getBbHeight() * 0.62D, 0.0D),
					listenerPosition, realizedEntityMotion(player), listenerMotion,
					!local && flightActive);

			// Every continuous load layer follows realized motion. Ctrl/dive only create
			// transition cues; they cannot make sustained engine audio jump ahead of speed.
			setLoop(client, LoopLayer.CORE,
					local ? ModSoundEvents.CORE_IDLE_INT : ModSoundEvents.CORE_IDLE_EXT,
					(float) mix.coreVolume() * listenerScale,
					(float) mix.corePitch());
			setLoop(client, LoopLayer.HUM,
					local ? ModSoundEvents.FLIGHT_HOVER_INT_LOOP : ModSoundEvents.FLIGHT_HOVER_EXT_LOOP,
					(float) mix.humVolume() * listenerScale,
					(float) mix.humPitch());
			setLoop(client, LoopLayer.SERVO, ModSoundEvents.FLIGHT_FIELD_LOOP,
					(float) (mix.servoVolume() * surface.servoVolumeMultiplier())
							* 0.80F * listenerScale,
					(float) (mix.servoPitch() + surface.servoPitchOffset()));
			setLoop(client, LoopLayer.DRIVE, ModSoundEvents.FLIGHT_BOOST_LOOP,
					(float) mix.driveVolume() * 0.92F * listenerScale,
					(float) mix.drivePitch());
			setLoop(client, LoopLayer.PRESSURE, ModSoundEvents.FLIGHT_DIVE_LOOP,
					(float) (mix.pressureVolume() * surface.airVolumeMultiplier())
							* 0.88F * listenerScale,
					(float) RadialDopplerModel.applyToPitch(
							mix.pressurePitch() + surface.airPitchOffset(), dopplerFactor));
			setLoop(client, LoopLayer.AIR,
					local ? ModSoundEvents.FLIGHT_AIR_INT_LOOP : ModSoundEvents.FLIGHT_AIR_EXT_LOOP,
					(float) (mix.airVolume() * surface.airVolumeMultiplier())
							* 0.82F * listenerScale,
					(float) RadialDopplerModel.applyToPitch(
							mix.airPitch() + surface.airPitchOffset(), dopplerFactor));
			setLoop(client, LoopLayer.WATER_HOVER, ModSoundEvents.UNDERWATER_HOVER_LOOP,
					(float) mix.waterHoverVolume() * listenerScale,
					(float) mix.waterPitch());
			setLoop(client, LoopLayer.WATER_FIELD, ModSoundEvents.UNDERWATER_FIELD_LOOP,
					(float) mix.waterFieldVolume() * 0.82F * listenerScale,
					(float) (mix.waterPitch() + 0.04D));
			setLoop(client, LoopLayer.WATER_DRIVE, ModSoundEvents.UNDERWATER_DIVE_LOOP,
					(float) mix.waterDriveVolume() * 0.94F * listenerScale,
					(float) (mix.waterPitch() + 0.08D));
			loops.entrySet().removeIf(entry -> entry.getValue().isStopped());
		}

		private void playModeEdges(Minecraft client, boolean flightActive,
				FlightArmorClient.FlightAudioSnapshot flight) {
			boolean boosting = flightActive && flight.boosting();
			boolean diving = flightActive && flight.diving();
			if (boosting && !previousBoosting) {
				playOneShot(client, player.isInWaterOrBubble()
						? ModSoundEvents.UNDERWATER_BOOST_ENTER
						: ModSoundEvents.FLIGHT_BOOST_ENTER, 0.60F, 0.98F);
			} else if (!boosting && previousBoosting && flightActive) {
				playOneShot(client, ModSoundEvents.FLIGHT_BOOST_RELEASE, 0.48F, 0.94F);
			}
			if (diving && !previousDiving) {
				playOneShot(client, ModSoundEvents.FLIGHT_DIVE_ENTER, 0.64F, 0.96F);
			} else if (!diving && previousDiving && flightActive) {
				playOneShot(client, ModSoundEvents.FLIGHT_DIVE_RECOVER, 0.54F, 0.98F);
			}
			previousBoosting = boosting;
			previousDiving = diving;
		}

		private void playFlightEdge(Minecraft client, ArmorSoundEdgeGate.Edge edge) {
			float pitchSignature = switch (activeFlightVariant()) {
				case VANGUARD -> 0.0F;
				case SWIFT -> 0.04F;
				case SENTINEL -> -0.04F;
			};
			if (edge == ArmorSoundEdgeGate.Edge.START) {
				playOneShot(client, ModSoundEvents.FLIGHT_ENGAGE, 0.56F, 0.98F + pitchSignature);
				return;
			}
			if (edge != ArmorSoundEdgeGate.Edge.STOP) return;
			if (player.onGround()) {
				boolean hardLanding = Math.abs(previousVerticalSpeed) >= 0.65F || previousMotionSpeed >= 0.90F;
				playOneShot(client,
						hardLanding ? ModSoundEvents.FLIGHT_LAND_HARD : ModSoundEvents.FLIGHT_LAND_SOFT,
						hardLanding ? 0.64F : 0.42F, hardLanding ? 0.92F : 1.0F);
			} else {
				playOneShot(client, ModSoundEvents.FLIGHT_DISENGAGE, 0.46F, 0.97F + pitchSignature);
			}
		}

		private ArmorVariant activeFlightVariant() {
			return ModItems.fullSetVariant(player)
					.orElseGet(() -> ModItems.bootsVariant(player).orElse(ArmorVariant.DEFAULT));
		}

		private void setLoop(Minecraft client, LoopLayer layer, SoundEvent event, float volume, float pitch) {
			ArmorLoopSound current = loops.get(layer);
			if (current != null && current.isStopped()) {
				loops.remove(layer);
				current = null;
			}
			if (volume >= LOOP_START_VOLUME && current == null) {
				current = new ArmorLoopSound(player, event, local);
				loops.put(layer, current);
				SoundManager soundManager = client.getSoundManager();
				soundManager.play(current);
			}
			if (current != null) {
				current.setTarget(volume <= LOOP_STOP_VOLUME ? 0.0F : volume, pitch);
				if (volume <= LOOP_STOP_VOLUME) current.fadeOut();
			}
		}

		private void playOneShot(Minecraft client, SoundEvent event, float volume, float pitch) {
			if (client.level == null) return;
			float localScale = local ? 0.74F : 1.0F;
			client.level.playLocalSound(player.getX(), player.getY() + player.getBbHeight() * 0.60D, player.getZ(),
					event, SoundSource.PLAYERS,
					volume * localScale * FlightArmorClientSettingsStore.masterVolume(), pitch, false);
		}

		private void stopImmediately() {
			for (ArmorLoopSound loop : loops.values()) loop.stopImmediately();
			loops.clear();
			doppler.reset();
			previousBoosting = false;
			previousDiving = false;
		}

		private boolean isSilent() {
			return loops.isEmpty();
		}
	}

	private static Vec3 realizedEntityMotion(Entity entity) {
		Vec3 interpolatedStep = entity.getPosition(1.0F).subtract(entity.getPosition(0.0F));
		return interpolatedStep.lengthSqr() > 1.0E-8D
				? interpolatedStep : entity.getDeltaMovement();
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static float playerCruiseAuthorityScale(Player player) {
		for (ArmorCruiseClient.CruiseState state : ArmorCruiseClient.states()) {
			if (!state.ownerId().equals(player.getUUID()) || state.phase() != ArmorCruisePhase.DOCKING) continue;
			float progress = ArmorCruiseClient.dockingProgress(state, 1.0F);
			// 战靴接管时先给玩家一小部分悬浮声，再在核心上线前完成载体到玩家的交叉淡化。
			return 0.25F + 0.75F * smoothstep(DockingTimeline.BOOTS_TRANSFER,
					DockingTimeline.CORE_START, progress);
		}
		return 1.0F;
	}

	private record CruisePhaseObservation(long sequenceId, ArmorCruisePhase phase, UUID carrierId,
			long phaseStartGameTime, int cueMask) {
	}
}
