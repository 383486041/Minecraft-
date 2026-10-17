package dev.codex.flightarmor.core;

import dev.codex.flightarmor.util.TagUtils;
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
		CompoundTag tag = root.getCompoundOrEmpty(ROOT);
		if (tag.getIntOr("Version", 0) != FORMAT_VERSION || !TagUtils.hasUUID(tag, "Core")
				|| !TagUtils.hasUUID(tag, "Owner") || !TagUtils.hasUUID(tag, "Nonce")) return Optional.empty();
		return Optional.of(new Identity(TagUtils.getUUID(tag, "Core"), TagUtils.getUUID(tag, "Owner"), TagUtils.getUUID(tag, "Nonce")));
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
		TagUtils.putUUID(tag, "Core", created.coreId());
		TagUtils.putUUID(tag, "Owner", created.ownerId());
		TagUtils.putUUID(tag, "Nonce", created.instanceNonce());
		root.put(ROOT, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return Optional.of(created);
	}

	public record Identity(UUID coreId, UUID ownerId, UUID instanceNonce) {
	}
}
