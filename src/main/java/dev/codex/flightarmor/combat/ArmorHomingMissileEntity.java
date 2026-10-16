package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.cruise.ArmorCruiseCombatController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative, persistent homing missile used by Swift escort armor.
 * The client receives an ordinary tracked entity and only renders its geometry.
 */
public final class ArmorHomingMissileEntity extends Projectile {
	private static final Set<ArmorHomingMissileEntity> LIVE = ConcurrentHashMap.newKeySet();
	private static final double INITIAL_SPEED = 0.52D;
	private static final double ACCELERATION_PER_TICK = 0.095D;
	private static final double MAXIMUM_SPEED = 1.72D;
	private static final double MAXIMUM_TURN_RADIANS = Math.toRadians(15.0D);
	private static final double COLLISION_RADIUS = 0.18D;
	private static final int DEFAULT_MAXIMUM_LIFE_TICKS = 90;

	private UUID ownerId;
	private UUID suitId;
	private UUID carrierId;
	private UUID targetId;
	private long actionSequence;
	private long carrierRevision;
	private long attackId;
	private int missileIndex;
	private float damage;
	private int maximumLifeTicks = DEFAULT_MAXIMUM_LIFE_TICKS;
	private double maximumTravel = ArmorCombatTuning.CRUISE_MISSILE_RANGE + 18.0D;
	private double distanceTravelled;
	private Vec3 launchOrigin = Vec3.ZERO;

