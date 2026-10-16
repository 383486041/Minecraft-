package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A button intent for the currently selected nearby cruise suit, for the explicit unarmored
 * nearby recall, or for the one-key remote-control toggle.
 *
 * <p>The packet deliberately carries no target, direction, damage or carrier id. The server
 * resolves all of those again from the owner's current view and the selected suit identity.</p>
 */
public record ArmorRemoteCommandPayload(
		long clientSequence,
		long selectionGeneration,
		Command command)
		implements CustomPacketPayload {
	public static final Type<ArmorRemoteCommandPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_remote_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorRemoteCommandPayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeVarLong(payload.clientSequence());
						buffer.writeVarLong(payload.selectionGeneration());
						buffer.writeVarInt(payload.command().ordinal());
					},
					buffer -> new ArmorRemoteCommandPayload(
							buffer.readVarLong(),
							buffer.readVarLong(),
							Command.fromNetworkId(buffer.readVarInt())));

	public enum Command {
		WRIST,
		CORE,
		TOGGLE_MELEE,
		MELEE_ULTIMATE,
		INVALID,
		/**
		 * Unarmored long-press R gesture. It has no selected-suit meaning and asks the server to
		 * choose exactly one nearest stable owned carrier.
		 */
		RECALL_NEAREST,
		/** M-key form change routed to the explicitly selected autonomous suit. */
		TOGGLE_CONFIGURATION,
		/**
		 * 遥控模式的一键开关。没有已选指令甲时，服务端按与最近召回完全相同的确定性规则接管
		 * 最近的一套己方稳定载体；已有指令甲时交还自主控制。玩家不需要打开任何面板。
		 */
		TOGGLE_NEAREST_REMOTE;

		public static Command fromNetworkId(int id) {
			Command[] values = values();
			return id >= 0 && id < values.length ? values[id] : INVALID;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
