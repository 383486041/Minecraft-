package dev.codex.flightarmor.network;

/** 玩家主动执行的战甲收纳操作；网络编号固定，不依赖枚举声明顺序。 */
public enum ArmorWardrobeAction {
	FULL_TOGGLE(0),
	STEP_REMOVE(1),
	STEP_EQUIP(2);

	private final int networkId;

	ArmorWardrobeAction(int networkId) {
		this.networkId = networkId;
	}

	public int networkId() {
		return networkId;
	}

	public static ArmorWardrobeAction fromNetworkId(int networkId) {
		for (ArmorWardrobeAction action : values()) {
			if (action.networkId == networkId) return action;
		}
		throw new IllegalArgumentException("未知的战甲收纳操作编号: " + networkId);
	}
}
