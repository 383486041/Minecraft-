package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** H-key intent for the currently worn, exact Sentinel suit. */
public record SentinelWingmanCommandPayload(
		long clientSequence,
		UUID expectedSuitId
) implements CustomPacketPayload {
	public static final Type<SentinelWingmanCommandPayload> TYPE =
			new Type<>(FlightArmorMod.id("sentinel_wingman_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SentinelWingmanCommandPayload>
			STREAM_CODEC = StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeVarLong(payload.clientSequence());
						buffer.writeUUID(payload.expectedSuitId());
					},
					buffer -> new SentinelWingmanCommandPayload(
							buffer.readVarLong(), buffer.readUUID()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
