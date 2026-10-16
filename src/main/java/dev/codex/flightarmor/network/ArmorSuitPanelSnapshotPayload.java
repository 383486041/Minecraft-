package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Set;
import java.util.UUID;

/**
 * Server-authored snapshot for the right-click suit panel.
 *
 * <p>The client receives presentation data plus an explicit capability mask. It never infers
 * whether a physical suit may move from the displayed phase, role, or conflict text.</p>
 */
public record ArmorSuitPanelSnapshotPayload(
		long sessionId,
		UUID suitId,
		String displayName,
		ArmorVariant variant,
		String ownerName,
		SubjectKind subjectKind,
		String lifecycleText,
		String roleText,
		String positionText,
		String distanceText,
		String integrityText,
		String conflictText,
		boolean ownerView,
		PrimaryAction primaryAction,
		int capabilityMask,
		boolean selectedForCommands,
		boolean followingOwner,
		boolean transitioning,
		boolean openRequested,
		boolean closeRequested,
		String notice
) implements CustomPacketPayload {
	public static final Type<ArmorSuitPanelSnapshotPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_suit_panel_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorSuitPanelSnapshotPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
				buffer.writeVarLong(payload.sessionId());
				buffer.writeUUID(payload.suitId());
				buffer.writeUtf(payload.displayName(), 64);
				buffer.writeUtf(payload.variant().serializedName(), 16);
				buffer.writeUtf(payload.ownerName(), 64);
				buffer.writeVarInt(payload.subjectKind().ordinal());
				buffer.writeUtf(payload.lifecycleText(), 96);
				buffer.writeUtf(payload.roleText(), 48);
				buffer.writeUtf(payload.positionText(), 128);
				buffer.writeUtf(payload.distanceText(), 64);
				buffer.writeUtf(payload.integrityText(), 96);
				buffer.writeUtf(payload.conflictText(), 160);
				buffer.writeBoolean(payload.ownerView());
				buffer.writeVarInt(payload.primaryAction().ordinal());
				buffer.writeVarInt(payload.capabilityMask());
				buffer.writeBoolean(payload.selectedForCommands());
				buffer.writeBoolean(payload.followingOwner());
				buffer.writeBoolean(payload.transitioning());
				buffer.writeBoolean(payload.openRequested());
				buffer.writeBoolean(payload.closeRequested());
				buffer.writeUtf(payload.notice() == null ? "" : payload.notice(), 240);
			}, buffer -> new ArmorSuitPanelSnapshotPayload(
					buffer.readVarLong(),
					buffer.readUUID(),
					buffer.readUtf(64),
					ArmorVariant.fromSerializedName(buffer.readUtf(16)),
					buffer.readUtf(64),
					SubjectKind.fromNetworkId(buffer.readVarInt()),
					buffer.readUtf(96),
					buffer.readUtf(48),
					buffer.readUtf(128),
					buffer.readUtf(64),
					buffer.readUtf(96),
					buffer.readUtf(160),
					buffer.readBoolean(),
					PrimaryAction.fromNetworkId(buffer.readVarInt()),
					buffer.readVarInt(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readUtf(240)));

	public boolean allows(Capability capability) {
		return capability != null && (capabilityMask & capability.bit()) != 0;
	}

	public static int capabilityMask(Set<Capability> capabilities) {
		int mask = 0;
		if (capabilities == null) return mask;
		for (Capability capability : capabilities) mask |= capability.bit();
		return mask;
	}

	public enum SubjectKind {
		PLATFORM,
		CRUISE,
		ESCORT;

		private static SubjectKind fromNetworkId(int id) {
			SubjectKind[] values = values();
			return id >= 0 && id < values.length ? values[id] : PLATFORM;
		}
	}

	public enum PrimaryAction {
		NONE,
		WEAR,
		SWITCH;

		private static PrimaryAction fromNetworkId(int id) {
			PrimaryAction[] values = values();
			return id >= 0 && id < values.length ? values[id] : NONE;
		}
	}

	public enum Capability {
		PRIMARY(1 << 0),
		TOGGLE_DISPLAY(1 << 1),
		SELECT_REMOTE(1 << 2),
		TOGGLE_FOLLOW(1 << 3),
		REFRESH(1 << 4);

		private final int bit;

		Capability(int bit) {
			this.bit = bit;
		}

		public int bit() {
			return bit;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
