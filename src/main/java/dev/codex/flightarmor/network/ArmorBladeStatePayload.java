package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Persistent electronic-blade deployment state for the owner and trackers. */
public record ArmorBladeStatePayload(
		UUID playerId,
		long revision,
		boolean extended,
		ArmorVariant variant
) implements CustomPacketPayload {
	public static final Type<ArmorBladeStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_blade_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorBladeStatePayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeUUID(payload.playerId());
						buffer.writeVarLong(payload.revision());
						buffer.writeBoolean(payload.extended());
						buffer.writeUtf(payload.variant().serializedName(), 16);
					},
					buffer -> new ArmorBladeStatePayload(
							buffer.readUUID(),
							buffer.readVarLong(),
							buffer.readBoolean(),
							ArmorVariant.fromSerializedName(buffer.readUtf(16))));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
