package dev.codex.flightarmor.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

/** 装甲核心 ItemStack 身份；平台列表保存在服务器 SavedData，不写进可复制的物品 NBT。 */
public final class ArmorCoreIdentity {
	private static final String ROOT = "FlightArmorNavigationCore";
	private static final int FORMAT_VERSION = 1;

	private ArmorCoreIdentity() {
	}

	public static Optional<Identity> read(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		CompoundTag root = data.copyTag();
		if (!root.contains(ROOT)) return Optional.empty();
		CompoundTag tag = root.getCompound(ROOT);
		if (tag.getInt("Version") != FORMAT_VERSION || !tag.hasUUID("Core")
				|| !tag.hasUUID("Owner") || !tag.hasUUID("Nonce")) return Optional.empty();
		return Optional.of(new Identity(tag.getUUID("Core"), tag.getUUID("Owner"), tag.getUUID("Nonce")));
	}

	public static Optional<Identity> claim(ItemStack stack, UUID ownerId) {
		Optional<Identity> existing = read(stack);
		if (existing.isPresent()) {
			return existing.get().ownerId().equals(ownerId) ? existing : Optional.empty();
		}
		Identity created = new Identity(UUID.randomUUID(), ownerId, UUID.randomUUID());
		CustomData oldData = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag root = oldData == null ? new CompoundTag() : oldData.copyTag();
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", FORMAT_VERSION);
		tag.putUUID("Core", created.coreId());
		tag.putUUID("Owner", created.ownerId());
		tag.putUUID("Nonce", created.instanceNonce());
		root.put(ROOT, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return Optional.of(created);
	}

	public record Identity(UUID coreId, UUID ownerId, UUID instanceNonce) {
	}
}
