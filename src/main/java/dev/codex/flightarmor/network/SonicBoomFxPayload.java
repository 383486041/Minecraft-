package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * One-shot, server-authoritative sound-barrier crossing event.
 *
 * <p>This is deliberately an event rather than a replicated "supersonic" flag:
 * {@code eventId} identifies one clean latch crossing, while the fixed origin
 * and direction let every observer render the same pressure front even after
 * the flyer has already moved several chunks ahead.</p>
 */
public record SonicBoomFxPayload(
		UUID flyerId,
		Identifier dimension,
		long eventId,
		Vec3 origin,
		Vec3 direction,
		float mach
) implements CustomPacketPayload {
	public static final Type<SonicBoomFxPayload> TYPE =
			new Type<>(FlightArmorMod.id("sonic_boom_fx"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SonicBoomFxPayload> STREAM_CODEC =
			StreamCodec.of(
					(buffer, payload) -> {
						buffer.writeUUID(payload.flyerId());
						buffer.writeIdentifier(payload.dimension());
						buffer.writeVarLong(payload.eventId());
						writeVec3(buffer, payload.origin());
						writeVec3(buffer, payload.direction());
						buffer.writeFloat(payload.mach());
					},
					buffer -> new SonicBoomFxPayload(
							buffer.readUUID(),
							buffer.readIdentifier(),
							buffer.readVarLong(),
							readVec3(buffer),
							readVec3(buffer),
							buffer.readFloat()));

	public SonicBoomFxPayload {
		if (!finite(origin)) origin = Vec3.ZERO;
		if (!finite(direction) || direction.lengthSqr() < 1.0E-8D) {
			direction = new Vec3(0.0D, 0.0D, 1.0D);
		} else {
			direction = direction.normalize();
		}
		if (!Float.isFinite(mach) || mach < 0.0F) mach = 0.0F;
	}

	private static void writeVec3(RegistryFriendlyByteBuf buffer, Vec3 value) {
		buffer.writeDouble(value.x);
		buffer.writeDouble(value.y);
		buffer.writeDouble(value.z);
	}

	private static Vec3 readVec3(RegistryFriendlyByteBuf buffer) {
		return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
	}

	private static boolean finite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
