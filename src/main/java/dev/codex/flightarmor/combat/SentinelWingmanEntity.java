package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.network.ArmorAttackFxPayload;
import dev.codex.flightarmor.sound.ModSoundEvents;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A real, independently tracked Sentinel wingman.
 *
 * <p>Movement, target selection and damage run only on the logical server. Synced data exposes
 * identity, side, integrity and shot revisions to the renderer; it never grants client authority.
 * Wingmen are transient derivatives of a stable worn Sentinel configuration rather than another
 * inventory authority. They are therefore never world-saved: the controller recreates the exact
 * owner/suit/side pair only while that worn configuration remains authorized.</p>
 */
public final class SentinelWingmanEntity extends Entity {
	private static final EntityDataAccessor<String> OWNER_ID =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> SUIT_ID =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> SIDE =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> INTEGRITY =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> SHOT_SEQUENCE =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DEPLOYMENT_PHASE =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> PHASE_START_TICK =
			SynchedEntityData.defineId(SentinelWingmanEntity.class, EntityDataSerializers.LONG);

	private static final float MAXIMUM_INTEGRITY = 32.0F;
	private static final float SHOT_DAMAGE = 8.0F;
	private static final int BASE_ATTACK_COOLDOWN_TICKS = 18;
	private static final double HARD_REPOSITION_DISTANCE_SQR = 72.0D * 72.0D;
	public static final int PHASE_DEPLOYING = 0;
	public static final int PHASE_ACTIVE = 1;
	public static final int PHASE_RETURNING = 2;
	public static final long DEPLOY_DURATION_TICKS = 30L;
	public static final long DEPLOY_HANDOFF_START_TICKS = 10L;
	public static final long DEPLOY_HANDOFF_END_TICKS = 13L;
	public static final long DEPLOY_EJECT_END_TICKS = 21L;
	public static final long RECOMBINE_SHRINK_START_TICKS = 22L;
	public static final long RECOMBINE_DURATION_TICKS = 30L;
	public static final long RECOMBINE_HARD_TIMEOUT_TICKS = 80L;
	private static final double RECOMBINE_LOCK_DISTANCE_SQR = 0.14D * 0.14D;

	private long nextAttackTick;
	private long phaseStartTick;
	private UUID retaliationTargetId;
	private int missingIdentityTicks;
	private Vec3 clientPreviousNetworkPosition;
	private Vec3 clientVisualPosition;
	private Vec3 clientVisualPositionOld;
	private float clientVisualYaw;
	private float clientVisualYawOld;
	private float clientVisualPitch;
	private float clientVisualPitchOld;
	/**
	 * 转弯侧倾（度）。僚机此前只有 yaw+pitch，转向时是「原地抹头」；这个量让它压坡度进弯。
	 *
	 * <p>{@code BANK_PER_TURN_DEGREE} 把「每 tick 的偏航变化」换算成坡度；符号决定压向哪一侧。
	 * 实机若看到进弯时向外侧翻，把符号翻成 {@code 1.0F} 即可 —— 其余量都只是幅度。</p>
	 */
	private static final float BANK_PER_TURN_DEGREE = -1.40F;
	private static final float MAXIMUM_BANK_DEGREES = 30.0F;
	private static final float BANK_SMOOTHING = 0.20F;
	private float clientVisualRoll;
	private float clientVisualRollOld;
	private boolean clientVisualPoseInitialized;

	public SentinelWingmanEntity(EntityType<? extends SentinelWingmanEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
		noPhysics = true;
	}

