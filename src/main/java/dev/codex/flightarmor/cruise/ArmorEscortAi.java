package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.combat.CombatTargetPolicy;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Stateless targeting and pursuit calculations for an escort suit.
 *
 * <p>The command anchor is deliberately separate from the carrier position. This keeps a suit
 * from chaining targets farther and farther away from its owner or hold point. The caller owns
 * the persistent policy and stance, while this class only evaluates the current server world.
 */
final class ArmorEscortAi {
	private static final double MIN_RANGE = 4.0D;
	private static final double MAX_RANGE = 96.0D;
	private static final double RETURN_MARGIN = 1.5D;

	private ArmorEscortAi() {
	}

	static LivingEntity selectTarget(ServerPlayer owner, ArmorStand carrier, Vec3 commandAnchor,
			double configuredRange, boolean includeNonHostile, ArmorVariant requestedVariant) {
		if (owner == null || carrier == null || commandAnchor == null
				|| carrier.level() != owner.level()) return null;
		ArmorVariant variant = requestedVariant == null ? ArmorVariant.DEFAULT : requestedVariant;
		double range = sanitizeRange(configuredRange);
		AABB area = new AABB(commandAnchor, commandAnchor).inflate(range);
		double rangeSqr = range * range;
		return owner.level().getEntitiesOfClass(LivingEntity.class, area, candidate ->
				isEligible(owner, carrier, candidate, commandAnchor, rangeSqr, includeNonHostile))
				.stream()
				.min(Comparator.<LivingEntity>comparingDouble(candidate ->
								tacticalScore(owner, carrier, candidate, commandAnchor, variant))
						.thenComparing(candidate -> candidate.getUUID().toString()))
				.orElse(null);
	}

	static List<LivingEntity> selectVolleyTargets(ServerPlayer owner, ArmorStand carrier,
			Vec3 commandAnchor, double configuredRange, boolean includeNonHostile,
			ArmorVariant requestedVariant, LivingEntity primaryTarget, int maximumTargets) {
		if (owner == null || carrier == null || commandAnchor == null || maximumTargets <= 0
				|| carrier.level() != owner.level()) return List.of();
		ArmorVariant variant = requestedVariant == null ? ArmorVariant.DEFAULT : requestedVariant;
		double range = sanitizeRange(configuredRange);
		double rangeSqr = range * range;
		AABB area = new AABB(commandAnchor, commandAnchor).inflate(range);
		List<LivingEntity> candidates = new ArrayList<>(
				owner.level().getEntitiesOfClass(LivingEntity.class, area, candidate ->
						isEligible(owner, carrier, candidate, commandAnchor,
								rangeSqr, includeNonHostile)));
		candidates.sort(Comparator
				.<LivingEntity>comparingInt(candidate -> candidate == primaryTarget ? 0 : 1)
				.thenComparingDouble(candidate ->
						tacticalScore(owner, carrier, candidate, commandAnchor, variant))
				.thenComparing(candidate -> candidate.getUUID().toString()));
		if (candidates.size() > maximumTargets) {
			candidates = new ArrayList<>(candidates.subList(0, maximumTargets));
		}
		return List.copyOf(candidates);
	}

	static boolean isStillValid(ServerPlayer owner, ArmorStand carrier, LivingEntity target,
			Vec3 commandAnchor, double configuredRange, boolean includeNonHostile) {
		double range = sanitizeRange(configuredRange);
		return isEligible(owner, carrier, target, commandAnchor, range * range, includeNonHostile);
	}

	/** Returns a stable firing position which remains inside the suit's command radius. */
	static Vec3 engagementPosition(ArmorStand carrier, LivingEntity target, Vec3 commandAnchor,
			double configuredRange, ArmorVariant requestedVariant, long now, UUID suitId) {
		ArmorVariant variant = requestedVariant == null ? ArmorVariant.DEFAULT : requestedVariant;
		TacticalProfile tactics = tactics(variant);
		double range = sanitizeRange(configuredRange);
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		Vec3 away = variant == ArmorVariant.SENTINEL
				? commandAnchor.subtract(targetCenter) : carrier.position().subtract(targetCenter);
		if (away.horizontalDistanceSqr() < 1.0E-5D) {
			away = commandAnchor.subtract(targetCenter);
		}
		if (away.horizontalDistanceSqr() < 1.0E-5D) away = new Vec3(0.0D, 0.0D, 1.0D);
		Vec3 horizontal = new Vec3(away.x, 0.0D, away.z).normalize();
		if (variant == ArmorVariant.SWIFT) {
			double angle = (now + Math.floorMod(suitId.hashCode(), 97)) * 0.075D;
			horizontal = new Vec3(Math.sin(angle), 0.0D, Math.cos(angle));
		}
		Vec3 desired = targetCenter.add(horizontal.scale(tactics.standoff()))
				.add(0.0D, tactics.heightOffset(), 0.0D);
		return clampToCommandRadius(desired, commandAnchor, Math.max(MIN_RANGE, range - RETURN_MARGIN));
	}

