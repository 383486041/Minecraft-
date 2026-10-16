package dev.codex.flightarmor.variant;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 可稳定保存的战甲型号。枚举名只供代码使用，物品数据必须写入 {@link #serializedName}。
 * 新型号只能追加，已有 serializedName 不能修改。
 */
public enum ArmorVariant {
	/** 均衡基准型号，也是旧物品和无效数据的安全回退。 */
	VANGUARD("vanguard", "先锋型", 0x36F4FF,
			new VariantCombatProfile(1.00F, 1.00D, 1.00F, 1.00D, 1.00F, 1.00D),
			flight(
					mode(0.42D, 0.62D, 1.40D, 0.85D, 280.0D, 0.26D, -0.85D, 0.60D),
					mode(0.92D, 1.15D, 10.00D, 2.20D, 190.0D, 0.38D, -1.00D, 0.72D),
					mode(18.00D, 19.50D, 15.00D, 3.80D, 48.0D, 2.20D, -18.50D, 6.00D),
					0.52D),
			flight(
					mode(0.30D, 0.44D, 1.60D, 1.00D, 250.0D, 0.20D, -0.55D, 0.42D),
					mode(0.58D, 0.74D, 12.00D, 2.50D, 170.0D, 0.27D, -0.72D, 0.52D),
					mode(9.00D, 10.00D, 14.00D, 4.00D, 42.0D, 1.20D, -9.50D, 3.50D),
					0.32D),
			new VariantDefenseProfile(0.08F, 0.04F, 0.00D)),
	/** 远距离快速压制，牺牲单次核心输出和俯冲覆盖面积。 */
	SWIFT("swift", "疾影型", 0xD14CFF,
			new VariantCombatProfile(0.92F, 1.16D, 0.88F, 1.12D, 1.08F, 0.82D),
			flight(
					mode(0.66D, 0.90D, 1.10D, 0.70D, 340.0D, 0.44D, -1.15D, 0.92D),
					mode(1.48D, 1.82D, 10.50D, 2.00D, 230.0D, 0.66D, -1.55D, 1.20D),
					mode(24.00D, 26.00D, 13.50D, 3.40D, 62.0D, 3.10D, -25.00D, 8.50D),
					0.16D),
			flight(
					mode(0.43D, 0.60D, 1.30D, 0.85D, 310.0D, 0.30D, -0.72D, 0.62D),
					mode(0.88D, 1.08D, 11.00D, 2.20D, 205.0D, 0.43D, -0.96D, 0.78D),
					mode(13.00D, 14.50D, 13.00D, 3.70D, 56.0D, 1.80D, -13.50D, 4.80D),
					0.20D),
			new VariantDefenseProfile(0.14F, 0.08F, 0.02D)),
	/** 重型守护与区域控制，射程略短但核心和俯冲更强。 */
	SENTINEL("sentinel", "守望型", 0xFFBE3B,
			new VariantCombatProfile(1.12F, 0.92D, 1.20F, 0.88D, 1.28F, 1.22D),
			flight(
					mode(0.27D, 0.38D, 1.80D, 1.10D, 220.0D, 0.18D, -0.48D, 0.36D),
					mode(0.55D, 0.70D, 14.00D, 2.80D, 145.0D, 0.26D, -0.68D, 0.48D),
					mode(12.00D, 13.50D, 14.00D, 4.80D, 32.0D, 1.45D, -12.50D, 4.00D),
					0.18D),
			flight(
					mode(0.23D, 0.32D, 2.00D, 1.30D, 200.0D, 0.15D, -0.38D, 0.29D),
					mode(0.42D, 0.54D, 15.00D, 3.00D, 130.0D, 0.21D, -0.50D, 0.38D),
					mode(7.00D, 8.00D, 15.00D, 5.00D, 28.0D, 0.90D, -7.50D, 2.60D),
					0.16D),
			new VariantDefenseProfile(0.03F, 0.02F, 0.00D));

	public static final ArmorVariant DEFAULT = VANGUARD;
	private static final Map<String, ArmorVariant> BY_SERIALIZED_NAME = java.util.Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(ArmorVariant::serializedName, Function.identity()));

	private final String serializedName;
	private final String chineseName;
	private final int primaryLightRgb;
	private final VariantCombatProfile combatProfile;
	private final VariantFlightProfile fullFlightProfile;
	private final VariantFlightProfile bootsFlightProfile;
	private final VariantDefenseProfile defenseProfile;

	ArmorVariant(String serializedName, String chineseName, int primaryLightRgb,
			VariantCombatProfile combatProfile, VariantFlightProfile fullFlightProfile,
			VariantFlightProfile bootsFlightProfile, VariantDefenseProfile defenseProfile) {
		this.serializedName = serializedName;
		this.chineseName = chineseName;
		this.primaryLightRgb = primaryLightRgb & 0xFFFFFF;
		this.combatProfile = combatProfile;
		this.fullFlightProfile = fullFlightProfile;
		this.bootsFlightProfile = bootsFlightProfile;
		this.defenseProfile = defenseProfile;
	}

	public String serializedName() {
		return serializedName;
	}

	public String chineseName() {
		return chineseName;
	}

	/** 24 位不透明度无关 RGB，可直接拆成 OBJ 发光层的三个通道。 */
	public int primaryLightRgb() {
		return primaryLightRgb;
	}

	public int lightRed() {
		return primaryLightRgb >> 16 & 0xFF;
	}

	public int lightGreen() {
		return primaryLightRgb >> 8 & 0xFF;
	}

	public int lightBlue() {
		return primaryLightRgb & 0xFF;
	}

	public VariantCombatProfile combatProfile() {
		return combatProfile;
	}

	public VariantFlightProfile fullFlightProfile() {
		return fullFlightProfile;
	}

	public VariantFlightProfile bootsFlightProfile() {
		return bootsFlightProfile;
	}

	public VariantDefenseProfile defenseProfile() {
		return defenseProfile;
	}

	public static Optional<ArmorVariant> find(String serializedName) {
		if (serializedName == null || serializedName.isBlank()) return Optional.empty();
		return Optional.ofNullable(BY_SERIALIZED_NAME.get(serializedName.toLowerCase(Locale.ROOT)));
	}

	public static ArmorVariant fromSerializedName(String serializedName) {
		return find(serializedName).orElse(DEFAULT);
	}

	private static VariantFlightProfile flight(VariantFlightProfile.Mode cruise,
			VariantFlightProfile.Mode boost, VariantFlightProfile.Mode dive,
			double collisionHorizontalRetention) {
		return new VariantFlightProfile(cruise, boost, dive,
				collisionHorizontalRetention);
	}

	private static VariantFlightProfile.Mode mode(double targetSpeed, double hardSpeedCap,
			double accelerationSeconds, double brakingSeconds, double turnRateDegreesPerSecond,
			double verticalSpeed,
			double minVerticalSpeed, double maxVerticalSpeed) {
		return new VariantFlightProfile.Mode(targetSpeed, hardSpeedCap,
				accelerationSeconds, brakingSeconds, turnRateDegreesPerSecond,
				verticalSpeed, minVerticalSpeed, maxVerticalSpeed);
	}
}
