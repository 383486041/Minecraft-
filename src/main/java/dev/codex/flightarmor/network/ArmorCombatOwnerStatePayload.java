package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorCombatDenyReason;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C 所属玩家私有战斗快照。
 * acceptedClientSequence 是服务端最后消费的新意图；重复包不会推进该值。
 */
public record ArmorCombatOwnerStatePayload(
		long acceptedClientSequence,
		long serverAttackId,
		float energy,
		float heat,
		boolean overheated,
		int wristPulseCooldownTicks,
		int coreBeamCooldownTicks,
		int diveImpactCooldownTicks,
		ArmorCombatPhase phase,
		ArmorCombatDenyReason denyReason
) implements CustomPacketPayload {
	public static final Type<ArmorCombatOwnerStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_combat_owner_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCombatOwnerStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarLong(payload.acceptedClientSequence());
				buffer.writeVarLong(payload.serverAttackId());
				buffer.writeFloat(payload.energy());
				buffer.writeFloat(payload.heat());
				buffer.writeBoolean(payload.overheated());
				buffer.writeVarInt(payload.wristPulseCooldownTicks());
				buffer.writeVarInt(payload.coreBeamCooldownTicks());
				buffer.writeVarInt(payload.diveImpactCooldownTicks());
				buffer.writeVarInt(payload.phase().ordinal());
				buffer.writeVarInt(payload.denyReason().ordinal());
			},
			buffer -> new ArmorCombatOwnerStatePayload(
					buffer.readVarLong(),
					buffer.readVarLong(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readBoolean(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					ArmorCombatPhase.fromNetworkId(buffer.readVarInt()),
					ArmorCombatDenyReason.fromNetworkId(buffer.readVarInt())
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
