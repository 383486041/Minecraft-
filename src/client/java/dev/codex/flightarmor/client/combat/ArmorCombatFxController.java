package dev.codex.flightarmor.client.combat;

import dev.codex.flightarmor.combat.ArmorAttackHitKind;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.EnhancedBeamMath;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 武器特效状态仓库。这里不再生成任何原版粒子，只保存服务端权威事件，
 * 由 {@link ArmorCombatFxRenderer} 在世界渲染阶段绘制原创动态几何。
 */
public final class ArmorCombatFxController {
	private static final int MAX_REMEMBERED_EVENTS = 256;
	private static final int MAX_ACTIVE_BEAMS = 24;
	private static final int MAX_ACTIVE_IMPACTS = 32;
	private static final int MAX_ACTIVE_SHOCKWAVES = 12;
	private static final int MAX_ACTIVE_MISSILES = 48;
	/**
	 * 光束特效的距离预算。**唯一真相源是 {@link EnhancedBeamMath#MAX_LANCE_FX_RANGE}**——
	 * 这里刻意不再自己写一份距离：灭世光束的射程一变，两边不同步的表现就是
	 * 「山被挖掉了却看不见光束」，而那是最难排查的一类 bug。
	 */
	private static final double MAX_EFFECT_DISTANCE_SQR =
			EnhancedBeamMath.MAX_LANCE_FX_RANGE * EnhancedBeamMath.MAX_LANCE_FX_RANGE;
	private static final List<ActiveBeam> ACTIVE_BEAMS = new ArrayList<>();
	private static final List<ActiveImpact> ACTIVE_IMPACTS = new ArrayList<>();
	private static final List<ActiveShockwave> ACTIVE_SHOCKWAVES = new ArrayList<>();
	private static final List<ActiveMissile> ACTIVE_MISSILES = new ArrayList<>();
	private static final Set<EventKey> SEEN_EVENTS = new HashSet<>();
	private static final ArrayDeque<EventKey> EVENT_ORDER = new ArrayDeque<>();

	private ArmorCombatFxController() {
	}

	public static void initialize() {
		ArmorCombatFxRenderer.initialize();
	}

