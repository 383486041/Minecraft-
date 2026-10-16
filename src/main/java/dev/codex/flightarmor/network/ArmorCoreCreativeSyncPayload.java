package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Mirrors the extra core slot after a creative-inventory pickup. Vanilla creative slot packets
 * only accept slots 1..45, while the appended core slot is index 46. The carried stack is part of
 * the same atomic transaction so an authoritative refresh cannot erase a core just picked up.
 */
public record ArmorCoreCreativeSyncPayload(
		ItemStack core, ItemStack expectedCore, ItemStack carriedAfter) implements CustomPacketPayload {
	public static final Type<ArmorCoreCreativeSyncPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_core_creative_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCoreCreativeSyncPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
						ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.core);
						ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.expectedCore);
						ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.carriedAfter);
					},
					buffer -> new ArmorCoreCreativeSyncPayload(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
							ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
							ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)));

	public ArmorCoreCreativeSyncPayload {
		core = core.isEmpty() ? ItemStack.EMPTY : core.copyWithCount(1);
		expectedCore = expectedCore.isEmpty() ? ItemStack.EMPTY : expectedCore.copyWithCount(1);
		carriedAfter = carriedAfter.isEmpty() ? ItemStack.EMPTY : carriedAfter.copyWithCount(1);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