	static SentinelWingmanEntity spawn(ServerLevel level, ServerPlayer owner, UUID suitId, int side) {
		SentinelWingmanEntity wingman =
				new SentinelWingmanEntity(ModCombatEntities.SENTINEL_WINGMAN, level);
		wingman.entityData.set(OWNER_ID, owner.getUUID().toString());
		wingman.entityData.set(SUIT_ID, suitId.toString());
		wingman.entityData.set(SIDE, normalizeSide(side));
		wingman.entityData.set(INTEGRITY, MAXIMUM_INTEGRITY);
		wingman.entityData.set(DEPLOYMENT_PHASE, PHASE_DEPLOYING);
		long now = level.getGameTime();
		long initialElapsed = SentinelWingmanController.initialDeploymentElapsed(owner, suitId, now);
		wingman.setPhaseStartTick(now - initialElapsed);
		Vec3 anchor = wingman.deploymentAnchor(owner, initialElapsed);
		wingman.setPos(anchor);
		wingman.setDeltaMovement(owner.getDeltaMovement());
		wingman.setYRot(owner.getYRot());
		wingman.yRotO = owner.getYRot();
		wingman.nextAttackTick = now + (side < 0 ? 7L : 13L);
		level.addFreshEntity(wingman);
		SentinelWingmanController.track(wingman);
		return wingman;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(OWNER_ID, "");
		builder.define(SUIT_ID, "");
		builder.define(SIDE, SentinelWingmanController.LEFT_SIDE);
		builder.define(INTEGRITY, MAXIMUM_INTEGRITY);
		builder.define(SHOT_SEQUENCE, 0);
		builder.define(DEPLOYMENT_PHASE, PHASE_DEPLOYING);
		builder.define(PHASE_START_TICK, 0L);
	}

	@Override
	public void tick() {
		super.tick();
		setNoGravity(true);
		noPhysics = true;
		if (level().isClientSide()) {
			tickClientVisualState();
			return;
		}
		if (!(level() instanceof ServerLevel level)) {
			recombine();
			return;
		}
		SentinelWingmanController.track(this);
		UUID ownerId = getOwnerId();
		UUID suitId = getSuitId();
		if (ownerId == null || suitId == null) {
			if (++missingIdentityTicks > 2) recombine();
			return;
		}
		missingIdentityTicks = 0;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.level() != level || !owner.isAlive()) {
			recombine();
			return;
		}
		long now = level.getGameTime();
		boolean deploymentAuthorized =
				SentinelWingmanController.isDeploymentAuthorized(owner, suitId, now);
		boolean combatAuthorized =
				SentinelWingmanController.isCombatAuthorized(owner, suitId, now);
		if (getDeploymentPhase() == PHASE_RETURNING) {
			if (deploymentAuthorized) {
				resumeDeployment(owner, now);
			} else {
				tickRecombine(owner, now);
				return;
			}
		}
		if (!deploymentAuthorized) {
			beginRecombine(owner, now);
			tickRecombine(owner, now);
			return;
		}

		Vec3 formation = formationAnchor(owner);
		Vec3 anchor;
		boolean socketCoupled = false;
		if (getDeploymentPhase() == PHASE_DEPLOYING) {
			long deploymentElapsed = Math.max(0L, now - phaseStartTick);
			float raw = Mth.clamp((float) deploymentElapsed
					/ (float) DEPLOY_DURATION_TICKS, 0.0F, 1.0F);
			anchor = deploymentAnchor(owner, deploymentElapsed);
			socketCoupled = deploymentElapsed <= DEPLOY_HANDOFF_END_TICKS;
			if (raw >= 1.0F) entityData.set(DEPLOYMENT_PHASE, PHASE_ACTIVE);
		} else {
			anchor = formation;
		}
		Vec3 error = anchor.subtract(position());
		if (socketCoupled) {
			// Keep the hidden/pre-release entity exactly on the physical socket. When render
			// ownership changes it starts with the same owner motion instead of chasing the back.
			setPos(anchor);
			setDeltaMovement(owner.getDeltaMovement());
		} else if (error.lengthSqr() > HARD_REPOSITION_DISTANCE_SQR) {
			setPos(anchor);
			setDeltaMovement(owner.getDeltaMovement());
		} else {
			Vec3 velocity = fromPure(SentinelWingmanKinematics.steer(
					toPure(getDeltaMovement()), toPure(error), toPure(owner.getDeltaMovement())));
			setDeltaMovement(velocity);
			setPos(position().add(velocity));
			if (velocity.horizontalDistanceSqr() > 1.0E-6D) {
				setYRot((float) (Mth.atan2(-velocity.x, velocity.z) * Mth.RAD_TO_DEG));
			}
		}
		if (socketCoupled) {
			setYRot(owner.getYRot());
		}

