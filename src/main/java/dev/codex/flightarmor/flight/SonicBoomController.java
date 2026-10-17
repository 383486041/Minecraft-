package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.combat.ImpactBlastController;
import dev.codex.flightarmor.network.SonicBoomFxPayload;
import dev.codex.flightarmor.sound.ArmorExplosionEffects;
import dev.codex.flightarmor.sound.ModSoundEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Server-authoritative native explosion produced by an actual Mach-1 crossing. */
final class SonicBoomController {
	private static final float EXPLOSION_POWER = 2.35F;
	private static final Map<UUID, SonicBoomLatch> LATCHES = new ConcurrentHashMap<>();
	private static final AtomicLong NEXT_EVENT_ID = new AtomicLong();

	private SonicBoomController() {
	}

	static void sample(ServerPlayer player, double realizedBlocksPerTick) {
		if (player == null) return;
		SonicBoomLatch latch = LATCHES.computeIfAbsent(
				player.getUUID(), ignored -> new SonicBoomLatch());
		if (player.isInWater()) {
			latch.reset();
			return;
		}
		// flightStep.totalSpeed() is the authoritative realized displacement speed.
		// With 1 block = 2 metres, Mach 1 is 150m/s = 3.75 blocks/tick.
		double mach = FlightPhysics.machForBlocksPerTick(realizedBlocksPerTick, false);
		if (!latch.sample(mach)) return;

		Vec3 direction = player.getDeltaMovement();
		if (direction.lengthSqr() < 1.0E-8D) direction = player.getLookAngle();
		if (direction.lengthSqr() < 1.0E-8D) direction = new Vec3(0.0D, 0.0D, 1.0D);
		direction = direction.normalize();
		Vec3 center = player.position().add(0.0D, player.getBbHeight() * 0.52D, 0.0D)
				.subtract(direction.scale(1.15D));

		boolean alreadyInvulnerable = player.isInvulnerable();
		if (!alreadyInvulnerable) player.setPermanentlyInvulnerable(true);
		try {
			ArmorExplosionEffects.explodeWithSonicReport(player.level(), player,
					center, EXPLOSION_POWER, false, Level.ExplosionInteraction.NONE);
			// 原生爆炸保持 NONE（不做无差别炸地形）；掠过破土另走一套带完整护栏、按马赫给预算的破坏。
			ImpactBlastController.shockwave(player, center, mach);
		} finally {
			if (!alreadyInvulnerable) player.setPermanentlyInvulnerable(false);
		}
		broadcastVisualEvent(player, center, direction, (float) mach);
		// The native explosion owns the external boom. This extra cue is listener-only
		// armor conduction, not a second spatial explosion broadcast.
		player.playSound(ModSoundEvents.FLIGHT_SONIC_INTERNAL, 0.66F, 0.96F);
	}

	private static void broadcastVisualEvent(ServerPlayer player, Vec3 center,
			Vec3 direction, float mach) {
		SonicBoomFxPayload payload = new SonicBoomFxPayload(
				player.getUUID(),
				player.level().dimension().identifier(),
				NEXT_EVENT_ID.incrementAndGet(),
				center,
				direction,
				mach);
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(player));
		recipients.add(player);
		recipients.addAll(PlayerLookup.around(player.level(), center,
				SonicBoomVisualProfile.MAX_RENDER_DISTANCE_BLOCKS));
		for (ServerPlayer recipient : recipients) {
			if (ServerPlayNetworking.canSend(recipient, SonicBoomFxPayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}

	static void remove(ServerPlayer player) {
		if (player != null) LATCHES.remove(player.getUUID());
	}
}
