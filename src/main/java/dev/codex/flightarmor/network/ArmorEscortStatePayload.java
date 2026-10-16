package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ArmorEscortStatePayload(UUID ownerId, UUID suitId, UUID carrierId,
		int carrierEntityId, boolean active, boolean guardEnabled, EscortPhase phase) implements CustomPacketPayload {
	public static final UUID NO_CARRIER = new UUID(0L, 0L);
	public static final Type<ArmorEscortStatePayload> TYPE = new Type<>(FlightArmorMod.id("armor_escort_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorEscortStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.ownerId()); buffer.writeUUID(payload.suitId());
				buffer.writeUUID(payload.carrierId()); buffer.writeVarInt(payload.carrierEntityId());
				buffer.writeBoolean(payload.active()); buffer.writeBoolean(payload.guardEnabled());
				buffer.writeVarInt(payload.phase().ordinal());
			},
			buffer -> new ArmorEscortStatePayload(buffer.readUUID(), buffer.readUUID(), buffer.readUUID(),
					buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
					EscortPhase.fromNetworkId(buffer.readVarInt())));

	public enum EscortPhase {
		LAUNCHING,
		TRANSIT,
		ESCORTING;

		private static EscortPhase fromNetworkId(int id) {
			EscortPhase[] values = values();
			return id >= 0 && id < values.length ? values[id] : ESCORTING;
		}
	}
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
