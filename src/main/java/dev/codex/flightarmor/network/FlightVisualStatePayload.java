package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 服务器广播给客户端的飞行外观状态。
 *
 * <p>这里刻意不携带每刻的速度或坐标，只同步会改变盔甲外观的离散状态，
 * 具体展开动画由各客户端自行平滑插值。</p>
 */
public record FlightVisualStatePayload(UUID playerId, boolean active, boolean diving, boolean boosting, boolean moving)
		implements CustomPacketPayload {
	public static final Type<FlightVisualStatePayload> TYPE = new Type<>(FlightArmorMod.id("flight_visual_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FlightVisualStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.playerId());
				buffer.writeBoolean(payload.active());
				buffer.writeBoolean(payload.diving());
				buffer.writeBoolean(payload.boosting());
				buffer.writeBoolean(payload.moving());
			},
			buffer -> new FlightVisualStatePayload(buffer.readUUID(), buffer.readBoolean(), buffer.readBoolean(),
					buffer.readBoolean(), buffer.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
