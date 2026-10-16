package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** 服务端广播的独立巡航载体快照。载体的位置由原版实体同步负责。 */
public record ArmorCruiseStatePayload(
		UUID playerId,
		long sequenceId,
		ArmorCruisePhase phase,
		UUID carrierId,
		int carrierEntityId,
		long phaseStartGameTime,
		/** 0 表示普通召回；非 0 值同时作为应急悬停会话的防重放标识。 */
		long emergencyRecallAttemptId
) implements CustomPacketPayload {
	public static final UUID NO_CARRIER_ID = new UUID(0L, 0L);
	public static final int NO_CARRIER_ENTITY_ID = -1;
	public static final Type<ArmorCruiseStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_cruise_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCruiseStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.playerId());
				buffer.writeLong(payload.sequenceId());
				buffer.writeVarInt(payload.phase().ordinal());
				buffer.writeUUID(payload.carrierId());
				buffer.writeVarInt(payload.carrierEntityId());
				buffer.writeLong(payload.phaseStartGameTime());
				buffer.writeLong(payload.emergencyRecallAttemptId());
			},
			buffer -> new ArmorCruiseStatePayload(
					buffer.readUUID(),
					buffer.readLong(),
					ArmorCruisePhase.fromNetworkId(buffer.readVarInt()),
					buffer.readUUID(),
					buffer.readVarInt(),
					buffer.readLong(),
					buffer.readLong()
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
