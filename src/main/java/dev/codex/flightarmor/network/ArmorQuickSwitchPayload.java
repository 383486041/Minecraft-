package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client intent to cycle to the next suit bound to the active navigation core. */
public record ArmorQuickSwitchPayload(long clientSequence) implements CustomPacketPayload {
	public static final Type<ArmorQuickSwitchPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_quick_switch"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorQuickSwitchPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> buffer.writeVarLong(payload.clientSequence()),
					buffer -> new ArmorQuickSwitchPayload(buffer.readVarLong()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}



