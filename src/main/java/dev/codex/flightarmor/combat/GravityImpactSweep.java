package dev.codex.flightarmor.combat;

/** Pure swept-AABB contact and once-per-dive action gate. */
final class GravityImpactSweep {
	static final double NO_HIT = Double.POSITIVE_INFINITY;
	private static final double EPSILON = 1.0E-9D;

	private GravityImpactSweep() {
	}

	static Contact sweep(Box moving, Vector delta, Box target, double padding) {
		if (moving == null || delta == null || target == null || !delta.finite()) {
			return Contact.miss();
		}
		double pad = Double.isFinite(padding) ? Math.max(0.0D, padding) : 0.0D;
		double halfX = Math.max(0.0D, (moving.maxX() - moving.minX()) * 0.5D);
		double halfY = Math.max(0.0D, (moving.maxY() - moving.minY()) * 0.5D);
		double halfZ = Math.max(0.0D, (moving.maxZ() - moving.minZ()) * 0.5D);
		double centerX = (moving.minX() + moving.maxX()) * 0.5D;
		double centerY = (moving.minY() + moving.maxY()) * 0.5D;
		double centerZ = (moving.minZ() + moving.maxZ()) * 0.5D;
		Box expanded = new Box(target.minX() - halfX - pad, target.minY() - halfY - pad,
				target.minZ() - halfZ - pad, target.maxX() + halfX + pad,
				target.maxY() + halfY + pad, target.maxZ() + halfZ + pad);

		AxisRange range = new AxisRange(0.0D, 1.0D, Vector.ZERO);
		range = clip(centerX, delta.x(), expanded.minX(), expanded.maxX(),
				new Vector(delta.x() >= 0.0D ? -1.0D : 1.0D, 0.0D, 0.0D), range);
		if (range == null) return Contact.miss();
		range = clip(centerY, delta.y(), expanded.minY(), expanded.maxY(),
				new Vector(0.0D, delta.y() >= 0.0D ? -1.0D : 1.0D, 0.0D), range);
		if (range == null) return Contact.miss();
		range = clip(centerZ, delta.z(), expanded.minZ(), expanded.maxZ(),
				new Vector(0.0D, 0.0D, delta.z() >= 0.0D ? -1.0D : 1.0D), range);
		if (range == null) return Contact.miss();
		Vector normal = range.normal();
		if (normal == Vector.ZERO) normal = oppositeDominant(delta);
		return new Contact(true, range.entry(), normal);
	}

	static boolean canCommit(long activeSequence, long completedSequence, boolean cooldownReady) {
		return activeSequence > 0L && activeSequence != completedSequence && cooldownReady;
	}

	private static AxisRange clip(double origin, double delta, double min, double max,
			Vector entryNormal, AxisRange current) {
		if (Math.abs(delta) < EPSILON) {
			return origin < min || origin > max ? null : current;
		}
		double first = (min - origin) / delta;
		double second = (max - origin) / delta;
		if (first > second) {
			double swap = first;
			first = second;
			second = swap;
		}
		double entry = Math.max(current.entry(), first);
		double exit = Math.min(current.exit(), second);
		if (entry > exit || exit < 0.0D || entry > 1.0D) return null;
		Vector normal = first > current.entry() ? entryNormal : current.normal();
		return new AxisRange(Math.max(0.0D, entry), Math.min(1.0D, exit), normal);
	}

	private static Vector oppositeDominant(Vector delta) {
		double ax = Math.abs(delta.x()), ay = Math.abs(delta.y()), az = Math.abs(delta.z());
		if (ax >= ay && ax >= az) return new Vector(delta.x() >= 0.0D ? -1.0D : 1.0D, 0.0D, 0.0D);
		if (ay >= az) return new Vector(0.0D, delta.y() >= 0.0D ? -1.0D : 1.0D, 0.0D);
		return new Vector(0.0D, 0.0D, delta.z() >= 0.0D ? -1.0D : 1.0D);
	}

	record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	record Vector(double x, double y, double z) {
		static final Vector ZERO = new Vector(0.0D, 0.0D, 0.0D);

		boolean finite() {
			return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
		}
	}

	record Contact(boolean hit, double fraction, Vector normal) {
		static Contact miss() {
			return new Contact(false, NO_HIT, Vector.ZERO);
		}
	}

	private record AxisRange(double entry, double exit, Vector normal) {
	}
}