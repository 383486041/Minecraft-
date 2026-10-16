package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.variant.ArmorVariant;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 服务端方块优先射线，并从近似腕部起点进行第二次遮挡校验。 */
final class CombatRaycast {
	private static final Vec3 ZERO_NORMAL = Vec3.ZERO;

	private CombatRaycast() {
	}

	static Result castWristPulse(ServerPlayer player, ArmorVariant variant, ArmorAnchorPose pose,
			boolean leftHand, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 maximumEnd = eye.add(direction.scale(range));
		BlockHitResult eyeBlock = clip(player, eye, maximumEnd);
		boolean eyeHitBlock = eyeBlock.getType() == HitResult.Type.BLOCK;
		Vec3 eyeLimitedEnd = eyeHitBlock ? eyeBlock.getLocation() : maximumEnd;
		double entityLimitSq = eye.distanceToSqr(eyeLimitedEnd);
		AABB searchBox = player.getBoundingBox()
				.expandTowards(direction.scale(Math.sqrt(entityLimitSq)))
				.inflate(1.0D);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				player, eye, eyeLimitedEnd, searchBox,
				candidate -> CombatTargetPolicy.canOcclude(player, candidate), entityLimitSq);

		Entity target = entityHit == null ? null : entityHit.getEntity();
		Vec3 endpoint = entityHit == null ? eyeLimitedEnd : entityHit.getLocation();
		ArmorAttackHitKind hitKind = target != null
				? ArmorAttackHitKind.ENTITY
				: eyeHitBlock ? ArmorAttackHitKind.BLOCK : ArmorAttackHitKind.MISS;
		Vec3 normal = target != null
				? direction.scale(-1.0D)
				: eyeHitBlock ? Vec3.atLowerCornerOf(eyeBlock.getDirection().getNormal()) : ZERO_NORMAL;

		Vec3 origin = ArmorAnchorResolver.resolve(player, variant, pose,
				ArmorSemanticAnchor.palm(leftHand), direction);
		BlockHitResult wristBlock = clip(player, origin, endpoint);
		boolean wristHitBlock = wristBlock.getType() == HitResult.Type.BLOCK
				&& origin.distanceToSqr(wristBlock.getLocation()) + 1.0E-5D
				< origin.distanceToSqr(endpoint);
		Vec3 wristLimitedEnd = wristHitBlock ? wristBlock.getLocation() : endpoint;
		double wristLimitSq = origin.distanceToSqr(wristLimitedEnd);
		EntityHitResult wristEntity = ProjectileUtil.getEntityHitResult(
				player, origin, wristLimitedEnd,
				new AABB(origin, wristLimitedEnd).inflate(1.0D),
				candidate -> CombatTargetPolicy.canOcclude(player, candidate), wristLimitSq);
		if (wristEntity != null) {
			target = wristEntity.getEntity();
			endpoint = wristEntity.getLocation();
			hitKind = ArmorAttackHitKind.ENTITY;
			Vec3 wristDirection = endpoint.subtract(origin);
			normal = wristDirection.lengthSqr() > 1.0E-8D
					? wristDirection.normalize().scale(-1.0D) : direction.scale(-1.0D);
		} else if (wristHitBlock) {
			target = null;
			endpoint = wristBlock.getLocation();
			hitKind = ArmorAttackHitKind.BLOCK;
			normal = Vec3.atLowerCornerOf(wristBlock.getDirection().getNormal());
		}
		return new Result(origin, endpoint, normal, hitKind, target);
	}

	static Result castCruiseWrist(ServerPlayer owner, Entity carrier, Entity intendedTarget,
			Vec3 origin, double range) {
		Vec3 targetCenter = intendedTarget.getBoundingBox().getCenter();
		Vec3 delta = targetCenter.subtract(origin);
		double distance = delta.length();
		if (distance < 1.0E-5D || distance > range) {
			return new Result(origin, origin, ZERO_NORMAL, ArmorAttackHitKind.MISS, null);
		}
		Vec3 direction = delta.normalize();
		BlockHitResult block = carrier.level().clip(new ClipContext(
				origin, targetCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, carrier));
		boolean blockHit = block.getType() == HitResult.Type.BLOCK
				&& origin.distanceToSqr(block.getLocation()) + 1.0E-5D < origin.distanceToSqr(targetCenter);
		Vec3 limitedEnd = blockHit ? block.getLocation() : targetCenter;
		double limitSq = origin.distanceToSqr(limitedEnd);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				carrier, origin, limitedEnd, new AABB(origin, limitedEnd).inflate(0.9D),
				candidate -> CombatTargetPolicy.canOcclude(owner, candidate), limitSq);
		if (entityHit != null) {
			Entity target = entityHit.getEntity();
			Vec3 hitDirection = entityHit.getLocation().subtract(origin);
			Vec3 normal = hitDirection.lengthSqr() > 1.0E-8D
					? hitDirection.normalize().scale(-1.0D) : direction.scale(-1.0D);
			return new Result(origin, entityHit.getLocation(), normal, ArmorAttackHitKind.ENTITY, target);
		}
		if (blockHit) {
			return new Result(origin, block.getLocation(),
					Vec3.atLowerCornerOf(block.getDirection().getNormal()), ArmorAttackHitKind.BLOCK, null);
		}
		return new Result(origin, targetCenter, ZERO_NORMAL, ArmorAttackHitKind.MISS, null);
	}

	/** 核心束仍以服务端真实视线选点，再从胸口核心执行第二次方块与实体遮挡校验。 */
	static Result castCoreBeam(ServerPlayer player, ArmorVariant variant, ArmorAnchorPose pose,
			double range, float charge) {
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 origin = ArmorAnchorResolver.resolve(player, variant, pose,
				ArmorSemanticAnchor.CHEST_CORE, direction);
		Vec3 maximumEnd = origin.add(direction.scale(range));
		double maximumDistanceSq = origin.distanceToSqr(maximumEnd);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				player, origin, maximumEnd, new AABB(origin, maximumEnd).inflate(1.25D),
				candidate -> CombatTargetPolicy.canOcclude(player, candidate), maximumDistanceSq);
		Vec3 terrainEnd = entityHit == null ? maximumEnd : entityHit.getLocation();

		DestructiveLaserController.Result blockPhase =
				DestructiveLaserController.resolveCoreBeam(player, origin, terrainEnd, charge);
		double resolvedDistanceSq = origin.distanceToSqr(blockPhase.endpoint());
		boolean reachedEntity = entityHit != null
				&& resolvedDistanceSq + 1.0E-5D >= origin.distanceToSqr(terrainEnd);
		if (reachedEntity) {
			Entity target = entityHit.getEntity();
			Vec3 hitDirection = terrainEnd.subtract(origin);
			Vec3 normal = hitDirection.lengthSqr() > 1.0E-8D
					? hitDirection.normalize().scale(-1.0D) : direction.scale(-1.0D);
			return new Result(origin, terrainEnd, normal, ArmorAttackHitKind.ENTITY, target);
		}
		return new Result(origin, blockPhase.endpoint(), blockPhase.impactNormal(),
				blockPhase.hitKind(), null);
	}
	private static BlockHitResult clip(ServerPlayer player, Vec3 start, Vec3 end) {
		return player.level().clip(new ClipContext(
				start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
	}


	record Result(Vec3 origin, Vec3 endpoint, Vec3 impactNormal,
			ArmorAttackHitKind hitKind, Entity entity) {
	}
}
