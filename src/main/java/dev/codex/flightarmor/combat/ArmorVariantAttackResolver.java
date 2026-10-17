package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.sound.ArmorExplosionEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 三种装甲型号的服务端附加命中解析。
 *
 * <p>这里负责“攻击机制”而不是单纯倍率：先锋制造近中程爆破，疾影沿射线或窄走廊贯穿并精确连锁，
 * 守望施加大范围减速和强制位移。所有查询、连锁和贯穿都有服务端硬上限，客户端不能提交目标列表或半径。
 * 只有 {@link ArmorWeaponRules} 明确许可的蓄力手炮、胸炮和高速巡航冲撞才会创建原版 Explosion；
 * 这三类爆炸统一使用 NONE，不破坏方块，也不依赖客户端粒子完成判定。</p>
 */
final class ArmorVariantAttackResolver {
	private static final int MAX_SECONDARY_CANDIDATES = 64;
	private static final int MAX_BLAST_TARGETS = 24;
	private static final int MAX_DIVE_TARGETS = ArmorCombatTuning.DIVE_IMPACT_MAX_TARGETS;
	private static final int MAX_SWIFT_PENETRATIONS = 4;
	private static final int MAX_SWIFT_CHAIN_TARGETS = 3;
	private static final float SWIFT_CORE_PENETRATION_FACTOR = 0.72F;
	private static final double SWIFT_CHAIN_RADIUS = 6.0D;
	private static final double WRIST_RAY_PADDING = 0.16D;
	private static final double CORE_RAY_PADDING = 0.28D;
	private static final double SENTINEL_WRIST_CONTROL_RADIUS = 3.8D;
	private static final double SECONDARY_OCCLUSION_EPSILON_SQR = 0.04D;

	private ArmorVariantAttackResolver() {
	}

	static List<VisualSegment> applyWrist(ServerPlayer attacker, ArmorVariant variant,
			CombatRaycast.Result hit, Entity primaryTarget, float directDamage, double range,
			float charge) {
		return applyWrist(attacker, attacker, variant, hit, primaryTarget,
				directDamage, range, charge, false);
	}

	static List<VisualSegment> applyWrist(ServerPlayer attacker, Entity directSource,
			ArmorVariant variant, CombatRaycast.Result hit, Entity primaryTarget,
			float directDamage, double range, float charge) {
		return applyWrist(attacker, directSource, variant, hit, primaryTarget,
				directDamage, range, charge, directSource != attacker);
	}

	static List<VisualSegment> applyWrist(ServerPlayer attacker, Entity directSource,
			ArmorVariant variant, CombatRaycast.Result hit, Entity primaryTarget,
			float directDamage, double range, float charge, boolean autonomous) {
		if (!validSecondaryImpact(attacker, hit, primaryTarget)) return List.of();
		float clampedCharge = Mth.clamp(charge, 0.0F, 1.0F);
		ArmorWeaponRules.ExplosionSpec explosion =
				ArmorWeaponRules.wristExplosion(variant, clampedCharge, autonomous);
		return switch (variant) {
			case VANGUARD -> {
				if (explosion.enabled()) {
					applyNativeExplosion(attacker, directSource,
							ModDamageTypes.gravityPulse(attacker, directSource),
							impactCenter(hit), explosion.power(),
							Level.ExplosionInteraction.NONE);
				}
				yield List.of();
			}
			case SWIFT -> {
				if (explosion.enabled()) {
					applyNativeExplosion(attacker, directSource,
							ModDamageTypes.gravityPulse(attacker, directSource),
							impactCenter(hit), explosion.power(),
							Level.ExplosionInteraction.NONE);
					yield List.of();
				}
				yield applyPrecisionChain(attacker, directSource,
						primaryTarget, directDamage);
			}
			case SENTINEL -> {
				if (explosion.enabled()) {
					applyNativeExplosion(attacker, directSource,
							ModDamageTypes.gravityPulse(attacker, directSource),
							impactCenter(hit), explosion.power(),
							Level.ExplosionInteraction.NONE);
				}
				applyContainmentField(attacker, impactCenter(hit),
						SENTINEL_WRIST_CONTROL_RADIUS, 34, 0,
						0.78D, 0.08D, primaryTarget);
				yield List.of();
			}
		};
	}

