package dev.codex.flightarmor.client.audio;

import dev.codex.flightarmor.assembly.ArmorAssemblySlotMask;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyPart;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.network.ArmorAssemblyStatePayload;
import dev.codex.flightarmor.sound.ModSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按服务端的 sequenceId 和游戏时间驱动分件着甲瞬态声音。
 *
 * <p>这里不从渲染器触发声音：同一帧可能渲染多次，而游戏时间才是动画的
 * 权威时钟。新追踪者收到中途快照时会从“当前 tick”开始，只播放尚未发生的事件，
 * 不会把过去几十 tick 的飞入和锁定声一次性补播。</p>
 */
public final class ArmorAssemblySoundController {
	private static final List<TimelineEvent> EVENTS = createTimeline();
	private static final Map<UUID, TimelineState> TIMELINES = new HashMap<>();
	private static final Map<UUID, Long> LAST_SEQUENCES = new HashMap<>();
	private static ClientLevel currentLevel;

	private ArmorAssemblySoundController() {
	}

	/** 接收权威快照，并把完整事件裁成当前槽位自己的局部正向/反向时间线。 */
	public static void receive(ArmorAssemblyStatePayload payload) {
		Minecraft client = Minecraft.getInstance();
		ensureLevel(client);

		long knownSequence = LAST_SEQUENCES.getOrDefault(payload.playerId(), Long.MIN_VALUE);
		if (payload.sequenceId() < knownSequence) return;
		LAST_SEQUENCES.put(payload.playerId(), payload.sequenceId());

		TimelineState existing = TIMELINES.get(payload.playerId());
		if (!payload.active() || !payload.assembling()) {
			if (existing != null && existing.sequenceId == payload.sequenceId()
					&& (payload.active() || localTick(client, existing) >= existing.durationTicks)) {
				// 完成包可能先于本帧声音 tick 到达；只补终点尚未消费的锁止声，不补历史事件。
				playEventsAtTick(client, existing, existing.durationTicks);
			}
			TIMELINES.remove(payload.playerId());
			return;
		}

		if (existing != null && existing.sequenceId == payload.sequenceId()) {
			existing.startGameTime = payload.startGameTime();
			existing.durationTicks = Math.max(1, payload.durationTicks());
			return;
		}

		int durationTicks = Math.max(1, payload.durationTicks());
		List<TimelineEvent> localEvents = createLocalTimeline(payload, durationTicks);
		int now = localTick(client, payload.startGameTime());
		TimelineState created = new TimelineState(
				payload.playerId(), payload.sequenceId(), payload.startGameTime(), durationTicks,
				localEvents, firstEventAtOrAfter(localEvents, now));
		TIMELINES.put(payload.playerId(), created);

		// 本机包允许 2 tick 网络宽限；局部头/胸/腿/鞋时间线的首事件都位于 local tick 0。
		if (now == 0) {
			processCurrentTick(client, created);
		} else if (client.player != null && payload.playerId().equals(client.player.getUUID()) && now <= 2) {
			created.nextEventIndex = 0;
			for (int catchUpTick = 0; catchUpTick <= now; catchUpTick++) {
				playEventsAtTick(client, created, catchUpTick);
			}
		}
	}

