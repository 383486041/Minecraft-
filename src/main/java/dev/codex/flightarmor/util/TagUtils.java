package dev.codex.flightarmor.util;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

public final class TagUtils {
	private TagUtils() {
	}

	public static void putUUID(CompoundTag tag, String key, UUID uuid) {
		if (uuid != null) {
			tag.putIntArray(key, UUIDUtil.uuidToIntArray(uuid));
		}
	}

	public static UUID getUUID(CompoundTag tag, String key) {
		return tag.getIntArray(key).map(UUIDUtil::uuidFromIntArray).orElse(null);
	}

	public static boolean hasUUID(CompoundTag tag, String key) {
		return tag.contains(key);
	}
}
