package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端只提交操作意图，具体槽位与物品事务始终由服务端判定。 */
public record ArmorWardrobeRequestPayload(ArmorWardrobeAction action) implements CustomPacketPayload {
	public static final Type<ArmorWardrobeRequestPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_wardrobe_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorWardrobeRequestPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> buffer.writeByte(payload.action().networkId()),
					buffer -> new ArmorWardrobeRequestPayload(
							ArmorWardrobeAction.fromNetworkId(buffer.readUnsignedByte())));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