	static Vec3 applyCore(ServerPlayer attacker, ArmorVariant variant,
			CombatRaycast.Result hit, Entity primaryTarget, float directDamage,
			double range, double directKnockback, float charge) {
		return applyCore(attacker, attacker, variant, hit, primaryTarget, directDamage,
				range, directKnockback, charge);
	}

	static Vec3 applyCore(ServerPlayer attacker, Entity directSource, ArmorVariant variant,
			CombatRaycast.Result hit, Entity primaryTarget, float directDamage,
			double range, double directKnockback, float charge) {
		if (!validSecondaryImpact(attacker, hit, primaryTarget)) {
			return hit == null ? attacker.getEyePosition() : hit.endpoint();
		}
		float clampedCharge = Mth.clamp(charge, 0.0F, 1.0F);
		ArmorWeaponRules.ExplosionSpec explosion =
				ArmorWeaponRules.coreExplosion(variant, clampedCharge);
		if (explosion.enabled()) {
			applyNativeExplosion(attacker, directSource,
					ModDamageTypes.coreBeam(attacker, directSource),
					impactCenter(hit), explosion.power(),
					Level.ExplosionInteraction.NONE);
			if (variant == ArmorVariant.SENTINEL) {
				double radius = Mth.lerp(clampedCharge,
						ArmorCombatTuning.CORE_MIN_BLAST_RADIUS + 0.8D,
						ArmorCombatTuning.CORE_MAX_BLAST_RADIUS + 1.8D);
				applyContainmentField(attacker, impactCenter(hit), radius,
						Mth.floor(Mth.lerp(clampedCharge, 42.0F, 78.0F)), 1,
						directKnockback * Mth.lerp(clampedCharge, 0.72D, 1.05D),
						0.16D + clampedCharge * 0.10D, primaryTarget);
			}
			return hit.endpoint();
		}
		return switch (variant) {
			case VANGUARD -> {
				double radius = Mth.lerp(clampedCharge, 2.15D, 3.75D);
				applyRadialDamage(attacker, directSource, impactCenter(hit), radius,
						directDamage * Mth.lerp(clampedCharge, 0.62F, 0.82F),
						primaryTarget, ArmorAttackType.CORE_BEAM,
						directKnockback * Mth.lerp(clampedCharge, 0.88D, 1.20D));
				yield hit.endpoint();
			}
			case SWIFT -> applyPenetration(attacker, directSource, hit, primaryTarget, range, directDamage,
					ArmorAttackType.CORE_BEAM, CORE_RAY_PADDING,
					SWIFT_CORE_PENETRATION_FACTOR);
			case SENTINEL -> hit.endpoint();
		};
	}

	/**
	 * 俯冲命中不再先执行同一个圆形模板；每种型号在服务端使用独立的命中几何和控制结果。
	 */
	static void applyDiveImpact(ServerPlayer attacker, ArmorVariant variant, Vec3 impact,
			Vec3 incomingVelocity, float damage, double radius, float strength) {
		if (attacker == null || impact == null || incomingVelocity == null
				|| !finite(impact) || !finite(incomingVelocity)
				|| !Float.isFinite(damage) || damage <= 0.0F
				|| !Double.isFinite(radius) || radius <= 0.0D
				|| !Float.isFinite(strength) || strength <= 0.0F) return;

		switch (variant) {
			case VANGUARD -> applyVanguardDiveLift(attacker, impact, damage, radius, strength);
			case SWIFT -> applySwiftDiveLance(attacker, impact, incomingVelocity,
					damage * 0.42F, radius, strength);
			case SENTINEL -> applyNativeExplosion(attacker, attacker,
					ModDamageTypes.gravityImpact(attacker), impact,
					Mth.lerp(Mth.clamp(strength, 0.0F, 1.0F), 4.0F, 8.0F),
					Level.ExplosionInteraction.BLOCK);
		}
	}

	/**
	 * High-speed autonomous ram air-burst. Ground slam deliberately never calls
	 * this entry point, so its vertical radial skill cannot inherit ram semantics.
	 */
	static boolean applyCruiseRamExplosion(ServerPlayer owner, Entity carrier,
			Vec3 impact, double speed) {
		ArmorWeaponRules.ExplosionSpec explosion =
				ArmorWeaponRules.cruiseRamExplosion(speed);
		if (!explosion.enabled() || owner == null || carrier == null
				|| impact == null || !finite(impact)) {
			return false;
		}
		applyNativeExplosion(owner, carrier,
				ModDamageTypes.gravityImpact(owner, carrier), impact,
				explosion.power(), Level.ExplosionInteraction.NONE);
		return true;
	}

