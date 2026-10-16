package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 服务端结束指定自动悬停会话，客户端据此清除旧令牌与本地预测。 */
public record AutomaticHoverEndPayload(long engagementId) implements CustomPacketPayload {
	public static final Type<AutomaticHoverEndPayload> TYPE =
			new Type<>(FlightArmorMod.id("automatic_hover_end"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AutomaticHoverEndPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> buffer.writeVarLong(payload.engagementId()),
					buffer -> new AutomaticHoverEndPayload(buffer.readVarLong()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
