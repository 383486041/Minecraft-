package dev.codex.flightarmor.combat;

/** 客户端只表达按键意图；命中、伤害和发射手始终由服务端决定。 */
public enum ArmorAttackAction {
	PRESS,
	RELEASE,
	CANCEL,
	/** 非法网络编号只能落到此安全值，不能伪装成合法取消请求。 */
	INVALID;

	public static ArmorAttackAction fromNetworkId(int id) {
		ArmorAttackAction[] values = values();
		return id >= 0 && id < values.length ? values[id] : INVALID;
	}
}
