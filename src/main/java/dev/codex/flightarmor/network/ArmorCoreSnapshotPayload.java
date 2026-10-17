package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.core.ArmorCoreAction;
import dev.codex.flightarmor.core.ArmorEscortProfile;
import dev.codex.flightarmor.runtime.SuitRuntimeSnapshot;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 核心菜单只读快照；所有按钮点击后服务端都会重新校验平台实体与真实 ItemStack。 */
public record ArmorCoreSnapshotPayload(long revision, boolean coreEquipped, UUID primarySuitId,
		List<Entry> entries, long resultClientSequence, ArmorCoreAction resultAction,
		UUID resultSuitId, String resultMessage) implements CustomPacketPayload {
	public static final Type<ArmorCoreSnapshotPayload> TYPE = new Type<>(FlightArmorMod.id("armor_core_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorCoreSnapshotPayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeVarLong(payload.revision());
				buffer.writeBoolean(payload.coreEquipped());
				buffer.writeBoolean(payload.primarySuitId() != null);
				if (payload.primarySuitId() != null) buffer.writeUUID(payload.primarySuitId());
				int size = Math.min(64, payload.entries().size());
				buffer.writeVarInt(size);
				for (int index = 0; index < size; index++) payload.entries().get(index).write(buffer);
				buffer.writeBoolean(payload.hasActionReceipt());
				if (payload.hasActionReceipt()) {
					buffer.writeVarLong(payload.resultClientSequence());
					buffer.writeVarInt(payload.resultAction().ordinal());
					buffer.writeBoolean(payload.resultSuitId() != null);
					if (payload.resultSuitId() != null) buffer.writeUUID(payload.resultSuitId());
				}
				buffer.writeUtf(payload.resultMessage() == null ? "" : payload.resultMessage(), 256);
			},
			buffer -> {
				long revision = buffer.readVarLong();
				boolean equipped = buffer.readBoolean();
				UUID primary = buffer.readBoolean() ? buffer.readUUID() : null;
				int size = buffer.readVarInt();
				if (size < 0 || size > 64) throw new IllegalArgumentException("Invalid armor core entry count: " + size);
				List<Entry> entries = new ArrayList<>(size);
				for (int index = 0; index < size; index++) entries.add(Entry.read(buffer));
				boolean hasReceipt = buffer.readBoolean();
				long resultSequence = hasReceipt ? buffer.readVarLong() : -1L;
				ArmorCoreAction resultAction = hasReceipt
						? ArmorCoreAction.fromNetworkId(buffer.readVarInt()) : null;
				UUID resultSuit = hasReceipt && buffer.readBoolean() ? buffer.readUUID() : null;
				return new ArmorCoreSnapshotPayload(revision, equipped, primary, List.copyOf(entries),
						resultSequence, resultAction, resultSuit, buffer.readUtf(256));
			});

	public boolean hasActionReceipt() {
		return resultClientSequence >= 0L && resultAction != null;
	}

	public enum Status {
		READY,
		INCOMPLETE,
		OFFLINE,
		WORN,
		ESCORTING,
		INVALID,
		RETURNING,
		CRUISING,
		TRANSITION,
		CONFLICT,
		MISSING,
		LOOSE;
		public static Status fromNetworkId(int id) {
			Status[] values = values();
			return id >= 0 && id < values.length ? values[id] : INVALID;
		}
	}

	public record Entry(UUID suitId, String displayName, ArmorVariant variant,
			Identifier dimension, int x, int y, int z, Status status,
			ArmorEscortProfile.CombatPolicy attackMode, int rangeBlocks, ArmorEscortProfile.EscortAnchor stance,
			boolean hasHome, int actionMask) {
		private void write(RegistryFriendlyByteBuf buffer) {
			buffer.writeUUID(suitId);
			buffer.writeUtf(displayName, 64);
			buffer.writeUtf(variant.serializedName(), 16);
			buffer.writeIdentifier(dimension);
			buffer.writeInt(x); buffer.writeInt(y); buffer.writeInt(z);
			buffer.writeVarInt(status.ordinal());
			buffer.writeUtf(attackMode.name(), 16);
			buffer.writeVarInt(rangeBlocks);
			buffer.writeUtf(stance.name(), 16);
			buffer.writeBoolean(hasHome);
			buffer.writeVarInt(actionMask);
		}
		private static Entry read(RegistryFriendlyByteBuf buffer) {
			return new Entry(buffer.readUUID(), buffer.readUtf(64),
					ArmorVariant.fromSerializedName(buffer.readUtf(16)), buffer.readIdentifier(),
					buffer.readInt(), buffer.readInt(), buffer.readInt(),
					Status.fromNetworkId(buffer.readVarInt()),
					ArmorEscortProfile.CombatPolicy.fromName(buffer.readUtf(16)),
					buffer.readVarInt(),
					ArmorEscortProfile.EscortAnchor.fromName(buffer.readUtf(16)),
					buffer.readBoolean(), buffer.readVarInt());
		}

		public boolean allows(SuitRuntimeSnapshot.AllowedAction action) {
			return action != null && (actionMask & actionBit(action)) != 0;
		}

		public static int encodeAllowedActions(Set<SuitRuntimeSnapshot.AllowedAction> actions) {
			if (actions == null || actions.isEmpty()) return 0;
			int mask = 0;
			for (SuitRuntimeSnapshot.AllowedAction action : actions) {
				if (action != null) mask |= actionBit(action);
			}
			return mask;
		}

		private static int actionBit(SuitRuntimeSnapshot.AllowedAction action) {
			return 1 << action.ordinal();
		}
	}

	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
