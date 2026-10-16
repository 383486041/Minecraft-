package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightInputPayload(long flightSessionToken, boolean active, boolean diving,
		boolean boosting, boolean movingForward, boolean movingBackward,
		boolean movingLeft, boolean movingRight, boolean ascending, boolean descending,
		float steeringYaw, float steeringPitch) implements CustomPacketPayload {
	public static final Type<FlightInputPayload> TYPE = new Type<>(FlightArmorMod.id("flight_input"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FlightInputPayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarLong(payload.flightSessionToken());
				buffer.writeBoolean(payload.active());
				buffer.writeBoolean(payload.diving());
				buffer.writeBoolean(payload.boosting());
				buffer.writeBoolean(payload.movingForward());
				buffer.writeBoolean(payload.movingBackward());
				buffer.writeBoolean(payload.movingLeft());
				buffer.writeBoolean(payload.movingRight());
				buffer.writeBoolean(payload.ascending());
				buffer.writeBoolean(payload.descending());
				buffer.writeFloat(payload.steeringYaw());
				buffer.writeFloat(payload.steeringPitch());
			},
			buffer -> new FlightInputPayload(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
					buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
					buffer.readBoolean(), buffer.readBoolean(), buffer.readFloat(), buffer.readFloat())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
