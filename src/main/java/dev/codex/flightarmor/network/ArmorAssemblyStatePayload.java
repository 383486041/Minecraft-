package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 服务端权威的分件着甲快照。客户端使用开始游戏时间和固定时长自行计算连续动画进度。
 */
public record ArmorAssemblyStatePayload(
		UUID playerId,
		long sequenceId,
		long startGameTime,
		int durationTicks,
		boolean active,
		boolean assembling,
		int affectedSlotMask,
		boolean retracting,
		int timelineStartTick,
		int timelineEndTick,
		boolean locksFlight
) implements CustomPacketPayload {
	public static final Type<ArmorAssemblyStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_assembly_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorAssemblyStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.playerId());
				buffer.writeLong(payload.sequenceId());
				buffer.writeLong(payload.startGameTime());
				buffer.writeVarInt(payload.durationTicks());
				buffer.writeBoolean(payload.active());
				buffer.writeBoolean(payload.assembling());
				buffer.writeVarInt(payload.affectedSlotMask());
				buffer.writeBoolean(payload.retracting());
				buffer.writeVarInt(payload.timelineStartTick());
				buffer.writeVarInt(payload.timelineEndTick());
				buffer.writeBoolean(payload.locksFlight());
			},
			buffer -> new ArmorAssemblyStatePayload(
					buffer.readUUID(),
					buffer.readLong(),
					buffer.readLong(),
					buffer.readVarInt(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readVarInt(),
					buffer.readBoolean(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readBoolean()
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
