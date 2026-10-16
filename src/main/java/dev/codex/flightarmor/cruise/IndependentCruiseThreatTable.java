package dev.codex.flightarmor.cruise;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure per-owner+suit threat scoring with target stickiness and bounded stale memory. */
final class IndependentCruiseThreatTable {
	static final int NO_TARGET = -1;
	private static final long MEMORY_TICKS = 40L;
	private static final double STICKINESS_BONUS = 28.0D;
	private static final double SWITCH_MARGIN = 14.0D;
	private final Map<Integer, Entry> entries = new HashMap<>();

	Selection update(long now, List<Observation> observations, int currentTargetId) {
		Set<Integer> observed = new HashSet<>();
		for (Observation observation : observations) {
			if (observation == null || observation.entityId() < 0
					|| !Double.isFinite(observation.distance()) || observation.distance() < 0.0D) continue;
			double score = score(observation);
			entries.put(observation.entityId(), new Entry(score, now));
			observed.add(observation.entityId());
		}
		entries.entrySet().removeIf(entry -> now - entry.getValue().lastObservedTick > MEMORY_TICKS);

		int bestId = NO_TARGET;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int id : observed) {
			Entry entry = entries.get(id);
			if (entry != null && entry.score > bestScore) {
				bestScore = entry.score;
				bestId = id;
			}
		}
		Entry current = observed.contains(currentTargetId) ? entries.get(currentTargetId) : null;
		if (current != null && bestId != currentTargetId
				&& bestScore < current.score + STICKINESS_BONUS + SWITCH_MARGIN) {
			return new Selection(currentTargetId, current.score, false);
		}
		return new Selection(bestId, bestScore, bestId != currentTargetId);
	}

	void clear() {
		entries.clear();
	}

	private static double score(Observation observation) {
		double score = Math.max(0.0D, 72.0D - observation.distance()) * 1.35D;
		if (observation.visible()) score += 26.0D;
		if (observation.hostile()) score += 42.0D;
		if (observation.attackingOwner()) score += 92.0D;
		if (observation.recentAttacker()) score += 138.0D;
		return score;
	}

	record Observation(int entityId, double distance, boolean visible,
			boolean hostile, boolean attackingOwner, boolean recentAttacker) {
	}

	record Selection(int targetEntityId, double score, boolean switched) {
	}

	private record Entry(double score, long lastObservedTick) {
	}
}
