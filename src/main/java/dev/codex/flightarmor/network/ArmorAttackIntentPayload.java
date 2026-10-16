package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorAttackAction;
import dev.codex.flightarmor.combat.ArmorAttackType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S 攻击意图。客户端不能指定目标、伤害、命中点或发射手。 */
public record ArmorAttackIntentPayload(
		long clientSequence,
		ArmorAttackType attackType,
		ArmorAttackAction action
) implements CustomPacketPayload {
	public static final Type<ArmorAttackIntentPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_attack_intent"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorAttackIntentPayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarLong(payload.clientSequence());
				buffer.writeVarInt(payload.attackType().ordinal());
				buffer.writeVarInt(payload.action().ordinal());
			},
			buffer -> new ArmorAttackIntentPayload(
					buffer.readVarLong(),
					ArmorAttackType.fromNetworkId(buffer.readVarInt()),
					ArmorAttackAction.fromNetworkId(buffer.readVarInt())
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
