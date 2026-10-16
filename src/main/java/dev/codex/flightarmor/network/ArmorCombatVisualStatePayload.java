package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.combat.ArmorCombatPhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** S2C 可追踪的攻击姿势快照；瞬发脉冲也保留短暂 FIRING/RECOVERY 阶段。 */
public record ArmorCombatVisualStatePayload(
		UUID attackerId,
		long serverAttackId,
		int visualRevision,
		ArmorAttackType attackType,
		ArmorCombatPhase phase,
		boolean leftHand,
		long serverStartGameTime,
		int phaseDurationTicks
) implements CustomPacketPayload {
	public static final Type<ArmorCombatVisualStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_combat_visual_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCombatVisualStatePayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.attackerId());
				buffer.writeVarLong(payload.serverAttackId());
				buffer.writeVarInt(payload.visualRevision());
				buffer.writeVarInt(payload.attackType().ordinal());
				buffer.writeVarInt(payload.phase().ordinal());
				buffer.writeBoolean(payload.leftHand());
				buffer.writeLong(payload.serverStartGameTime());
				buffer.writeVarInt(payload.phaseDurationTicks());
			},
			buffer -> new ArmorCombatVisualStatePayload(
					buffer.readUUID(),
					buffer.readVarLong(),
					buffer.readVarInt(),
					ArmorAttackType.fromNetworkId(buffer.readVarInt()),
					ArmorCombatPhase.fromNetworkId(buffer.readVarInt()),
					buffer.readBoolean(),
					buffer.readLong(),
					buffer.readVarInt()
			)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