	/** 返回 false 表示非法或重复事件，调用方不应再播放声音或后坐。 */
	public static boolean receive(Minecraft client, ArmorAttackFxPayload payload) {
		if (payload.attackType() == ArmorAttackType.INVALID
				|| client.level == null
				|| !client.level.dimension().identifier().equals(payload.dimension())
				|| !isFinite(payload.origin()) || !isFinite(payload.endpoint())
				|| !isFinite(payload.visualEndpoint())) {
			return false;
		}
		Vec3 delta = payload.visualEndpoint().subtract(payload.origin());
		if (delta.lengthSqr() > MAX_EFFECT_DISTANCE_SQR) return false;

		EventKey key = new EventKey(payload.attackerId(), payload.serverAttackId(), payload.eventIndex());
		if (!SEEN_EVENTS.add(key)) return false;
		EVENT_ORDER.addLast(key);
		while (EVENT_ORDER.size() > MAX_REMEMBERED_EVENTS) {
			SEEN_EVENTS.remove(EVENT_ORDER.removeFirst());
		}

		long startGameTime = client.level.getGameTime();
		float charge = Mth.clamp(payload.charge(), 0.0F, 1.0F);
		switch (payload.attackType()) {
			case WRIST_GRAVITY_PULSE -> {
				boundedAdd(ACTIVE_BEAMS, new ActiveBeam(
						payload.attackerId(), payload.serverAttackId(), payload.eventIndex(),
						payload.origin(), payload.visualEndpoint(),
						ArmorAttackType.WRIST_GRAVITY_PULSE, payload.variant(), payload.leftHand(), charge,
						payload.underwater(), startGameTime, wristBeamLifetime(payload.variant())),
						MAX_ACTIVE_BEAMS);
				if (payload.hitKind() != ArmorAttackHitKind.MISS) {
					boundedAdd(ACTIVE_IMPACTS, new ActiveImpact(
							payload.serverAttackId(), payload.endpoint(), normalizedOrUp(payload.impactNormal()),
							ArmorAttackType.WRIST_GRAVITY_PULSE, payload.variant(), payload.hitKind(), charge,
							payload.underwater(), startGameTime, wristImpactLifetime(payload.variant())),
							MAX_ACTIVE_IMPACTS);
				}
				if (payload.eventIndex() == 0) {
					ArmorWeaponSoundController.playPulse(client, payload.attackerId(), payload.origin(),
							payload.endpoint(), payload.hitKind(), payload.underwater(), payload.variant(), charge);
				} else if (payload.hitKind() != ArmorAttackHitKind.MISS) {
					ArmorWeaponSoundController.playChainImpact(
							client, payload.endpoint(), payload.underwater(), payload.variant());
				}
			}
			case ENERGY_BLADE_SLASH -> {
				boundedAdd(ACTIVE_BEAMS, new ActiveBeam(
						payload.attackerId(), payload.serverAttackId(), payload.eventIndex(), payload.origin(),
						payload.visualEndpoint(), ArmorAttackType.ENERGY_BLADE_SLASH,
						payload.variant(), payload.leftHand(), 1.0F, payload.underwater(),
						startGameTime, payload.variant() == ArmorVariant.SWIFT ? 6 : 8),
						MAX_ACTIVE_BEAMS);
				ArmorWeaponSoundController.playBladeSlash(client, payload.attackerId(),
						payload.origin(), payload.variant(), payload.leftHand());
			}
			case ENERGY_BLADE_COMBO -> {
				// Close-range primary arc: sound/recoil only, never a ranged blade wave.
				ArmorWeaponSoundController.playBladeSlash(client, payload.attackerId(),
						payload.origin(), payload.variant(), payload.leftHand());
			}
			case CORE_BEAM, CRUISE_CORE_BEAM -> {
				boundedAdd(ACTIVE_BEAMS, new ActiveBeam(
						payload.attackerId(), payload.serverAttackId(), payload.eventIndex(),
						payload.origin(), payload.visualEndpoint(),
						payload.attackType(), payload.variant(), false, charge,
						payload.underwater(), startGameTime, coreBeamLifetime(payload.variant())),
						MAX_ACTIVE_BEAMS);
				if (payload.hitKind() != ArmorAttackHitKind.MISS) {
					boundedAdd(ACTIVE_IMPACTS, new ActiveImpact(
							payload.serverAttackId(), payload.endpoint(), normalizedOrUp(payload.impactNormal()),
							payload.attackType(), payload.variant(), payload.hitKind(), charge,
							payload.underwater(), startGameTime, coreImpactLifetime(payload.variant())),
							MAX_ACTIVE_IMPACTS);
				}
				// Sentinel pod companion rays share the authoritative chest attack id at indexes
				// 1/2. They draw their own beam but must not triple the main cannon release sound.
				if (payload.eventIndex() == 0) {
					ArmorWeaponSoundController.playCoreRelease(client, payload.attackerId(),
							payload.origin(), payload.endpoint(), payload.hitKind(),
							payload.underwater(), charge, payload.variant());
				}
			}
			case GRAVITY_DIVE_IMPACT, CRUISE_RAM, CRUISE_GROUND_SLAM -> {
				boundedAdd(ACTIVE_SHOCKWAVES, new ActiveShockwave(
						payload.serverAttackId(), payload.attackType(), payload.origin(),
						normalizedOrUp(payload.impactNormal()),
						payload.visualEndpoint().subtract(payload.origin()), payload.variant(),
						Math.max(0.35F, charge), payload.underwater(), startGameTime,
						diveLifetime(payload.variant())),
						MAX_ACTIVE_SHOCKWAVES);
				ArmorWeaponSoundController.playGravityImpact(client, payload.attackerId(),
						payload.origin(), payload.underwater(), Math.max(0.35F, charge), payload.variant());
			}
			case CRUISE_MISSILE_VOLLEY -> {
				if (payload.hitKind() == ArmorAttackHitKind.MISS) {
					if (payload.eventIndex() == 0) {
						ArmorWeaponSoundController.playMissileLaunch(client, payload.origin());
					}
				} else {
					boundedAdd(ACTIVE_IMPACTS, new ActiveImpact(
							payload.serverAttackId(), payload.endpoint(), normalizedOrUp(payload.impactNormal()),
							ArmorAttackType.CRUISE_MISSILE_VOLLEY, ArmorVariant.SWIFT,
							payload.hitKind(), 1.0F, false, startGameTime, 7), MAX_ACTIVE_IMPACTS);
					ArmorWeaponSoundController.playMissileImpact(client, payload.endpoint());
				}
			}
			/*
			 * 手部光束两档刻意排在 CORE_BEAM 区块**之外**：
			 * `SentinelDronePodContractTest` 会截取「case CORE_BEAM … → case GRAVITY_DIVE_IMPACT」之间的
			 * 源码，并要求其中只出现一次 playCoreRelease( ——那是「守望翼炮 1/2 号副射线不得重放主炮释放音」
			 * 的源码不变量。灭世档复用同一个重型释放音，若插进那段窗口会立刻翻红（已踩过一次）。
			 */
			case HAND_BEAM_PULSE -> {
				boundedAdd(ACTIVE_BEAMS, new ActiveBeam(
						payload.attackerId(), payload.serverAttackId(), payload.eventIndex(),
						payload.origin(), payload.visualEndpoint(),
						ArmorAttackType.HAND_BEAM_PULSE, payload.variant(), payload.leftHand(), charge,
						payload.underwater(), startGameTime, handBeamLifetime(payload.variant())),
						MAX_ACTIVE_BEAMS);
				if (payload.hitKind() != ArmorAttackHitKind.MISS) {
					boundedAdd(ACTIVE_IMPACTS, new ActiveImpact(
							payload.serverAttackId(), payload.endpoint(), normalizedOrUp(payload.impactNormal()),
							ArmorAttackType.HAND_BEAM_PULSE, payload.variant(), payload.hitKind(), charge,
							payload.underwater(), startGameTime,
							handPulseImpactLifetime(payload.variant())),
							MAX_ACTIVE_IMPACTS);
				}
				ArmorWeaponSoundController.playPulse(client, payload.attackerId(), payload.origin(),
						payload.endpoint(), payload.hitKind(), payload.underwater(),
						payload.variant(), charge);
			}
			case HAND_BEAM_LANCE -> {
				boundedAdd(ACTIVE_BEAMS, new ActiveBeam(
						payload.attackerId(), payload.serverAttackId(), payload.eventIndex(),
						payload.origin(), payload.visualEndpoint(),
						ArmorAttackType.HAND_BEAM_LANCE, payload.variant(), false, charge,
						payload.underwater(), startGameTime, handLanceLifetime(payload.variant())),
						MAX_ACTIVE_BEAMS);
				if (payload.hitKind() != ArmorAttackHitKind.MISS) {
					boundedAdd(ACTIVE_IMPACTS, new ActiveImpact(
							payload.serverAttackId(), payload.endpoint(), normalizedOrUp(payload.impactNormal()),
							ArmorAttackType.HAND_BEAM_LANCE, payload.variant(), payload.hitKind(), charge,
							payload.underwater(), startGameTime,
							handLanceImpactLifetime(payload.variant())),
							MAX_ACTIVE_IMPACTS);
				}
				// 长按档复用核心炮的重型释放音：不需要新增 .ogg；同时它会写入
				// RECENT_CORE_RELEASES，蓄力循环音才能被识别为「释放」而不是「取消」。
				ArmorWeaponSoundController.playCoreRelease(client, payload.attackerId(),
						payload.origin(), payload.endpoint(), payload.hitKind(),
						payload.underwater(), charge, payload.variant());
			}
			case ENERGY_BLADE_TOGGLE, INVALID -> {
				return false;
			}
		}
		return true;
	}

