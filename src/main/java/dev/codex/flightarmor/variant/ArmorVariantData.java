package dev.codex.flightarmor.variant;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Objects;
import java.util.Optional;

/**
 * 战甲型号在 {@link DataComponents#CUSTOM_DATA} 中的唯一读写入口。
 *
 * <p>工具类不依赖 ModItems，因此配方、平台、命令或后续升级台都可以复用。调用者负责确认
 * 目标物品确实是凌空战甲。写入时会保留认主数据和其他模组共存的 CustomData。</p>
 */
public final class ArmorVariantData {
	private static final String ROOT_KEY = "FlightArmorVariant";
	private static final String VERSION_KEY = "Version";
	private static final String VARIANT_KEY = "Variant";
	private static final int FORMAT_VERSION = 1;

	private ArmorVariantData() {
	}

	/** 未写入、损坏或未来未知格式一律安全回退到先锋型。 */
	public static ArmorVariant get(ItemStack stack) {
		return readExplicit(stack).orElse(ArmorVariant.DEFAULT);
	}

	/** 仅在物品带有当前格式的有效显式型号时返回值。 */
	public static Optional<ArmorVariant> readExplicit(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return Optional.empty();
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		CompoundTag customRoot = data.copyTag();
		if (!customRoot.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag variantRoot = customRoot.getCompound(ROOT_KEY);
		if (variantRoot.getInt(VERSION_KEY) != FORMAT_VERSION) return Optional.empty();
		return ArmorVariant.find(variantRoot.getString(VARIANT_KEY));
	}

	/**
	 * 写入一个显式型号。空物品不会被修改；返回值表示是否成功写入。
	 */
	public static boolean set(ItemStack stack, ArmorVariant variant) {
		if (stack == null || stack.isEmpty()) return false;
		Objects.requireNonNull(variant, "variant");
		CustomData oldData = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag customRoot = oldData == null ? new CompoundTag() : oldData.copyTag();
		CompoundTag variantRoot = new CompoundTag();
		variantRoot.putInt(VERSION_KEY, FORMAT_VERSION);
		variantRoot.putString(VARIANT_KEY, variant.serializedName());
		customRoot.put(ROOT_KEY, variantRoot);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customRoot));
		return true;
	}

	/** 清除显式数据后，物品会按默认先锋型处理。 */
	public static boolean clear(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData oldData = stack.get(DataComponents.CUSTOM_DATA);
		if (oldData == null) return false;
		CompoundTag customRoot = oldData.copyTag();
		if (!customRoot.contains(ROOT_KEY)) return false;
		customRoot.remove(ROOT_KEY);
		if (customRoot.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customRoot));
		}
		return true;
	}
}
