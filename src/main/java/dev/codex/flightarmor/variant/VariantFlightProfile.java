package dev.codex.flightarmor.variant;

/**
 * 单一战甲型号的服务端/客户端共用动力参数。
 *
 * <p>完整同型号套装使用 {@code full} 参数，只有战靴或四件混装时使用较弱的
 * {@code boots} 参数。该类型只保存纯数值，避免客户端预测与服务端权威物理维护两份配置。</p>
 */
public record VariantFlightProfile(
		Mode cruise,
		Mode boost,
		Mode dive,
		double collisionHorizontalRetention) {

	public VariantFlightProfile {
		if (cruise == null || boost == null || dive == null) {
			throw new IllegalArgumentException("飞行动力档位不能为空");
		}
		requireFiniteRange(collisionHorizontalRetention, 0.0D, 1.0D, "碰撞水平速度保留率");
	}

	/**
	 * 单个动力档位。速度单位均为 block/tick；加速/制动时间是从静止到目标速度的标称秒数，
	 * 转向速度使用度/秒。使用具有物理含义的参数后，客户端预测和服务端权威计算不再依赖帧率或插值响应率。
	 */
	public record Mode(
			double targetSpeed,
			double hardSpeedCap,
			double accelerationSeconds,
			double brakingSeconds,
			double turnRateDegreesPerSecond,
			double verticalSpeed,
			double minVerticalSpeed,
			double maxVerticalSpeed) {

		public Mode {
			requireFiniteRange(targetSpeed, 0.01D, 64.0D, "目标速度");
			requireFiniteRange(hardSpeedCap, targetSpeed, 64.0D, "硬速度上限");
			requireFiniteRange(accelerationSeconds, 0.10D, 60.0D, "标称加速秒数");
			requireFiniteRange(brakingSeconds, 0.10D, 20.0D, "标称制动秒数");
			requireFiniteRange(turnRateDegreesPerSecond, 1.0D, 720.0D, "转向角速度");
			requireFiniteRange(verticalSpeed, 0.01D, 16.0D, "垂直速度");
			if (!Double.isFinite(minVerticalSpeed) || !Double.isFinite(maxVerticalSpeed)
					|| minVerticalSpeed >= maxVerticalSpeed) {
				throw new IllegalArgumentException("垂直速度范围无效："
						+ minVerticalSpeed + ".." + maxVerticalSpeed);
			}
		}
	}

	private static void requireFiniteRange(double value, double minimum, double maximum, String label) {
		if (!Double.isFinite(value) || value < minimum || value > maximum) {
			throw new IllegalArgumentException(label + "超出范围：" + value
					+ "，应为 " + minimum + ".." + maximum);
		}
	}
}
