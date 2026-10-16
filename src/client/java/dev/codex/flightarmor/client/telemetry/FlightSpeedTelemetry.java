package dev.codex.flightarmor.client.telemetry;

import dev.codex.flightarmor.flight.FlightPhysics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端飞行速度的唯一遥测来源。
 *
 * <p>每个客户端 tick 按实体实际位置位移采样，先用三点中值滤除单帧抖动，再用短 EMA
 * 平滑展示。HUD、玩家飞行声和玩家装甲灯光都读取这里，避免位置速度与预测速度各说各话。</p>
 */
public final class FlightSpeedTelemetry {
	private static final double OUTLIER_CAP_MULTIPLIER = 1.5D;
	private static final double RISE_EMA_ALPHA = 0.30D;
	private static final double FALL_EMA_ALPHA = 0.45D;
	private static final Map<UUID, EntitySample> SAMPLES = new HashMap<>();
	private static ResourceKey<Level> sampledDimension;
	private static boolean initialized;

	private FlightSpeedTelemetry() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(FlightSpeedTelemetry::samplePlayers);
	}

	/** 当前 tick 已平滑的真实位移速度，单位为 block/tick。 */
	public static double currentBlocksPerTick(Player player) {
		EntitySample sample = sampleFor(player);
		return sample == null ? 0.0D : sample.currentSmoothed;
	}

	/** 上一个 tick 已平滑的真实位移速度，供渲染帧插值。 */
	public static double previousBlocksPerTick(Player player) {
		EntitySample sample = sampleFor(player);
		return sample == null ? 0.0D : sample.previousSmoothed;
	}

	/** 在上一 tick 与当前 tick 的平滑速度之间插值。 */
	public static double interpolatedBlocksPerTick(Player player, float partialTick) {
		EntitySample sample = sampleFor(player);
		if (sample == null) return 0.0D;
		return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
				sample.previousSmoothed, sample.currentSmoothed);
	}

	/** 按玩家当前所处介质换算的马赫数。 */
	public static double mediumMach(Player player, float partialTick) {
		boolean underwater = player.isUnderWater() || player.isInWaterOrBubble();
		return FlightPhysics.machForBlocksPerTick(
				interpolatedBlocksPerTick(player, partialTick), underwater);
	}

	/** 按玩家当前整甲/战靴动力档案归一化的表现进度。 */
	public static double profileEffectProgress(Player player, float partialTick) {
		return FlightPhysics.effectSpeedProgress(player,
				interpolatedBlocksPerTick(player, partialTick));
	}

	/** 当前 tick 的实际垂直位移，仅供接地声音判定，不参与速度表数值。 */
	public static double currentVerticalBlocksPerTick(Player player) {
		EntitySample sample = sampleFor(player);
		return sample == null ? 0.0D : sample.currentVerticalDisplacement;
	}

	public static void reset() {
		SAMPLES.clear();
		sampledDimension = null;
	}

	public static void reset(Player player) {
		if (player == null) return;
		SAMPLES.remove(player.getUUID());
	}

	private static void samplePlayers(Minecraft client) {
		if (client.level == null) {
			reset();
			return;
		}

		ResourceKey<Level> dimension = client.level.dimension();
		if (sampledDimension != null && !sampledDimension.equals(dimension)) {
			SAMPLES.clear();
		}
		sampledDimension = dimension;

		Set<UUID> observed = new HashSet<>();
		for (Player player : client.level.players()) {
			UUID playerId = player.getUUID();
			if (player.isRemoved()) {
				SAMPLES.remove(playerId);
				continue;
			}
			observed.add(playerId);
			EntitySample sample = SAMPLES.get(playerId);
			if (sample == null || sample.player != player || !sample.dimension.equals(dimension)) {
				sample = new EntitySample(player, dimension);
				SAMPLES.put(playerId, sample);
			}
			sample.tick();
		}

		Iterator<Map.Entry<UUID, EntitySample>> iterator = SAMPLES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, EntitySample> entry = iterator.next();
			EntitySample sample = entry.getValue();
			if (!observed.contains(entry.getKey()) || sample.player.isRemoved()
					|| !sample.dimension.equals(dimension)) {
				iterator.remove();
			}
		}
	}

	private static EntitySample sampleFor(Player player) {
		if (player == null || player.isRemoved()) return null;
		EntitySample sample = SAMPLES.get(player.getUUID());
		return sample != null && sample.player == player ? sample : null;
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
	}

	private static double median(double first, double second, double third) {
		return first + second + third - Math.min(first, Math.min(second, third))
				- Math.max(first, Math.max(second, third));
	}

	private static final class EntitySample {
		private final Player player;
		private final ResourceKey<Level> dimension;
		private final double[] rawWindow = new double[3];
		private Vec3 previousPosition;
		private int rawCursor;
		private boolean hasSample;
		private double previousSmoothed;
		private double currentSmoothed;
		private double lastAcceptedRaw;
		private double currentVerticalDisplacement;

		private EntitySample(Player player, ResourceKey<Level> dimension) {
			this.player = player;
			this.dimension = dimension;
		}

		private void tick() {
			Vec3 position = player.position();
			var profile = FlightPhysics.resolveFlightProfile(player);
			double outlierCap = profile.dive().hardSpeedCap() * OUTLIER_CAP_MULTIPLIER;
			// 正常加速每 tick 只会增长约 0.01～0.10 格；留出网络纠正余量，但拒绝把传送当成高速。
			double maximumIncrease = Math.max(0.45D,
					profile.dive().targetSpeed()
							/ (profile.dive().accelerationSeconds() * FlightPhysics.TICKS_PER_SECOND) * 1.50D + 0.15D);
			if (!hasSample || previousPosition == null) {
				// 首帧没有相邻位置，使用同步速度向量建立读数；后续不再依赖预测速度。
				double fallback = finiteNonNegative(player.getDeltaMovement().length());
				double initial = fallback <= outlierCap ? fallback : 0.0D;
				for (int index = 0; index < rawWindow.length; index++) rawWindow[index] = initial;
				lastAcceptedRaw = initial;
				previousSmoothed = initial;
				currentSmoothed = initial;
				currentVerticalDisplacement = finiteVertical(player.getDeltaMovement().y, outlierCap);
				hasSample = true;
			} else {
				Vec3 displacement = position.subtract(previousPosition);
				double raw = displacement.length();
				boolean accepted = Double.isFinite(raw) && raw >= 0.0D && raw <= outlierCap
						&& raw <= lastAcceptedRaw + maximumIncrease;
				if (accepted) {
					lastAcceptedRaw = raw;
					currentVerticalDisplacement = finiteVertical(displacement.y, outlierCap);
				} else {
					// 传送、跨区块纠正等异常帧保持最近有效值，不能污染中值窗口。
					raw = lastAcceptedRaw;
					currentVerticalDisplacement = 0.0D;
				}
				rawWindow[rawCursor] = raw;
				rawCursor = (rawCursor + 1) % rawWindow.length;
				double filtered = median(rawWindow[0], rawWindow[1], rawWindow[2]);
				previousSmoothed = currentSmoothed;
				double alpha = filtered > currentSmoothed ? RISE_EMA_ALPHA : FALL_EMA_ALPHA;
				currentSmoothed += (filtered - currentSmoothed) * alpha;
			}
			previousPosition = position;
		}

		private static double finiteVertical(double vertical, double outlierCap) {
			return Double.isFinite(vertical) && Math.abs(vertical) <= outlierCap ? vertical : 0.0D;
		}
	}
}
