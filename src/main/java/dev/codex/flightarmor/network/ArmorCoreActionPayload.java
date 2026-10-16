package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.core.ArmorCoreAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ArmorCoreActionPayload(long clientSequence, ArmorCoreAction action, UUID suitId)
		implements CustomPacketPayload {
	public static final Type<ArmorCoreActionPayload> TYPE = new Type<>(FlightArmorMod.id("armor_core_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCoreActionPayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarLong(payload.clientSequence());
				buffer.writeVarInt(payload.action().ordinal());
				buffer.writeBoolean(payload.suitId() != null);
				if (payload.suitId() != null) buffer.writeUUID(payload.suitId());
			},
			buffer -> new ArmorCoreActionPayload(buffer.readVarLong(),
					ArmorCoreAction.fromNetworkId(buffer.readVarInt()),
					buffer.readBoolean() ? buffer.readUUID() : null));
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
