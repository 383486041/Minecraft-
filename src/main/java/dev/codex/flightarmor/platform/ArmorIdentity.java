package dev.codex.flightarmor.platform;

import dev.codex.flightarmor.item.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/**
 * 装甲认主数据的唯一读写入口。
 *
 * <p>身份写在原版 {@link DataComponents#CUSTOM_DATA} 中，因此物品在玩家、实体、掉落物和
 * 方块实体之间迁移时都会自然保留。四件装甲共享 ownerId 和 suitId，但各自记录固定槽位，
 * 平台不会接受被改名成其他部件的伪造物品。</p>
 */
public final class ArmorIdentity {
	private static final String ROOT_KEY = "FlightArmorIdentity";
	private static final String OWNER_KEY = "Owner";
	private static final String SUIT_KEY = "Suit";
	private static final String SLOT_KEY = "Slot";
	private static final String VERSION_KEY = "Version";
	private static final int FORMAT_VERSION = 1;

	private ArmorIdentity() {
	}

	public static Optional<Identity> read(ItemStack stack) {
		if (stack.isEmpty()) return Optional.empty();
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		CompoundTag root = data.copyTag();
		if (!root.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag identity = root.getCompound(ROOT_KEY);
		if (identity.getInt(VERSION_KEY) != FORMAT_VERSION) return Optional.empty();
		if (!identity.hasUUID(OWNER_KEY) || !identity.hasUUID(SUIT_KEY)) return Optional.empty();
		EquipmentSlot slot;
		try {
			slot = EquipmentSlot.valueOf(identity.getString(SLOT_KEY));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
		if (!isArmorSlot(slot) || !ModItems.isExactFlightArmor(stack, slot)) return Optional.empty();
		return Optional.of(new Identity(identity.getUUID(OWNER_KEY), identity.getUUID(SUIT_KEY), slot));
	}

	/** 给尚未认主的部件写入身份；已有身份必须与请求完全一致。 */
	public static boolean claim(ItemStack stack, UUID ownerId, UUID suitId, EquipmentSlot slot) {
		if (!ModItems.isExactFlightArmor(stack, slot)) return false;
		Optional<Identity> present = read(stack);
		if (present.isPresent()) return present.get().matches(ownerId, suitId, slot);

		CustomData oldData = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = oldData == null ? new CompoundTag() : oldData.copyTag();
		// 只要身份根节点已经存在但无法按当前格式读取，就视为损坏或未来格式。
		// 这里必须失败关闭，不能通过一次 claim 覆盖掉未知身份而被重新认主。
		if (root.contains(ROOT_KEY)) return false;

		CompoundTag identity = new CompoundTag();
		identity.putInt(VERSION_KEY, FORMAT_VERSION);
		identity.putUUID(OWNER_KEY, ownerId);
		identity.putUUID(SUIT_KEY, suitId);
		identity.putString(SLOT_KEY, slot.name());
		root.put(ROOT_KEY, identity);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return true;
	}

	public static boolean belongsTo(ItemStack stack, UUID ownerId, UUID suitId, EquipmentSlot slot) {
		return read(stack).map(identity -> identity.matches(ownerId, suitId, slot)).orElse(false);
	}

	/**
	 * Unclaimed armor remains usable before its first platform bind. Once claimed, only the owner may use it.
	 * A malformed identity marker fails closed instead of silently becoming unowned.
	 */
	public static boolean canBeUsedBy(ItemStack stack, UUID playerId, EquipmentSlot slot) {
		if (!ModItems.isExactFlightArmor(stack, slot)) return false;
		Optional<Identity> identity = read(stack);
		if (identity.isPresent()) return identity.get().ownerId().equals(playerId) && identity.get().slot() == slot;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null || !data.copyTag().contains(ROOT_KEY);
	}

	public static Optional<EquipmentSlot> armorSlot(ItemStack stack) {
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (ModItems.isExactFlightArmor(stack, slot)) return Optional.of(slot);
		}
		return Optional.empty();
	}

	private static boolean isArmorSlot(EquipmentSlot slot) {
		return slot == EquipmentSlot.FEET || slot == EquipmentSlot.LEGS
				|| slot == EquipmentSlot.CHEST || slot == EquipmentSlot.HEAD;
	}

	public record Identity(UUID ownerId, UUID suitId, EquipmentSlot slot) {
		public boolean matches(UUID expectedOwner, UUID expectedSuit, EquipmentSlot expectedSlot) {
			return ownerId.equals(expectedOwner) && suitId.equals(expectedSuit) && slot == expectedSlot;
		}
	}
}
