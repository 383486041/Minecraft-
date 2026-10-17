package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 服务端确认空中着装完成后，通知所属客户端接管现有悬停输入状态。 */
public record AutomaticHoverEngagePayload(long engagementId, AutomaticHoverSource source,
		long sourceSequenceId, long sourceAttemptId,
		Identifier dimension) implements CustomPacketPayload {
	public static final Type<AutomaticHoverEngagePayload> TYPE =
			new Type<>(FlightArmorMod.id("automatic_hover_engage"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AutomaticHoverEngagePayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
				buffer.writeVarLong(payload.engagementId());
				buffer.writeByte(payload.source().networkId());
				buffer.writeVarLong(payload.sourceSequenceId());
				buffer.writeVarLong(payload.sourceAttemptId());
				buffer.writeIdentifier(payload.dimension());
			}, buffer -> new AutomaticHoverEngagePayload(
					buffer.readVarLong(),
					AutomaticHoverSource.fromNetworkId(buffer.readUnsignedByte()),
					buffer.readVarLong(),
					buffer.readVarLong(),
					buffer.readIdentifier()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