		if (tickCount % 20 == 0 && getIntegrity() < MAXIMUM_INTEGRITY) {
			setIntegrity(Math.min(MAXIMUM_INTEGRITY, getIntegrity() + 0.35F));
		}
		if (getDeploymentPhase() == PHASE_ACTIVE && combatAuthorized
				&& now >= nextAttackTick) {
			tryAutoAttack(level, owner, now);
		}
	}

	/**
	 * Updates only the render pose observed by the client.
	 *
	 * <p>The server sends this entity every tick. Moving it again here used to advance the same
	 * module along a second, slightly different path before the next packet corrected it. That
	 * packet/prediction tug-of-war was especially visible at the ends of the long wing plates.
	 * Client code must therefore never call {@code setPos}, {@code setDeltaMovement} or
	 * {@code setYRot} for a wingman. Vanilla tracking owns position interpolation; this method
	 * merely low-pass filters the visual yaw and pitch used by the renderer.</p>
	 */
	private void tickClientVisualState() {
		clientVisualYawOld = clientVisualYaw;
		clientVisualPitchOld = clientVisualPitch;
		clientVisualRollOld = clientVisualRoll;
		UUID ownerId = getOwnerId();
		Player owner = ownerId == null ? null : level().players().stream()
				.filter(player -> ownerId.equals(player.getUUID()))
				.findFirst()
				.orElse(null);

		Vec3 currentNetworkPosition = position();
		Vec3 observedStep = clientPreviousNetworkPosition == null
				? Vec3.ZERO : currentNetworkPosition.subtract(clientPreviousNetworkPosition);
		clientPreviousNetworkPosition = currentNetworkPosition;
		if (!isFinite(observedStep)) observedStep = Vec3.ZERO;

		long phaseElapsed = Math.max(0L, level().getGameTime() - getPhaseStartTick());
		boolean socketCoupled = getDeploymentPhase() == PHASE_DEPLOYING
				&& phaseElapsed <= DEPLOY_HANDOFF_END_TICKS;
		boolean stableFormation = getDeploymentPhase() == PHASE_ACTIVE || socketCoupled;
		Vec3 targetVisualPosition = currentNetworkPosition;
		if (owner != null) {
			if (getDeploymentPhase() == PHASE_ACTIVE) {
				targetVisualPosition = formationAnchor(owner);
			} else if (getDeploymentPhase() == PHASE_DEPLOYING) {
				targetVisualPosition = deploymentAnchor(owner, phaseElapsed);
			}
		}
		float targetYaw = getYRot();
		float targetPitch = 0.0F;
		if (stableFormation && owner != null) {
			// A deployed module holds the owner's heading instead of oscillating around tiny
			// left/right formation corrections.
			targetYaw = owner.getYRot();
		} else if (observedStep.horizontalDistanceSqr() > 2.5E-4D) {
			targetYaw = (float) (Mth.atan2(-observedStep.x, observedStep.z)
					* Mth.RAD_TO_DEG);
			targetPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(
					observedStep.y, Math.max(0.01D, observedStep.horizontalDistance()))),
					-20.0D, 20.0D);
		}

		if (!clientVisualPoseInitialized) {
			clientVisualPosition = targetVisualPosition;
			clientVisualPositionOld = targetVisualPosition;
			clientVisualYaw = targetYaw;
			clientVisualYawOld = targetYaw;
			clientVisualPitch = targetPitch;
			clientVisualPitchOld = targetPitch;
			clientVisualRoll = 0.0F;
			clientVisualRollOld = 0.0F;
			clientVisualPoseInitialized = true;
			return;
		}
		clientVisualPositionOld = clientVisualPosition;
		clientVisualPosition = targetVisualPosition;
		clientVisualYaw = Mth.rotLerp(0.22F, clientVisualYaw, targetYaw);
		clientVisualPitch = Mth.lerp(0.18F, clientVisualPitch, targetPitch);
		// 坡度取自「已平滑的视觉偏航」每 tick 的变化量，所以它天然滞后于转向、松杆后自动回正。
		float turnRate = Mth.wrapDegrees(clientVisualYaw - clientVisualYawOld);
		float targetRoll = Mth.clamp(turnRate * BANK_PER_TURN_DEGREE,
				-MAXIMUM_BANK_DEGREES, MAXIMUM_BANK_DEGREES);
		clientVisualRoll = Mth.lerp(BANK_SMOOTHING, clientVisualRoll, targetRoll);
	}

	private Vec3 formationAnchor(Player owner) {
		return formationAnchor(owner, owner.getDeltaMovement());
	}

	private Vec3 formationAnchor(Player owner, Vec3 ownerVelocity) {
		Vec3 facing = owner.getLookAngle();
		Vec3 forward = new Vec3(facing.x, 0.0D, facing.z);
		if (forward.lengthSqr() < 1.0E-6D) {
			float yaw = owner.getYRot() * Mth.DEG_TO_RAD;
			forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		} else {
			forward = forward.normalize();
		}
		Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
		double speed = ownerVelocity.horizontalDistance();
		double lateral = 2.15D + Math.min(0.55D, speed * 0.16D);
		double rearward = 0.82D + Math.min(1.20D, speed * 0.22D);
		// A long rigid wing amplifies even a small socket bob. Keep only a slow, restrained
		// anti-gravity float so it reads as hovering instead of positional jitter.
		double wave = Math.sin((owner.tickCount + getSide() * 17) * 0.055D) * 0.035D;
		return owner.position()
				.add(0.0D, 1.48D + wave, 0.0D)
				.add(right.scale(getSide() * lateral))
				.add(forward.scale(-rearward));
	}

	private Vec3 dockingAnchor(Player owner) {
		Vec3 facing = owner.getLookAngle();
		Vec3 forward = new Vec3(facing.x, 0.0D, facing.z);
		if (forward.lengthSqr() < 1.0E-6D) {
			float yaw = owner.getYRot() * Mth.DEG_TO_RAD;
			forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		} else {
			forward = forward.normalize();
		}
		Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
		/*
		 * Matches SentinelWingModuleMesh's body-local pivot (±4.16, 12.10, 4.92)
		 * after the vanilla humanoid 1/16 scale and Y flip. Keeping the simulation anchor on
		 * the authored pivot makes the binary body/entity matrix hand-off visually continuous.
		 */
		return owner.position().add(0.0D, 0.745D, 0.0D)
				.add(forward.scale(-0.3075D))
				.add(right.scale(getSide() * 0.26D));
	}

	private Vec3 deploymentAnchor(Player owner, double elapsed) {
		return deploymentAnchor(owner, owner.getDeltaMovement(), elapsed);
	}

	private Vec3 deploymentAnchor(Player owner, Vec3 ownerVelocity, double elapsed) {
		Vec3 start = dockingAnchor(owner);
		if (elapsed <= DEPLOY_HANDOFF_END_TICKS) return start;
		Vec3 end = formationAnchor(owner, ownerVelocity);
		float raw = Mth.clamp((float) (elapsed - DEPLOY_HANDOFF_END_TICKS)
				/ (float) (DEPLOY_DURATION_TICKS - DEPLOY_HANDOFF_END_TICKS),
				0.0F, 1.0F);
		float eased = raw * raw * (3.0F - 2.0F * raw);
		Vec3 delta = end.subtract(start);
		Vec3 controlA = start.add(delta.scale(0.24D)).add(0.0D, 0.48D, 0.0D);
		Vec3 controlB = end.add(delta.scale(-0.18D)).add(0.0D, 0.24D, 0.0D);
		return cubicBezier(start, controlA, controlB, end, eased);
	}

	/**
	 * Recall is deliberately not a straight teleport to the back. The module first brakes into
	 * a high, outboard capture corridor, then arcs inward and performs the last eight ticks close
	 * to the docking rail while its visible panels refold.
	 */
	private Vec3 returnApproachAnchor(Player owner, Vec3 ownerVelocity, double elapsed) {
		Vec3 dock = dockingAnchor(owner);
		Vec3 facing = owner.getLookAngle();
		Vec3 forward = new Vec3(facing.x, 0.0D, facing.z);
		if (forward.lengthSqr() < 1.0E-6D) {
			float yaw = owner.getYRot() * Mth.DEG_TO_RAD;
			forward = new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
		} else {
			forward = forward.normalize();
		}
		Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
		Vec3 capture = dock
				.add(right.scale(getSide() * 1.16D))
				.add(forward.scale(-0.58D))
				.add(0.0D, 0.42D, 0.0D)
				.add(ownerVelocity.scale(0.12D));
		if (elapsed <= 8.0D) return capture;
		float raw = Mth.clamp((float) ((elapsed - 8.0D) / 14.0D), 0.0F, 1.0F);
		float eased = raw * raw * (3.0F - 2.0F * raw);
		Vec3 controlA = capture
				.add(right.scale(getSide() * 0.28D))
				.add(0.0D, 0.22D, 0.0D);
		Vec3 controlB = dock
				.add(right.scale(getSide() * 0.20D))
				.add(forward.scale(-0.16D))
				.add(0.0D, 0.08D, 0.0D);
		return cubicBezier(capture, controlA, controlB, dock, eased);
	}

	void beginRecombine(ServerPlayer owner, long now) {
		if (isRemoved() || getDeploymentPhase() == PHASE_RETURNING) return;
		entityData.set(DEPLOYMENT_PHASE, PHASE_RETURNING);
		setPhaseStartTick(now);
		nextAttackTick = Long.MAX_VALUE;
	}

	private void resumeDeployment(ServerPlayer owner, long now) {
		if (getDeploymentPhase() != PHASE_RETURNING) return;
		long commandElapsed = SentinelWingmanController.initialDeploymentElapsed(
				owner, getSuitId(), now);
		long elapsed = Math.max(DEPLOY_HANDOFF_END_TICKS,
				Math.min(DEPLOY_DURATION_TICKS, commandElapsed));
		entityData.set(DEPLOYMENT_PHASE, PHASE_DEPLOYING);
		setPhaseStartTick(now - elapsed);
		nextAttackTick = now + (getSide() < 0 ? 7L : 13L);
	}

	private void tickRecombine(ServerPlayer owner, long now) {
		long elapsed = Math.max(0L, now - phaseStartTick);
		Vec3 anchor = returnApproachAnchor(owner, owner.getDeltaMovement(), elapsed);
		Vec3 error = anchor.subtract(position());
		double dockDistanceSqr = dockingAnchor(owner).distanceToSqr(position());
		if ((elapsed >= RECOMBINE_DURATION_TICKS
				&& dockDistanceSqr <= RECOMBINE_LOCK_DISTANCE_SQR)
				|| elapsed >= RECOMBINE_HARD_TIMEOUT_TICKS) {
			if (getSide() == SentinelWingmanController.LEFT_SIDE) {
				owner.level().playSound(null, owner.getX(), owner.getY(), owner.getZ(),
						ModSoundEvents.ASSEMBLY_LOCK_TORSO, SoundSource.PLAYERS,
						0.78F, 1.04F);
			}
			recombine();
			return;
		}
		Vec3 velocity = fromPure(SentinelWingmanKinematics.steer(
				toPure(getDeltaMovement().scale(0.72D)), toPure(error.scale(1.32D)),
				toPure(owner.getDeltaMovement())));
		setDeltaMovement(velocity);
		setPos(position().add(velocity));
		if (velocity.horizontalDistanceSqr() > 1.0E-6D) {
			setYRot((float) (Mth.atan2(-velocity.x, velocity.z) * Mth.RAD_TO_DEG));
		}
	}

	private void tryAutoAttack(ServerLevel level, ServerPlayer owner, long now) {
		ArmorEscortProfile profile = ArmorCoreController.escortProfile(owner, getSuitId());
		ArmorEscortProfile.CombatPolicy policy = profile.attackMode();
		if (policy == ArmorEscortProfile.CombatPolicy.OFF) {
			nextAttackTick = now + 10L;
			return;
		}
		double targetRange = profile.rangeBlocks();
		double targetRangeSqr = targetRange * targetRange;
		LivingEntity target = level.getEntitiesOfClass(LivingEntity.class,
						AABB.ofSize(position(), targetRange * 2.0D, targetRange * 2.0D,
								targetRange * 2.0D),
						candidate -> isThreat(candidate, owner, policy) && candidate.isAlive()
								&& !candidate.isSpectator()
								&& CombatTargetPolicy.canDamage(owner, candidate))
				.stream()
				.filter(candidate -> distanceToSqr(candidate) <= targetRangeSqr)
				.filter(candidate -> hasClearShot(level, candidate))
				.min(Comparator.comparingDouble(this::distanceToSqr))
				.orElse(null);
		if (target == null) {
			nextAttackTick = now + 6L;
			return;
		}

		Vec3 origin = position().add(0.0D, 0.04D, 0.0D);
		Vec3 endpoint = target.getBoundingBox().getCenter();
		boolean hurt = target.hurtOrSimulate(ModDamageTypes.coreBeam(owner, this), SHOT_DAMAGE);
		if (hurt) {
			target.knockback(0.18D, origin.x - target.getX(), origin.z - target.getZ(), null, 0.0F);
		}
		entityData.set(SHOT_SEQUENCE, entityData.get(SHOT_SEQUENCE) + 1);
		broadcastShot(level, owner, target, origin, endpoint);
		nextAttackTick = now + BASE_ATTACK_COOLDOWN_TICKS + (getSide() < 0 ? 0L : 4L);
	}

	private boolean isThreat(LivingEntity candidate, ServerPlayer owner,
			ArmorEscortProfile.CombatPolicy policy) {
		if (policy == ArmorEscortProfile.CombatPolicy.ALL) return true;
		// Enemy remains the default autonomous policy. A non-Enemy is admitted only after
		// actually attacking the owner/wingman (or being the owner's most recent attacker), so
		// passive creatures, pets and friendly players are never selected merely for proximity.
		return candidate instanceof Enemy
				|| owner.getLastHurtByMob() == candidate
				|| candidate.getLastHurtMob() == owner
				|| candidate.getUUID().equals(retaliationTargetId);
	}

	private boolean hasClearShot(ServerLevel level, LivingEntity target) {
		Vec3 origin = position().add(0.0D, 0.04D, 0.0D);
		Vec3 endpoint = target.getBoundingBox().getCenter();
		HitResult block = level.clip(new ClipContext(origin, endpoint,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return block.getType() == HitResult.Type.MISS
				|| block.getLocation().distanceToSqr(origin) + 0.04D
						>= endpoint.distanceToSqr(origin);
	}

	private void broadcastShot(ServerLevel level, ServerPlayer owner, LivingEntity target,
			Vec3 origin, Vec3 endpoint) {
		Vec3 normal = endpoint.subtract(origin);
		normal = normal.lengthSqr() < 1.0E-8D
				? new Vec3(0.0D, 1.0D, 0.0D) : normal.normalize().scale(-1.0D);
		ArmorAttackFxPayload payload = new ArmorAttackFxPayload(
				getUUID(), level.dimension().identifier(), getShotSequence(),
				0, ArmorAttackType.CRUISE_CORE_BEAM, ArmorVariant.SENTINEL,
				getSide() < 0, origin, endpoint, normal, ArmorAttackHitKind.ENTITY,
				target.getId(), 0.58F, isInWater());
		Set<ServerPlayer> recipients = new HashSet<>(PlayerLookup.tracking(this));
		recipients.add(owner);
		for (ServerPlayer recipient : recipients) {
			if (recipient.level() == level
					&& ServerPlayNetworking.canSend(recipient, ArmorAttackFxPayload.TYPE)) {
				ServerPlayNetworking.send(recipient, payload);
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel serverLevel, DamageSource source, float amount) {
		if (isRemoved() || amount <= 0.0F) return false;
		Entity attacker = source.getEntity();
		UUID ownerId = getOwnerId();
		if (attacker != null && ownerId != null) {
			if (ownerId.equals(attacker.getUUID())) return false;
			ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(ownerId);
			if (owner != null && owner.isAlliedTo(attacker)) return false;
			if (owner != null && attacker instanceof LivingEntity
					&& CombatTargetPolicy.canDamage(owner, attacker)) {
				retaliationTargetId = attacker.getUUID();
				nextAttackTick = Math.min(nextAttackTick, serverLevel.getGameTime() + 2L);
			}
		}
		float effective = source.is(DamageTypeTags.IS_EXPLOSION) ? amount * 0.45F : amount;
		setIntegrity(getIntegrity() - Math.max(0.5F, effective));
		markHurt();
		if (getIntegrity() <= 0.0F) {
			SentinelWingmanController.markDestroyed(this, serverLevel.getGameTime());
			discard();
		}
		return true;
	}

	@Override
	public boolean hurtClient(DamageSource source) {
		markHurt();
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
		return 0.42F;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		UUID ownerId = getOwnerId();
		UUID suitId = getSuitId();
		if (ownerId != null) output.store("FlightArmorOwner", UUIDUtil.CODEC, ownerId);
		if (suitId != null) output.store("FlightArmorSuit", UUIDUtil.CODEC, suitId);
		output.putInt("FlightArmorSide", getSide());
		output.putFloat("FlightArmorIntegrity", getIntegrity());
		output.putInt("FlightArmorShotSequence", getShotSequence());
		output.putLong("FlightArmorNextAttack", nextAttackTick);
		if (retaliationTargetId != null) {
			output.store("FlightArmorRetaliationTarget", UUIDUtil.CODEC, retaliationTargetId);
		}
		output.putInt("FlightArmorDeploymentPhase", getDeploymentPhase());
		output.putLong("FlightArmorPhaseStart", getPhaseStartTick());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		entityData.set(OWNER_ID, input.read("FlightArmorOwner", UUIDUtil.CODEC).map(UUID::toString).orElse(""));
		entityData.set(SUIT_ID, input.read("FlightArmorSuit", UUIDUtil.CODEC).map(UUID::toString).orElse(""));
		entityData.set(SIDE, normalizeSide(input.getIntOr("FlightArmorSide", 0)));
		entityData.set(INTEGRITY, Mth.clamp(
				input.getFloatOr("FlightArmorIntegrity", MAXIMUM_INTEGRITY), 0.5F, MAXIMUM_INTEGRITY));
		entityData.set(SHOT_SEQUENCE, Math.max(0, input.getIntOr("FlightArmorShotSequence", 0)));
		nextAttackTick = Math.max(0L, input.getLongOr("FlightArmorNextAttack", 0L));
		retaliationTargetId = input.read("FlightArmorRetaliationTarget", UUIDUtil.CODEC).orElse(null);
		entityData.set(DEPLOYMENT_PHASE, Mth.clamp(
				input.getIntOr("FlightArmorDeploymentPhase", PHASE_DEPLOYING), PHASE_DEPLOYING, PHASE_RETURNING));
		setPhaseStartTick(Math.max(0L, input.getLongOr("FlightArmorPhaseStart", 0L)));
		SentinelWingmanController.track(this);
	}

	@Override
	public void remove(RemovalReason reason) {
		SentinelWingmanController.untrack(this);
		super.remove(reason);
	}

	public void recombine() {
		if (!isRemoved()) discard();
	}

	public UUID getOwnerId() {
		String s = entityData.get(OWNER_ID);
		return s.isEmpty() ? null : UUID.fromString(s);
	}

	public UUID getSuitId() {
		String s = entityData.get(SUIT_ID);
		return s.isEmpty() ? null : UUID.fromString(s);
	}

	public int getSide() {
		return normalizeSide(entityData.get(SIDE));
	}

	public float getIntegrity() {
		return entityData.get(INTEGRITY);
	}

	public int getShotSequence() {
		return entityData.get(SHOT_SEQUENCE);
	}

	public int getDeploymentPhase() {
		return entityData.get(DEPLOYMENT_PHASE);
	}

	public long getPhaseStartTick() {
		return entityData.get(PHASE_START_TICK);
	}

	public float getClientVisualYaw(float partialTick) {
		if (!clientVisualPoseInitialized) return getYRot();
		return Mth.rotLerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				clientVisualYawOld, clientVisualYaw);
	}

	public float getClientVisualPitch(float partialTick) {
		if (!clientVisualPoseInitialized) return 0.0F;
		return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				clientVisualPitchOld, clientVisualPitch);
	}

	public float getClientVisualRoll(float partialTick) {
		if (!clientVisualPoseInitialized) return 0.0F;
		return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				clientVisualRollOld, clientVisualRoll);
	}

	/**
	 * Returns a world-axis render correction that cancels packet-time changes to the vanilla
	 * entity transform. The dispatcher may observe a teleport between two frames; the wing mesh
	 * instead follows the once-per-client-tick visual samples above.
	 */
	public Vec3 getClientVisualRenderOffset(float partialTick) {
		if (!clientVisualPoseInitialized || clientVisualPosition == null
				|| clientVisualPositionOld == null) {
			return Vec3.ZERO;
		}
		double t = Mth.clamp(partialTick, 0.0F, 1.0F);
		Vec3 visual = clientVisualPositionOld.add(
				clientVisualPosition.subtract(clientVisualPositionOld).scale(t));
		Vec3 vanilla = new Vec3(
				xOld + (getX() - xOld) * t,
				yOld + (getY() - yOld) * t,
				zOld + (getZ() - zOld) * t);
		Vec3 offset = visual.subtract(vanilla);
		return isFinite(offset) ? offset : Vec3.ZERO;
	}

	SentinelWingmanController.WingmanKey key() {
		UUID ownerId = getOwnerId();
		UUID suitId = getSuitId();
		return ownerId == null || suitId == null ? null
				: new SentinelWingmanController.WingmanKey(ownerId, suitId, getSide());
	}

	private void setIntegrity(float integrity) {
		entityData.set(INTEGRITY, Mth.clamp(integrity, 0.0F, MAXIMUM_INTEGRITY));
	}

	private void setPhaseStartTick(long tick) {
		phaseStartTick = Math.max(0L, tick);
		entityData.set(PHASE_START_TICK, phaseStartTick);
	}

	private static int normalizeSide(int side) {
		return side < 0 ? SentinelWingmanController.LEFT_SIDE
				: SentinelWingmanController.RIGHT_SIDE;
	}

	private static Vec3 cubicBezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
		double clamped = Mth.clamp(t, 0.0D, 1.0D);
		double inverse = 1.0D - clamped;
		return a.scale(inverse * inverse * inverse)
				.add(b.scale(3.0D * inverse * inverse * clamped))
				.add(c.scale(3.0D * inverse * clamped * clamped))
				.add(d.scale(clamped * clamped * clamped));
	}

	private static boolean isFinite(Vec3 value) {
		return value != null
				&& Double.isFinite(value.x)
				&& Double.isFinite(value.y)
				&& Double.isFinite(value.z);
	}

	private static SentinelWingmanKinematics.Vector toPure(Vec3 value) {
		return new SentinelWingmanKinematics.Vector(value.x, value.y, value.z);
	}

	private static Vec3 fromPure(SentinelWingmanKinematics.Vector value) {
		return new Vec3(value.x(), value.y(), value.z());
	}
}