	static double sanitizeRange(double configuredRange) {
		if (!Double.isFinite(configuredRange)) return 24.0D;
		return Math.max(MIN_RANGE, Math.min(MAX_RANGE, configuredRange));
	}

	private static boolean isEligible(ServerPlayer owner, ArmorStand carrier, LivingEntity candidate,
			Vec3 commandAnchor, double rangeSqr, boolean includeNonHostile) {
		if (candidate == null || candidate == owner || candidate == carrier || !candidate.isAlive()
				|| candidate.isRemoved() || candidate.isSpectator()
				|| candidate.position().distanceToSqr(commandAnchor) > rangeSqr) return false;
		// Decorative stands and every autonomous armor carrier are never combat targets.
		if (candidate instanceof ArmorStand) return false;
		if (ArmorEscortController.isEscortCarrier(candidate)
				|| ArmorCruiseController.isCruiseCarrier(candidate)) return false;
		if (!CombatTargetPolicy.canDamage(owner, candidate)) return false;
		if (includeNonHostile) return true;
		return candidate instanceof Enemy
				|| candidate instanceof Mob mob && (mob.getTarget() == owner || mob.getTarget() == carrier);
	}

	static double pursuitSpeed(ArmorVariant requestedVariant) {
		return tactics(requestedVariant).pursuitSpeed();
	}

	static long successfulFireDelay(ArmorVariant requestedVariant, int activeCombatSuits) {
		return tactics(requestedVariant).fireCycleTicks() * Math.max(1, activeCombatSuits);
	}

	static long retryDelay(ArmorVariant requestedVariant) {
		return tactics(requestedVariant).retryTicks();
	}

	static PreferredWeapon preferredWeapon(ArmorVariant requestedVariant) {
		return tactics(requestedVariant).preferredWeapon();
	}

	private static double tacticalScore(ServerPlayer owner, ArmorStand carrier,
			LivingEntity candidate, Vec3 commandAnchor, ArmorVariant variant) {
		int threatPriority;
		if (candidate instanceof Mob mob && (mob.getTarget() == owner || mob.getTarget() == carrier)) {
			threatPriority = 0;
		} else if (candidate instanceof Enemy) {
			threatPriority = 1;
		} else {
			threatPriority = 2;
		}
		double threatBand = threatPriority * 1_000_000.0D;
		double carrierDistance = carrier.distanceToSqr(candidate);
		return switch (variant) {
			case VANGUARD -> threatBand + carrierDistance;
			case SENTINEL -> threatBand + candidate.position().distanceToSqr(commandAnchor);
			case SWIFT -> threatBand + carrierDistance * 0.65D
					- Math.min(64.0D, candidate.getDeltaMovement().lengthSqr() * 24.0D);
		};
	}

	private static TacticalProfile tactics(ArmorVariant requestedVariant) {
		ArmorVariant variant = requestedVariant == null ? ArmorVariant.DEFAULT : requestedVariant;
		return switch (variant) {
			case VANGUARD -> new TacticalProfile(
					-1.25D, 0.20D, 2.55D, 30L, 2L, PreferredWeapon.IMPACT);
			case SENTINEL -> new TacticalProfile(
					17.0D, 1.30D, 0.95D, 42L, 4L, PreferredWeapon.CORE_BEAM);
			case SWIFT -> new TacticalProfile(
					9.5D, 1.0D, 3.10D, 34L, 2L, PreferredWeapon.MISSILE_VOLLEY);
		};
	}

	enum PreferredWeapon {
		IMPACT,
		CORE_BEAM,
		MISSILE_VOLLEY
	}

	private record TacticalProfile(double standoff, double heightOffset, double pursuitSpeed,
			long fireCycleTicks, long retryTicks, PreferredWeapon preferredWeapon) {
	}

	private static Vec3 clampToCommandRadius(Vec3 desired, Vec3 anchor, double radius) {
		Vec3 offset = desired.subtract(anchor);
		double distance = offset.length();
		if (distance <= radius || distance < 1.0E-5D) return desired;
		return anchor.add(offset.scale(radius / distance));
	}
}
