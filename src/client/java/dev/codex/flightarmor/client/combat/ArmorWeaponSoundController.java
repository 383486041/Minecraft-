package dev.codex.flightarmor.client.combat;

import dev.codex.flightarmor.client.audio.ArmorLoopSound;
import dev.codex.flightarmor.client.settings.FlightArmorClientSettingsStore;
import dev.codex.flightarmor.combat.ArmorAttackHitKind;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.sound.ModSoundEvents;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 武器声音统一出口：腕炮短音、核心蓄力循环/释放与俯冲冲击。 */
final class ArmorWeaponSoundController {
	private static final Map<UUID, ChargeRig> CHARGE_RIGS = new HashMap<>();
	private static final Map<UUID, BladeRig> BLADE_RIGS = new HashMap<>();
	private static final Map<UUID, Long> RECENT_CORE_RELEASES = new HashMap<>();

	private ArmorWeaponSoundController() {
	}

	static void tick(Minecraft client) {
		if (client.level == null) {
			clear();
			return;
		}
		long now = client.level.getGameTime();
		Set<UUID> loaded = new HashSet<>();
		for (Player player : client.level.players()) {
			loaded.add(player.getUUID());
			/*
			 * 蓄力循环音对「核心炮」「腕炮蓄力」「手部光束灭世档」共用同一套 ChargeRig：
			 * 玩家听到的是一条随蓄力进度升高的循环，而不是三套各自为政的音效。
			 * 松手/被取消由 RECENT_CORE_RELEASES 区分——灭世档释放同样走 playCoreRelease。
			 */
			ArmorAttackType chargingType = ArmorCombatClient.attackType(player);
			boolean charging = (chargingType == ArmorAttackType.CORE_BEAM
					|| chargingType == ArmorAttackType.WRIST_GRAVITY_PULSE
					|| chargingType == ArmorAttackType.HAND_BEAM_LANCE)
					&& ArmorCombatClient.phase(player) == ArmorCombatPhase.CHARGING;
			ChargeRig rig = CHARGE_RIGS.get(player.getUUID());
			if (charging) {
				if (rig == null) {
					rig = new ChargeRig(player,
							client.player != null && client.player.getUUID().equals(player.getUUID()));
					CHARGE_RIGS.put(player.getUUID(), rig);
				}
				rig.tickCharging(client, ArmorCombatClient.phaseProgress(player, 1.0F));
			} else if (rig != null) {
				boolean released = RECENT_CORE_RELEASES.getOrDefault(player.getUUID(), Long.MIN_VALUE) >= now;
				rig.tickRelease(client, released);
			}
			float bladeProgress = ArmorCombatClient.bladeProgress(player, 1.0F);
			BladeRig bladeRig = BLADE_RIGS.get(player.getUUID());
			if (bladeProgress > 0.01F && bladeRig == null) {
				bladeRig = new BladeRig(player,
						client.player != null && client.player.getUUID().equals(player.getUUID()));
				BLADE_RIGS.put(player.getUUID(), bladeRig);
			}
			if (bladeRig != null) bladeRig.tick(client, bladeProgress);
		}

		Iterator<Map.Entry<UUID, ChargeRig>> iterator = CHARGE_RIGS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ChargeRig> entry = iterator.next();
			if (!loaded.contains(entry.getKey())) entry.getValue().stopImmediately();
			if (!loaded.contains(entry.getKey()) || entry.getValue().finished()) iterator.remove();
		}
		Iterator<Map.Entry<UUID, BladeRig>> bladeIterator = BLADE_RIGS.entrySet().iterator();
		while (bladeIterator.hasNext()) {
			Map.Entry<UUID, BladeRig> entry = bladeIterator.next();
			if (!loaded.contains(entry.getKey())) entry.getValue().stopImmediately();
			if (!loaded.contains(entry.getKey()) || entry.getValue().finished()) bladeIterator.remove();
		}
		RECENT_CORE_RELEASES.entrySet().removeIf(entry -> entry.getValue() < now);
	}

	static void clear() {
		for (ChargeRig rig : CHARGE_RIGS.values()) rig.stopImmediately();
		for (BladeRig rig : BLADE_RIGS.values()) rig.stopImmediately();
		CHARGE_RIGS.clear();
		BLADE_RIGS.clear();
		RECENT_CORE_RELEASES.clear();
	}

	static void playPulse(Minecraft client, UUID attackerId, Vec3 origin,
			Vec3 endpoint, ArmorAttackHitKind hitKind, boolean underwater) {
		playPulse(client, attackerId, origin, endpoint, hitKind, underwater, ArmorVariant.DEFAULT);
	}

	static void playPulse(Minecraft client, UUID attackerId, Vec3 origin,
			Vec3 endpoint, ArmorAttackHitKind hitKind, boolean underwater, ArmorVariant variant) {
		playPulse(client, attackerId, origin, endpoint, hitKind, underwater, variant, 0.0F);
	}

	static void playPulse(Minecraft client, UUID attackerId, Vec3 origin,
			Vec3 endpoint, ArmorAttackHitKind hitKind, boolean underwater, ArmorVariant variant,
			float charge) {
		if (client.level == null) return;
		RECENT_CORE_RELEASES.put(attackerId, client.level.getGameTime() + 5L);
		SoundSignature signature = SoundSignature.forVariant(variant);
		boolean localAttack = client.player != null && client.player.getUUID().equals(attackerId);
		SoundEvent fireSound = localAttack
				? ModSoundEvents.WEAPON_WRIST_PULSE_FIRE_INT
				: ModSoundEvents.WEAPON_WRIST_PULSE_FIRE_EXT;
		float strength = Mth.clamp(charge, 0.0F, 1.0F);
		playAt(client, origin, fireSound,
				((localAttack ? 0.78F : 1.05F) + strength * 0.18F) * signature.pulseVolume,
				(underwater ? 0.82F : 0.96F + strength * 0.08F) * signature.pulsePitch);
		if (hitKind != ArmorAttackHitKind.MISS) {
			playAt(client, endpoint, ModSoundEvents.WEAPON_WRIST_PULSE_IMPACT,
					((underwater ? 0.70F : 0.82F) + strength * 0.28F) * signature.impactVolume,
					(underwater ? 0.78F : 0.92F + strength * 0.06F) * signature.impactPitch);
		}
	}

	/** 疾影连锁只播放逐点命中，不重复播放腕部开火瞬态。 */
	static void playChainImpact(Minecraft client, Vec3 endpoint,
			boolean underwater, ArmorVariant variant) {
		if (client.level == null) return;
		SoundSignature signature = SoundSignature.forVariant(variant);
		playAt(client, endpoint, ModSoundEvents.WEAPON_WRIST_PULSE_IMPACT,
				(underwater ? 0.38F : 0.48F) * signature.impactVolume,
				(underwater ? 0.86F : 1.08F) * signature.impactPitch);
	}

	static void playCoreRelease(Minecraft client, UUID attackerId, Vec3 origin,
			Vec3 endpoint, ArmorAttackHitKind hitKind, boolean underwater, float charge) {
		playCoreRelease(client, attackerId, origin, endpoint, hitKind, underwater, charge,
				ArmorVariant.DEFAULT);
	}

	static void playCoreRelease(Minecraft client, UUID attackerId, Vec3 origin,
			Vec3 endpoint, ArmorAttackHitKind hitKind, boolean underwater, float charge,
			ArmorVariant variant) {
		if (client.level == null) return;
		SoundSignature signature = SoundSignature.forVariant(variant);
		boolean localAttack = client.player != null && client.player.getUUID().equals(attackerId);
		RECENT_CORE_RELEASES.put(attackerId, client.level.getGameTime() + 5L);
		SoundEvent release = localAttack
				? ModSoundEvents.WEAPON_CORE_RELEASE_INT : ModSoundEvents.WEAPON_CORE_RELEASE_EXT;
		float strength = Mth.clamp(charge, 0.0F, 1.0F);
		playAt(client, origin, release,
				((localAttack ? 0.86F : 1.12F) + strength * 0.18F) * signature.coreVolume,
				(underwater ? 0.78F : 0.94F + strength * 0.04F) * signature.corePitch);
		if (hitKind != ArmorAttackHitKind.MISS) {
			playAt(client, endpoint, ModSoundEvents.WEAPON_CORE_IMPACT,
					(0.92F + strength * 0.26F) * signature.impactVolume,
					(underwater ? 0.76F : 0.91F + strength * 0.03F) * signature.impactPitch);
		}
	}

	static void playGravityImpact(Minecraft client, UUID attackerId, Vec3 impact,
			boolean underwater, float strength) {
		playGravityImpact(client, attackerId, impact, underwater, strength, ArmorVariant.DEFAULT);
	}

	static void playGravityImpact(Minecraft client, UUID attackerId, Vec3 impact,
			boolean underwater, float strength, ArmorVariant variant) {
		if (client.level == null) return;
		SoundSignature signature = SoundSignature.forVariant(variant);
		boolean localAttack = client.player != null && client.player.getUUID().equals(attackerId);
		float power = Mth.clamp(strength, 0.35F, 1.0F);
		playAt(client, impact, ModSoundEvents.WEAPON_GRAVITY_IMPACT_EXT,
				(0.95F + power * 0.38F) * signature.diveVolume,
				(underwater ? 0.74F : 0.90F + power * 0.025F) * signature.divePitch);
		if (localAttack) {
			playAt(client, impact, ModSoundEvents.WEAPON_GRAVITY_IMPACT_INT,
					(0.72F + power * 0.18F) * signature.diveVolume,
					(underwater ? 0.72F : 0.96F) * signature.divePitch);
		}
	}

	/** Swift missile pods reuse the short mechanical weapon transient; no synthetic hover tone is added. */
	static void playMissileLaunch(Minecraft client, Vec3 origin) {
		if (client.level == null) return;
		playAt(client, origin, ModSoundEvents.WEAPON_WRIST_PULSE_FIRE_EXT, 0.82F, 1.24F);
	}

	static void playMissileImpact(Minecraft client, Vec3 endpoint) {
		if (client.level == null) return;
		playAt(client, endpoint, ModSoundEvents.WEAPON_WRIST_PULSE_IMPACT, 0.72F, 1.16F);
	}

	static void playBladeSlash(Minecraft client, UUID attackerId, Vec3 origin,
			ArmorVariant variant, boolean leftHand) {
		if (client.level == null) return;
		boolean localAttack = client.player != null && client.player.getUUID().equals(attackerId);
		SoundSignature signature = SoundSignature.forVariant(variant);
		float pitch = switch (variant) {
			case VANGUARD -> 1.04F;
			case SWIFT -> leftHand ? 1.35F : 1.28F;
			case SENTINEL -> 0.78F;
		};
		playAt(client, origin, localAttack
						? ModSoundEvents.WEAPON_BLADE_SLASH_INT
						: ModSoundEvents.WEAPON_BLADE_SLASH_EXT,
				(localAttack ? 0.56F : 0.78F) * signature.pulseVolume, pitch);
	}

	static void playBladeToggle(Minecraft client, UUID attackerId,
			boolean extended, ArmorVariant variant) {
		if (client.level == null) return;
		Player attacker = client.level.getPlayerByUUID(attackerId);
		if (attacker == null) return;
		boolean local = client.player != null && client.player.getUUID().equals(attackerId);
		SoundSignature signature = SoundSignature.forVariant(variant);
		playAt(client, attacker.position().add(0.0D, attacker.getBbHeight() * 0.58D, 0.0D),
				extended
						? local ? ModSoundEvents.WEAPON_BLADE_DEPLOY_INT
								: ModSoundEvents.WEAPON_BLADE_DEPLOY_EXT
						: local ? ModSoundEvents.WEAPON_BLADE_RETRACT_INT
								: ModSoundEvents.WEAPON_BLADE_RETRACT_EXT,
				extended ? 0.72F : 0.62F,
				(extended ? 1.12F : 0.96F) * signature.pulsePitch);
	}
	private static void playAt(Minecraft client, Vec3 position, SoundEvent sound,
			float volume, float pitch) {
		if (!isFinite(position) || client.level == null) return;
		client.level.playLocalSound(position.x, position.y, position.z, sound,
				SoundSource.PLAYERS, volume * FlightArmorClientSettingsStore.masterVolume(), pitch, false);
	}

	private static boolean isFinite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private static ArmorVariant variantOf(Player player) {
		return ModItems.fullSetVariant(player)
				.orElseGet(() -> ModItems.bootsVariant(player).orElse(ArmorVariant.DEFAULT));
	}

	private static final class BladeRig {
		private final Player player;
		private final boolean local;
		private ArmorLoopSound loop;

		private BladeRig(Player player, boolean local) {
			this.player = player;
			this.local = local;
		}

		private void tick(Minecraft client, float progress) {
			float extension = Mth.clamp(progress, 0.0F, 1.0F);
			if (extension > 0.01F && (loop == null || loop.isStopped())) {
				loop = new ArmorLoopSound(player,
						local ? ModSoundEvents.WEAPON_BLADE_HUM_INT
								: ModSoundEvents.WEAPON_BLADE_HUM_EXT, local);
				client.getSoundManager().play(loop);
			}
			if (loop == null) return;
			if (extension <= 0.01F) {
				loop.fadeOut();
				return;
			}
			SoundSignature signature = SoundSignature.forVariant(variantOf(player));
			loop.setTarget(((local ? 0.12F : 0.17F) + extension * (local ? 0.10F : 0.14F))
							* signature.pulseVolume
							* FlightArmorClientSettingsStore.masterVolume(),
					(0.91F + extension * 0.08F) * signature.pulsePitch);
		}

		private boolean finished() {
			return loop != null && loop.isStopped();
		}

		private void stopImmediately() {
			if (loop != null) loop.stopImmediately();
		}
	}

	private static final class ChargeRig {
		private final Player player;
		private final boolean local;
		private ArmorLoopSound loop;
		private boolean cancelPlayed;

		private ChargeRig(Player player, boolean local) {
			this.player = player;
			this.local = local;
		}

		private void tickCharging(Minecraft client, float progress) {
			cancelPlayed = false;
			if (loop == null || loop.isStopped()) {
				loop = new ArmorLoopSound(player,
						local ? ModSoundEvents.WEAPON_CORE_CHARGE_INT_LOOP
								: ModSoundEvents.WEAPON_CORE_CHARGE_EXT_LOOP, local);
				client.getSoundManager().play(loop);
			}
			float charge = Mth.clamp(progress, 0.0F, 1.0F);
			SoundSignature signature = SoundSignature.forVariant(variantOf(player));
			loop.setTarget(((local ? 0.34F : 0.45F) + charge * (local ? 0.13F : 0.17F))
						* signature.coreVolume * FlightArmorClientSettingsStore.masterVolume(),
					(0.93F + charge * 0.06F) * signature.corePitch);
		}

		private void tickRelease(Minecraft client, boolean released) {
			if (loop != null) loop.fadeOut();
			if (!released && !cancelPlayed && client.level != null) {
				SoundSignature signature = SoundSignature.forVariant(variantOf(player));
				client.level.playLocalSound(player.getX(), player.getY() + player.getBbHeight() * 0.60D,
						player.getZ(), ModSoundEvents.WEAPON_CORE_CANCEL, SoundSource.PLAYERS,
						(local ? 0.48F : 0.62F) * signature.coreVolume
								* FlightArmorClientSettingsStore.masterVolume(),
						0.94F * signature.corePitch, false);
				cancelPlayed = true;
			}
		}

		private boolean finished() {
			return loop == null || loop.isStopped();
		}

		private void stopImmediately() {
			if (loop != null) loop.stopImmediately();
		}
	}

	private record SoundSignature(float pulseVolume, float pulsePitch,
			float impactVolume, float impactPitch, float coreVolume, float corePitch,
			float diveVolume, float divePitch) {
		private static SoundSignature forVariant(ArmorVariant variant) {
			return switch (variant == null ? ArmorVariant.DEFAULT : variant) {
				case VANGUARD -> new SoundSignature(1.10F, 0.90F, 1.12F, 0.86F,
						1.12F, 0.84F, 1.10F, 0.86F);
				case SWIFT -> new SoundSignature(0.78F, 1.24F, 0.82F, 1.28F,
						0.84F, 1.14F, 0.80F, 1.18F);
				case SENTINEL -> new SoundSignature(1.22F, 0.70F, 1.28F, 0.66F,
						1.26F, 0.68F, 1.34F, 0.64F);
			};
		}
	}
}
