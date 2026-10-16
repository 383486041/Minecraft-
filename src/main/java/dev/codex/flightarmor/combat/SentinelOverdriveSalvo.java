package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Authoritative charged weapon for a stable Sentinel overdrive suit.
 *
 * <p>The client still submits the ordinary G-key {@link ArmorAttackType#CORE_BEAM}
 * press/release intent. This resolver derives charge, wing ownership, origins,
 * targets, block destruction and native explosions exclusively from server
 * state. A completely docked wing group contributes the two armor-mounted
 * cannon lanes. Deployed or transitioning wingmen leave G with the chest lane
 * only, so detached hardware can never fire twice. Every destructive corridor
 * inherits
 * {@link DestructiveLaserController}'s hardness/count budget and protection
 * hooks, while the native explosions use entity-only interaction so they cannot
 * bypass claim protection around the impact.</p>
 */
final class SentinelOverdriveSalvo {
	static final float CATACLYSM_CHARGE_THRESHOLD = 0.96F;
	static final float MINIMUM_ENERGY_COST = 12.0F;
	static final float MAXIMUM_ENERGY_COST = 64.0F;
	static final float MAXIMUM_HEAT = 68.0F;
	static final int MAXIMUM_COOLDOWN_TICKS = 100;
	static final float MAXIMUM_CHEST_EXPLOSION_POWER = 8.0F;
	static final float MAXIMUM_WING_EXPLOSION_POWER = 6.25F;
	private static final double MAXIMUM_VISUAL_RANGE = 92.0D;
	private static final double ENTITY_RAY_PADDING = 1.25D;

	private SentinelOverdriveSalvo() {
	}

	/**
	 * Resolves a release after {@link ArmorCombatGate} accepted the existing
	 * CORE_BEAM protocol. Empty means the exact suit/form authority changed
	 * between the gate and the commit frame.
	 */
	static Optional<Result> fire(ServerPlayer attacker, ArmorAnchorPose pose,
			float requestedCharge, double baseRange, float baseDamage,
			double baseKnockback, FireLayout expectedLayout) {
		if (attacker == null || pose == null
				|| !Float.isFinite(requestedCharge)
				|| !Double.isFinite(baseRange) || baseRange <= 0.0D
				|| !Float.isFinite(baseDamage) || baseDamage <= 0.0F
				|| !Double.isFinite(baseKnockback) || baseKnockback < 0.0D
				|| expectedLayout == null) {
			return Optional.empty();
		}
		long now = attacker.serverLevel().getGameTime();
		Optional<FireLayout> authorizedLayout = authorizedLayout(attacker, now);
		if (authorizedLayout.isEmpty() || authorizedLayout.get() != expectedLayout) {
			return Optional.empty();
		}
		FireLayout layout = authorizedLayout.get();

		Profile profile = profile(requestedCharge);
		Vec3 forward = normalized(attacker.getLookAngle(), new Vec3(0.0D, 0.0D, 1.0D));
		Vec3 right = horizontalRight(attacker, forward);
		double range = Math.min(MAXIMUM_VISUAL_RANGE,
				Math.max(1.0D, baseRange * profile.rangeMultiplier()));
		Vec3 chestOrigin = ArmorAnchorResolver.resolve(attacker, ArmorVariant.SENTINEL,
				pose, ArmorSemanticAnchor.CHEST_CORE, forward);
		Vec3 aimCenter = chestOrigin.add(forward.scale(range));

		Shot chest = resolveLane(attacker, 0, false, chestOrigin, aimCenter,
				profile.terrainCharge(), baseDamage * profile.chestDamageMultiplier(),
				baseKnockback * profile.chestKnockbackMultiplier(),
				profile.chestExplosionPower());
		if (layout == FireLayout.CHEST_ONLY) {
			return Optional.of(new Result(profile, layout, List.of(chest)));
		}

		Vec3 leftOrigin = ArmorAnchorResolver.resolve(attacker, ArmorVariant.SENTINEL,
				pose, ArmorSemanticAnchor.wingCannon(true), forward);
		Vec3 rightOrigin = ArmorAnchorResolver.resolve(attacker, ArmorVariant.SENTINEL,
				pose, ArmorSemanticAnchor.wingCannon(false), forward);
		Shot left = resolveLane(attacker, 1, true, leftOrigin,
				aimCenter.add(right.scale(-profile.wingSpread())),
				profile.terrainCharge(), baseDamage * profile.wingDamageMultiplier(),
				baseKnockback * profile.wingKnockbackMultiplier(),
				profile.wingExplosionPower());
		Shot rightShot = resolveLane(attacker, 2, false, rightOrigin,
				aimCenter.add(right.scale(profile.wingSpread())),
				profile.terrainCharge(), baseDamage * profile.wingDamageMultiplier(),
				baseKnockback * profile.wingKnockbackMultiplier(),
				profile.wingExplosionPower());
		return Optional.of(new Result(profile, layout, List.of(chest, left, rightShot)));
	}

	/**
	 * Resolves the exact server-owned hardware layout for this release frame.
	 * Missing/mixed identity and an unstable armor form reject the attack. Wing
	 * hand-off itself fails closed to the chest lane rather than materializing
	 * either detached cannon on the player.
	 */
	static Optional<FireLayout> authorizedLayout(ServerPlayer attacker, long gameTime) {
		if (attacker == null) return Optional.empty();
		ArmorConfigurationAccess.ConfiguredSentinel configured =
				ArmorConfigurationAccess.configuredSentinel(attacker).orElse(null);
		if (configured == null
				|| !configured.ownerId().equals(attacker.getUUID())
				|| configured.snapshot().mode() != SentinelArmorMode.OVERDRIVE_FLIGHT
				|| ArmorConfigurationAccess.isTransitioning(configured.snapshot(), gameTime)
				|| !ArmorConfigurationAccess.allowsRangedCombat(attacker, gameTime)) {
			return Optional.empty();
		}
		return Optional.of(layoutFor(configured.snapshot(), gameTime));
	}

	/**
	 * Only a stable, completely docked configuration owns both wearable wing
	 * cannons. The target deployment flag alone is deliberately insufficient:
	 * during recall it flips to docked before the physical hand-off finishes.
	 */
	static FireLayout layoutFor(ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		if (snapshot == null || snapshot.wingmenDeployed()
				|| ArmorConfigurationAccess.isWingmenTransitioning(snapshot, gameTime)) {
			return FireLayout.CHEST_ONLY;
		}
		return FireLayout.DOCKED_TRIPLE;
	}

	/**
	 * Converts the requested charge to the highest server-payable charge. A
	 * negative result means even the minimum Sentinel discharge cannot be
	 * paid. The discontinuity at cataclysm charge is intentional: a player with
	 * only partial reserves receives the strongest affordable ordinary salvo
	 * rather than a free full blast.
	 */
	static float affordableCharge(float requestedCharge, float availableEnergy) {
		if (!Float.isFinite(requestedCharge) || !Float.isFinite(availableEnergy)
				|| availableEnergy + 0.0001F < MINIMUM_ENERGY_COST) {
			return -1.0F;
		}
		float desired = unit(requestedCharge);
		if (profile(desired).energyCost() <= availableEnergy + 0.0001F) return desired;
		float low = 0.0F;
		float high = desired;
		for (int iteration = 0; iteration < 16; iteration++) {
			float middle = (low + high) * 0.5F;
			if (profile(middle).energyCost() <= availableEnergy + 0.0001F) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return low;
	}

	static Profile profile(float requestedCharge) {
		float charge = unit(requestedCharge);
		boolean cataclysm = charge >= CATACLYSM_CHARGE_THRESHOLD;
		if (cataclysm) {
			return new Profile(charge, true, 1.25D, 3.65D,
					MAXIMUM_ENERGY_COST, MAXIMUM_HEAT, MAXIMUM_COOLDOWN_TICKS, 16,
					2.80F, 1.80F, 1.65D, 1.28D,
					MAXIMUM_CHEST_EXPLOSION_POWER, MAXIMUM_WING_EXPLOSION_POWER,
					1.0F);
		}
		float destructiveProgress = thresholdProgress(charge, 0.70F);
		return new Profile(charge, false,
				Mth.lerp(charge, 1.03D, 1.22D),
				Mth.lerp(charge, 1.35D, 2.65D),
				Mth.lerp(charge, MINIMUM_ENERGY_COST, 48.0F),
				Mth.lerp(charge, 13.0F, 44.0F),
				Mth.floor(Mth.lerp(charge, 42.0F, 72.0F)),
				Mth.floor(Mth.lerp(charge, 8.0F, 12.0F)),
				Mth.lerp(charge, 1.18F, 1.92F),
				Mth.lerp(charge, 0.58F, 1.12F),
				Mth.lerp(charge, 1.05D, 1.42D),
				Mth.lerp(charge, 0.72D, 1.08D),
				Mth.lerp(charge, 3.55F, 5.45F),
				destructiveProgress <= 0.0F
						? 0.0F : Mth.lerp(destructiveProgress, 2.15F, 4.35F),
				charge);
	}

	private static Shot resolveLane(ServerPlayer attacker, int eventIndex,
			boolean leftWing, Vec3 origin, Vec3 requestedEnd, float terrainCharge,
			float directDamage, double knockback, float explosionPower) {
		CombatRaycast.Result hit = castLane(attacker, origin, requestedEnd, terrainCharge);
		Entity target = hit.entity();
		boolean damageableTarget = target != null && CombatTargetPolicy.canDamage(attacker, target);
		boolean validImpact = hit.hitKind() == ArmorAttackHitKind.BLOCK
				|| hit.hitKind() == ArmorAttackHitKind.ENTITY && damageableTarget;
		if (validImpact && explosionPower > 0.0F) {
			ArmorVariantAttackResolver.applySentinelOverdriveExplosion(
					attacker, impactCenter(hit), explosionPower);
		} else if (damageableTarget && target.hurt(ModDamageTypes.coreBeam(attacker), directDamage)
				&& target instanceof LivingEntity living) {
			living.knockback(knockback,
					attacker.getX() - living.getX(), attacker.getZ() - living.getZ());
			living.hurtMarked = true;
		}
		return new Shot(eventIndex, leftWing, hit.origin(), hit.endpoint(),
				hit.impactNormal(), hit.hitKind(),
				target == null ? -1 : target.getId());
	}

	private static CombatRaycast.Result castLane(ServerPlayer attacker, Vec3 origin,
			Vec3 requestedEnd, float terrainCharge) {
		Vec3 delta = requestedEnd.subtract(origin);
		if (!finite(origin) || !finite(requestedEnd) || delta.lengthSqr() < 1.0E-8D) {
			return new CombatRaycast.Result(origin, origin, Vec3.ZERO,
					ArmorAttackHitKind.MISS, null);
		}
		double maximumDistanceSq = origin.distanceToSqr(requestedEnd);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				attacker, origin, requestedEnd,
				new AABB(origin, requestedEnd).inflate(ENTITY_RAY_PADDING),
				candidate -> CombatTargetPolicy.canOcclude(attacker, candidate),
				maximumDistanceSq);
		Vec3 terrainEnd = entityHit == null ? requestedEnd : entityHit.getLocation();
		DestructiveLaserController.Result terrain =
				DestructiveLaserController.resolveCoreBeam(
						attacker, origin, terrainEnd, terrainCharge);
		boolean reachedEntity = entityHit != null
				&& origin.distanceToSqr(terrain.endpoint()) + 1.0E-5D
						>= origin.distanceToSqr(terrainEnd);
		if (reachedEntity) {
			Entity target = entityHit.getEntity();
			Vec3 hitDirection = terrainEnd.subtract(origin);
			Vec3 normal = hitDirection.lengthSqr() > 1.0E-8D
					? hitDirection.normalize().scale(-1.0D) : Vec3.ZERO;
			return new CombatRaycast.Result(origin, terrainEnd, normal,
					ArmorAttackHitKind.ENTITY, target);
		}
		return new CombatRaycast.Result(origin, terrain.endpoint(), terrain.impactNormal(),
				terrain.hitKind(), null);
	}

	private static Vec3 impactCenter(CombatRaycast.Result hit) {
		Vec3 normal = hit.impactNormal();
		return normal.lengthSqr() < 1.0E-8D
				? hit.endpoint() : hit.endpoint().add(normal.normalize().scale(0.08D));
	}

	private static Vec3 horizontalRight(ServerPlayer attacker, Vec3 forward) {
		Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
		if (right.lengthSqr() > 1.0E-8D) return right.normalize();
		double yaw = Math.toRadians(attacker.getYRot());
		return new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw));
	}

	private static Vec3 normalized(Vec3 value, Vec3 fallback) {
		return finite(value) && value.lengthSqr() > 1.0E-8D
				? value.normalize() : fallback;
	}

	private static float thresholdProgress(float value, float threshold) {
		return Mth.clamp((value - threshold) / Math.max(0.0001F, 1.0F - threshold),
				0.0F, 1.0F);
	}

	private static float unit(float value) {
		return Float.isFinite(value) ? Mth.clamp(value, 0.0F, 1.0F) : 0.0F;
	}

	private static boolean finite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	record Profile(
			float charge,
			boolean cataclysm,
			double rangeMultiplier,
			double wingSpread,
			float energyCost,
			float heat,
			int cooldownTicks,
			int firingTicks,
			float chestDamageMultiplier,
			float wingDamageMultiplier,
			double chestKnockbackMultiplier,
			double wingKnockbackMultiplier,
			float chestExplosionPower,
			float wingExplosionPower,
			float terrainCharge
	) {
	}

	record Shot(
			int eventIndex,
			boolean leftWing,
			Vec3 origin,
			Vec3 endpoint,
			Vec3 impactNormal,
			ArmorAttackHitKind hitKind,
			int hitEntityId
	) {
	}

	enum FireLayout {
		DOCKED_TRIPLE,
		CHEST_ONLY
	}

	record Result(Profile profile, FireLayout layout, List<Shot> shots) {
		Result {
			if (profile == null || layout == null || shots == null) {
				throw new IllegalArgumentException("Sentinel overdrive salvo result is incomplete");
			}
			shots = List.copyOf(shots);
			int expectedLanes = layout == FireLayout.DOCKED_TRIPLE ? 3 : 1;
			if (shots.size() != expectedLanes) {
				throw new IllegalArgumentException(
						"Sentinel overdrive salvo lane count does not match wing ownership");
			}
			for (int index = 0; index < shots.size(); index++) {
				if (shots.get(index) == null || shots.get(index).eventIndex() != index) {
					throw new IllegalArgumentException(
							"Sentinel overdrive salvo event indexes must be contiguous from chest lane 0");
				}
			}
		}
	}
}
