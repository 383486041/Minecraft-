package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A panel button sends only a session and intent; the server resolves the exact target again. */
public record ArmorSuitPanelActionPayload(long sessionId, Action action)
		implements CustomPacketPayload {
	public static final Type<ArmorSuitPanelActionPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_suit_panel_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorSuitPanelActionPayload> STREAM_CODEC =
			StreamCodec.of((buffer, payload) -> {
				buffer.writeVarLong(payload.sessionId());
				buffer.writeVarInt(payload.action().ordinal());
			}, buffer -> new ArmorSuitPanelActionPayload(
					buffer.readVarLong(), Action.fromNetworkId(buffer.readVarInt())));

	public enum Action {
		PRIMARY,
		TOGGLE_DISPLAY,
		SELECT_REMOTE,
		TOGGLE_FOLLOW,
		REFRESH,
		/** Opens the single authoritative core command center; this panel remains telemetry-only. */
		OPEN_COMMAND_CENTER;

		private static Action fromNetworkId(int id) {
			Action[] values = values();
			// Invalid or future action ids must remain read-only, never fall back to a movement.
			return id >= 0 && id < values.length ? values[id] : REFRESH;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