	public static void tick(Minecraft client) {
		if (client.level == null) {
			clearActiveEffects();
			return;
		}
		long now = client.level.getGameTime();
		pruneExpired(ACTIVE_BEAMS.iterator(), now);
		pruneExpired(ACTIVE_IMPACTS.iterator(), now);
		pruneExpired(ACTIVE_SHOCKWAVES.iterator(), now);
		pruneExpired(ACTIVE_MISSILES.iterator(), now);
	}

	public static void clear() {
		clearActiveEffects();
		SEEN_EVENTS.clear();
		EVENT_ORDER.clear();
	}

	public static List<ActiveBeam> activeBeams() {
		return ACTIVE_BEAMS;
	}

	public static List<ActiveImpact> activeImpacts() {
		return ACTIVE_IMPACTS;
	}

	public static List<ActiveShockwave> activeShockwaves() {
		return ACTIVE_SHOCKWAVES;
	}

	public static List<ActiveMissile> activeMissiles() {
		return ACTIVE_MISSILES;
	}

	private static void clearActiveEffects() {
		ACTIVE_BEAMS.clear();
		ACTIVE_IMPACTS.clear();
		ACTIVE_SHOCKWAVES.clear();
		ACTIVE_MISSILES.clear();
	}

	private static <T> void boundedAdd(List<T> list, T value, int limit) {
		while (list.size() >= limit) list.removeFirst();
		list.add(value);
	}

