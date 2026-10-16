package dev.codex.flightarmor.configuration;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.platform.ArmorIdentity;
import dev.codex.flightarmor.platform.ChargingPlatformBlockEntity;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only authority shared by Sentinel rendering, flight and combat.
 *
 * <p>A configured Sentinel is deliberately stricter than a visually matching set: all four
 * pieces must be Sentinel armor, carry valid identities for their physical slots, and share one
 * owner and one suit id. This prevents a chest configuration copied onto a mixed set from
 * granting either form's abilities.</p>
 */
public final class ArmorConfigurationAccess {
	/** One and a half seconds at 20 TPS; abilities stay locked until the mechanism settles. */
	public static final long TRANSITION_DURATION_TICKS = 30L;
	/** H wing-module hand-off: lock/pack, transfer, eject/align and final ownership settle. */
	public static final long WINGMEN_TRANSITION_DURATION_TICKS = 30L;
	public static final long WINGMEN_HANDOFF_START_TICKS = 10L;
	public static final long WINGMEN_HANDOFF_END_TICKS = 13L;

	private ArmorConfigurationAccess() {
	}

	public static Optional<ConfiguredSentinel> configuredSentinel(LivingEntity entity) {
		if (entity == null
				|| ModItems.fullSetVariant(entity).orElse(null) != ArmorVariant.SENTINEL) {
			return Optional.empty();
		}
		UUID ownerId = null;
		UUID suitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = entity.getItemBySlot(slot);
			ArmorIdentity.Identity identity = ArmorIdentity.read(stack).orElse(null);
			if (identity == null || identity.slot() != slot) return Optional.empty();
			if (ownerId != null && !ownerId.equals(identity.ownerId())) return Optional.empty();
			if (suitId != null && !suitId.equals(identity.suitId())) return Optional.empty();
			ownerId = identity.ownerId();
			suitId = identity.suitId();
		}
		if (ownerId == null || suitId == null) return Optional.empty();
		ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
		return Optional.of(new ConfiguredSentinel(
				ownerId, suitId, ArmorConfigurationData.read(chest)));
	}

	public static boolean isTransitioning(LivingEntity entity, long gameTime) {
		return configuredSentinel(entity)
				.map(configured -> isTransitioning(configured.snapshot(), gameTime))
				.orElse(false);
	}

	public static boolean isTransitioning(
			ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		return snapshot != null && snapshot.revision() > 0L
				&& transitionProgress(snapshot, gameTime) < 1.0F;
	}

	/**
	 * Returns 1 for stable/default data, otherwise an eased-consumer-friendly normalized clock.
	 * Callers own the visual easing; the authority window itself remains exactly 30 server ticks.
	 */
	public static float transitionProgress(
			ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		if (snapshot == null || snapshot.revision() <= 0L) return 1.0F;
		long elapsed = Math.max(0L, gameTime - snapshot.transitionStartTick());
		return Mth.clamp((float) elapsed / (float) TRANSITION_DURATION_TICKS, 0.0F, 1.0F);
	}

	public static long transitionTicksRemaining(
			ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		if (snapshot == null || snapshot.revision() <= 0L) return 0L;
		long elapsed = Math.max(0L, gameTime - snapshot.transitionStartTick());
		return Math.max(0L, TRANSITION_DURATION_TICKS - elapsed);
	}

	public static boolean isWingmenTransitioning(
			ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		if (snapshot == null || snapshot.wingmenRevision() <= 0L) return false;
		long elapsed = Math.max(0L, gameTime - snapshot.wingmenTransitionStartTick());
		return elapsed < WINGMEN_TRANSITION_DURATION_TICKS;
	}

	/**
	 * Binary render ownership of the detachable H modules. There is deliberately no fractional
	 * interval: before the hand-off the armor renderer owns the exact module geometry, and on the
	 * hand-off frame the entity renderer owns that same geometry. This prevents translucent
	 * cross-fades, duplicate plates and "growing a second craft" silhouettes.
	 */
	public static boolean wingmenEntityOwnsGeometry(
			ArmorConfigurationData.Snapshot snapshot, double gameTime) {
		if (snapshot == null) return false;
		if (snapshot.wingmenRevision() <= 0L) return snapshot.wingmenDeployed();
		double elapsed = Math.max(0.0D,
				gameTime - snapshot.wingmenTransitionStartTick());
		boolean entityOwnedAtTransitionStart =
				snapshot.wingmenTransitionStartOwnership() >= 0.5F;
		if (snapshot.wingmenDeployed()) {
			return entityOwnedAtTransitionStart
					|| elapsed >= WINGMEN_HANDOFF_START_TICKS;
		}
		return entityOwnedAtTransitionStart && elapsed < 22.0D;
	}

	/**
	 * Compatibility scalar for render callers. The value is always exactly zero or one; callers
	 * must never use it as alpha or scale.
	 */
	public static float wingmenEntityOwnership(
			ArmorConfigurationData.Snapshot snapshot, long gameTime) {
		return wingmenEntityOwnership(snapshot, (double) gameTime);
	}

	public static float wingmenEntityOwnership(
			ArmorConfigurationData.Snapshot snapshot, double gameTime) {
		return wingmenEntityOwnsGeometry(snapshot, gameTime) ? 1.0F : 0.0F;
	}

	/**
	 * Non-Sentinel armor keeps its existing weapon contract. Sentinel must be one exact configured
	 * suit, be mechanically stable, and be in breach form.
	 */
	public static boolean allowsHeavyMelee(LivingEntity entity, long gameTime) {
		return allows(entity, gameTime, SentinelArmorMode.BREACH_MELEE);
	}

	/**
	 * Non-Sentinel armor keeps its existing ranged contract. Sentinel's wrist, core and pod lanes
	 * belong exclusively to overdrive form.
	 */
	public static boolean allowsRangedCombat(LivingEntity entity, long gameTime) {
		return allows(entity, gameTime, SentinelArmorMode.OVERDRIVE_FLIGHT);
	}

	/** True for a stable exact Sentinel or for any complete non-Sentinel set. */
	public static boolean allowsAnyCombat(LivingEntity entity, long gameTime) {
		ArmorVariant variant = entity == null
				? null : ModItems.fullSetVariant(entity).orElse(null);
		if (variant == null) return false;
		if (variant != ArmorVariant.SENTINEL) return true;
		return configuredSentinel(entity)
				.map(configured -> !isTransitioning(configured.snapshot(), gameTime))
				.orElse(false);
	}

	private static boolean allows(
			LivingEntity entity, long gameTime, SentinelArmorMode requiredMode) {
		ArmorVariant variant = entity == null
				? null : ModItems.fullSetVariant(entity).orElse(null);
		if (variant == null) return false;
		if (variant != ArmorVariant.SENTINEL) return true;
		return configuredSentinel(entity)
				.map(configured -> configured.snapshot().mode() == requiredMode
						&& !isTransitioning(configured.snapshot(), gameTime))
				.orElse(false);
	}

	public record ConfiguredSentinel(
			UUID ownerId, UUID suitId, ArmorConfigurationData.Snapshot snapshot) {
	}
}