	/** 每个客户端主 tick 调用一次。 */
	public static void tick(Minecraft client) {
		ensureLevel(client);
		if (client.level == null) return;

		Iterator<Map.Entry<UUID, TimelineState>> iterator = TIMELINES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, TimelineState> entry = iterator.next();
			Player player = client.level.getPlayerByUUID(entry.getKey());
			if (player == null || !player.isAlive()) {
				// 实体离开客户端世界后不保留任何声音调度状态。
				iterator.remove();
				LAST_SEQUENCES.remove(entry.getKey());
				continue;
			}

			TimelineState state = entry.getValue();
			processCurrentTick(client, state);
			if (localTick(client, state) > state.durationTicks) {
				iterator.remove();
			}
		}
	}

	/** 断线、退出世界或资源生命周期结束时清理全部序列游标。 */
	public static void clear() {
		TIMELINES.clear();
		LAST_SEQUENCES.clear();
		currentLevel = null;
	}

	/** 玩家实体离开客户端追踪范围时，同时清理声音序列和旧 sequence 门槛。 */
	public static void remove(UUID playerId) {
		TIMELINES.remove(playerId);
		LAST_SEQUENCES.remove(playerId);
	}

	private static void ensureLevel(Minecraft client) {
		if (client.level == currentLevel) return;
		TIMELINES.clear();
		LAST_SEQUENCES.clear();
		currentLevel = client.level;
	}

	private static void processCurrentTick(Minecraft client, TimelineState state) {
		int now = localTick(client, state);
		playEventsAtTick(client, state, now);
	}

	private static void playEventsAtTick(Minecraft client, TimelineState state, int now) {
		if (client.level == null) return;
		Player player = client.level.getPlayerByUUID(state.playerId);
		if (player == null || !player.isAlive()) return;
		// 时间快照跳跃时直接越过已经过去的事件，绝不集中补播。
		while (state.nextEventIndex < state.events.size()) {
			TimelineEvent event = state.events.get(state.nextEventIndex);
			if (event.tick < now) {
				state.nextEventIndex++;
				continue;
			}
			if (event.tick != now) break;
			play(client, player, event);
			state.nextEventIndex++;
		}
	}

	private static List<TimelineEvent> createLocalTimeline(ArmorAssemblyStatePayload payload,
			int durationTicks) {
		int start = payload.timelineStartTick();
		int end = Math.max(start + 1, payload.timelineEndTick());
		int span = Math.max(1, end - start);
		List<TimelineEvent> result = new ArrayList<>();
		for (TimelineEvent event : EVENTS) {
			if (!eventIncluded(payload.affectedSlotMask(), payload.retracting(), event)
					|| event.tick < start || event.tick > end) continue;
			int canonicalOffset = payload.retracting() ? end - event.tick : event.tick - start;
			int localEventTick = Math.round(canonicalOffset * durationTicks / (float) span);
			TimelineEvent audible = payload.retracting() ? asRetractionEvent(event) : event;
			result.add(new TimelineEvent(localEventTick, event.part, audible.sound, audible.volume, audible.pitch));
		}
		result.sort(Comparator.comparingInt(TimelineEvent::tick));
		return List.copyOf(result);
	}

	private static int localTick(Minecraft client, TimelineState state) {
		return localTick(client, state.startGameTime);
	}

	private static int localTick(Minecraft client, long startGameTime) {
		if (client.level == null) return 0;
		return (int) Math.floor(Math.max(0.0D, client.level.getGameTime() - startGameTime) + 1.0E-6D);
	}

	private static int firstEventAtOrAfter(List<TimelineEvent> events, int tick) {
		int index = 0;
		while (index < events.size() && events.get(index).tick < tick) index++;
		return index;
	}

	private static boolean eventIncluded(int affectedSlotMask, boolean retracting, TimelineEvent event) {
		if (event.part == null) {
			return !retracting && (affectedSlotMask & ArmorAssemblySlotMask.FULL_SET)
					== ArmorAssemblySlotMask.FULL_SET;
		}
		return ArmorAssemblySlotMask.contains(affectedSlotMask, event.part.equipmentSlot());
	}

	private static TimelineEvent asRetractionEvent(TimelineEvent event) {
		SoundEvent sound = event.sound;
		float volume = event.volume;
		float pitch = 0.98F;
		if (sound == ModSoundEvents.CORE_ONLINE) {
			sound = ModSoundEvents.CORE_SHUTDOWN;
			volume = 0.56F;
		} else if (sound == ModSoundEvents.ASSEMBLY_CORE_LOCK
				|| sound == ModSoundEvents.ASSEMBLY_LOCK_TORSO) {
			sound = ModSoundEvents.ASSEMBLY_UNLOCK_TORSO;
			volume = 0.58F;
		} else if (sound == ModSoundEvents.ASSEMBLY_LOCK_BOOT) {
			sound = ModSoundEvents.ASSEMBLY_UNLOCK_BOOT;
			volume = 0.46F;
		} else if (sound == ModSoundEvents.ASSEMBLY_LOCK_LIMB
				|| sound == ModSoundEvents.ASSEMBLY_HELMET_SEAL) {
			sound = ModSoundEvents.ASSEMBLY_UNLOCK_LIMB;
			volume = 0.50F;
		} else if (sound == ModSoundEvents.ASSEMBLY_INBOUND_HEAVY) {
			sound = ModSoundEvents.ASSEMBLY_RETRACT_HEAVY;
			volume = 0.48F;
		} else if (sound == ModSoundEvents.ASSEMBLY_INBOUND_MEDIUM
				|| sound == ModSoundEvents.ASSEMBLY_INBOUND_HELMET) {
			sound = ModSoundEvents.ASSEMBLY_RETRACT_MEDIUM;
			volume = 0.43F;
		} else if (sound == ModSoundEvents.ASSEMBLY_INBOUND_LIGHT
				|| sound == ModSoundEvents.ASSEMBLY_INBOUND_FACEPLATE
				|| sound == ModSoundEvents.ASSEMBLY_CORE_CHARGE) {
			sound = ModSoundEvents.ASSEMBLY_RETRACT_LIGHT;
			volume = 0.38F;
		}
		return new TimelineEvent(event.tick, event.part, sound, volume, pitch);
	}

	private static void play(Minecraft client, Player player, TimelineEvent event) {
		if (client.level == null) return;
		SpatialOffset offset = offsetFor(event.part);
		double yaw = Math.toRadians(player.getYRot());
		double rightX = Math.cos(yaw);
		double rightZ = Math.sin(yaw);
		double forwardX = -Math.sin(yaw);
		double forwardZ = Math.cos(yaw);
		double x = player.getX() + rightX * offset.right + forwardX * offset.forward;
		double z = player.getZ() + rightZ * offset.right + forwardZ * offset.forward;
		double y = player.getY() + offset.up;
		float listenerScale = client.player != null && client.player.getUUID().equals(player.getUUID()) ? 0.84F : 1.0F;
		client.level.playLocalSound(x, y, z, event.sound, SoundSource.PLAYERS,
				event.volume * listenerScale * FlightArmorClientSettingsStore.masterVolume(),
				event.pitch * sidePitch(event.part), false);
	}

	private static float sidePitch(ArmorAssemblyPart part) {
		if (part == null) return 1.0F;
		return switch (part) {
			case LEFT_BOOT, LEFT_LEG, LEFT_FOREARM, LEFT_UPPER_ARM -> 0.992F;
			case RIGHT_BOOT, RIGHT_LEG, RIGHT_FOREARM, RIGHT_UPPER_ARM -> 1.008F;
			default -> 1.0F;
		};
	}

	private static SpatialOffset offsetFor(ArmorAssemblyPart part) {
		if (part == null) return new SpatialOffset(0.0D, 1.05D, 0.0D);
		return switch (part) {
			case LEFT_BOOT -> new SpatialOffset(-0.20D, 0.12D, 0.0D);
			case RIGHT_BOOT -> new SpatialOffset(0.20D, 0.12D, 0.0D);
			case LEFT_LEG -> new SpatialOffset(-0.20D, 0.62D, 0.0D);
			case RIGHT_LEG -> new SpatialOffset(0.20D, 0.62D, 0.0D);
			case LEFT_FOREARM, LEFT_UPPER_ARM -> new SpatialOffset(-0.38D, 1.18D, 0.0D);
			case RIGHT_FOREARM, RIGHT_UPPER_ARM -> new SpatialOffset(0.38D, 1.18D, 0.0D);
			case BACK -> new SpatialOffset(0.0D, 1.10D, -0.18D);
			case TORSO, CORE -> new SpatialOffset(0.0D, 1.16D, 0.18D);
			case HELMET_SHELL, HELMET_FACE -> new SpatialOffset(0.0D, 1.65D, 0.08D);
		};
	}

	private static List<TimelineEvent> createTimeline() {
		List<TimelineEvent> events = new ArrayList<>();
		events.add(new TimelineEvent(0, null, ModSoundEvents.ASSEMBLY_RECALL_START, 0.62F, 1.0F));
		events.add(new TimelineEvent(0, null, ModSoundEvents.CORE_WAKE, 0.36F, 1.0F));

		for (ArmorAssemblyPart part : ArmorAssemblyPart.values()) {
			if (part == ArmorAssemblyPart.CORE) {
				events.add(new TimelineEvent(part.startTick(), part,
						ModSoundEvents.ASSEMBLY_CORE_CHARGE, 0.52F, 1.0F));
				// 机械锁止提前 1 tick，随后由核心上线声收尾，避免两个强瞬态同帧叠浑。
				events.add(new TimelineEvent(part.endTick() - 1, part,
						ModSoundEvents.ASSEMBLY_CORE_LOCK, 0.86F, 1.0F));
				events.add(new TimelineEvent(part.endTick(), part,
						ModSoundEvents.CORE_ONLINE, 0.56F, 1.0F));
				continue;
			}

			events.add(new TimelineEvent(part.startTick(), part, inboundSound(part), inboundVolume(part), 1.0F));
			events.add(new TimelineEvent(part.endTick(), part, lockSound(part), lockVolume(part), 1.0F));
		}
		events.sort(Comparator.comparingInt(TimelineEvent::tick));
		return List.copyOf(events);
	}

	private static SoundEvent inboundSound(ArmorAssemblyPart part) {
		return switch (part) {
			case LEFT_FOREARM, RIGHT_FOREARM -> ModSoundEvents.ASSEMBLY_INBOUND_LIGHT;
			case LEFT_BOOT, RIGHT_BOOT, LEFT_UPPER_ARM, RIGHT_UPPER_ARM ->
					ModSoundEvents.ASSEMBLY_INBOUND_MEDIUM;
			case LEFT_LEG, RIGHT_LEG, BACK, TORSO -> ModSoundEvents.ASSEMBLY_INBOUND_HEAVY;
			case HELMET_SHELL -> ModSoundEvents.ASSEMBLY_INBOUND_HELMET;
			case HELMET_FACE -> ModSoundEvents.ASSEMBLY_INBOUND_FACEPLATE;
			case CORE -> ModSoundEvents.ASSEMBLY_CORE_CHARGE;
		};
	}

	private static SoundEvent lockSound(ArmorAssemblyPart part) {
		return switch (part) {
			case LEFT_BOOT, RIGHT_BOOT -> ModSoundEvents.ASSEMBLY_LOCK_BOOT;
			case LEFT_LEG, RIGHT_LEG, LEFT_FOREARM, RIGHT_FOREARM, LEFT_UPPER_ARM, RIGHT_UPPER_ARM ->
					ModSoundEvents.ASSEMBLY_LOCK_LIMB;
			case BACK, TORSO -> ModSoundEvents.ASSEMBLY_LOCK_TORSO;
			case HELMET_SHELL, HELMET_FACE -> ModSoundEvents.ASSEMBLY_HELMET_SEAL;
			case CORE -> ModSoundEvents.ASSEMBLY_CORE_LOCK;
		};
	}

	private static float inboundVolume(ArmorAssemblyPart part) {
		return switch (part) {
			case LEFT_FOREARM, RIGHT_FOREARM, HELMET_FACE -> 0.42F;
			case LEFT_LEG, RIGHT_LEG, BACK, TORSO -> 0.60F;
			default -> 0.51F;
		};
	}

	private static float lockVolume(ArmorAssemblyPart part) {
		return switch (part) {
			case BACK, TORSO -> 0.78F;
			case HELMET_SHELL, HELMET_FACE -> 0.68F;
			default -> 0.62F;
		};
	}

	private record TimelineEvent(int tick, ArmorAssemblyPart part, SoundEvent sound,
			float volume, float pitch) {
	}

	private record SpatialOffset(double right, double up, double forward) {
	}

	private static final class TimelineState {
		private final UUID playerId;
		private final long sequenceId;
		private long startGameTime;
		private int durationTicks;
		private final List<TimelineEvent> events;
		private int nextEventIndex;

		private TimelineState(UUID playerId, long sequenceId, long startGameTime,
				int durationTicks, List<TimelineEvent> events, int nextEventIndex) {
			this.playerId = playerId;
			this.sequenceId = sequenceId;
			this.startGameTime = startGameTime;
			this.durationTicks = durationTicks;
			this.events = events;
			this.nextEventIndex = nextEventIndex;
		}
	}
}
