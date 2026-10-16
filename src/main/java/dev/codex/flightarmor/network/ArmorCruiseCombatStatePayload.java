package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.cruise.CruiseCombatBehavior;
import dev.codex.flightarmor.cruise.CruiseCombatMode;
import dev.codex.flightarmor.cruise.CruiseCombatStage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record ArmorCruiseCombatStatePayload(
		UUID ownerId,
		UUID carrierId,
		long cruiseSequenceId,
		long combatRevision,
		long serverAttackId,
		CruiseCombatMode mode,
		CruiseCombatBehavior behavior,
		ArmorAttackType attackType,
		CruiseCombatStage stage,
		int targetEntityId,
		boolean leftHand,
		long phaseStartGameTime,
		int phaseDurationTicks
) implements CustomPacketPayload {
	public static final int NO_TARGET_ENTITY_ID = -1;
	public static final Type<ArmorCruiseCombatStatePayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_cruise_combat_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCruiseCombatStatePayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeUUID(payload.ownerId());
						buffer.writeUUID(payload.carrierId());
						buffer.writeVarLong(payload.cruiseSequenceId());
						buffer.writeVarLong(payload.combatRevision());
						buffer.writeVarLong(payload.serverAttackId());
						buffer.writeVarInt(payload.mode().ordinal());
						buffer.writeVarInt(payload.behavior().ordinal());
						buffer.writeVarInt(payload.attackType().ordinal());
						buffer.writeVarInt(payload.stage().ordinal());
						buffer.writeVarInt(payload.targetEntityId());
						buffer.writeBoolean(payload.leftHand());
						buffer.writeLong(payload.phaseStartGameTime());
						buffer.writeVarInt(payload.phaseDurationTicks());
					},
					buffer -> new ArmorCruiseCombatStatePayload(
							buffer.readUUID(),
							buffer.readUUID(),
							buffer.readVarLong(),
							buffer.readVarLong(),
							buffer.readVarLong(),
							CruiseCombatMode.fromNetworkId(buffer.readVarInt()),
							CruiseCombatBehavior.fromNetworkId(buffer.readVarInt()),
							ArmorAttackType.fromNetworkId(buffer.readVarInt()),
							CruiseCombatStage.fromNetworkId(buffer.readVarInt()),
							buffer.readVarInt(),
							buffer.readBoolean(),
							buffer.readLong(),
							buffer.readVarInt()
					)
			);

	public ArmorCruiseCombatStatePayload {
		if (stage == null || stage == CruiseCombatStage.INVALID) {
			stage = CruiseCombatStage.fromLegacy(mode, behavior, attackType);
		}
	}

	/** Source-compatible bridge for callers using the payload shape from before combat stages. */
	public ArmorCruiseCombatStatePayload(UUID ownerId, UUID carrierId, long cruiseSequenceId,
			long combatRevision, long serverAttackId, CruiseCombatMode mode,
			CruiseCombatBehavior behavior, ArmorAttackType attackType, int targetEntityId,
			boolean leftHand, long phaseStartGameTime, int phaseDurationTicks) {
		this(ownerId, carrierId, cruiseSequenceId, combatRevision, serverAttackId, mode, behavior,
				attackType, CruiseCombatStage.fromLegacy(mode, behavior, attackType), targetEntityId,
				leftHand, phaseStartGameTime, phaseDurationTicks);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
