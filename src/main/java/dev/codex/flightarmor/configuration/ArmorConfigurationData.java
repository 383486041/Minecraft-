package dev.codex.flightarmor.configuration;

import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Persistent Sentinel configuration stored on the chest piece.
 *
 * <p>The configuration has its own {@link DataComponents#CUSTOM_DATA} root so armor identity,
 * variant, and future modules survive a form change unchanged.</p>
 */
public final class ArmorConfigurationData {
	private static final String ROOT_KEY = "FlightArmorConfiguration";
	private static final String VERSION_KEY = "Version";
	private static final String MODE_KEY = "Mode";
	private static final String REVISION_KEY = "Revision";
	private static final String TRANSITION_START_KEY = "TransitionStart";
	private static final String WINGMEN_DEPLOYED_KEY = "WingmenDeployed";
	private static final String WINGMEN_REVISION_KEY = "WingmenRevision";
	private static final String WINGMEN_TRANSITION_START_KEY = "WingmenTransitionStart";
	private static final String WINGMEN_START_OWNERSHIP_KEY = "WingmenStartOwnership";
	private static final int FORMAT_VERSION = 2;
	private static volatile CachedSnapshot lastRead;

	private ArmorConfigurationData() {
	}

	public static Snapshot read(ItemStack chestStack) {
		if (chestStack == null || chestStack.isEmpty()) return Snapshot.DEFAULT;
		CustomData data = chestStack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Snapshot.DEFAULT;
		CachedSnapshot cached = lastRead;
		if (cached != null && cached.data == data) {
			return cached.snapshot;
		}
		CompoundTag customRoot = data.copyTag();
		if (!customRoot.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)) {
			lastRead = new CachedSnapshot(data, Snapshot.DEFAULT);
			return Snapshot.DEFAULT;
		}
		CompoundTag root = customRoot.getCompound(ROOT_KEY);
		int version = root.getInt(VERSION_KEY);
		if (version < 1 || version > FORMAT_VERSION) {
			lastRead = new CachedSnapshot(data, Snapshot.DEFAULT);
			return Snapshot.DEFAULT;
		}
		Snapshot parsed = new Snapshot(
				SentinelArmorMode.fromSerializedName(root.getString(MODE_KEY)),
				Math.max(0L, root.getLong(REVISION_KEY)),
				Math.max(0L, root.getLong(TRANSITION_START_KEY)),
				version >= 2 && root.getBoolean(WINGMEN_DEPLOYED_KEY),
				version >= 2 ? Math.max(0L, root.getLong(WINGMEN_REVISION_KEY)) : 0L,
				version >= 2
						? Math.max(0L, root.getLong(WINGMEN_TRANSITION_START_KEY)) : 0L,
				version >= 2 ? clampUnit(root.getFloat(WINGMEN_START_OWNERSHIP_KEY)) : 0.0F);
		lastRead = new CachedSnapshot(data, parsed);
		return parsed;
	}

	public static boolean set(ItemStack chestStack, SentinelArmorMode mode,
			long revision, long transitionStartTick) {
		if (chestStack == null || chestStack.isEmpty() || mode == null) return false;
		Snapshot current = read(chestStack);
		long wingmenRevision = current.wingmenRevision();
		long wingmenTransitionStart = current.wingmenTransitionStartTick();
		if (current.wingmenDeployed()) {
			float startOwnership = ArmorConfigurationAccess.wingmenEntityOwnership(
					current, transitionStartTick);
			wingmenRevision = nextRevision(wingmenRevision);
			wingmenTransitionStart = transitionStartTick;
			return write(chestStack, new Snapshot(mode, revision, transitionStartTick,
					false, wingmenRevision, wingmenTransitionStart, startOwnership));
		}
		return write(chestStack, new Snapshot(mode, revision, transitionStartTick,
				false, wingmenRevision, wingmenTransitionStart,
				current.wingmenTransitionStartOwnership()));
	}

	public static boolean setWingmenDeployed(ItemStack chestStack, boolean deployed,
			long transitionStartTick) {
		if (chestStack == null || chestStack.isEmpty()) return false;
		Snapshot current = read(chestStack);
		if (current.wingmenDeployed() == deployed) return true;
		float startOwnership = ArmorConfigurationAccess.wingmenEntityOwnership(
				current, transitionStartTick);
		return write(chestStack, new Snapshot(
				current.mode(), current.revision(), current.transitionStartTick(),
				deployed, nextRevision(current.wingmenRevision()), transitionStartTick,
				startOwnership));
	}

	private static boolean write(ItemStack chestStack, Snapshot snapshot) {
		if (chestStack == null || chestStack.isEmpty() || snapshot == null
				|| snapshot.mode() == null) return false;
		CustomData oldData = chestStack.get(DataComponents.CUSTOM_DATA);
		CompoundTag customRoot = oldData == null ? new CompoundTag() : oldData.copyTag();
		CompoundTag root = new CompoundTag();
		root.putInt(VERSION_KEY, FORMAT_VERSION);
		root.putString(MODE_KEY, snapshot.mode().serializedName());
		root.putLong(REVISION_KEY, Math.max(0L, snapshot.revision()));
		root.putLong(TRANSITION_START_KEY, Math.max(0L, snapshot.transitionStartTick()));
		root.putBoolean(WINGMEN_DEPLOYED_KEY, snapshot.wingmenDeployed());
		root.putLong(WINGMEN_REVISION_KEY, Math.max(0L, snapshot.wingmenRevision()));
		root.putLong(WINGMEN_TRANSITION_START_KEY,
				Math.max(0L, snapshot.wingmenTransitionStartTick()));
		root.putFloat(WINGMEN_START_OWNERSHIP_KEY,
				clampUnit(snapshot.wingmenTransitionStartOwnership()));
		customRoot.put(ROOT_KEY, root);
		CustomData updated = CustomData.of(customRoot);
		chestStack.set(DataComponents.CUSTOM_DATA, updated);
		lastRead = new CachedSnapshot(updated, snapshot);
		return true;
	}

	private static long nextRevision(long current) {
		return current >= Long.MAX_VALUE - 1L ? 1L : Math.max(0L, current) + 1L;
	}

	private static float clampUnit(float value) {
		return Float.isFinite(value) ? Math.max(0.0F, Math.min(1.0F, value)) : 0.0F;
	}

	/**
	 * Returns a configuration only for a complete worn Sentinel set. Consumers never accidentally
	 * interpret an isolated Sentinel chest plate or another armor variant as a transformed suit.
	 */
	public static Optional<Snapshot> equippedSentinel(LivingEntity entity) {
		if (entity == null
				|| ModItems.fullSetVariant(entity).orElse(null) != ArmorVariant.SENTINEL) {
			return Optional.empty();
		}
		ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
		if (!chest.is(ModItems.FLIGHT_CHESTPLATE)) return Optional.empty();
		return Optional.of(read(chest));
	}

	public record Snapshot(SentinelArmorMode mode, long revision, long transitionStartTick,
			boolean wingmenDeployed, long wingmenRevision, long wingmenTransitionStartTick,
			float wingmenTransitionStartOwnership) {
		public Snapshot(SentinelArmorMode mode, long revision, long transitionStartTick) {
			this(mode, revision, transitionStartTick, false, 0L, 0L, 0.0F);
		}

		private static final Snapshot DEFAULT =
				new Snapshot(SentinelArmorMode.DEFAULT, 0L, 0L,
						false, 0L, 0L, 0.0F);
	}

	private record CachedSnapshot(CustomData data, Snapshot snapshot) {
	}
}
