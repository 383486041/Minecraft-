package dev.codex.flightarmor.network;

/** 自动悬停事件的权威来源；网络 ID 固定，禁止依赖枚举 ordinal。 */
public enum AutomaticHoverSource {
	ASSEMBLY_BOOTS(0),
	DOCKING_BOOTS(1),
	ASSEMBLY_COMPLETE(2),
	DOCKING_COMPLETE(3),
	/** 独立巡航战甲尚未抵达时，由其反重力核心提供的只读应急制动。 */
	EMERGENCY_RECALL(4),
	/** 快速换甲完成后的补偿悬停；不借用组装动画的序列号。 */
	QUICK_SWITCH_COMPLETE(5),
	/** Server-observed false-to-true eligibility from an ordinary equipment-slot change. */
	ORDINARY_EQUIP(6);

	private final int networkId;

	AutomaticHoverSource(int networkId) {
		this.networkId = networkId;
	}

	public int networkId() {
		return networkId;
	}

	public boolean isBootLockSource() {
		return this == ASSEMBLY_BOOTS || this == DOCKING_BOOTS || this == EMERGENCY_RECALL;
	}

	public static AutomaticHoverSource fromNetworkId(int networkId) {
		for (AutomaticHoverSource source : values()) {
			if (source.networkId == networkId) return source;
		}
		return ASSEMBLY_COMPLETE;
	}
}
