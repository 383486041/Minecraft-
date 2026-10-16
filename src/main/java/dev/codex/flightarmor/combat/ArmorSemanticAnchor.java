package dev.codex.flightarmor.combat;

/** Stable semantic attachment points shared by combat, flight FX and renderers. */
public enum ArmorSemanticAnchor {
	CHEST_CORE(0),
	LEFT_PALM(-1),
	RIGHT_PALM(1),
	LEFT_BOOT_SOLE(-1),
	RIGHT_BOOT_SOLE(1),
	LEFT_MISSILE_RACK(-1),
	RIGHT_MISSILE_RACK(1),
	LEFT_DRONE_POD(-1),
	RIGHT_DRONE_POD(1),
	/** Fixed muzzle on the player's transformed mechanical overdrive wing. */
	LEFT_WING_CANNON(-1),
	/** Fixed muzzle on the player's transformed mechanical overdrive wing. */
	RIGHT_WING_CANNON(1);

	private final int sideSign;

	ArmorSemanticAnchor(int sideSign) {
		this.sideSign = sideSign;
	}

	public int sideSign() {
		return sideSign;
	}

	public boolean isPalm() {
		return this == LEFT_PALM || this == RIGHT_PALM;
	}

	public boolean isBootSole() {
		return this == LEFT_BOOT_SOLE || this == RIGHT_BOOT_SOLE;
	}

	public boolean isMissileRack() {
		return this == LEFT_MISSILE_RACK || this == RIGHT_MISSILE_RACK;
	}

	public boolean isDronePod() {
		return this == LEFT_DRONE_POD || this == RIGHT_DRONE_POD;
	}

	public boolean isWingCannon() {
		return this == LEFT_WING_CANNON || this == RIGHT_WING_CANNON;
	}

	public static ArmorSemanticAnchor palm(boolean left) {
		return left ? LEFT_PALM : RIGHT_PALM;
	}

	public static ArmorSemanticAnchor bootSole(boolean left) {
		return left ? LEFT_BOOT_SOLE : RIGHT_BOOT_SOLE;
	}

	public static ArmorSemanticAnchor missileRack(int missileIndex) {
		return (missileIndex & 1) == 0 ? LEFT_MISSILE_RACK : RIGHT_MISSILE_RACK;
	}

	public static ArmorSemanticAnchor dronePod(boolean left) {
		return left ? LEFT_DRONE_POD : RIGHT_DRONE_POD;
	}

	public static ArmorSemanticAnchor wingCannon(boolean left) {
		return left ? LEFT_WING_CANNON : RIGHT_WING_CANNON;
	}
}
