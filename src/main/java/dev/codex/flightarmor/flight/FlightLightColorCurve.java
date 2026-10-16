package dev.codex.flightarmor.flight;

/**
 * Pure full-bright RGB palette driven only by normalized realized flight speed.
 *
 * <p>The renderer supplies a value produced from smoothed entity displacement.
 * Input modes such as Ctrl thrust or high-speed flight never enter this curve,
 * so accelerating and braking follow the same colors in opposite directions.</p>
 */
public final class FlightLightColorCurve {
	public static final double CRUISE_END = 0.35D;
	public static final double BOOST_END = 0.55D;
	public static final double FAST_END = 0.72D;
	public static final double HYPERSONIC_END = 0.88D;

	private static final Rgb HOVER_CYAN = new Rgb(54, 244, 255);
	private static final Rgb CRUISE_BLUE = new Rgb(44, 112, 255);
	private static final Rgb BOOST_VIOLET = new Rgb(184, 52, 255);
	private static final Rgb FAST_MAGENTA = new Rgb(255, 32, 112);
	private static final Rgb HYPERSONIC_ORANGE = new Rgb(255, 92, 24);
	private static final Rgb MAXIMUM_GOLD = new Rgb(255, 226, 82);

	private FlightLightColorCurve() {
	}

	public static Rgb colorAt(double speedProgress) {
		double progress = clamp01(speedProgress);
		if (progress <= CRUISE_END) {
			return mix(HOVER_CYAN, CRUISE_BLUE,
					smoothRange(0.0D, CRUISE_END, progress));
		}
		if (progress <= BOOST_END) {
			return mix(CRUISE_BLUE, BOOST_VIOLET,
					smoothRange(CRUISE_END, BOOST_END, progress));
		}
		if (progress <= FAST_END) {
			return mix(BOOST_VIOLET, FAST_MAGENTA,
					smoothRange(BOOST_END, FAST_END, progress));
		}
		if (progress <= HYPERSONIC_END) {
			return mix(FAST_MAGENTA, HYPERSONIC_ORANGE,
					smoothRange(FAST_END, HYPERSONIC_END, progress));
		}
		return mix(HYPERSONIC_ORANGE, MAXIMUM_GOLD,
				smoothRange(HYPERSONIC_END, 1.0D, progress));
	}

	private static double smoothRange(double start, double end, double value) {
		double linear = clamp01((value - start) / Math.max(1.0E-9D, end - start));
		return linear * linear * (3.0D - 2.0D * linear);
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) return 0.0D;
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static Rgb mix(Rgb from, Rgb to, double progress) {
		double value = clamp01(progress);
		return new Rgb(
				(int) Math.round(from.red + (to.red - from.red) * value),
				(int) Math.round(from.green + (to.green - from.green) * value),
				(int) Math.round(from.blue + (to.blue - from.blue) * value));
	}

	public record Rgb(int red, int green, int blue) {
		public Rgb {
			if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) {
				throw new IllegalArgumentException("RGB channel outside 0..255");
			}
		}
	}
}
