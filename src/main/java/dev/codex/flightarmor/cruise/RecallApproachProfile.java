package dev.codex.flightarmor.cruise;

/**
 * 战甲可见召回的三段式接近曲线。
 *
 * <p>距离统一使用巡航载体锚点到玩家背后上方着装捕获点的直线距离。服务端用同一组阈值控制速度，
 * 客户端用 {@link #brakingProgress(double)} 与 {@link #hoverProgress(double)} 驱动俯冲、抬头和
 * 悬空姿态，避免两端各自维护一套容易漂移的数字。</p>
 */
public final class RecallApproachProfile {
	/** 远距俯冲结束并开始连续抬头制动的距离。 */
	public static final double BRAKING_START_DISTANCE = 72.0D;
	/** 制动结束并进入低速悬空贴近的距离。 */
	public static final double HOVER_APPROACH_DISTANCE = 12.0D;
	/** 进入该捕获半径后立刻把剩余动势交给 DOCKING，不在捕获点原地悬停。 */
	public static final double CAPTURE_TOLERANCE = 2.40D;
	/** 捕获点相对玩家朝向向后的距离。 */
	public static final double CAPTURE_REAR_OFFSET = 1.55D;
	/** 捕获点相对玩家脚下锚点向上的高度。 */
	public static final double CAPTURE_HEIGHT_OFFSET = 0.82D;

	/** 普通可见召回的预备速度。 */
	public static final double INITIAL_SPEED = 10.00D;
	/** 折跃重入保留高速动势，但不会从近景接入点瞬移到玩家身边。 */
	public static final double PHASING_REENTRY_SPEED = 18.00D;
	/** 每 tick 最大正向加速度；首帧权威位移不超过 18 格。 */
	public static final double ACCELERATION_PER_TICK = 8.00D;
	/** 每 tick 最大制动量；较长的速度坡道替代旧 16/7 格处的瞬时急刹。 */
	public static final double BRAKING_PER_TICK = 6.00D;
	/** 远距召回上限 34 格/tick；按本模组 5 格/秒的游戏性音速属于极高速召回。 */
	public static final double MAX_SPEED = 34.00D;
	/** 进入悬空贴近时的速度。 */
	public static final double HOVER_ENTRY_SPEED = 3.60D;
	/** DOCKING 前最后贴近的最低速度；仍高于原版玩家疾跑位移，避免移动目标永远追不上。 */
	public static final double DOCKING_APPROACH_SPEED = 1.20D;
	/** 每 tick 最多闭合剩余距离的比例；保证近景有连续可见的减速帧。 */
	public static final double MAX_CLOSURE_RATIO = 0.45D;
	/** 近景最小位移，防止浮点渐近导致永远无法进入 DOCKING。 */
	public static final double MIN_CLOSURE_STEP = 1.00D;
	/** 剩余距离小于该值时直接交给 DOCKING，最后一步不会跨过捕获位。 */
	public static final double FINAL_CAPTURE_STEP = 1.00D;
	public static final int ENTRY_POSE_TICKS = 2;

	private RecallApproachProfile() {
	}

	/**
	 * 远距俯冲到近距悬空之间的抬头/制动进度：72 格为 0，12 格为 1。
	 */
	public static double brakingProgress(double distance) {
		double linear = 1.0D - inverseLerp(HOVER_APPROACH_DISTANCE, BRAKING_START_DISTANCE,
				sanitizeDistance(distance));
		return smoothStep(linear);
	}

	/**
	 * 悬空贴近进度：12 格为 0，进入 2.4 格对接接管半径时为 1。
	 * 该值到 1 时可无缝衔接 DOCKING 初始姿态。
	 */
	public static double hoverProgress(double distance) {
		double linear = 1.0D - inverseLerp(CAPTURE_TOLERANCE, HOVER_APPROACH_DISTANCE,
				sanitizeDistance(distance));
		return smoothStep(linear);
	}

	/** 远距低风阻俯冲姿态的权重。 */
	public static double diveProgress(double distance) {
		return 1.0D - brakingProgress(distance);
	}

	/**
	 * 按当前位置计算期望速度。72→12 格从远距极高速连续降到近景速度，
	 * 12→2.4 格继续平滑降到对接速度。
	 */
	public static double targetSpeed(double distance) {
		double sanitized = sanitizeDistance(distance);
		if (sanitized >= BRAKING_START_DISTANCE) {
			return MAX_SPEED;
		}
		if (sanitized > HOVER_APPROACH_DISTANCE) {
			return lerp(MAX_SPEED, HOVER_ENTRY_SPEED, brakingProgress(sanitized));
		}
		return lerp(HOVER_ENTRY_SPEED, DOCKING_APPROACH_SPEED, hoverProgress(sanitized));
	}

	/**
	 * 以受限加速度追随目标速度。远距快速升到召回上限，进入近景后连续减速，
	 * 不再在任意特效阈值上强制停一帧。
	 */
	public static double advanceSpeed(double currentSpeed, double distance) {
		double speed = Double.isFinite(currentSpeed)
				? clamp(currentSpeed, 0.0D, MAX_SPEED)
				: INITIAL_SPEED;
		double target = targetSpeed(distance);
		if (speed < target) {
			return Math.min(target, speed + ACCELERATION_PER_TICK);
		}
		return Math.max(target, speed - BRAKING_PER_TICK);
	}

	/**
	 * 限制单次权威位移：不再硬采样固定视觉边界，而是按剩余距离连续收敛。
	 * 这样既保留远距高速峰值，又能保证近景至少有数个可见移动 tick，并且绝不会穿过捕获位。
	 */
	public static double safeStep(double distance, double requestedSpeed) {
		double remaining = Math.max(0.0D, sanitizeDistance(distance) - CAPTURE_TOLERANCE);
		if (remaining <= FINAL_CAPTURE_STEP) {
			return remaining;
		}
		double closureLimit = Math.max(MIN_CLOSURE_STEP, remaining * MAX_CLOSURE_RATIO);
		return Math.min(clamp(requestedSpeed, 0.0D, MAX_SPEED), Math.min(closureLimit, remaining));
	}

	private static double sanitizeDistance(double distance) {
		if (Double.isNaN(distance) || distance <= 0.0D) {
			return 0.0D;
		}
		return distance;
	}

	private static double inverseLerp(double start, double end, double value) {
		return clamp((value - start) / (end - start), 0.0D, 1.0D);
	}

	private static double smoothStep(double value) {
		double clamped = clamp(value, 0.0D, 1.0D);
		return clamped * clamped * (3.0D - 2.0D * clamped);
	}

	private static double lerp(double start, double end, double progress) {
		return start + (end - start) * clamp(progress, 0.0D, 1.0D);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
