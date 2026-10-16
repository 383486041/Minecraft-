package dev.codex.flightarmor.network;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.combat.ArmorAttackHitKind;
import dev.codex.flightarmor.combat.ArmorAttackType;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** S2C 一次性权威光束/命中特效；serverAttackId + eventIndex 是客户端去重键。 */
public record ArmorAttackFxPayload(
		UUID attackerId,
		ResourceLocation dimension,
		long serverAttackId,
		int eventIndex,
		ArmorAttackType attackType,
		ArmorVariant variant,
		boolean leftHand,
		Vec3 origin,
		Vec3 endpoint,
		Vec3 visualEndpoint,
		Vec3 impactNormal,
		ArmorAttackHitKind hitKind,
		int hitEntityId,
		float charge,
		boolean underwater
) implements CustomPacketPayload {
	public static final int NO_ENTITY_ID = -1;
	public static final Type<ArmorAttackFxPayload> TYPE =
			// 2.16 扩展了线协议；使用新通道让旧客户端安全地收不到 FX，而不是按旧字段错解并断线。
			new Type<>(FlightArmorMod.id("armor_attack_fx_v2"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ArmorAttackFxPayload> STREAM_CODEC = StreamCodec.of(
			(buffer, payload) -> {
				buffer.writeUUID(payload.attackerId());
				buffer.writeResourceLocation(payload.dimension());
				buffer.writeVarLong(payload.serverAttackId());
				buffer.writeVarInt(payload.eventIndex());
				buffer.writeVarInt(payload.attackType().ordinal());
				buffer.writeUtf(payload.variant().serializedName(), 16);
				buffer.writeBoolean(payload.leftHand());
				writeVec3(buffer, payload.origin());
				writeVec3(buffer, payload.endpoint());
				writeVec3(buffer, payload.visualEndpoint());
				writeVec3(buffer, payload.impactNormal());
				buffer.writeVarInt(payload.hitKind().ordinal());
				buffer.writeVarInt(payload.hitEntityId());
				buffer.writeFloat(payload.charge());
				buffer.writeBoolean(payload.underwater());
			},
			buffer -> new ArmorAttackFxPayload(
					buffer.readUUID(),
					buffer.readResourceLocation(),
					buffer.readVarLong(),
					buffer.readVarInt(),
					ArmorAttackType.fromNetworkId(buffer.readVarInt()),
					ArmorVariant.fromSerializedName(buffer.readUtf(16)),
					buffer.readBoolean(),
					readVec3(buffer),
					readVec3(buffer),
					readVec3(buffer),
					readVec3(buffer),
					ArmorAttackHitKind.fromNetworkId(buffer.readVarInt()),
					buffer.readVarInt(),
					buffer.readFloat(),
					buffer.readBoolean()
			)
	);

	public ArmorAttackFxPayload {
		variant = variant == null ? ArmorVariant.DEFAULT : variant;
		visualEndpoint = visualEndpoint == null ? endpoint : visualEndpoint;
	}

	/** 常规攻击的判定端点和可见光束端点相同。 */
	public ArmorAttackFxPayload(UUID attackerId, ResourceLocation dimension, long serverAttackId,
			int eventIndex, ArmorAttackType attackType, ArmorVariant variant, boolean leftHand,
			Vec3 origin, Vec3 endpoint, Vec3 impactNormal, ArmorAttackHitKind hitKind,
			int hitEntityId, float charge, boolean underwater) {
		this(attackerId, dimension, serverAttackId, eventIndex, attackType, variant, leftHand,
				origin, endpoint, endpoint, impactNormal, hitKind, hitEntityId, charge, underwater);
	}

	/**
	 * 兼容仍按旧字段列表创建特效事件的调用点；旧调用统一使用先锋型反馈。
	 */
	public ArmorAttackFxPayload(UUID attackerId, ResourceLocation dimension, long serverAttackId,
			int eventIndex, ArmorAttackType attackType, boolean leftHand, Vec3 origin, Vec3 endpoint,
			Vec3 impactNormal, ArmorAttackHitKind hitKind, int hitEntityId, float charge,
			boolean underwater) {
		this(attackerId, dimension, serverAttackId, eventIndex, attackType, ArmorVariant.DEFAULT,
				leftHand, origin, endpoint, endpoint, impactNormal, hitKind, hitEntityId, charge, underwater);
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