	/**
	 * Independent-cruise ground slam. Unlike a forward ram, this resolves a
	 * ground-centred radial impulse and attributes the direct source to the armor
	 * carrier while preserving the owner as the damage authority.
	 */
	static int applyCruiseGroundSlam(ServerPlayer owner, Entity carrier, Vec3 impact,
			float damage, double radius, double maximumKnockback) {
		if (owner == null || carrier == null || impact == null || !finite(impact)
				|| !Float.isFinite(damage) || damage <= 0.0F
				|| !Double.isFinite(radius) || radius <= 0.0D
				|| !Double.isFinite(maximumKnockback) || maximumKnockback <= 0.0D) {
			return 0;
		}
		int affected = 0;
		for (LivingEntity target : diveCandidates(owner, impact, radius, Math.max(3.0D, radius * 0.72D))) {
			if (target == carrier) continue;
			Vec3 center = target.getBoundingBox().getCenter();
			double horizontalDistance = horizontalDistance(center, impact);
			if (horizontalDistance > radius
					|| Math.abs(center.y - impact.y) > Math.max(3.0D, radius * 0.72D)
					|| isOccluded(owner, impact.add(0.0D, 0.24D, 0.0D), center)) {
				continue;
			}
			float falloff = (float) Mth.clamp(1.0D - horizontalDistance / radius, 0.0D, 1.0D);
			float appliedDamage = damage * (0.34F + falloff * 0.66F);
			if (!target.hurtOrSimulate(ModDamageTypes.gravityImpact(owner, carrier), appliedDamage)) continue;
			knockAway(target, impact,
					maximumKnockback * (0.48D + falloff * 0.72D),
					0.42D + falloff * 0.54D);
			target.syncVelocity = true;
			affected++;
		}
		return affected;
	}

	/** 疾影腕炮从首个命中目标开始跳向最近的可伤害目标，跳跃不会越过方块。 */
	/**
	 * Native entity-damaging impact for one Sentinel overdrive cannon lane.
	 *
	 * <p>Block interaction stays NONE on purpose. The corresponding beam corridor
	 * already passed through {@link DestructiveLaserController}, whose mobGriefing,
	 * hardness budget, protected-tag, block-entity, player permission and Fabric
	 * claim-hook checks must not be bypassed by a spherical explosion.</p>
	 */
	static void applySentinelOverdriveExplosion(ServerPlayer attacker,
			Vec3 center, float requestedPower) {
		if (!Float.isFinite(requestedPower) || requestedPower <= 0.0F) return;
		float boundedPower = Mth.clamp(requestedPower, 0.0F,
				SentinelOverdriveSalvo.MAXIMUM_CHEST_EXPLOSION_POWER);
		applyNativeExplosion(attacker, attacker, ModDamageTypes.coreBeam(attacker),
				center, boundedPower, Level.ExplosionInteraction.NONE);
	}

	/**
	 * 撞击爆破的实体伤害入口：在撞击点放一次原版爆炸，炸到周围合法目标，但排除自己与随行战甲。
	 *
	 * <p>方块交互恒定 {@code NONE}。地形破坏必须走 {@link ImpactBlastController} 的护栏，
	 * 不能让球形爆炸绕过 mobGriefing、保护标签与领地钩子。</p>
	 */
	static void applyImpactBlast(ServerPlayer attacker, Vec3 center, float power) {
		applyNativeExplosion(attacker, attacker,
				ModDamageTypes.gravityImpact(attacker), center, power,
				Level.ExplosionInteraction.NONE);
	}

	/**
	 * 增强能量光束的实体伤害入口：在爆坑中心放一次原版爆炸，炸到周围合法目标，排除自己与随行战甲。
	 *
	 * <p>方块交互恒定 {@code NONE}——地形破坏已经由 {@link ImpactBlastController#crater}
	 * 在同一个中心点按护栏做完，再交给原版球形爆炸就等于让爆炸绕过 mobGriefing、保护标签
	 * 与 Fabric 领地钩子，把同一片地形挖第二遍。</p>
	 */
	static void applyHandBeamBlast(ServerPlayer attacker, Vec3 center, float power) {
		applyNativeExplosion(attacker, attacker,
				ModDamageTypes.coreBeam(attacker), center, power,
				Level.ExplosionInteraction.NONE);
	}

