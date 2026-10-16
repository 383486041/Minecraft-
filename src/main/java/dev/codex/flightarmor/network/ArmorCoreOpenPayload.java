package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ArmorCoreOpenPayload() implements CustomPacketPayload {
	public static final Type<ArmorCoreOpenPayload> TYPE = new Type<>(FlightArmorMod.id("armor_core_open"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCoreOpenPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {}, buffer -> new ArmorCoreOpenPayload());
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