	public ArmorHomingMissileEntity(EntityType<? extends ArmorHomingMissileEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static ArmorHomingMissileEntity launch(ServerLevel level, ServerPlayer owner, Entity carrier,
			LivingEntity target, UUID suitId, long actionSequence, long carrierRevision,
			long attackId, int missileIndex, float damage, Vec3 origin) {
		ArmorHomingMissileEntity missile = new ArmorHomingMissileEntity(ModCombatEntities.HOMING_MISSILE, level);
		missile.ownerId = owner.getUUID();
		missile.suitId = suitId;
		missile.carrierId = carrier.getUUID();
		missile.targetId = target.getUUID();
		missile.actionSequence = actionSequence;
		missile.carrierRevision = carrierRevision;
		missile.attackId = attackId;
		missile.missileIndex = missileIndex;
		missile.damage = damage;
		missile.launchOrigin = origin;
		missile.setOwner(owner);
		missile.setPos(origin);
		Vec3 aim = target.getBoundingBox().getCenter().subtract(origin);
		Vec3 direction = aim.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 1.0D, 0.0D) : aim.normalize();
		// A small upward bias gives the volley visible separation while guidance remains bounded.
		double side = (missileIndex & 1) == 0 ? -1.0D : 1.0D;
		Vec3 lateral = new Vec3(direction.z, 0.0D, -direction.x).scale(side * 0.13D);
		Vec3 launchDirection = direction.add(lateral).add(0.0D, 0.16D + missileIndex * 0.018D, 0.0D).normalize();
		missile.setDeltaMovement(launchDirection.scale(INITIAL_SPEED));
		LIVE.add(missile);
		return missile;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		// Position and velocity are covered by vanilla entity tracking. Combat identity stays server-only.
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) {
			Vec3 movement = getDeltaMovement();
			if (movement.lengthSqr() > 1.0E-8D) {
				setPos(position().add(movement));
				updateRotation();
			}
			return;
		}
		if (!(level() instanceof ServerLevel level)) {
			discardWithoutDamage();
			return;
		}
		LIVE.add(this);
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.level() != level) {
			discardWithoutDamage();
			return;
		}
		Entity carrier = carrierId == null ? null : level.getEntity(carrierId);
		Entity rawTarget = targetId == null ? null : level.getEntity(targetId);
		if (carrier == null || carrier.isRemoved()
				|| !(rawTarget instanceof LivingEntity target) || !target.isAlive()
				|| target.isRemoved() || !CombatTargetPolicy.canDamage(owner, target)
				|| tickCount > maximumLifeTicks || distanceTravelled >= maximumTravel) {
			discardWithoutDamage();
			return;
		}

		boolean validAttack = ArmorEscortController.isDelayedAttackValid(owner, suitId, carrierId,
				actionSequence, carrierRevision)
				|| ArmorCruiseCombatController.isDelayedAttackValid(owner, suitId, carrierId,
				actionSequence, carrierRevision, target);
		if (!validAttack) {
			discardWithoutDamage();
			return;
		}
		Vec3 start = position();
		Vec3 aim = target.getBoundingBox().getCenter().subtract(start);
		Vec3 guided = fromPure(HomingMissileKinematics.steer(toPure(getDeltaMovement()), toPure(aim),
				ACCELERATION_PER_TICK, MAXIMUM_SPEED, MAXIMUM_TURN_RADIANS));
		if (guided.lengthSqr() < 1.0E-8D) {
			discardWithoutDamage();
			return;
		}
		setDeltaMovement(guided);
		Vec3 end = start.add(guided);

		BlockHitResult blockHit = level.clip(new ClipContext(start, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		double blockFraction = blockHit.getType() == HitResult.Type.MISS
				? Double.POSITIVE_INFINITY : segmentFraction(start, end, blockHit.getLocation());
		EntityCollision entityCollision = firstEntityCollision(level, owner, carrier, start, end);

		if (entityCollision.entity() != null && entityCollision.fraction() <= blockFraction) {
			Vec3 impact = start.lerp(end, entityCollision.fraction());
			setPos(impact);
			hitEntity(owner, entityCollision.entity(), impact, guided);
			return;
		}
		if (blockFraction != Double.POSITIVE_INFINITY) {
			Vec3 impact = blockHit.getLocation();
			setPos(impact);
			Vec3 normal = new Vec3(blockHit.getDirection().getStepX(),
					blockHit.getDirection().getStepY(), blockHit.getDirection().getStepZ());
			ArmorCombatController.emitHomingMissileImpact(this, owner, impact, normal,
					ArmorAttackHitKind.BLOCK, ArmorAttackFxPayload.NO_ENTITY_ID);
			discardWithoutDamage();
			return;
		}

		setPos(end);
		distanceTravelled += guided.length();
		updateRotation();
	}

	private EntityCollision firstEntityCollision(ServerLevel level, ServerPlayer owner, Entity carrier,
			Vec3 start, Vec3 end) {
		AABB broadPhase = getBoundingBox().expandTowards(end.subtract(start)).inflate(COLLISION_RADIUS);
		Entity best = null;
		double bestFraction = Double.POSITIVE_INFINITY;
		for (Entity candidate : level.getEntities(this, broadPhase,
				candidate -> candidate != carrier && CombatTargetPolicy.canOcclude(owner, candidate))) {
			AABB box = candidate.getBoundingBox().inflate(COLLISION_RADIUS);
			double fraction = HomingMissileKinematics.segmentAabbEntry(toPure(start), toPure(end),
					new HomingMissileKinematics.Box(box.minX, box.minY, box.minZ,
							box.maxX, box.maxY, box.maxZ));
			if (fraction < bestFraction) {
				bestFraction = fraction;
				best = candidate;
			}
		}
		return new EntityCollision(best, bestFraction);
	}

	private void hitEntity(ServerPlayer owner, Entity entity, Vec3 impact, Vec3 incoming) {
		if (CombatTargetPolicy.canDamage(owner, entity)) {
			boolean hurt = entity.hurt(ModDamageTypes.armorMissile(owner, this), damage);
			if (hurt && entity instanceof LivingEntity living) {
				living.knockback(ArmorCombatTuning.CRUISE_MISSILE_KNOCKBACK,
						position().x - living.getX(), position().z - living.getZ());
			}
		}
		Vec3 normal = incoming.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 1.0D, 0.0D) : incoming.normalize().scale(-1.0D);
		ArmorCombatController.emitHomingMissileImpact(this, owner, impact, normal,
				ArmorAttackHitKind.ENTITY, entity.getId());
		discardWithoutDamage();
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (isRemoved() || amount <= 0.0F) return false;
		if (!level().isClientSide) discardWithoutDamage();
		else markHurt();
		return true;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canBeHitByProjectile() {
		return true;
	}

	@Override
	public float getPickRadius() {
		return 0.32F;
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		putUuid(tag, "FlightArmorOwner", ownerId);
		putUuid(tag, "FlightArmorSuit", suitId);
		putUuid(tag, "FlightArmorCarrier", carrierId);
		putUuid(tag, "FlightArmorTarget", targetId);
		tag.putLong("FlightArmorActionSequence", actionSequence);
		tag.putLong("FlightArmorCarrierRevision", carrierRevision);
		tag.putLong("FlightArmorAttackId", attackId);
		tag.putInt("FlightArmorMissileIndex", missileIndex);
		tag.putFloat("FlightArmorDamage", damage);
		tag.putInt("FlightArmorMaximumLife", maximumLifeTicks);
		tag.putDouble("FlightArmorMaximumTravel", maximumTravel);
		tag.putDouble("FlightArmorDistanceTravelled", distanceTravelled);
		tag.putDouble("FlightArmorLaunchX", launchOrigin.x);
		tag.putDouble("FlightArmorLaunchY", launchOrigin.y);
		tag.putDouble("FlightArmorLaunchZ", launchOrigin.z);
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		ownerId = readUuid(tag, "FlightArmorOwner");
		suitId = readUuid(tag, "FlightArmorSuit");
		carrierId = readUuid(tag, "FlightArmorCarrier");
		targetId = readUuid(tag, "FlightArmorTarget");
		actionSequence = tag.getLong("FlightArmorActionSequence");
		carrierRevision = tag.getLong("FlightArmorCarrierRevision");
		attackId = tag.getLong("FlightArmorAttackId");
		missileIndex = tag.getInt("FlightArmorMissileIndex");
		damage = tag.getFloat("FlightArmorDamage");
		if (tag.contains("FlightArmorMaximumLife")) maximumLifeTicks = tag.getInt("FlightArmorMaximumLife");
		if (tag.contains("FlightArmorMaximumTravel")) maximumTravel = tag.getDouble("FlightArmorMaximumTravel");
		distanceTravelled = tag.getDouble("FlightArmorDistanceTravelled");
		launchOrigin = new Vec3(tag.getDouble("FlightArmorLaunchX"), tag.getDouble("FlightArmorLaunchY"),
				tag.getDouble("FlightArmorLaunchZ"));
		if (!level().isClientSide) LIVE.add(this);
	}

	@Override
	public void remove(RemovalReason reason) {
		LIVE.remove(this);
		super.remove(reason);
	}

	/**
	 * Discards missiles for one exact physical carrier incarnation. A non-positive revision is a
	 * deliberate wildcard used only when the carrier itself is being destroyed.
	 */
	public static void discardByCarrier(UUID suitId, UUID carrierId, long carrierRevision) {
		if (suitId == null || carrierId == null) return;
		for (ArmorHomingMissileEntity missile : LIVE) {
			if (suitId.equals(missile.suitId)
					&& carrierId.equals(missile.carrierId)
					&& (carrierRevision <= 0L
							|| carrierRevision == missile.carrierRevision)) {
				missile.discardWithoutDamage();
			}
		}
	}

	public static void discardByOwner(UUID ownerId) {
		if (ownerId == null) return;
		for (ArmorHomingMissileEntity missile : LIVE) {
			if (ownerId.equals(missile.ownerId)) missile.discardWithoutDamage();
		}
	}

	public static void clearLiveIndex() {
		LIVE.clear();
	}

	long attackId() {
		return attackId;
	}

	int missileIndex() {
		return missileIndex;
	}

	UUID carrierId() {
		return carrierId;
	}

	Vec3 launchOrigin() {
		return launchOrigin;
	}

	private void discardWithoutDamage() {
		if (!isRemoved()) discard();
	}

	private static double segmentFraction(Vec3 start, Vec3 end, Vec3 point) {
		double length = start.distanceTo(end);
		return length < 1.0E-8D ? 0.0D : Math.max(0.0D, Math.min(1.0D, start.distanceTo(point) / length));
	}

	private static HomingMissileKinematics.Vector toPure(Vec3 vector) {
		return new HomingMissileKinematics.Vector(vector.x, vector.y, vector.z);
	}

	private static Vec3 fromPure(HomingMissileKinematics.Vector vector) {
		return new Vec3(vector.x(), vector.y(), vector.z());
	}

	private static void putUuid(CompoundTag tag, String key, UUID value) {
		if (value != null) tag.putUUID(key, value);
	}

	private static UUID readUuid(CompoundTag tag, String key) {
		return tag.contains(key) ? tag.getUUID(key) : null;
	}

	private record EntityCollision(Entity entity, double fraction) {
	}
}
