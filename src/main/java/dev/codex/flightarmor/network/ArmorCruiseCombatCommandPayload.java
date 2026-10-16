package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.cruise.CruiseCombatMode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ArmorCruiseCombatCommandPayload(
		long clientSequence,
		long cruiseSequenceId,
		UUID carrierId,
		CruiseCombatMode requestedMode
) implements CustomPacketPayload {
	public static final Type<ArmorCruiseCombatCommandPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_cruise_combat_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCruiseCombatCommandPayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeVarLong(payload.clientSequence());
						buffer.writeVarLong(payload.cruiseSequenceId());
						buffer.writeUUID(payload.carrierId());
						buffer.writeVarInt(payload.requestedMode().ordinal());
					},
					buffer -> new ArmorCruiseCombatCommandPayload(
							buffer.readVarLong(),
							buffer.readVarLong(),
							buffer.readUUID(),
							CruiseCombatMode.fromNetworkId(buffer.readVarInt())
					)
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
