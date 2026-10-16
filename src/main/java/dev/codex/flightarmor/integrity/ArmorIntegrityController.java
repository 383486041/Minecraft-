package dev.codex.flightarmor.integrity;

import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Server boundary for carrier damage, degraded operation, disablement and platform repair. */
public final class ArmorIntegrityController {
	private ArmorIntegrityController() {}

	public static boolean isManagedCarrier(Entity entity) {
		return entity instanceof ArmorStand stand
				&& (ArmorCruiseController.isCruiseCarrier(stand)
				|| ArmorEscortController.isEscortCarrier(stand))
				&& identifyCarrier(stand).isPresent();
	}

	public static Optional<SuitIdentity> identifyCarrier(ArmorStand carrier) {
		if (carrier == null) return Optional.empty();
		UUID ownerId = null;
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!ModItems.isExactFlightArmor(carrier.getItemBySlot(slot), slot)) return Optional.empty();
			ArmorIdentity.Identity identity = ArmorIdentity.read(carrier.getItemBySlot(slot)).orElse(null);
			if (identity == null || identity.slot() != slot) return Optional.empty();
			if (ownerId != null && !ownerId.equals(identity.ownerId())) return Optional.empty();
			if (suitId != null && !suitId.equals(identity.suitId())) return Optional.empty();
			ownerId = identity.ownerId();
			suitId = identity.suitId();
		}
		return ownerId == null || suitId == null ? Optional.empty()
				: Optional.of(new SuitIdentity(ownerId, suitId));
	}

	public static ArmorIntegritySavedData.Snapshot snapshot(
			MinecraftServer server, UUID ownerId, UUID suitId) {
		return ArmorIntegritySavedData.get(server).snapshot(ownerId, suitId);
	}

	public static int integrity(MinecraftServer server, UUID ownerId, UUID suitId) {
		return snapshot(server, ownerId, suitId).integrity();
	}

	public static int integrity(ArmorStand carrier) {
		if (!(carrier.level() instanceof ServerLevel level)) return ArmorIntegrityRules.MAX_INTEGRITY;
		SuitIdentity identity = identifyCarrier(carrier).orElse(null);
		return identity == null ? ArmorIntegrityRules.MAX_INTEGRITY
				: integrity(level.getServer(), identity.ownerId(), identity.suitId());
	}

	public static boolean isDisabled(ArmorStand carrier) {
		return ArmorIntegrityRules.isDisabled(integrity(carrier));
	}

	public static boolean canAttack(MinecraftServer server, UUID ownerId, UUID suitId) {
		return !ArmorIntegrityRules.isDisabled(integrity(server, ownerId, suitId));
	}

	public static double movementMultiplier(ArmorStand carrier) {
		return ArmorIntegrityRules.recoveryMovementMultiplier(integrity(carrier));
	}

	public static long scaledAttackDelay(MinecraftServer server, UUID ownerId, UUID suitId,
			long baseDelay) {
		return ArmorIntegrityRules.scaledAttackDelay(baseDelay, integrity(server, ownerId, suitId));
	}

	/**
	 * Consumes the original source without synthesizing another damage event. The caller must always
	 * cancel vanilla ArmorStand damage so its four real ItemStacks can never drop or be destroyed.
	 */
	public static boolean handleCarrierDamage(ArmorStand carrier, DamageSource source, float rawDamage) {
		if (!(carrier.level() instanceof ServerLevel level) || source == null
				|| !Float.isFinite(rawDamage) || rawDamage <= 0.0F) return false;
		SuitIdentity identity = identifyCarrier(carrier).orElse(null);
		if (identity == null || friendlySource(carrier, identity, source)) return false;
		int units = ArmorIntegrityRules.damageUnits(rawDamage, damageKind(source));
		Entity direct = source.getDirectEntity();
		Entity causing = source.getEntity();
		ArmorIntegritySavedData.Mutation mutation = ArmorIntegritySavedData.get(level.getServer()).damage(
				identity.ownerId(), identity.suitId(), units,
				direct == null ? null : direct.getUUID(),
				causing == null ? null : causing.getUUID(), level.getGameTime());
		if (mutation.changed()) notifyThreshold(level, identity, mutation);
		return units > 0;
	}

	private static boolean friendlySource(ArmorStand carrier, SuitIdentity identity, DamageSource source) {
		return friendlyEntity(carrier, identity, source.getDirectEntity())
				|| friendlyEntity(carrier, identity, source.getEntity());
	}

	private static boolean friendlyEntity(ArmorStand carrier, SuitIdentity identity, Entity entity) {
		if (entity == null) return false;
		if (entity == carrier || entity.getUUID().equals(identity.ownerId())) return true;
		if (!(entity instanceof ArmorStand allied) || !isManagedCarrier(allied)) return false;
		return identifyCarrier(allied).map(other -> other.ownerId().equals(identity.ownerId()))
				.orElse(false);
	}

	private static ArmorIntegrityRules.DamageKind damageKind(DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return ArmorIntegrityRules.DamageKind.BYPASS;
		if (source.is(DamageTypeTags.IS_EXPLOSION)) return ArmorIntegrityRules.DamageKind.EXPLOSION;
		if (source.is(DamageTypeTags.IS_FIRE)) return ArmorIntegrityRules.DamageKind.FIRE;
		if (source.is(DamageTypeTags.IS_PROJECTILE)) return ArmorIntegrityRules.DamageKind.PROJECTILE;
		return ArmorIntegrityRules.DamageKind.GENERIC;
	}

	private static void notifyThreshold(ServerLevel level, SuitIdentity identity,
			ArmorIntegritySavedData.Mutation mutation) {
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(identity.ownerId());
		if (owner == null) return;
		if (mutation.current() == 0 && mutation.previous() > 0) {
			owner.displayClientMessage(Component.literal(
					"战甲完整度归零：作战与反重力失能，可召回平台维修"), true);
		} else if (mutation.current() < ArmorIntegrityRules.DEGRADED_THRESHOLD
				&& mutation.previous() >= ArmorIntegrityRules.DEGRADED_THRESHOLD) {
			owner.displayClientMessage(Component.literal(
					"战甲完整度低于 25%：机动与攻击频率下降"), true);
		}
	}

	public static PlatformRepair repairPlatform(ChargingPlatformBlockEntity platform) {
		if (!(platform.getLevel() instanceof ServerLevel level) || !platform.isComplete()) {
			return new PlatformRepair(false, false, ArmorIntegrityRules.MAX_INTEGRITY);
		}
		UUID ownerId = platform.getOwnerUuid().orElse(null);
		UUID suitId = platform.getSuitId().orElse(null);
		if (ownerId == null || suitId == null) {
			return new PlatformRepair(false, false, ArmorIntegrityRules.MAX_INTEGRITY);
		}
		ArmorIntegritySavedData data = ArmorIntegritySavedData.get(level.getServer());
		ArmorIntegritySavedData.Mutation mutation = data.repair(ownerId, suitId,
				ArmorIntegrityRules.PLATFORM_REPAIR_PER_TICK, level.getGameTime());
		return new PlatformRepair(true, mutation.changed(), mutation.current());
	}

	public static String statusText(MinecraftServer server, UUID ownerId, UUID suitId) {
		int integrity = integrity(server, ownerId, suitId);
		String state = ArmorIntegrityRules.isDisabled(integrity) ? "失能"
				: ArmorIntegrityRules.isDegraded(integrity) ? "受损" : "正常";
		return "完整度 " + integrity + "/" + ArmorIntegrityRules.MAX_INTEGRITY
				+ "（" + ArmorIntegrityRules.percent(integrity) + "% · " + state + "）";
	}

	/** Zero-integrity stable carriers fall under collision instead of hovering or disappearing. */
	public static void tickDisabledCarrier(ArmorStand carrier) {
		carrier.setInvulnerable(true);
		if (carrier.level() instanceof ServerLevel level
				&& carrier.getY() <= level.getMinBuildHeight() + 2.5D) {
			carrier.setPos(carrier.getX(), level.getMinBuildHeight() + 2.5D, carrier.getZ());
			carrier.setNoGravity(true);
			carrier.noPhysics = true;
			carrier.setDeltaMovement(Vec3.ZERO);
			carrier.fallDistance = 0.0F;
			return;
		}
		carrier.setNoGravity(false);
		carrier.noPhysics = false;
		Vec3 velocity = carrier.getDeltaMovement();
		double y = carrier.onGround() ? 0.0D : Math.max(-0.16D, velocity.y - 0.035D);
		carrier.setDeltaMovement(velocity.x * 0.72D, y, velocity.z * 0.72D);
		carrier.fallDistance = 0.0F;
	}

	/** Recall/return controllers call this before their authoritative movement step. */
	public static void prepareControlledMovement(ArmorStand carrier) {
		carrier.setInvulnerable(true);
		carrier.setNoGravity(true);
		carrier.noPhysics = true;
		carrier.setDeltaMovement(Vec3.ZERO);
	}

	public record SuitIdentity(UUID ownerId, UUID suitId) {}
	public record PlatformRepair(boolean validSuit, boolean repairing, int integrity) {}
}
