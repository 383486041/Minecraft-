package dev.codex.flightarmor.platform;

/** 充能平台的两种纯视觉陈列方式；不会改变四件装甲的实际存储结构。 */
public enum ChargingPlatformDisplayMode {
	/** 四件装甲闭合为完整人形，进入低功耗待机。 */
	ASSEMBLED,
	/** 四件装甲彼此分离，悬浮在平台的四个展示位。 */
	PARTS;

	public ChargingPlatformDisplayMode toggled() {
		return this == ASSEMBLED ? PARTS : ASSEMBLED;
	}

	static ChargingPlatformDisplayMode fromName(String name) {
		if (name == null || name.isBlank()) return ASSEMBLED;
		try {
			return valueOf(name);
		} catch (IllegalArgumentException ignored) {
			return ASSEMBLED;
		}
	}
}