	private static void pruneExpired(Iterator<? extends TimedEffect> iterator, long now) {
		while (iterator.hasNext()) {
			TimedEffect effect = iterator.next();
			if (now - effect.startGameTime() >= effect.lifetimeTicks()) iterator.remove();
		}
	}

	private static Vec3 normalizedOrUp(Vec3 value) {
		return isFinite(value) && value.lengthSqr() > 1.0E-5D
				? value.normalize() : new Vec3(0.0D, 1.0D, 0.0D);
	}

	private static int wristBeamLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 5;
			case SWIFT -> 4;
			case SENTINEL -> 7;
		};
	}

	private static int wristImpactLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 8;
			case SWIFT -> 6;
			case SENTINEL -> 10;
		};
	}

	private static int coreBeamLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 8;
			case SWIFT -> 6;
			case SENTINEL -> 10;
		};
	}

	private static int coreImpactLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 11;
			case SWIFT -> 8;
			case SENTINEL -> 14;
		};
	}

	/** 点射是短促的一条，寿命与腕炮同量级。 */
	private static int handBeamLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 5;
			case SWIFT -> 4;
			case SENTINEL -> 7;
		};
	}

	private static int handPulseImpactLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 7;
			case SWIFT -> 5;
			case SENTINEL -> 9;
		};
	}

	/** 灭世档的光束要比核心炮留得更久，否则削出的坑还没看清光束就没了。 */
	private static int handLanceLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 12;
			case SWIFT -> 9;
			case SENTINEL -> 15;
		};
	}

	private static int handLanceImpactLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 18;
			case SWIFT -> 13;
			case SENTINEL -> 22;
		};
	}

	private static int diveLifetime(ArmorVariant variant) {
		return switch (variant) {
			case VANGUARD -> 13;
			case SWIFT -> 9;
			case SENTINEL -> 18;
		};
	}

	private static boolean isFinite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private record EventKey(UUID attackerId, long serverAttackId, int eventIndex) {
	}

	interface TimedEffect {
		long startGameTime();

		int lifetimeTicks();
	}

	public static final class ActiveBeam implements TimedEffect {
		public final UUID attackerId;
		public final long attackId;
		public final int eventIndex;
		public final Vec3 origin;
		public final Vec3 endpoint;
		public final ArmorAttackType type;
		public final ArmorVariant variant;
		public final boolean leftHand;
		public final float charge;
		public final boolean underwater;
		public final long startGameTime;
		public final int lifetimeTicks;
		public Vec3 visualOrigin;

		private ActiveBeam(UUID attackerId, long attackId, int eventIndex,
				Vec3 origin, Vec3 endpoint, ArmorAttackType type,
				ArmorVariant variant, boolean leftHand, float charge, boolean underwater,
				long startGameTime, int lifetimeTicks) {
			this.attackerId = attackerId;
			this.attackId = attackId;
			this.eventIndex = eventIndex;
			this.origin = origin;
			this.endpoint = endpoint;
			this.type = type;
			this.variant = variant;
			this.leftHand = leftHand;
			this.charge = charge;
			this.underwater = underwater;
			this.startGameTime = startGameTime;
			this.lifetimeTicks = lifetimeTicks;
		}

		@Override
		public long startGameTime() {
			return startGameTime;
		}

		@Override
		public int lifetimeTicks() {
			return lifetimeTicks;
		}
	}

	public static final class ActiveMissile implements TimedEffect {
		public final UUID attackerId;
		public final long attackId;
		public final int missileIndex;
		public final Vec3 origin;
		public final Vec3 endpoint;
		public final int targetEntityId;
		public final long startGameTime;
		public final int lifetimeTicks;

		private ActiveMissile(UUID attackerId, long attackId, int missileIndex,
				Vec3 origin, Vec3 endpoint, int targetEntityId,
				long startGameTime, int lifetimeTicks) {
			this.attackerId = attackerId;
			this.attackId = attackId;
			this.missileIndex = missileIndex;
			this.origin = origin;
			this.endpoint = endpoint;
			this.targetEntityId = targetEntityId;
			this.startGameTime = startGameTime;
			this.lifetimeTicks = lifetimeTicks;
		}

		@Override
		public long startGameTime() {
			return startGameTime;
		}

		@Override
		public int lifetimeTicks() {
			return lifetimeTicks;
		}
	}

	public static final class ActiveImpact implements TimedEffect {
		public final long attackId;
		public final Vec3 center;
		public final Vec3 normal;
		public final ArmorAttackType type;
		public final ArmorVariant variant;
		public final ArmorAttackHitKind hitKind;
		public final float strength;
		public final boolean underwater;
		public final long startGameTime;
		public final int lifetimeTicks;

		private ActiveImpact(long attackId, Vec3 center, Vec3 normal, ArmorAttackType type,
				ArmorVariant variant, ArmorAttackHitKind hitKind, float strength, boolean underwater,
				long startGameTime, int lifetimeTicks) {
			this.attackId = attackId;
			this.center = center;
			this.normal = normal;
			this.type = type;
			this.variant = variant;
			this.hitKind = hitKind;
			this.strength = strength;
			this.underwater = underwater;
			this.startGameTime = startGameTime;
			this.lifetimeTicks = lifetimeTicks;
		}

		@Override
		public long startGameTime() {
			return startGameTime;
		}

		@Override
		public int lifetimeTicks() {
			return lifetimeTicks;
		}
	}

	public static final class ActiveShockwave implements TimedEffect {
		public final long attackId;
		public final ArmorAttackType type;
		public final Vec3 center;
		public final Vec3 normal;
		public final Vec3 travelDirection;
		public final ArmorVariant variant;
		public final float strength;
		public final boolean underwater;
		public final long startGameTime;
		public final int lifetimeTicks;

		private ActiveShockwave(long attackId, ArmorAttackType type,
				Vec3 center, Vec3 normal, Vec3 travelDirection,
				ArmorVariant variant, float strength, boolean underwater,
				long startGameTime, int lifetimeTicks) {
			this.attackId = attackId;
			this.type = type;
			this.center = center;
			this.normal = normal;
			this.travelDirection = travelDirection;
			this.variant = variant;
			this.strength = strength;
			this.underwater = underwater;
			this.startGameTime = startGameTime;
			this.lifetimeTicks = lifetimeTicks;
		}

		@Override
		public long startGameTime() {
			return startGameTime;
		}

		@Override
		public int lifetimeTicks() {
			return lifetimeTicks;
		}
	}
}