	private static List<VisualSegment> applyPrecisionChain(ServerPlayer attacker, Entity directSource,
			Entity primaryTarget, float directDamage) {
		// 直击伤害先于连锁结算；即使首个目标被当场击杀，也必须以其最后位置继续跳链。
		if (!(primaryTarget instanceof LivingEntity primary)) return List.of();
		List<VisualSegment> visualSegments = new ArrayList<>(MAX_SWIFT_CHAIN_TARGETS);
		Set<Integer> visited = new HashSet<>();
		visited.add(attacker.getId());
		visited.add(primary.getId());
		LivingEntity previous = primary;

		for (int hop = 0; hop < MAX_SWIFT_CHAIN_TARGETS; hop++) {
			Vec3 origin = previous.getBoundingBox().getCenter();
			AABB area = AABB.ofSize(origin, SWIFT_CHAIN_RADIUS * 2.0D,
					SWIFT_CHAIN_RADIUS * 1.5D, SWIFT_CHAIN_RADIUS * 2.0D);
			List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
					LivingEntity.class, area,
					candidate -> !visited.contains(candidate.getId())
							&& CombatTargetPolicy.canDamage(attacker, candidate));
			candidates.sort((left, right) -> Double.compare(
					origin.distanceToSqr(left.getBoundingBox().getCenter()),
					origin.distanceToSqr(right.getBoundingBox().getCenter())));

			LivingEntity next = null;
			int inspected = 0;
			for (LivingEntity candidate : candidates) {
				if (inspected++ >= MAX_SECONDARY_CANDIDATES) break;
				Vec3 targetCenter = candidate.getBoundingBox().getCenter();
				if (origin.distanceToSqr(targetCenter) > SWIFT_CHAIN_RADIUS * SWIFT_CHAIN_RADIUS) break;
				if (!isOccluded(attacker, origin, targetCenter)) {
					next = candidate;
					break;
				}
			}
			if (next == null) break;

			float chainFactor = switch (hop) {
				case 0 -> 0.62F;
				case 1 -> 0.48F;
				default -> 0.36F;
			};
			next.hurt(ModDamageTypes.gravityPulse(attacker, directSource), directDamage * chainFactor);
			visualSegments.add(new VisualSegment(origin, next.getBoundingBox().getCenter()));
			visited.add(next.getId());
			previous = next;
		}
		return List.copyOf(visualSegments);
	}

	/** 沿原服务端射线继续解析；友军和实体遮挡都会终止穿透链。 */
	private static Vec3 applyPenetration(ServerPlayer attacker, Entity directSource,
			CombatRaycast.Result hit, Entity primaryTarget, double range, float directDamage,
			ArmorAttackType attackType, double rayPadding, float initialDamageFactor) {
		if (primaryTarget == null || !Double.isFinite(range) || range <= 0.0D) return hit.endpoint();
		Vec3 delta = hit.endpoint().subtract(hit.origin());
		if (delta.lengthSqr() < 1.0E-8D) return hit.endpoint();
		Vec3 direction = delta.normalize();
		double primaryProjection = Math.max(delta.length(),
				primaryTarget.getBoundingBox().getCenter().subtract(hit.origin()).dot(direction));
		if (primaryProjection + 0.05D >= range) return hit.endpoint();

		Vec3 maximumEnd = hit.origin().add(direction.scale(range));
		HitResult blockHit = attacker.level().clip(new ClipContext(
				hit.origin(), maximumEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
		Vec3 limitedEnd = blockHit.getType() == HitResult.Type.BLOCK
				? blockHit.getLocation() : maximumEnd;
		double limitedProjection = limitedEnd.subtract(hit.origin()).dot(direction);
		if (limitedProjection <= primaryProjection + 0.05D) return hit.endpoint();

		AABB searchArea = new AABB(hit.origin(), limitedEnd).inflate(rayPadding + 0.35D);
		List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
				LivingEntity.class, searchArea,
				candidate -> candidate != attacker && candidate != primaryTarget
						&& CombatTargetPolicy.canOcclude(attacker, candidate));
		candidates.sort((left, right) -> Double.compare(
				rayProjection(hit.origin(), direction, left),
				rayProjection(hit.origin(), direction, right)));

		int inspected = 0;
		int penetrated = 0;
		for (LivingEntity candidate : candidates) {
			if (inspected++ >= MAX_SECONDARY_CANDIDATES
					|| penetrated >= MAX_SWIFT_PENETRATIONS) break;
			Optional<Vec3> intercept = candidate.getBoundingBox().inflate(rayPadding)
					.clip(hit.origin(), limitedEnd);
			if (intercept.isEmpty()) continue;
			double projection = intercept.get().subtract(hit.origin()).dot(direction);
			if (projection <= primaryProjection + 0.05D || projection > limitedProjection + 1.0E-5D) continue;
			if (isOccluded(attacker, hit.origin(), intercept.get())) continue;
			if (!CombatTargetPolicy.canDamage(attacker, candidate)) break;

			float decay = Math.max(0.42F, 1.0F - penetrated * 0.17F);
			float damage = directDamage * initialDamageFactor * decay;
			hurt(attacker, directSource, candidate, attackType, damage);
			penetrated++;
		}
		return limitedEnd;
	}

	private static void applyRadialDamage(ServerPlayer attacker, Vec3 center,
			double radius, float maximumDamage, Entity primaryTarget,
			ArmorAttackType attackType, double maximumKnockback) {
		applyRadialDamage(attacker, attacker, center, radius, maximumDamage,
				primaryTarget, attackType, maximumKnockback);
	}

	private static void applyRadialDamage(ServerPlayer attacker, Entity directSource, Vec3 center,
			double radius, float maximumDamage, Entity primaryTarget,
			ArmorAttackType attackType, double maximumKnockback) {
		if (!Double.isFinite(radius) || radius <= 0.0D
				|| !Float.isFinite(maximumDamage) || maximumDamage <= 0.0F) return;
		AABB area = AABB.ofSize(center, radius * 2.0D, radius * 2.0D, radius * 2.0D);
		List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
				LivingEntity.class, area,
				candidate -> candidate != attacker && candidate != primaryTarget
						&& CombatTargetPolicy.canDamage(attacker, candidate));
		candidates.sort((left, right) -> Double.compare(
				center.distanceToSqr(left.getBoundingBox().getCenter()),
				center.distanceToSqr(right.getBoundingBox().getCenter())));

		int inspected = 0;
		int damaged = 0;
		for (LivingEntity candidate : candidates) {
			if (inspected++ >= MAX_SECONDARY_CANDIDATES || damaged >= MAX_BLAST_TARGETS) break;
			Vec3 targetCenter = candidate.getBoundingBox().getCenter();
			double distance = center.distanceTo(targetCenter);
			if (distance > radius) break;
			if (isOccluded(attacker, center, targetCenter)) continue;
			float falloff = (float) Mth.clamp(1.0D - distance / radius, 0.0D, 1.0D);
			float damage = maximumDamage * (0.35F + falloff * 0.65F);
			if (!hurt(attacker, directSource, candidate, attackType, damage)) continue;
			damaged++;
			if (maximumKnockback > 0.0D) {
				knockAway(candidate, center,
						maximumKnockback * (0.35D + falloff * 0.65D), 0.04D + falloff * 0.08D);
			}
		}
	}

	/** 守望的控制场作用于首个目标和周边目标，但不会借此伤害友军。 */
	private static void applyContainmentField(ServerPlayer attacker, Vec3 center, double radius,
			int slowTicks, int slowAmplifier, double maximumKnockback, double lift,
			Entity primaryTarget) {
		int affected = 0;
		if (primaryTarget instanceof LivingEntity primary
				&& CombatTargetPolicy.canDamage(attacker, primary)) {
			applyContainmentToTarget(primary, center, slowTicks, slowAmplifier,
					maximumKnockback, lift, 1.0F);
			affected++;
		}
		AABB area = AABB.ofSize(center, radius * 2.0D, radius * 1.5D, radius * 2.0D);
		List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
				LivingEntity.class, area,
				candidate -> candidate != attacker && candidate != primaryTarget
						&& CombatTargetPolicy.canDamage(attacker, candidate));
		candidates.sort((left, right) -> Double.compare(
				center.distanceToSqr(left.getBoundingBox().getCenter()),
				center.distanceToSqr(right.getBoundingBox().getCenter())));

		int inspected = 0;
		for (LivingEntity candidate : candidates) {
			if (inspected++ >= MAX_SECONDARY_CANDIDATES || affected >= MAX_BLAST_TARGETS) break;
			Vec3 targetCenter = candidate.getBoundingBox().getCenter();
			double distance = center.distanceTo(targetCenter);
			if (distance > radius) break;
			if (isOccluded(attacker, center, targetCenter)) continue;
			float falloff = (float) Mth.clamp(1.0D - distance / radius, 0.0D, 1.0D);
			applyContainmentToTarget(candidate, center, slowTicks, slowAmplifier,
					maximumKnockback, lift, falloff);
			affected++;
		}
	}

	private static void applyContainmentToTarget(LivingEntity target, Vec3 center,
			int slowTicks, int slowAmplifier, double maximumKnockback, double lift, float falloff) {
		int duration = Math.max(12, Mth.floor(slowTicks * (0.55F + falloff * 0.45F)));
		target.addEffect(new MobEffectInstance(
				MobEffects.SLOWNESS, duration, slowAmplifier));
		Vec3 velocity = target.getDeltaMovement();
		target.setDeltaMovement(velocity.x * 0.42D,
				Math.min(velocity.y, 0.18D), velocity.z * 0.42D);
		knockAway(target, center,
				maximumKnockback * (0.42D + falloff * 0.58D), lift * (0.70D + falloff * 0.30D));
		target.syncVelocity = true;
	}

	private static void applyVanguardDiveLift(ServerPlayer attacker, Vec3 impact,
			float damage, double radius, float strength) {
		for (LivingEntity target : diveCandidates(attacker, impact, radius, radius)) {
			Vec3 center = target.getBoundingBox().getCenter();
			double distance = horizontalDistance(center, impact);
			if (distance > radius || isOccluded(attacker, impact.add(0.0D, 0.22D, 0.0D), center)) continue;
			float falloff = (float) Mth.clamp(1.0D - distance / radius, 0.0D, 1.0D);
			if (!target.hurtOrSimulate(ModDamageTypes.gravityImpact(attacker),
					damage * (0.24F + falloff * 0.24F))) continue;
			knockAway(target, impact,
					ArmorCombatTuning.DIVE_IMPACT_MAX_KNOCKBACK * strength
							* (0.70D + falloff * 0.55D),
					0.38D + strength * (0.24D + falloff * 0.22D));
		}
	}

	/** 守望重装把下坠动能压进一个高伤害、无方块破坏的重力爆心。 */
	private static void applyNativeExplosion(ServerPlayer attacker, Entity source,
			DamageSource damageSource, Vec3 center, float power,
			Level.ExplosionInteraction interaction) {
		if (attacker == null || center == null || !finite(center)
				|| !Float.isFinite(power) || power <= 0.0F) return;
		Entity direct = source == null ? attacker : source;
		boolean ownerInvulnerable = attacker.isInvulnerable();
		boolean directInvulnerable = direct.isInvulnerable();
		if (!ownerInvulnerable) attacker.setPermanentlyInvulnerable(true);
		if (direct != attacker && !directInvulnerable) direct.setPermanentlyInvulnerable(true);
		ExplosionDamageCalculator targetFilter = new ExplosionDamageCalculator() {
			@Override
			public boolean shouldDamageEntity(Explosion explosion, Entity candidate) {
				if (candidate == attacker || candidate == direct) return false;
				return !(candidate instanceof LivingEntity)
						|| CombatTargetPolicy.canDamage(attacker, candidate);
			}
		};
		try {
			ArmorExplosionEffects.explodeWithLightReport(
					attacker.level(), direct, damageSource, targetFilter,
					center, power, false, interaction);
		} finally {
			if (direct != attacker && !directInvulnerable) direct.setPermanentlyInvulnerable(false);
			if (!ownerInvulnerable) attacker.setPermanentlyInvulnerable(false);
		}
	}

	/** 疾影俯冲把冲量投射为一条窄走廊，沿走廊连续命中而不是制造圆形爆炸。 */
	private static void applySwiftDiveLance(ServerPlayer attacker, Vec3 impact, Vec3 incomingVelocity,
			float damage, double radius, float strength) {
		Vec3 axis = horizontalDirection(incomingVelocity);
		if (axis.lengthSqr() < 1.0E-8D) axis = horizontalDirection(attacker.getLookAngle());
		if (axis.lengthSqr() < 1.0E-8D) axis = new Vec3(0.0D, 0.0D, 1.0D);

		double rearReach = Math.max(1.0D, radius * 0.35D);
		double forwardReach = Math.max(5.0D, radius * 2.35D);
		double halfWidth = Math.max(1.15D, radius * 0.30D);
		Vec3 laneStart = impact.subtract(axis.scale(rearReach));
		Vec3 laneEnd = impact.add(axis.scale(forwardReach));
		AABB area = new AABB(laneStart, laneEnd).inflate(halfWidth, Math.max(2.4D, radius * 0.70D), halfWidth);
		List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
				LivingEntity.class, area, candidate -> CombatTargetPolicy.canDamage(attacker, candidate));
		final Vec3 laneAxis = axis;
		candidates.sort((left, right) -> Double.compare(
				left.getBoundingBox().getCenter().subtract(impact).dot(laneAxis),
				right.getBoundingBox().getCenter().subtract(impact).dot(laneAxis)));

		int affected = 0;
		for (LivingEntity target : candidates) {
			if (affected >= MAX_DIVE_TARGETS) break;
			Vec3 center = target.getBoundingBox().getCenter();
			Vec3 relative = center.subtract(impact);
			double projection = relative.dot(axis);
			if (projection < -rearReach || projection > forwardReach) continue;
			Vec3 lateral = new Vec3(relative.x, 0.0D, relative.z).subtract(axis.scale(projection));
			double lateralDistance = lateral.length();
			if (lateralDistance > halfWidth
					|| Math.abs(relative.y) > Math.max(2.6D, radius * 0.75D)
					|| isOccluded(attacker, impact.add(0.0D, 0.22D, 0.0D), center)) continue;

			float precision = (float) Mth.clamp(1.0D - lateralDistance / halfWidth, 0.0D, 1.0D);
			float laneFalloff = (float) Mth.clamp(1.0D - Math.max(0.0D, projection) / forwardReach,
					0.0D, 1.0D);
			if (!target.hurtOrSimulate(ModDamageTypes.gravityImpact(attacker),
					damage * (0.64F + precision * 0.26F + laneFalloff * 0.10F))) continue;
			Vec3 velocity = target.getDeltaMovement().add(axis.scale(0.48D + strength * 0.52D))
					.add(0.0D, 0.07D + strength * 0.05D, 0.0D);
			target.setDeltaMovement(velocity);
			target.syncVelocity = true;
			affected++;
		}
	}

	/** 守望俯冲牺牲中心爆发，换取更大的减速场和强制外推。 */
	private static void applySentinelDiveField(ServerPlayer attacker, Vec3 impact,
			float damage, double radius, float strength) {
		for (LivingEntity target : diveCandidates(attacker, impact, radius, radius * 0.85D)) {
			Vec3 center = target.getBoundingBox().getCenter();
			double distance = horizontalDistance(center, impact);
			if (distance > radius || isOccluded(attacker, impact.add(0.0D, 0.22D, 0.0D), center)) continue;
			float falloff = (float) Mth.clamp(1.0D - distance / radius, 0.0D, 1.0D);
			boolean hurt = target.hurtOrSimulate(ModDamageTypes.gravityImpact(attacker),
					damage * (0.38F + falloff * 0.30F));
			if (!hurt) continue;
			int slowTicks = Mth.floor(46.0F + strength * 30.0F + falloff * 18.0F);
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, slowTicks, 1));
			Vec3 velocity = target.getDeltaMovement();
			target.setDeltaMovement(velocity.x * 0.28D, Math.min(velocity.y, 0.10D), velocity.z * 0.28D);
			knockAway(target, impact,
					ArmorCombatTuning.DIVE_IMPACT_MAX_KNOCKBACK * strength
							* (0.92D + falloff * 0.58D),
					0.24D + strength * (0.18D + falloff * 0.12D));
		}
	}

	private static List<LivingEntity> diveCandidates(ServerPlayer attacker, Vec3 impact,
			double horizontalRadius, double verticalRadius) {
		AABB area = AABB.ofSize(impact, horizontalRadius * 2.0D,
				Math.max(3.0D, verticalRadius * 2.0D), horizontalRadius * 2.0D);
		List<LivingEntity> candidates = attacker.level().getEntitiesOfClass(
				LivingEntity.class, area, candidate -> CombatTargetPolicy.canDamage(attacker, candidate));
		candidates.sort((left, right) -> Double.compare(
				horizontalDistanceSqr(left.getBoundingBox().getCenter(), impact),
				horizontalDistanceSqr(right.getBoundingBox().getCenter(), impact)));
		return candidates.size() <= MAX_DIVE_TARGETS
				? candidates : candidates.subList(0, MAX_DIVE_TARGETS);
	}

	private static boolean validSecondaryImpact(ServerPlayer attacker,
			CombatRaycast.Result hit, Entity primaryTarget) {
		if (hit == null || hit.hitKind() == ArmorAttackHitKind.MISS) return false;
		if (hit.hitKind() == ArmorAttackHitKind.BLOCK) return primaryTarget == null;
		return primaryTarget != null && (!primaryTarget.isAlive()
				|| CombatTargetPolicy.canDamage(attacker, primaryTarget));
	}

	private static boolean hurt(ServerPlayer attacker, LivingEntity candidate,
			ArmorAttackType attackType, float damage) {
		return hurt(attacker, attacker, candidate, attackType, damage);
	}

	private static boolean hurt(ServerPlayer attacker, Entity directSource,
			LivingEntity candidate, ArmorAttackType attackType, float damage) {
		return switch (attackType) {
			case HAND_BEAM_PULSE, HAND_BEAM_LANCE ->
					candidate.hurtOrSimulate(ModDamageTypes.coreBeam(attacker, directSource), damage);
			case CORE_BEAM, CRUISE_CORE_BEAM ->
					candidate.hurtOrSimulate(ModDamageTypes.coreBeam(attacker, directSource), damage);
			case GRAVITY_DIVE_IMPACT, CRUISE_RAM, CRUISE_GROUND_SLAM ->
					candidate.hurtOrSimulate(ModDamageTypes.gravityImpact(attacker, directSource), damage);
			case WRIST_GRAVITY_PULSE, ENERGY_BLADE_SLASH, ENERGY_BLADE_COMBO,
					ENERGY_BLADE_TOGGLE, CRUISE_MISSILE_VOLLEY, INVALID ->
					candidate.hurtOrSimulate(ModDamageTypes.gravityPulse(attacker, directSource), damage);
		};
	}

	private static void knockAway(LivingEntity target, Vec3 center, double strength, double lift) {
		if (strength > 0.0D) {
			target.knockback(strength, center.x - target.getX(), center.z - target.getZ(), null, 0.0F);
		}
		if (lift > 0.0D) {
			target.setDeltaMovement(target.getDeltaMovement().add(0.0D, lift, 0.0D));
		}
		target.syncVelocity = true;
	}

	private static double rayProjection(Vec3 origin, Vec3 direction, LivingEntity entity) {
		return entity.getBoundingBox().getCenter().subtract(origin).dot(direction);
	}

	private static Vec3 impactCenter(CombatRaycast.Result hit) {
		Vec3 normal = hit.impactNormal();
		return normal.lengthSqr() < 1.0E-8D
				? hit.endpoint()
				: hit.endpoint().add(normal.normalize().scale(0.08D));
	}

	private static Vec3 horizontalDirection(Vec3 vector) {
		Vec3 horizontal = new Vec3(vector.x, 0.0D, vector.z);
		return horizontal.lengthSqr() < 1.0E-8D ? Vec3.ZERO : horizontal.normalize();
	}

	private static double horizontalDistance(Vec3 first, Vec3 second) {
		return Math.sqrt(horizontalDistanceSqr(first, second));
	}

	private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
		double dx = first.x - second.x;
		double dz = first.z - second.z;
		return dx * dx + dz * dz;
	}

	private static boolean finite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}

	private static boolean isOccluded(ServerPlayer attacker, Vec3 start, Vec3 target) {
		if (start.distanceToSqr(target) < 1.0E-6D) return false;
		HitResult blockHit = attacker.level().clip(new ClipContext(
				start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
		return blockHit.getType() == HitResult.Type.BLOCK
				&& start.distanceToSqr(blockHit.getLocation()) + SECONDARY_OCCLUSION_EPSILON_SQR
				< start.distanceToSqr(target);
	}

	/** 服务端已实际结算的疾影连锁段；客户端只负责把这些端点画出来。 */
	record VisualSegment(Vec3 origin, Vec3 endpoint) {
	}
}
