package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Server acknowledgement and authoritative routing state for nearby-suit commands.
 *
 * <p>The selected suit id is meaningful only while {@code remoteModeActive} is true. Sequence
 * zero is reserved for selection/session state changes; command acknowledgements echo the C2S
 * sequence so a rejected command can never look like a successful local weapon press.</p>
 */
public record ArmorRemoteCommandAckPayload(
		long clientSequence,
		long selectionGeneration,
		UUID suitId,
		boolean remoteModeActive,
		Outcome outcome,
		String message
) implements CustomPacketPayload {
	private static final UUID NO_SUIT = new UUID(0L, 0L);

	public static final Type<ArmorRemoteCommandAckPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_remote_command_ack"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorRemoteCommandAckPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
				buffer.writeVarLong(payload.clientSequence());
				buffer.writeVarLong(payload.selectionGeneration());
				buffer.writeUUID(payload.suitId() == null ? NO_SUIT : payload.suitId());
				buffer.writeBoolean(payload.remoteModeActive());
				buffer.writeVarInt(payload.outcome().ordinal());
				buffer.writeUtf(payload.message() == null ? "" : payload.message(), 160);
			}, buffer -> {
				long sequence = buffer.readVarLong();
				long selectionGeneration = buffer.readVarLong();
				UUID encodedSuit = buffer.readUUID();
				boolean active = buffer.readBoolean();
				Outcome outcome = Outcome.fromNetworkId(buffer.readVarInt());
				String message = buffer.readUtf(160);
				return new ArmorRemoteCommandAckPayload(sequence, selectionGeneration,
						NO_SUIT.equals(encodedSuit) ? null : encodedSuit,
						active, outcome, message);
			});

	public enum Outcome {
		SELECTED,
		CLEARED,
		ACCEPTED,
		REJECTED,
		OUT_OF_RANGE,
		NO_TARGET;

		private static Outcome fromNetworkId(int id) {
			Outcome[] values = values();
			return id >= 0 && id < values.length ? values[id] : REJECTED;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
