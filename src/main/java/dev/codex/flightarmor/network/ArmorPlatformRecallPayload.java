package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.assembly.ArmorAssemblyTiming;
import dev.codex.flightarmor.platform.ChargingPlatformDisplayMode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** One-shot visual snapshot for an owner-bound suit recalled from a charging platform. */
public record ArmorPlatformRecallPayload(
		UUID playerId,
		long recallSequence,
		ResourceLocation originDimension,
		Vec3 origin,
		ChargingPlatformDisplayMode displayMode,
		long startGameTime,
		int animationOffsetTicks
) implements CustomPacketPayload {
	/** Keeps the platform visual owner alive until the shared assembly timeline is complete. */
	public static final int DURATION_TICKS = ArmorAssemblyTiming.TOTAL_DURATION_TICKS;
	public static final int EXPIRY_TICKS = DURATION_TICKS + 3;
	/** 整甲第 6 tick 完成“升空—俯冲—近进—急刹”，随后战靴反重力场接管下坠。 */
	public static final int ASSEMBLED_ARRIVAL_TICK = 6;
	/** 分件模式下双靴在第 6 tick 抵达并锁定，服务端同 tick 才授权悬空。 */
	public static final int PARTS_ARRIVAL_TICK = 6;
	public static final Type<ArmorPlatformRecallPayload> TYPE =
			new Type<>(FlightArmorMod.id("armor_platform_recall"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorPlatformRecallPayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeUUID(payload.playerId());
						buffer.writeVarLong(payload.recallSequence());
						buffer.writeResourceLocation(payload.originDimension());
						writeVec3(buffer, payload.origin());
						buffer.writeVarInt(payload.displayMode().ordinal());
						buffer.writeVarLong(payload.startGameTime());
						buffer.writeVarInt(payload.animationOffsetTicks());
					},
					buffer -> new ArmorPlatformRecallPayload(
							buffer.readUUID(),
							buffer.readVarLong(),
							buffer.readResourceLocation(),
							readVec3(buffer),
							modeFromNetwork(buffer.readVarInt()),
							buffer.readVarLong(),
							buffer.readVarInt()
					)
			);

	private static ChargingPlatformDisplayMode modeFromNetwork(int id) {
		ChargingPlatformDisplayMode[] values = ChargingPlatformDisplayMode.values();
		return id >= 0 && id < values.length
				? values[id] : ChargingPlatformDisplayMode.ASSEMBLED;
	}

	private static void writeVec3(RegistryFriendlyByteBuf buffer, Vec3 value) {
		buffer.writeDouble(value.x);
		buffer.writeDouble(value.y);
		buffer.writeDouble(value.z);
	}

	private static Vec3 readVec3(RegistryFriendlyByteBuf buffer) {
		return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
