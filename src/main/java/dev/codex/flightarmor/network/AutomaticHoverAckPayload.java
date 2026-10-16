package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端确认已经接管指定自动悬停会话，服务端随后恢复处理普通关闭输入。 */
public record AutomaticHoverAckPayload(long engagementId) implements CustomPacketPayload {
	public static final Type<AutomaticHoverAckPayload> TYPE =
			new Type<>(FlightArmorMod.id("automatic_hover_ack"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AutomaticHoverAckPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> buffer.writeVarLong(payload.engagementId()),
					buffer -> new AutomaticHoverAckPayload(buffer.readVarLong()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
