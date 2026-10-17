package dev.codex.flightarmor.integrity;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;

import java.util.UUID;

/** Offline arithmetic, NBT restart and duplicate-record fault injection. */
public final class ArmorIntegrityPersistenceTest {
	private ArmorIntegrityPersistenceTest() {}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		verifyPureRules();
		verifyNbtRoundTripAndAttribution();
		verifyDuplicateIdentityResolution();
		System.out.println("ArmorIntegrityPersistenceTest passed");
	}

	private static void verifyPureRules() {
		require(ArmorIntegrityRules.damageUnits(1.0F,
				ArmorIntegrityRules.DamageKind.GENERIC) == 24, "generic conversion changed");
		require(ArmorIntegrityRules.damageUnits(1.0F,
				ArmorIntegrityRules.DamageKind.EXPLOSION) == 33, "explosion conversion changed");
		require(ArmorIntegrityRules.damageUnits(Float.NaN,
				ArmorIntegrityRules.DamageKind.GENERIC) == 0, "NaN damage was accepted");
		require(ArmorIntegrityRules.applyDamage(40, 80) == 0, "damage did not clamp at zero");
		require(ArmorIntegrityRules.repair(998, 8) == 1_000, "repair exceeded maximum");
		require(ArmorIntegrityRules.recoveryMovementMultiplier(0) == 0.30D,
				"disabled recovery movement floor changed");
		require(ArmorIntegrityRules.recoveryMovementMultiplier(250) == 1.0D,
				"healthy movement should not be slowed");
		require(ArmorIntegrityRules.scaledAttackDelay(10L, 100) > 10L,
				"degraded attack interval was not increased");
		require(ArmorIntegrityRules.scaledAttackDelay(10L, 0) == Long.MAX_VALUE,
				"disabled suit still received an attack cadence");
	}

	private static void verifyNbtRoundTripAndAttribution() {
		UUID owner = UUID.randomUUID();
		UUID suit = UUID.randomUUID();
		UUID direct = UUID.randomUUID();
		UUID causing = UUID.randomUUID();
		ArmorIntegritySavedData data = new ArmorIntegritySavedData();
		ArmorIntegritySavedData.Mutation damage =
				data.damage(owner, suit, 175, direct, causing, 400L);
		require(damage.changed() && damage.current() == 825, "damage mutation failed");

		CompoundTag encoded = data.save(new CompoundTag());
		require(!encoded.toString().contains("ItemStack")
				&& !encoded.toString().contains("Inventory")
				&& !encoded.toString().contains("ArmorItems"),
				"integrity SavedData duplicated armor items");
		ArmorIntegritySavedData decoded = ArmorIntegritySavedData.load(encoded);
		ArmorIntegritySavedData.Snapshot snapshot = decoded.snapshot(owner, suit);
		require(snapshot.integrity() == 825 && snapshot.revision() == 1L,
				"restart round-trip lost integrity or revision");
		require(direct.equals(snapshot.lastDirectAttackerId())
				&& causing.equals(snapshot.lastCausingAttackerId()),
				"direct/causing attacker attribution was not preserved");
		decoded.repair(owner, suit, 8, 401L);
		require(decoded.snapshot(owner, suit).integrity() == 833, "platform repair arithmetic failed");
	}

	private static void verifyDuplicateIdentityResolution() {
		UUID owner = UUID.randomUUID();
		UUID suit = UUID.randomUUID();
		ArmorIntegritySavedData.SuitKey key = new ArmorIntegritySavedData.SuitKey(owner, suit);
		ListTag list = new ListTag();
		list.add(ArmorIntegritySavedData.writeRecord(new ArmorIntegritySavedData.Record(
				key, 800, 7L, null, null, 20L)));
		list.add(ArmorIntegritySavedData.writeRecord(new ArmorIntegritySavedData.Record(
				key, 200, 7L, null, null, 21L)));
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 1);
		root.put("Suits", list);
		ArmorIntegritySavedData loaded = ArmorIntegritySavedData.load(root);
		require(loaded.snapshot(owner, suit).integrity() == 200,
				"equal-revision duplicate did not fail safe to lower integrity");
		require(loaded.snapshot(UUID.randomUUID(), suit).integrity()
				== ArmorIntegrityRules.MAX_INTEGRITY,
				"owner+suit composite identity leaked across owners");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
