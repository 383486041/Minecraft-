package dev.codex.flightarmor.cruise;

import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatController;
import dev.codex.flightarmor.combat.ArmorCombatTuning;
import dev.codex.flightarmor.combat.ArmorHomingMissileEntity;
import dev.codex.flightarmor.combat.CombatTargetPolicy;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.network.ArmorCruiseCombatCommandPayload;
import dev.codex.flightarmor.network.ArmorCruiseCombatStatePayload;
import dev.codex.flightarmor.network.ArmorCruiseStatePayload;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Rotations;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ArmorCruiseCombatController {
	private static final int SCAN_TICKS=5, LOCK_TICKS=6, DIVE_TICKS=24;
	private static final int TRACK_TICKS=10, ESCAPE_TICKS=12, HOLD_TICKS=12, RETRY_TICKS=4;
	private static final int RAM_CLIMB_TIMEOUT_TICKS=48, RAM_PULL_UP_TIMEOUT_TICKS=28;
	private static final int RAM_ANALYZE_TICKS=6, RAM_WRIST_WINDUP_TICKS=3, RAM_WRIST_HOLD_TICKS=7;
	private static final int SLAM_HOVER_TICKS=6;
	private static final int SLAM_DROP_TIMEOUT_TICKS=24, SLAM_IMPACT_HOLD_TICKS=5;
	private static final int SLAM_RECOVER_TIMEOUT_TICKS=28, SLAM_REUSE_COOLDOWN_TICKS=140;
	private static final double SWEEP_PADDING=0.22D;
	private static final Map<CombatKey,CombatState> STATES=new ConcurrentHashMap<>();
	private static final AtomicLong NEXT_REVISION=new AtomicLong(), NEXT_ACTION=new AtomicLong();

	private ArmorCruiseCombatController(){}

	public static void handleCommand(ServerPlayer owner, ArmorCruiseCombatCommandPayload payload){
		ArmorCruiseController.CombatHandle handle=ArmorCruiseController.combatHandle(owner);
		long now=owner.serverLevel().getGameTime();
		if(handle==null||handle.phase()!=ArmorCruisePhase.CRUISING){
			for(Map.Entry<CombatKey,CombatState> e:ownerStates(owner.getUUID())){
				e.getValue().mode=CruiseCombatMode.PASSIVE;
				invalidate(e.getKey(),e.getValue(),true);
				setBehavior(owner,null,e.getValue(),CruiseCombatBehavior.PASSIVE,null,now,1);
			}
			return;
		}
		CombatKey key=combatKey(owner,handle);
		CombatState state=STATES.computeIfAbsent(key,k->new CombatState());
		ensureIdentity(key,state,handle);
		if(payload.clientSequence()<=state.lastClientSequence)return;
		state.lastClientSequence=payload.clientSequence();
		if(handle.sequenceId()!=payload.cruiseSequenceId()
				||!handle.carrier().getUUID().equals(payload.carrierId())){
			sync(owner,handle,state,now);
			return;
		}
		state.mode=payload.requestedMode()==CruiseCombatMode.INVALID
				?CruiseCombatMode.PASSIVE:payload.requestedMode();
		invalidate(key,state,true);
		state.behavior=state.mode==CruiseCombatMode.GUARD
				?CruiseCombatBehavior.SEARCHING:CruiseCombatBehavior.PASSIVE;
		state.revision=NEXT_REVISION.incrementAndGet();
		sync(owner,handle,state,now);
	}

	/**
	 * Advances autonomous combat and reports whether combat owns carrier movement for this tick.
	 * Patrol movement must run only when this method returns {@code false}; otherwise two controllers
	 * would write the carrier position in the same server tick.
	 */
	public static boolean tick(ServerPlayer owner, ArmorCruiseController.CombatHandle handle,long now){
		if(handle==null||handle.phase()!=ArmorCruisePhase.CRUISING){
			cancel(owner,"not_cruising");
			return false;
		}
		CombatKey key=combatKey(owner,handle);
		CombatState state=STATES.computeIfAbsent(key,k->new CombatState());
		ensureIdentity(key,state,handle);
		ArmorCombatController.tickCruiseResources(owner,handle.carrier());
		ArmorEscortProfile profile=ArmorCoreController.escortProfile(owner,key.suitId());
		if(state.mode!=CruiseCombatMode.GUARD
				||profile.attackMode()==ArmorEscortProfile.CombatPolicy.OFF){
			if(state.phase!=IndependentCruiseTactics.Phase.IDLE)invalidate(key,state,true);
			ArmorCruiseController.setCruisePose(handle.carrier());
			setBehavior(owner,handle,state,CruiseCombatBehavior.PASSIVE,null,now,1);
			return false;
		}
		ArmorVariant variant=ModItems.fullSetVariant(handle.carrier()).orElse(null);
		if(variant==null){
			invalidate(key,state,true);
			setBehavior(owner,handle,state,CruiseCombatBehavior.SEARCHING,null,now,SCAN_TICKS);
			return false;
		}
		if(state.variant!=null&&state.variant!=variant
				&&state.phase!=IndependentCruiseTactics.Phase.IDLE)invalidate(key,state,true);
		Vec3 anchor=combatAnchor(owner,handle,profile);
		LivingEntity target=resolveTarget(owner,handle,profile,anchor,state);
		if(state.phase==IndependentCruiseTactics.Phase.COOLDOWN){
			if(now<state.cooldownUntil){
				ArmorCruiseController.setCruisePose(handle.carrier());
				setBehavior(owner,handle,state,CruiseCombatBehavior.COOLDOWN,target,now,
						(int)Math.max(1L,state.cooldownUntil-now));
				return false;
			}
			state.phase=IndependentCruiseTactics.Phase.IDLE;
			state.phaseStart=now;
		}
		if(state.phase==IndependentCruiseTactics.Phase.IDLE){
			if(target==null||now>=state.nextScan){
				target=selectTarget(owner,handle,profile,anchor,state,now);
				state.nextScan=now+SCAN_TICKS;
			}
			if(target==null){
				state.targetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID;
				ArmorCruiseController.setCruisePose(handle.carrier());
				setBehavior(owner,handle,state,CruiseCombatBehavior.SEARCHING,null,now,SCAN_TICKS);
				return false;
			}
			startAction(key,state,variant,target,handle,profile,anchor,now);
		}
		target=resolveTarget(owner,handle,profile,anchor,state);
		if(target==null&&variant!=ArmorVariant.VANGUARD){
			invalidate(key,state,true);
			ArmorCruiseController.setCruisePose(handle.carrier());
			setBehavior(owner,handle,state,CruiseCombatBehavior.SEARCHING,null,now,SCAN_TICKS);
			return false;
		}
		switch(variant){
			case VANGUARD->tickVanguard(owner,handle,key,state,profile,anchor,target,now);
			case SWIFT->tickSwift(owner,handle,key,state,profile,anchor,target,now);
			case SENTINEL->tickSentinel(owner,handle,key,state,profile,anchor,target,now);
		}
		return true;
	}

	public static boolean isDelayedAttackValid(ServerPlayer owner,UUID suitId,UUID carrierId,
			long actionSequence,long carrierRevision,LivingEntity target){
		if(owner==null||suitId==null||carrierId==null||target==null
				||actionSequence<=0L||carrierRevision<=0L)return false;
		CombatState state=STATES.get(new CombatKey(owner.getUUID(),suitId));
		if(state==null||state.mode!=CruiseCombatMode.GUARD
				||state.validMissileSequence!=actionSequence
				||state.carrierRevision!=carrierRevision
				||!carrierId.equals(state.carrierId)||state.targetId!=target.getId())return false;
		ArmorCruiseController.CombatHandle handle=ArmorCruiseController.combatHandle(owner);
		if(handle==null||handle.phase()!=ArmorCruisePhase.CRUISING
				||handle.sequenceId()!=carrierRevision
				||!handle.carrier().getUUID().equals(carrierId)
				||owner.level()!=target.level())return false;
		ArmorCruiseController.CruiseRuntimeView view=ArmorCruiseController.runtimeView(owner).orElse(null);
		if(view==null||!view.loaded()||!suitId.equals(view.suitId())
				||view.phase()!=ArmorCruisePhase.CRUISING||view.sequenceId()!=carrierRevision
				||!carrierId.equals(view.carrierId())
				||!view.dimension().equals(owner.level().dimension()))return false;
		ArmorEscortProfile profile=ArmorCoreController.escortProfile(owner,suitId);
		return profile.attackMode()!=ArmorEscortProfile.CombatPolicy.OFF
				&&validTarget(owner,handle,profile,combatAnchor(owner,handle,profile),target);
	}

	public static void cancel(ServerPlayer owner,String reason){
		ArmorCruiseController.CombatHandle active=ArmorCruiseController.combatHandle(owner);
		long now=owner.serverLevel().getGameTime();
		for(Map.Entry<CombatKey,CombatState> e:ownerStates(owner.getUUID())){
			CombatState state=e.getValue();
			state.mode=CruiseCombatMode.PASSIVE;
			invalidate(e.getKey(),state,true);
			ArmorCruiseController.CombatHandle shown=active!=null
					&&active.carrier().getUUID().equals(state.carrierId)?active:null;
			setBehavior(owner,shown,state,CruiseCombatBehavior.PASSIVE,null,now,1);
		}
	}

	public static void cancelByOwnerId(UUID ownerId){
		if(ownerId==null)return;
		for(CombatKey key:List.copyOf(STATES.keySet())){
			if(key.ownerId().equals(ownerId)){
				CombatState state=STATES.remove(key);
				if(state!=null)invalidate(key,state,true);
			}
		}
	}

	public static boolean isGuardEnabled(ServerPlayer owner){
		for(Map.Entry<CombatKey,CombatState> e:ownerStates(owner.getUUID()))
			if(e.getValue().mode==CruiseCombatMode.GUARD)return true;
		return false;
	}
	private static void tickVanguard(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatKey key,CombatState state,ArmorEscortProfile profile,Vec3 anchor,
			LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		switch(state.phase){
			case VANGUARD_CLIMB->{
				if(target==null){
					beginVanguardPullUp(handle,state,profile,anchor,now);
					return;
				}
				if(state.ramStagingPoint==null)prepareRamClimb(handle,state,target,profile,anchor,now);
				aim(handle.carrier(),target);
				setRamClimbPose(handle.carrier());
				move(handle.carrier(),state.ramStagingPoint,0.88);
				double altitude=handle.carrier().getY()-target.getBoundingBox().maxY;
				double stageDistance=handle.carrier().position().distanceTo(state.ramStagingPoint);
				setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,
						RAM_CLIMB_TIMEOUT_TICKS);
				if(VanguardRamKinematics.ramStagingReady(elapsed,altitude,stageDistance)){
					transition(state,IndependentCruiseTactics.Phase.VANGUARD_LOCK,now);
					handle.carrier().setDeltaMovement(Vec3.ZERO);
				}else if(elapsed>=RAM_CLIMB_TIMEOUT_TICKS){
					beginVanguardPullUp(handle,state,profile,anchor,now);
				}
			}
			case VANGUARD_LOCK->{
				if(target==null){
					beginVanguardPullUp(handle,state,profile,anchor,now);
					return;
				}
				holdCarrier(handle.carrier());
				aim(handle.carrier(),target);
				setRamLockPose(handle.carrier());
				setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,LOCK_TICKS);
				if(elapsed>=LOCK_TICKS){
					Vec3 targetMotion=target.getDeltaMovement();
					if(targetMotion.length()>0.60D)targetMotion=targetMotion.normalize().scale(0.60D);
					state.intercept=target.getBoundingBox().getCenter().add(targetMotion.scale(4));
					Vec3 direction=state.intercept.subtract(handle.carrier().getBoundingBox().getCenter());
					if(!VanguardRamKinematics.ramDiveDirectionReady(
							direction.x,direction.y,direction.z)){
						if(++state.ramReplanCount<=2){
							prepareRamClimb(handle,state,target,profile,anchor,now);
						}else{
							beginVanguardPullUp(handle,state,profile,anchor,now);
						}
						return;
					}
					state.diveDirection=direction.normalize();
					transition(state,IndependentCruiseTactics.Phase.VANGUARD_DIVE,now);
				}
			}
			case VANGUARD_DIVE->{
				setBehavior(owner,handle,state,CruiseCombatBehavior.FIRING,target,now,DIVE_TICKS);
				tickDive(owner,handle,state,profile,anchor,target,now);
			}
			case VANGUARD_PULL_UP->{
				tickVanguardPullUp(owner,handle,state,profile,anchor,target,now);
			}
			case VANGUARD_ANALYZE->{
				tickVanguardAnalyze(owner,handle,state,profile,anchor,target,now);
			}
			case VANGUARD_WRIST->{
				if(target==null){
					target=selectTarget(owner,handle,profile,anchor,state,now);
					if(target==null){
						finishCooldown(owner,handle,state,null,now,12);
						return;
					}
				}
				setRamWristPose(handle.carrier(),state.leftHand);
				aim(handle.carrier(),target);
				boolean weaponCommitted=state.completedWeapon==state.actionSequence;
				setBehavior(owner,handle,state,weaponCommitted
								?CruiseCombatBehavior.FIRING:CruiseCombatBehavior.AIMING,
						target,now,RAM_WRIST_HOLD_TICKS);
				if(elapsed<RAM_WRIST_WINDUP_TICKS)return;
				if(IndependentCruiseTactics.canCommit(state.actionSequence,state.completedWeapon)){
					ArmorCombatController.CruiseWristResult result=ArmorCombatController.tryFireCruiseWrist(
							owner,handle.carrier(),target,
							handle.carrier().isEyeInFluid(FluidTags.WATER));
					if(!result.fired()&&elapsed<RETRY_TICKS){
						setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,RETRY_TICKS);
						return;
					}
					state.completedWeapon=state.actionSequence;
					if(result.fired()){
						state.serverAttackId=result.serverAttackId();
						state.leftHand=result.leftHand();
						setBehavior(owner,handle,state,CruiseCombatBehavior.FIRING,target,now,
								Math.max(1,RAM_WRIST_HOLD_TICKS-(int)elapsed));
					}
				}
				if(elapsed>=RAM_WRIST_HOLD_TICKS)finishCooldown(owner,handle,state,target,now,24);
			}
			case VANGUARD_SLAM_CLIMB->{
				tickSlamClimb(owner,handle,state,target,now);
			}
			case VANGUARD_SLAM_HOVER->{
				tickSlamHover(owner,handle,state,target,now);
			}
			case VANGUARD_SLAM_DROP->{
				tickSlamDrop(owner,handle,state,profile,anchor,target,now);
			}
			case VANGUARD_SLAM_IMPACT->{
				tickSlamImpact(owner,handle,state,profile,anchor,target,now);
			}
			case VANGUARD_SLAM_RECOVER->{
				tickSlamRecover(owner,handle,state,profile,anchor,target,now);
			}
			default->invalidate(key,state,true);
		}
	}

	private static void tickDive(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatState state,ArmorEscortProfile profile,Vec3 anchor,
			LivingEntity target,long now){
		ArmorStand carrier=handle.carrier();
		long elapsed=now-state.phaseStart;
		if(target==null||state.intercept==null
				||!VanguardRamKinematics.ramDiveDirectionReady(
						state.diveDirection.x,state.diveDirection.y,state.diveDirection.z)){
			beginVanguardPullUp(handle,state,profile,anchor,now);
			return;
		}
		double speed=VanguardRamKinematics.diveSpeed(elapsed);
		Vec3 delta=state.diveDirection.scale(speed);
		setRamDivePose(carrier);
		Vec3 center=carrier.getBoundingBox().getCenter();
		AABB carrierBox=carrier.getBoundingBox(), targetBox=target.getBoundingBox();
		VanguardRamKinematics.SweepResult sweep=VanguardRamKinematics.sweep(
				center.x,center.y,center.z,carrierBox.getXsize()*0.5,carrierBox.getYsize()*0.5,
				carrierBox.getZsize()*0.5,delta.x,delta.y,delta.z,
				targetBox.minX,targetBox.minY,targetBox.minZ,
				targetBox.maxX,targetBox.maxY,targetBox.maxZ,SWEEP_PADDING);
		double block=blockFraction(carrier,center,center.add(delta));
		if(sweep.hit()&&sweep.fraction()<=block){
			carrier.setPos(carrier.position().add(delta.scale(sweep.fraction())));
			carrier.setDeltaMovement(delta);
			carrier.hasImpulse=true;
			if(IndependentCruiseTactics.canCommit(state.actionSequence,state.completedRam)){
				ArmorCombatController.CruiseWeaponResult result=
						ArmorCombatController.tryCruiseImpact(owner,carrier,target,delta);
				state.completedRam=state.actionSequence;
				state.lastRamConnected=result.fired();
				if(result.fired())state.serverAttackId=result.serverAttackId();
			}
			beginVanguardPullUp(handle,state,profile,anchor,now);
			return;
		}
		if(block<1){
			carrier.setPos(carrier.position().add(delta.scale(Math.max(0,block-0.05))));
			carrier.setDeltaMovement(Vec3.ZERO);
			beginVanguardPullUp(handle,state,profile,anchor,now);
			return;
		}
		carrier.setPos(carrier.position().add(delta));
		carrier.setDeltaMovement(delta);
		carrier.hasImpulse=true;
		Vec3 end=center.add(delta);
		if(elapsed>=DIVE_TICKS||VanguardRamKinematics.passedInterceptPlane(
				end.x,end.y,end.z,state.intercept.x,state.intercept.y,state.intercept.z,
				state.diveDirection.x,state.diveDirection.y,state.diveDirection.z))
			beginVanguardPullUp(handle,state,profile,anchor,now);
	}

	private static void prepareRamClimb(ArmorCruiseController.CombatHandle handle,
			CombatState state,LivingEntity target,ArmorEscortProfile profile,Vec3 anchor,long now){
		ArmorStand carrier=handle.carrier();
		Vec3 targetCenter=target.getBoundingBox().getCenter();
		Vec3 away=horizontalAway(target.position(),carrier.position());
		double stageY=Math.max(carrier.getY()+6.0D,target.getBoundingBox().maxY+8.0D);
		stageY=clampFlightY(carrier,stageY);
		Vec3 desired=new Vec3(targetCenter.x+away.x*6.5D,stageY,
				targetCenter.z+away.z*6.5D);
		state.ramStagingPoint=boundedHorizontal(desired,anchor,vanguardMovementRadius(profile));
		state.intercept=null;
		state.diveDirection=Vec3.ZERO;
		state.recoveryPoint=null;
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_CLIMB,now);
	}

	private static void beginVanguardPullUp(ArmorCruiseController.CombatHandle handle,
			CombatState state,ArmorEscortProfile profile,Vec3 anchor,long now){
		ArmorStand carrier=handle.carrier();
		Vec3 horizontal=new Vec3(state.diveDirection.x,0.0D,state.diveDirection.z);
		if(horizontal.lengthSqr()<1.0E-6D){
			double yaw=Math.toRadians(carrier.getYRot());
			horizontal=new Vec3(-Math.sin(yaw),0.0D,Math.cos(yaw));
		}else horizontal=horizontal.normalize();
		state.recoveryStartY=carrier.getY();
		double recoveryY=clampFlightY(carrier,carrier.getY()+7.0D);
		Vec3 desired=new Vec3(carrier.getX()+horizontal.x*8.0D,recoveryY,
				carrier.getZ()+horizontal.z*8.0D);
		state.recoveryPoint=boundedHorizontal(desired,anchor,vanguardMovementRadius(profile));
		carrier.setDeltaMovement(Vec3.ZERO);
		setRamPullUpPose(carrier);
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_PULL_UP,now);
	}

	private static void tickVanguardPullUp(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			ArmorEscortProfile profile,Vec3 anchor,LivingEntity target,long now){
		if(state.recoveryPoint==null){
			beginVanguardPullUp(handle,state,profile,anchor,now);
			return;
		}
		long elapsed=now-state.phaseStart;
		setRamPullUpPose(handle.carrier());
		move(handle.carrier(),state.recoveryPoint,1.10D);
		setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,
				RAM_PULL_UP_TIMEOUT_TICKS);
		double climbed=Math.max(0.0D,handle.carrier().getY()-state.recoveryStartY);
		double distance=handle.carrier().position().distanceTo(state.recoveryPoint);
		double speed=handle.carrier().getDeltaMovement().length();
		if(VanguardRamKinematics.ramRecoveryReady(elapsed,climbed,distance,speed)
				||elapsed>=RAM_PULL_UP_TIMEOUT_TICKS){
			holdCarrier(handle.carrier());
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
		}
	}

	private static void tickVanguardAnalyze(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			ArmorEscortProfile profile,Vec3 anchor,LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		holdCarrier(handle.carrier());
		setRamAnalyzePose(handle.carrier(),elapsed);
		if(target!=null)aimYawOnly(handle.carrier(),target);
		setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,RAM_ANALYZE_TICKS);
		if(elapsed<RAM_ANALYZE_TICKS)return;

		if(target==null){
			state.targetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID;
			target=selectTarget(owner,handle,profile,anchor,state,now);
		}
		if(target==null){
			finishCooldown(owner,handle,state,null,now,12);
			return;
		}
		state.targetId=target.getId();
		if(tryBeginGroundSlam(handle,state,profile,anchor,target,now))return;
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_WRIST,now);
	}

	private static boolean tryBeginGroundSlam(ArmorCruiseController.CombatHandle handle,
			CombatState state,ArmorEscortProfile profile,Vec3 anchor,
			LivingEntity target,long now){
		if(!state.lastRamConnected||now<state.nextSlamTick||!target.onGround())return false;
		Vec3 impact=findGroundImpact(handle.carrier(),target);
		if(impact==null)return false;
		double radius=vanguardMovementRadius(profile);
		Vec3 horizontal=new Vec3(impact.x-anchor.x,0.0D,impact.z-anchor.z);
		if(horizontal.lengthSqr()>(radius-1.0D)*(radius-1.0D))return false;
		double hoverY=clampFlightY(handle.carrier(),
				Math.max(handle.carrier().getY()+5.0D,impact.y+10.0D));
		state.slamImpactPoint=impact;
		state.slamHoverPoint=new Vec3(impact.x,hoverY,impact.z);
		state.slamDirection=Vec3.ZERO;
		state.slamImpactVelocity=Vec3.ZERO;
		state.slamRecoveryPoint=null;
		state.nextSlamTick=now+SLAM_REUSE_COOLDOWN_TICKS;
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_CLIMB,now);
		return true;
	}

	private static void tickSlamClimb(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			LivingEntity target,long now){
		if(state.slamHoverPoint==null||state.slamImpactPoint==null){
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
			return;
		}
		long elapsed=now-state.phaseStart;
		setSlamClimbPose(handle.carrier());
		move(handle.carrier(),state.slamHoverPoint,1.05D);
		if(target!=null)aimYawOnly(handle.carrier(),target);
		setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,
				RAM_CLIMB_TIMEOUT_TICKS);
		double altitude=handle.carrier().getY()-state.slamImpactPoint.y;
		double distance=handle.carrier().position().distanceTo(state.slamHoverPoint);
		if(VanguardRamKinematics.ramStagingReady(elapsed,altitude,distance)){
			holdCarrier(handle.carrier());
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_HOVER,now);
		}else if(elapsed>=RAM_CLIMB_TIMEOUT_TICKS){
			state.lastRamConnected=false;
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
		}
	}

	private static void tickSlamHover(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		holdCarrier(handle.carrier());
		setSlamHoverPose(handle.carrier(),elapsed);
		setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,SLAM_HOVER_TICKS);
		if(elapsed<SLAM_HOVER_TICKS)return;
		if(state.slamImpactPoint==null){
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
			return;
		}
		Vec3 direction=state.slamImpactPoint.subtract(
				handle.carrier().getBoundingBox().getCenter());
		if(!VanguardRamKinematics.groundSlamDirectionReady(
				direction.x,direction.y,direction.z)){
			state.lastRamConnected=false;
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
			return;
		}
		state.slamDirection=direction.normalize();
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_DROP,now);
	}

	private static void tickSlamDrop(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			ArmorEscortProfile profile,Vec3 anchor,LivingEntity target,long now){
		ArmorStand carrier=handle.carrier();
		long elapsed=now-state.phaseStart;
		if(state.slamImpactPoint==null||!VanguardRamKinematics.groundSlamDirectionReady(
				state.slamDirection.x,state.slamDirection.y,state.slamDirection.z)){
			beginAbortedSlamRecovery(handle,state,profile,anchor,now);
			return;
		}
		setSlamDropPose(carrier);
		setBehavior(owner,handle,state,CruiseCombatBehavior.FIRING,target,now,
				SLAM_DROP_TIMEOUT_TICKS);
		double speed=VanguardRamKinematics.groundSlamDescentSpeed(
				elapsed+VanguardRamKinematics.GROUND_SLAM_BRACE_TICKS);
		Vec3 delta=state.slamDirection.scale(speed);
		Vec3 center=carrier.getBoundingBox().getCenter();
		double block=blockFraction(carrier,center,center.add(delta));
		if(block<1.0D){
			Vec3 contactCenter=center.add(delta.scale(block));
			carrier.setPos(contactCenter.x,contactCenter.y+0.02D,contactCenter.z);
			carrier.setDeltaMovement(Vec3.ZERO);
			state.slamImpactPoint=contactCenter;
			state.slamImpactVelocity=delta;
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_IMPACT,now);
			return;
		}
		carrier.setPos(carrier.position().add(delta));
		carrier.setDeltaMovement(delta);
		carrier.hasImpulse=true;
		if(carrier.getBoundingBox().getCenter().y<=state.slamImpactPoint.y+0.20D){
			state.slamImpactVelocity=delta;
			carrier.setPos(state.slamImpactPoint.x,state.slamImpactPoint.y+0.02D,
					state.slamImpactPoint.z);
			carrier.setDeltaMovement(Vec3.ZERO);
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_IMPACT,now);
		}else if(elapsed>=SLAM_DROP_TIMEOUT_TICKS){
			beginAbortedSlamRecovery(handle,state,profile,anchor,now);
		}
	}

	private static void tickSlamImpact(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			ArmorEscortProfile profile,Vec3 anchor,LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		holdCarrier(handle.carrier());
		setSlamImpactPose(handle.carrier(),elapsed);
		setBehavior(owner,handle,state,CruiseCombatBehavior.FIRING,target,now,
				SLAM_IMPACT_HOLD_TICKS);
		if(IndependentCruiseTactics.canCommit(state.actionSequence,state.completedSlam)
				&&state.slamImpactPoint!=null&&state.slamImpactVelocity.lengthSqr()>1.0E-6D){
			ArmorCombatController.CruiseWeaponResult result=
					ArmorCombatController.tryCruiseGroundSlam(owner,handle.carrier(),
							state.slamImpactPoint,state.slamImpactVelocity);
			state.completedSlam=state.actionSequence;
			if(result.fired())state.serverAttackId=result.serverAttackId();
		}
		if(elapsed>=SLAM_IMPACT_HOLD_TICKS){
			beginSlamRecovery(handle,state,profile,anchor,now);
		}
	}

	private static void beginAbortedSlamRecovery(ArmorCruiseController.CombatHandle handle,
			CombatState state,ArmorEscortProfile profile,Vec3 anchor,long now){
		state.completedSlam=state.actionSequence;
		beginSlamRecovery(handle,state,profile,anchor,now);
	}

	private static void beginSlamRecovery(ArmorCruiseController.CombatHandle handle,
			CombatState state,ArmorEscortProfile profile,Vec3 anchor,long now){
		ArmorStand carrier=handle.carrier();
		double yaw=Math.toRadians(carrier.getYRot());
		Vec3 forward=new Vec3(-Math.sin(yaw),0.0D,Math.cos(yaw));
		double y=clampFlightY(carrier,carrier.getY()+7.0D);
		Vec3 desired=new Vec3(carrier.getX()+forward.x*4.0D,y,
				carrier.getZ()+forward.z*4.0D);
		if(profile!=null&&anchor!=null){
			desired=boundedHorizontal(desired,anchor,vanguardMovementRadius(profile));
		}
		state.slamRecoveryStartY=carrier.getY();
		state.slamRecoveryPoint=desired;
		state.lastRamConnected=false;
		transition(state,IndependentCruiseTactics.Phase.VANGUARD_SLAM_RECOVER,now);
	}

	private static void tickSlamRecover(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			ArmorEscortProfile profile,Vec3 anchor,LivingEntity target,long now){
		if(state.slamRecoveryPoint==null){
			beginSlamRecovery(handle,state,profile,anchor,now);
			return;
		}
		long elapsed=now-state.phaseStart;
		setSlamRecoverPose(handle.carrier(),elapsed);
		move(handle.carrier(),state.slamRecoveryPoint,1.02D);
		setBehavior(owner,handle,state,CruiseCombatBehavior.COOLDOWN,target,now,
				SLAM_RECOVER_TIMEOUT_TICKS);
		double climbed=Math.max(0.0D,handle.carrier().getY()-state.slamRecoveryStartY);
		double distance=handle.carrier().position().distanceTo(state.slamRecoveryPoint);
		double speed=handle.carrier().getDeltaMovement().length();
		if(VanguardRamKinematics.ramRecoveryReady(elapsed,climbed,distance,speed)
				||elapsed>=SLAM_RECOVER_TIMEOUT_TICKS){
			holdCarrier(handle.carrier());
			transition(state,IndependentCruiseTactics.Phase.VANGUARD_ANALYZE,now);
		}
	}

	private static void tickSwift(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatKey key,CombatState state,ArmorEscortProfile profile,Vec3 anchor,
			LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		switch(state.phase){
			case SWIFT_TRACK->{
				Vec3 future=target.getBoundingBox().getCenter().add(target.getDeltaMovement().scale(5));
				Vec3 radial=horizontalAway(target.position(),handle.carrier().position());
				Vec3 lateral=new Vec3(-radial.z,0,radial.x)
						.scale((state.actionSequence&1L)==0L?8:-8);
				move(handle.carrier(),bounded(future.add(lateral).add(0,3.2,0),
						anchor,movementRadius(profile)),1.22);
				aim(handle.carrier(),target);
				setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,TRACK_TICKS);
				if(elapsed>=TRACK_TICKS)transition(state,IndependentCruiseTactics.next(state.phase),now);
			}
			case SWIFT_VOLLEY->{
				aim(handle.carrier(),target);
				if(IndependentCruiseTactics.canCommit(state.actionSequence,state.completedWeapon)){
					int count=2+Math.floorMod(state.actionSequence,3);
					state.validMissileSequence=state.actionSequence;
					ArmorCombatController.CruiseWeaponResult result=
							ArmorCombatController.tryFireIndependentCruiseMissileVolley(
									owner,handle.carrier(),key.suitId(),state.actionSequence,
									state.carrierRevision,target,count);
					if(!result.fired()&&elapsed<RETRY_TICKS){
						setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,RETRY_TICKS);
						return;
					}
					state.completedWeapon=state.actionSequence;
					if(result.fired())state.serverAttackId=result.serverAttackId();
					else state.validMissileSequence=0;
				}
				transition(state,IndependentCruiseTactics.next(state.phase),now);
			}
			case SWIFT_ESCAPE->{
				Vec3 desired=handle.carrier().position()
						.add(horizontalAway(target.position(),handle.carrier().position()).scale(14))
						.add(0,5,0);
				move(handle.carrier(),bounded(desired,anchor,movementRadius(profile)),1.32);
				setBehavior(owner,handle,state,CruiseCombatBehavior.FIRING,target,now,ESCAPE_TICKS);
				if(elapsed>=ESCAPE_TICKS)finishCooldown(owner,handle,state,target,now,30);
			}
			default->invalidate(key,state,true);
		}
	}

	private static void tickSentinel(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatKey key,CombatState state,ArmorEscortProfile profile,Vec3 anchor,
			LivingEntity target,long now){
		long elapsed=now-state.phaseStart;
		switch(state.phase){
			case SENTINEL_HOLD->{
				Vec3 desired=target.getBoundingBox().getCenter()
						.add(horizontalAway(target.position(),handle.carrier().position()).scale(14))
						.add(0,4,0);
				move(handle.carrier(),bounded(desired,anchor,movementRadius(profile)),0.48);
				aim(handle.carrier(),target);
				setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,HOLD_TICKS);
				if(elapsed>=HOLD_TICKS)transition(state,IndependentCruiseTactics.next(state.phase),now);
			}
			case SENTINEL_FIRE->{
				aim(handle.carrier(),target);
				if(IndependentCruiseTactics.canCommit(state.actionSequence,state.completedWeapon)){
					ArmorCombatController.CruiseWeaponResult result=
							ArmorCombatController.tryFireCruiseCore(owner,handle.carrier(),target);
					if(!result.fired()){
						result=ArmorCombatController.tryFireSentinelDronePulse(
								owner,handle.carrier(),target);
					}
					if(!result.fired()&&elapsed<RETRY_TICKS){
						setBehavior(owner,handle,state,CruiseCombatBehavior.AIMING,target,now,RETRY_TICKS);
						return;
					}
					state.completedWeapon=state.actionSequence;
					if(result.fired())state.serverAttackId=result.serverAttackId();
				}
				finishCooldown(owner,handle,state,target,now,38);
			}
			default->invalidate(key,state,true);
		}
	}

	private static void startAction(CombatKey key,CombatState state,ArmorVariant variant,
			LivingEntity target,ArmorCruiseController.CombatHandle handle,
			ArmorEscortProfile profile,Vec3 anchor,long now){
		ArmorHomingMissileEntity.discardByCarrier(
				key.suitId(),state.carrierId,state.carrierRevision);
		state.validMissileSequence=0;
		state.actionSequence=NEXT_ACTION.incrementAndGet();
		state.variant=variant;
		state.targetId=target.getId();
		state.syncedTargetId=target.getId();
		state.intercept=null;
		state.diveDirection=Vec3.ZERO;
		state.phase=IndependentCruiseTactics.initial(role(variant));
		state.phaseStart=now;
		state.ramStagingPoint=null;
		state.recoveryPoint=null;
		state.ramReplanCount=0;
		state.lastRamConnected=false;
		state.slamHoverPoint=null;
		state.slamImpactPoint=null;
		state.slamDirection=Vec3.ZERO;
		state.slamImpactVelocity=Vec3.ZERO;
		state.slamRecoveryPoint=null;
		if(variant==ArmorVariant.VANGUARD){
			prepareRamClimb(handle,state,target,profile,anchor,now);
		}
	}

	private static void finishCooldown(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatState state,LivingEntity target,long now,int ticks){
		state.phase=IndependentCruiseTactics.Phase.COOLDOWN;
		state.phaseStart=now;
		state.cooldownUntil=now+ticks;
		ArmorCruiseController.setCruisePose(handle.carrier());
		setBehavior(owner,handle,state,CruiseCombatBehavior.COOLDOWN,target,now,ticks);
	}

	private static void transition(CombatState state,IndependentCruiseTactics.Phase next,long now){
		state.phase=next;
		state.phaseStart=now;
	}

	private static IndependentCruiseTactics.Role role(ArmorVariant variant){
		return switch(variant){
			case VANGUARD->IndependentCruiseTactics.Role.VANGUARD;
			case SWIFT->IndependentCruiseTactics.Role.SWIFT;
			case SENTINEL->IndependentCruiseTactics.Role.SENTINEL;
		};
	}
	private static LivingEntity resolveTarget(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,ArmorEscortProfile profile,
			Vec3 anchor,CombatState state){
		if(state.targetId<0||!(handle.carrier().level() instanceof ServerLevel level))return null;
		Entity entity=level.getEntity(state.targetId);
		return entity instanceof LivingEntity living
				&&validTarget(owner,handle,profile,anchor,living)?living:null;
	}

	private static LivingEntity selectTarget(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,ArmorEscortProfile profile,
			Vec3 anchor,CombatState state,long now){
		double range=profile.rangeBlocks();
		AABB area=AABB.ofSize(anchor,range*2,Math.max(20,range*1.35),range*2);
		LivingEntity recent=owner.getLastHurtByMob();
		boolean recentWindow=recent!=null
				&&owner.tickCount-owner.getLastHurtByMobTimestamp()<=100;
		List<LivingEntity> candidates=owner.serverLevel().getEntitiesOfClass(
				LivingEntity.class,area,c->validTarget(owner,handle,profile,anchor,c));
		List<IndependentCruiseThreatTable.Observation> observations=new ArrayList<>(candidates.size());
		for(LivingEntity candidate:candidates){
			boolean attacking=candidate instanceof Mob mob&&mob.getTarget()==owner;
			observations.add(new IndependentCruiseThreatTable.Observation(candidate.getId(),
					handle.carrier().getBoundingBox().getCenter()
							.distanceTo(candidate.getBoundingBox().getCenter()),
					hasLineOfSight(handle.carrier(),candidate),candidate instanceof Enemy,
					attacking,recentWindow&&candidate==recent));
		}
		IndependentCruiseThreatTable.Selection selected=
				state.threatTable.update(now,observations,state.targetId);
		state.targetId=selected.targetEntityId();
		Entity entity=state.targetId<0?null:owner.serverLevel().getEntity(state.targetId);
		return entity instanceof LivingEntity living?living:null;
	}

	private static boolean validTarget(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,ArmorEscortProfile profile,
			Vec3 anchor,LivingEntity target){
		if(target==null||!target.isAlive()||target.isSpectator()
				||target==owner||target==handle.carrier()||target.level()!=owner.level()
				||isOwnersPet(owner,target)||!CombatTargetPolicy.canDamage(owner,target))return false;
		double range=profile.rangeBlocks();
		if(target.getBoundingBox().getCenter().distanceToSqr(anchor)>range*range
				||target.distanceToSqr(handle.carrier())>(range+20)*(range+20))return false;
		if(profile.attackMode()==ArmorEscortProfile.CombatPolicy.ALL)return true;
		if(profile.attackMode()==ArmorEscortProfile.CombatPolicy.OFF)return false;
		boolean attacking=target instanceof Mob mob&&mob.getTarget()==owner;
		boolean recent=owner.getLastHurtByMob()==target
				&&owner.tickCount-owner.getLastHurtByMobTimestamp()<=100;
		return target instanceof Enemy||attacking||recent;
	}

	private static Vec3 combatAnchor(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,ArmorEscortProfile profile){
		if(profile.stance()==ArmorEscortProfile.EscortAnchor.HOLD
				&&profile.holdPosition()!=null
				&&Objects.equals(profile.holdDimension(),owner.level().dimension()))
			return Vec3.atCenterOf(profile.holdPosition());
		return handle.patrolAnchor();
	}

	private static double movementRadius(ArmorEscortProfile profile){
		return Math.max(8,Math.min(20,profile.rangeBlocks()*0.72));
	}

	private static double vanguardMovementRadius(ArmorEscortProfile profile){
		return Math.max(8.0D,ArmorEscortAi.sanitizeRange(profile.rangeBlocks())-1.5D);
	}

	private static Vec3 bounded(Vec3 desired,Vec3 anchor,double radius){
		Vec3 delta=desired.subtract(anchor);
		return delta.lengthSqr()<=radius*radius||delta.lengthSqr()<1.0E-8
				?desired:anchor.add(delta.normalize().scale(radius));
	}

	/** Horizontal command-radius clamp which deliberately preserves maneuver altitude. */
	private static Vec3 boundedHorizontal(Vec3 desired,Vec3 anchor,double radius){
		Vec3 horizontal=new Vec3(desired.x-anchor.x,0.0D,desired.z-anchor.z);
		double distance=horizontal.length();
		if(distance<=radius||distance<1.0E-8D)return desired;
		Vec3 clamped=horizontal.scale(radius/distance);
		return new Vec3(anchor.x+clamped.x,desired.y,anchor.z+clamped.z);
	}

	private static double clampFlightY(ArmorStand carrier,double desiredY){
		return Mth.clamp(desiredY,carrier.level().getMinBuildHeight()+2.0D,
				carrier.level().getMaxBuildHeight()-3.0D);
	}

	private static Vec3 horizontalAway(Vec3 target,Vec3 carrier){
		Vec3 away=new Vec3(carrier.x-target.x,0,carrier.z-target.z);
		return away.lengthSqr()<1.0E-8?new Vec3(1,0,0):away.normalize();
	}

	private static void move(ArmorStand carrier,Vec3 desired,double maxStep){
		Vec3 delta=desired.subtract(carrier.position());
		Vec3 step=delta.length()<=maxStep?delta:delta.normalize().scale(maxStep);
		if(step.lengthSqr()<1.0E-8){carrier.setDeltaMovement(Vec3.ZERO);return;}
		carrier.setPos(carrier.position().add(step));
		carrier.setDeltaMovement(step);
		carrier.hasImpulse=true;
	}

	private static void holdCarrier(ArmorStand carrier){
		carrier.setDeltaMovement(Vec3.ZERO);
		carrier.hasImpulse=true;
	}

	private static double blockFraction(ArmorStand carrier,Vec3 start,Vec3 end){
		HitResult hit=carrier.level().clip(new ClipContext(
				start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,carrier));
		if(hit.getType()!=HitResult.Type.BLOCK)return 1;
		double total=start.distanceTo(end);
		return total<1.0E-8?0:Mth.clamp(start.distanceTo(hit.getLocation())/total,0,1);
	}

	private static Vec3 findGroundImpact(ArmorStand carrier,LivingEntity target){
		Vec3 center=target.getBoundingBox().getCenter();
		Vec3 start=new Vec3(center.x,target.getBoundingBox().maxY+2.0D,center.z);
		Vec3 end=new Vec3(center.x,
				Math.max(carrier.level().getMinBuildHeight()+1.0D,target.getY()-6.0D),center.z);
		HitResult hit=carrier.level().clip(new ClipContext(
				start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,carrier));
		if(hit.getType()==HitResult.Type.BLOCK)return hit.getLocation().add(0.0D,0.04D,0.0D);
		return target.onGround()?new Vec3(center.x,target.getY()+0.04D,center.z):null;
	}

	private static boolean hasLineOfSight(ArmorStand carrier,Entity target){
		Vec3 start=carrier.position().add(0,1.35,0),end=target.getBoundingBox().getCenter();
		HitResult hit=carrier.level().clip(new ClipContext(
				start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,carrier));
		return hit.getType()!=HitResult.Type.BLOCK
				||start.distanceToSqr(hit.getLocation())+0.04>=start.distanceToSqr(end);
	}

	private static void aim(ArmorStand carrier,Entity target){
		Vec3 delta=target.getBoundingBox().getCenter()
				.subtract(carrier.position().add(0,1.25,0));
		float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
		carrier.setYRot(Mth.rotLerp(0.34F,carrier.getYRot(),yaw));
		carrier.setYBodyRot(carrier.getYRot());
		carrier.setHeadPose(new Rotations(-7,0,0));
		carrier.setBodyPose(new Rotations(3,0,0));
		carrier.setLeftArmPose(new Rotations(-28,0,-9));
		carrier.setRightArmPose(new Rotations(-23,0,10));
		carrier.setLeftLegPose(new Rotations(12,0,-7));
		carrier.setRightLegPose(new Rotations(-6,0,6));
	}

	private static void aimYawOnly(ArmorStand carrier,Entity target){
		Vec3 delta=target.getBoundingBox().getCenter()
				.subtract(carrier.position().add(0,1.25,0));
		float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
		carrier.setYRot(Mth.rotLerp(0.34F,carrier.getYRot(),yaw));
		carrier.setYBodyRot(carrier.getYRot());
	}

	private static void setRamClimbPose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(-9,0,0));
		carrier.setBodyPose(new Rotations(-12,0,0));
		carrier.setLeftArmPose(new Rotations(16,0,-8));
		carrier.setRightArmPose(new Rotations(10,0,9));
		carrier.setLeftLegPose(new Rotations(19,0,-5));
		carrier.setRightLegPose(new Rotations(7,0,5));
	}

	private static void setRamLockPose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(12,0,0));
		carrier.setBodyPose(new Rotations(9,0,0));
		carrier.setLeftArmPose(new Rotations(4,0,-5));
		carrier.setRightArmPose(new Rotations(4,0,5));
		carrier.setLeftLegPose(new Rotations(10,0,-3));
		carrier.setRightLegPose(new Rotations(5,0,3));
	}

	private static void setRamDivePose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(17,0,0));
		carrier.setBodyPose(new Rotations(23,0,0));
		carrier.setLeftArmPose(new Rotations(8,0,-3));
		carrier.setRightArmPose(new Rotations(8,0,3));
		carrier.setLeftLegPose(new Rotations(6,0,-2));
		carrier.setRightLegPose(new Rotations(6,0,2));
	}

	private static void setRamPullUpPose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(-12,0,0));
		carrier.setBodyPose(new Rotations(-18,0,0));
		carrier.setLeftArmPose(new Rotations(22,0,-11));
		carrier.setRightArmPose(new Rotations(15,0,12));
		carrier.setLeftLegPose(new Rotations(22,0,-5));
		carrier.setRightLegPose(new Rotations(9,0,5));
	}

	private static void setRamAnalyzePose(ArmorStand carrier,long elapsed){
		float scan=(float)Math.sin(elapsed*0.72D)*7.0F;
		carrier.setHeadPose(new Rotations(-3,scan,0));
		carrier.setBodyPose(new Rotations(2,0,0));
		carrier.setLeftArmPose(new Rotations(-17,0,-13));
		carrier.setRightArmPose(new Rotations(-10,0,15));
		carrier.setLeftLegPose(new Rotations(10,0,-6));
		carrier.setRightLegPose(new Rotations(-5,0,6));
	}

	private static void setRamWristPose(ArmorStand carrier,boolean left){
		carrier.setHeadPose(new Rotations(-5,0,0));
		carrier.setBodyPose(new Rotations(5,0,left?-4:4));
		carrier.setLeftArmPose(new Rotations(left?-76:-18,left?-5:0,left?-7:-13));
		carrier.setRightArmPose(new Rotations(left?-14:-76,left?0:5,left?15:7));
		carrier.setLeftLegPose(new Rotations(9,0,-5));
		carrier.setRightLegPose(new Rotations(-4,0,5));
	}

	private static void setSlamClimbPose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(-13,0,0));
		carrier.setBodyPose(new Rotations(-16,0,0));
		carrier.setLeftArmPose(new Rotations(28,0,-14));
		carrier.setRightArmPose(new Rotations(23,0,14));
		carrier.setLeftLegPose(new Rotations(27,0,-7));
		carrier.setRightLegPose(new Rotations(18,0,7));
	}

	private static void setSlamHoverPose(ArmorStand carrier,long elapsed){
		float brace=Mth.clamp(elapsed/(float)Math.max(1,SLAM_HOVER_TICKS),0.0F,1.0F);
		carrier.setHeadPose(new Rotations(Mth.lerp(brace,-6,20),0,0));
		carrier.setBodyPose(new Rotations(Mth.lerp(brace,0,16),0,0));
		carrier.setLeftArmPose(new Rotations(Mth.lerp(brace,-24,38),0,-18));
		carrier.setRightArmPose(new Rotations(Mth.lerp(brace,-18,34),0,18));
		carrier.setLeftLegPose(new Rotations(Mth.lerp(brace,14,42),0,-8));
		carrier.setRightLegPose(new Rotations(Mth.lerp(brace,4,37),0,8));
	}

	private static void setSlamDropPose(ArmorStand carrier){
		carrier.setHeadPose(new Rotations(2,0,0));
		carrier.setBodyPose(new Rotations(0,0,0));
		carrier.setLeftArmPose(new Rotations(10,0,-6));
		carrier.setRightArmPose(new Rotations(10,0,6));
		carrier.setLeftLegPose(new Rotations(-3,0,-4));
		carrier.setRightLegPose(new Rotations(-3,0,4));
	}

	private static void setSlamImpactPose(ArmorStand carrier,long elapsed){
		float settle=Mth.clamp(elapsed/(float)Math.max(1,SLAM_IMPACT_HOLD_TICKS),0.0F,1.0F);
		carrier.setHeadPose(new Rotations(Mth.lerp(settle,24,8),0,0));
		carrier.setBodyPose(new Rotations(Mth.lerp(settle,32,20),0,0));
		carrier.setLeftArmPose(new Rotations(Mth.lerp(settle,-48,-28),-8,-25));
		carrier.setRightArmPose(new Rotations(Mth.lerp(settle,12,-18),9,22));
		carrier.setLeftLegPose(new Rotations(Mth.lerp(settle,-18,-8),0,-10));
		carrier.setRightLegPose(new Rotations(Mth.lerp(settle,54,38),0,11));
	}

	private static void setSlamRecoverPose(ArmorStand carrier,long elapsed){
		float rise=Mth.clamp(elapsed/10.0F,0.0F,1.0F);
		carrier.setHeadPose(new Rotations(Mth.lerp(rise,8,-6),0,0));
		carrier.setBodyPose(new Rotations(Mth.lerp(rise,20,-12),0,0));
		carrier.setLeftArmPose(new Rotations(Mth.lerp(rise,-28,18),0,-14));
		carrier.setRightArmPose(new Rotations(Mth.lerp(rise,-18,12),0,14));
		carrier.setLeftLegPose(new Rotations(Mth.lerp(rise,-8,22),0,-6));
		carrier.setRightLegPose(new Rotations(Mth.lerp(rise,38,10),0,6));
	}


	private static void ensureIdentity(CombatKey key,CombatState state,
			ArmorCruiseController.CombatHandle handle){
		UUID carrierId=handle.carrier().getUUID();
		ResourceKey<Level> dimension=handle.carrier().level().dimension();
		if(carrierId.equals(state.carrierId)&&state.carrierRevision==handle.sequenceId()
				&&Objects.equals(state.dimension,dimension))return;
		invalidate(key,state,true);
		state.carrierId=carrierId;
		state.carrierRevision=handle.sequenceId();
		state.dimension=dimension;
	}

	private static void invalidate(CombatKey key,CombatState state,boolean discardMissiles){
		if(discardMissiles)ArmorHomingMissileEntity.discardByCarrier(
				key.suitId(),state.carrierId,state.carrierRevision);
		state.validMissileSequence=0;
		state.actionSequence=0;
		state.variant=null;
		state.phase=IndependentCruiseTactics.Phase.IDLE;
		state.phaseStart=0;
		state.cooldownUntil=0;
		state.targetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID;
		state.syncedTargetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID;
		state.intercept=null;
		state.diveDirection=Vec3.ZERO;
		state.ramStagingPoint=null;
		state.recoveryPoint=null;
		state.ramReplanCount=0;
		state.lastRamConnected=false;
		state.slamHoverPoint=null;
		state.slamImpactPoint=null;
		state.slamDirection=Vec3.ZERO;
		state.slamImpactVelocity=Vec3.ZERO;
		state.slamRecoveryPoint=null;
		state.threatTable.clear();
	}

	private static boolean committed(IndependentCruiseTactics.Phase phase){
		return phase!=IndependentCruiseTactics.Phase.IDLE
				&&phase!=IndependentCruiseTactics.Phase.COOLDOWN;
	}

	private static CombatKey combatKey(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle){
		UUID suitId=ArmorCruiseController.runtimeView(owner)
				.map(ArmorCruiseController.CruiseRuntimeView::suitId).orElse(null);
		return new CombatKey(owner.getUUID(),suitId==null?handle.carrier().getUUID():suitId);
	}

	private static List<Map.Entry<CombatKey,CombatState>> ownerStates(UUID ownerId){
		List<Map.Entry<CombatKey,CombatState>> result=new ArrayList<>();
		for(Map.Entry<CombatKey,CombatState> e:STATES.entrySet())
			if(e.getKey().ownerId().equals(ownerId))result.add(e);
		return result;
	}

	private static boolean isOwnersPet(ServerPlayer owner,LivingEntity target){
		return target instanceof TamableAnimal pet&&owner.getUUID().equals(pet.getOwnerUUID());
	}
	public static void syncCarrierTo(Entity carrier,ServerPlayer recipient){
		if(!(carrier instanceof ArmorStand stand))return;
		ArmorCruiseController.ownerIdOf(stand).ifPresent(ownerId->{
			for(Map.Entry<CombatKey,CombatState> e:ownerStates(ownerId)){
				CombatState state=e.getValue();
				if(!carrier.getUUID().equals(state.carrierId))continue;
				if(ServerPlayNetworking.canSend(recipient,ArmorCruiseCombatStatePayload.TYPE))
					ServerPlayNetworking.send(recipient,payload(ownerId,carrier.getUUID(),
							state.carrierRevision,state,recipient.serverLevel().getGameTime()));
			}
		});
	}

	public static void syncAllTo(ServerPlayer recipient){
		for(Map.Entry<CombatKey,CombatState> e:STATES.entrySet()){
			CombatState state=e.getValue();
			if(state.mode==CruiseCombatMode.PASSIVE
					&&state.behavior==CruiseCombatBehavior.PASSIVE)continue;
			if(ServerPlayNetworking.canSend(recipient,ArmorCruiseCombatStatePayload.TYPE)){
				UUID carrier=state.carrierId==null?ArmorCruiseStatePayload.NO_CARRIER_ID:state.carrierId;
				ServerPlayNetworking.send(recipient,payload(e.getKey().ownerId(),carrier,
						state.carrierRevision,state,recipient.serverLevel().getGameTime()));
			}
		}
	}

	private static void setBehavior(ServerPlayer owner,
			ArmorCruiseController.CombatHandle handle,CombatState state,
			CruiseCombatBehavior behavior,Entity target,long now,int ticks){
		int targetId=target==null?ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID:target.getId();
		CruiseCombatStage stage=stage(state.phase);
		if(state.behavior==behavior&&state.syncedTargetId==targetId
				&&state.syncedStage==stage&&now-state.lastSync<10
				&&behavior!=CruiseCombatBehavior.FIRING)return;
		state.behavior=behavior;
		state.syncedTargetId=targetId;
		state.syncedStage=stage;
		state.uiStart=now;
		state.uiTicks=Math.max(1,ticks);
		state.revision=NEXT_REVISION.incrementAndGet();
		sync(owner,handle,state,now);
	}

	private static void sync(ServerPlayer owner,ArmorCruiseController.CombatHandle handle,
			CombatState state,long now){
		state.lastSync=now;
		UUID carrier=handle==null?ArmorCruiseStatePayload.NO_CARRIER_ID:handle.carrier().getUUID();
		long sequence=handle==null?state.carrierRevision:handle.sequenceId();
		ArmorCruiseCombatStatePayload payload=payload(owner.getUUID(),carrier,sequence,state,now);
		Set<ServerPlayer> recipients=new HashSet<>();
		recipients.add(owner);
		if(handle!=null)recipients.addAll(PlayerLookup.tracking(handle.carrier()));
		for(ServerPlayer recipient:recipients)
			if(ServerPlayNetworking.canSend(recipient,ArmorCruiseCombatStatePayload.TYPE))
				ServerPlayNetworking.send(recipient,payload);
	}

	private static ArmorCruiseCombatStatePayload payload(UUID ownerId,UUID carrierId,
			long sequence,CombatState state,long now){
		return new ArmorCruiseCombatStatePayload(ownerId,carrierId,sequence,
				state.revision,state.serverAttackId,state.mode,state.behavior,attackType(state),
				stage(state.phase),state.syncedTargetId,state.leftHand,state.uiStart==0?now:state.uiStart,
				Math.max(1,state.uiTicks));
	}

	private static ArmorAttackType attackType(CombatState state){
		if(state.variant==null||state.phase==IndependentCruiseTactics.Phase.IDLE
				||state.phase==IndependentCruiseTactics.Phase.COOLDOWN)
			return ArmorAttackType.INVALID;
		return switch(state.variant){
			case VANGUARD->{
				if(IndependentCruiseTactics.isGroundSlamPhase(state.phase))
					yield ArmorAttackType.CRUISE_GROUND_SLAM;
				yield state.phase==IndependentCruiseTactics.Phase.VANGUARD_WRIST
						?ArmorAttackType.WRIST_GRAVITY_PULSE:ArmorAttackType.CRUISE_RAM;
			}
			case SWIFT->ArmorAttackType.CRUISE_MISSILE_VOLLEY;
			case SENTINEL->ArmorAttackType.CRUISE_CORE_BEAM;
		};
	}

	private static CruiseCombatStage stage(IndependentCruiseTactics.Phase phase){
		return switch(phase){
			case IDLE->CruiseCombatStage.IDLE;
			case VANGUARD_CLIMB->CruiseCombatStage.VANGUARD_CLIMB;
			case VANGUARD_LOCK->CruiseCombatStage.VANGUARD_LOCK;
			case VANGUARD_DIVE->CruiseCombatStage.VANGUARD_DIVE;
			case VANGUARD_PULL_UP->CruiseCombatStage.VANGUARD_PULL_UP;
			case VANGUARD_ANALYZE->CruiseCombatStage.VANGUARD_ANALYZE;
			case VANGUARD_WRIST->CruiseCombatStage.VANGUARD_WRIST;
			case VANGUARD_SLAM_CLIMB->CruiseCombatStage.VANGUARD_SLAM_CLIMB;
			case VANGUARD_SLAM_HOVER->CruiseCombatStage.VANGUARD_SLAM_HOVER;
			case VANGUARD_SLAM_DROP->CruiseCombatStage.VANGUARD_SLAM_DROP;
			case VANGUARD_SLAM_IMPACT->CruiseCombatStage.VANGUARD_SLAM_IMPACT;
			case VANGUARD_SLAM_RECOVER->CruiseCombatStage.VANGUARD_SLAM_RECOVER;
			case SWIFT_TRACK->CruiseCombatStage.SWIFT_TRACK;
			case SWIFT_VOLLEY->CruiseCombatStage.SWIFT_VOLLEY;
			case SWIFT_ESCAPE->CruiseCombatStage.SWIFT_ESCAPE;
			case SENTINEL_HOLD->CruiseCombatStage.SENTINEL_HOLD;
			case SENTINEL_FIRE->CruiseCombatStage.SENTINEL_FIRE;
			case COOLDOWN->CruiseCombatStage.COOLDOWN;
		};
	}

	private record CombatKey(UUID ownerId,UUID suitId){}

	private static final class CombatState{
		private final IndependentCruiseThreatTable threatTable=new IndependentCruiseThreatTable();
		private long lastClientSequence=-1,revision,serverAttackId,carrierRevision,phaseStart;
		private long actionSequence,validMissileSequence,completedRam,completedSlam,completedWeapon;
		private long nextScan,cooldownUntil,nextSlamTick,uiStart,lastSync=Long.MIN_VALUE/2;
		private int targetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID;
		private int syncedTargetId=ArmorCruiseCombatStatePayload.NO_TARGET_ENTITY_ID,uiTicks=1;
		private int ramReplanCount;
		private CruiseCombatMode mode=CruiseCombatMode.PASSIVE;
		private CruiseCombatBehavior behavior=CruiseCombatBehavior.PASSIVE;
		private IndependentCruiseTactics.Phase phase=IndependentCruiseTactics.Phase.IDLE;
		private CruiseCombatStage syncedStage=CruiseCombatStage.IDLE;
		private UUID carrierId;
		private ResourceKey<Level> dimension;
		private ArmorVariant variant;
		private boolean leftHand,lastRamConnected;
		private double recoveryStartY,slamRecoveryStartY;
		private Vec3 intercept,diveDirection=Vec3.ZERO,ramStagingPoint,recoveryPoint;
		private Vec3 slamHoverPoint,slamImpactPoint,slamDirection=Vec3.ZERO;
		private Vec3 slamImpactVelocity=Vec3.ZERO,slamRecoveryPoint;
	}
}
