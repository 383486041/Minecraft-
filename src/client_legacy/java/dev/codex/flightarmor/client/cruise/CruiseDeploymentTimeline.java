package dev.codex.flightarmor.client.cruise;

import dev.codex.flightarmor.cruise.ArmorCruisePhase;
import net.minecraft.util.Mth;

/**
 * 巡航脱甲的整甲同步时间轴。
 *
 * <p>全部区域共享同一个开启因子：先在玩家体表外同步解锁，再一次性交给载体，
 * 沿抬升弧线高速脱离并同步闭合；任何时刻都不会按槽位逐件穿过玩家。</p>
 */
public final class CruiseDeploymentTimeline {
	public static final float UNLOCK_END = 6.0F / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS;
	public static final float HANDOFF = 9.0F / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS;
	public static final float CLOSURE_START = 11.0F / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS;
	public static final float CLOSURE_END = 27.0F / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS;
	public static final float TRAVEL_END = 24.0F / ArmorCruisePhase.DEPLOYMENT_DURATION_TICKS;

	private CruiseDeploymentTimeline() {
	}

	public static Sample sample(float progress) {
		float time = Mth.clamp(progress, 0.0F, 1.0F);
		float unlock = smoothstep(0.0F, UNLOCK_END, time);
		float close = smoothstep(CLOSURE_START, CLOSURE_END, time);
		float open = unlock * (1.0F - close);
		float travel = ballistic(HANDOFF, TRAVEL_END, time);
		float flight = smoothstep(HANDOFF, TRAVEL_END, time);
		return new Sample(time, open, travel, flight, time < HANDOFF);
	}

	private static float smoothstep(float start, float end, float value) {
		float normalized = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		return normalized * normalized * (3.0F - 2.0F * normalized);
	}

	private static float ballistic(float start, float end, float value) {
		float normalized = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
		// 脱离瞬间速度最大、接近巡航位时迅速制动，避免整甲滞留在玩家碰撞箱内。
		return 1.0F - (float) Math.pow(1.0F - normalized, 3.0D);
	}

	public record Sample(float progress, float open, float travel, float flight,
			boolean playerOwnsVisual) {
		public boolean carrierOwnsVisual() {
			return !playerOwnsVisual;
		}
	}
}
