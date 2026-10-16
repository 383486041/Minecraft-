package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 客户端请求播放一次分件召回着甲。物品与流程是否合法始终由服务端判定。 */
public record ArmorAssemblyRequestPayload() implements CustomPacketPayload {
	public static final Type<ArmorAssemblyRequestPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_assembly_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorAssemblyRequestPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
			}, buffer -> new ArmorAssemblyRequestPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
