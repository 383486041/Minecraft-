package dev.codex.flightarmor.client.audio;

import dev.codex.flightarmor.sound.RadialDopplerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Per-source radial-distance sampler. World changes and invalid positions prime
 * a fresh neutral sample instead of carrying pitch state across dimensions.
 */
public final class RadialDopplerTracker {
	private ClientLevel sampledLevel;
	private Vec3 previousSource;
	private Vec3 previousListener;
	private double previousDistance;
	private double smoothedFactor = 1.0D;
	private boolean primed;

	public float sample(ClientLevel level, Vec3 sourcePosition,
			Vec3 listenerPosition, boolean spatialSource) {
		if (!spatialSource || level == null || !finite(sourcePosition)
				|| !finite(listenerPosition)) {
			reset();
			return 1.0F;
		}
		if (sampledLevel != level) {
			reset();
			sampledLevel = level;
		}

		double distance = sourcePosition.distanceTo(listenerPosition);
		if (!Double.isFinite(distance)) {
			reset();
			return 1.0F;
		}
		if (!primed) {
			prime(sourcePosition, listenerPosition, distance);
			return 1.0F;
		}

		double sourceStep = sourcePosition.distanceTo(previousSource);
		double listenerStep = listenerPosition.distanceTo(previousListener);
		double radialDelta = previousDistance - distance;
		boolean valid = Double.isFinite(sourceStep) && Double.isFinite(listenerStep)
				&& sourceStep <= RadialDopplerModel.MAX_VALID_RADIAL_DELTA
				&& listenerStep <= RadialDopplerModel.MAX_VALID_RADIAL_DELTA
				&& Math.abs(radialDelta) <= RadialDopplerModel.MAX_VALID_RADIAL_DELTA;
		double target = valid
				? RadialDopplerModel.targetFactor(true, distance, radialDelta)
				: 1.0D;
		smoothedFactor = valid
				? RadialDopplerModel.smoothFactor(smoothedFactor, target) : 1.0D;
		prime(sourcePosition, listenerPosition, distance);
		return (float) smoothedFactor;
	}

	/**
	 * Uses actual source/listener motion only for a safe first sample. Once
	 * primed, subsequent calls fall back to measured separation deltas.
	 */
	public float sampleWithMotionWarmup(ClientLevel level, Vec3 sourcePosition,
			Vec3 listenerPosition, Vec3 sourceMotion, Vec3 listenerMotion,
			boolean spatialSource) {
		if (primed && sampledLevel == level) {
			return sample(level, sourcePosition, listenerPosition, spatialSource);
		}
		if (!spatialSource || level == null || !finite(sourcePosition)
				|| !finite(listenerPosition)) {
			reset();
			return 1.0F;
		}
		if (sampledLevel != level) {
			reset();
			sampledLevel = level;
		}
		double distance = sourcePosition.distanceTo(listenerPosition);
		if (!Double.isFinite(distance)) {
			reset();
			return 1.0F;
		}
		double closingSpeed = finite(sourceMotion) && finite(listenerMotion)
				? RadialDopplerModel.projectedClosingSpeed(
						sourcePosition.x - listenerPosition.x,
						sourcePosition.y - listenerPosition.y,
						sourcePosition.z - listenerPosition.z,
						sourceMotion.x, sourceMotion.y, sourceMotion.z,
						listenerMotion.x, listenerMotion.y, listenerMotion.z)
				: 0.0D;
		double target = RadialDopplerModel.targetFactor(true, distance, closingSpeed);
		smoothedFactor = RadialDopplerModel.smoothFactor(1.0D, target);
		prime(sourcePosition, listenerPosition, distance);
		return (float) smoothedFactor;
	}

	public void reset() {
		sampledLevel = null;
		previousSource = null;
		previousListener = null;
		previousDistance = 0.0D;
		smoothedFactor = 1.0D;
		primed = false;
	}

	private void prime(Vec3 source, Vec3 listener, double distance) {
		previousSource = source;
		previousListener = listener;
		previousDistance = distance;
		primed = true;
	}

	private static boolean finite(Vec3 value) {
		return value != null && Double.isFinite(value.x)
				&& Double.isFinite(value.y) && Double.isFinite(value.z);
	}
}
