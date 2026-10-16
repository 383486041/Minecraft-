package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client intent to toggle the complete worn Sentinel armor between its two forms. */
public record ArmorConfigurationTogglePayload(long clientSequence)
		implements CustomPacketPayload {
	public static final Type<ArmorConfigurationTogglePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_configuration_toggle"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorConfigurationTogglePayload>
			STREAM_CODEC = StreamCodec.of(
					(buffer, payload) -> buffer.writeVarLong(payload.clientSequence()),
					buffer -> new ArmorConfigurationTogglePayload(buffer.readVarLong()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
