package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.VariantFlightProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline regression gate for the realized-speed light palette.
 */
public final class FlightLightColorCurveTest {
	private FlightLightColorCurveTest() {
	}

	public static void main(String[] args) {
		VariantFlightProfile profile = ArmorVariant.DEFAULT.fullFlightProfile();
		double cruise = profile.cruise().targetSpeed();
		double boost = profile.boost().targetSpeed();
		double maximum = profile.dive().targetSpeed();
		double[] acceleratingSpeeds = {
				0.0D,
				cruise * 0.50D,
				cruise,
				(cruise + boost) * 0.50D,
				boost,
				(boost + maximum) * 0.50D,
				maximum
		};

		List<Double> acceleratingProgress = new ArrayList<>();
		List<FlightLightColorCurve.Rgb> acceleratingColors = new ArrayList<>();
		double previous = -1.0D;
		for (double speed : acceleratingSpeeds) {
			double progress = FlightPhysics.effectSpeedProgress(speed);
			require(progress + 1.0E-9D >= previous,
					"realized-speed effect progress regressed while accelerating");
			acceleratingProgress.add(progress);
			acceleratingColors.add(FlightLightColorCurve.colorAt(progress));
			previous = progress;
		}

		FlightLightColorCurve.Rgb cruiseColor =
				FlightLightColorCurve.colorAt(FlightPhysics.effectSpeedProgress(cruise));
		FlightLightColorCurve.Rgb ctrlBoostColor =
				FlightLightColorCurve.colorAt(FlightPhysics.effectSpeedProgress(boost));
		require(!cruiseColor.equals(ctrlBoostColor),
				"cruise and Ctrl-thrust target speeds must have visibly different colors");
		require(colorDistanceSquared(cruiseColor, ctrlBoostColor) >= 2_500,
				"cruise-to-Ctrl color transition is too subtle");

		List<Double> brakingProgress = new ArrayList<>();
		List<FlightLightColorCurve.Rgb> brakingColors = new ArrayList<>();
		previous = Double.POSITIVE_INFINITY;
		for (int index = acceleratingSpeeds.length - 1; index >= 0; index--) {
			double progress = FlightPhysics.effectSpeedProgress(acceleratingSpeeds[index]);
			require(progress <= previous + 1.0E-9D,
					"realized-speed effect progress increased while braking");
			brakingProgress.add(progress);
			brakingColors.add(FlightLightColorCurve.colorAt(progress));
			previous = progress;
		}

		List<Double> expectedReverseProgress = new ArrayList<>(acceleratingProgress);
		List<FlightLightColorCurve.Rgb> expectedReverseColors = new ArrayList<>(acceleratingColors);
		Collections.reverse(expectedReverseProgress);
		Collections.reverse(expectedReverseColors);
		require(brakingProgress.equals(expectedReverseProgress),
				"braking must traverse the same progress samples in reverse");
		require(brakingColors.equals(expectedReverseColors),
				"braking must traverse the same color ramp in reverse");

		System.out.println("Flight light color curve regression passed");
	}

	private static long colorDistanceSquared(FlightLightColorCurve.Rgb first,
			FlightLightColorCurve.Rgb second) {
		long red = first.red() - second.red();
		long green = first.green() - second.green();
		long blue = first.blue() - second.blue();
		return red * red + green * green + blue * blue;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
