package dev.codex.flightarmor.flight;

import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.VariantFlightProfile;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class FlightPhysics {
	/** Minecraft 的固定逻辑频率；速度向 HUD 换算时不要按渲染帧率计算。 */
	public static final double TICKS_PER_SECOND = FlightUnits.TICKS_PER_SECOND;
	/** 当前世界比例：1 格=2 米；空气中 75 格/秒即 150 米/秒，为 Mach 1。 */
	public static final double APPROXIMATE_SOUND_SPEED_BLOCKS_PER_SECOND = 75.0D;
	/** 水中保留约 4.32 倍介质差异，Mach 1 为 324 格/秒。 */
	public static final double APPROXIMATE_WATER_SOUND_SPEED_BLOCKS_PER_SECOND = 324.0D;
	public static final double APPROXIMATE_SOUND_SPEED_BLOCKS_PER_TICK =
			APPROXIMATE_SOUND_SPEED_BLOCKS_PER_SECOND / TICKS_PER_SECOND;
	public static final double APPROXIMATE_WATER_SOUND_SPEED_BLOCKS_PER_TICK =
			APPROXIMATE_WATER_SOUND_SPEED_BLOCKS_PER_SECOND / TICKS_PER_SECOND;
	private static final double MOTION_EPSILON = 1.0E-6D;
	private static final double BRAKE_TO_HOVER_COMMAND_SPEED = 0.045D;
	private static final double BRAKE_TO_HOVER_HORIZONTAL_SPEED = 0.10D;
	private static final double BRAKE_TO_HOVER_TOTAL_SPEED = 0.14D;
	private static final double MANEUVER_DEADBAND_RADIANS = Math.toRadians(8.0D);
	private static final double MANEUVER_FULL_BRAKE_RADIANS = Math.toRadians(95.0D);
	private static final double MANEUVER_SPEED_FLOOR_FRACTION = 0.20D;
	private static final double MAXIMUM_SHARP_TURN_ASSIST = 1.65D;
	private static final double STRAFE_STEERING_DEGREES = 18.0D;
	/** 客户端和服务端实体对象分别持有一份上一 tick 实际位置，不会在整合服务器内互相覆盖。 */
	private static final Map<Player, MotionSample> MOTION_SAMPLES = new WeakHashMap<>();
	/** High-speed mode keeps a commanded speed instead of jumping to the former dive target. */
	private static final Map<Player, HighSpeedControl> HIGH_SPEED_CONTROLS = new WeakHashMap<>();

	private FlightPhysics() {
	}

	/**
	 * 着装完成的捕获帧先消除高速下坠，再由同一 tick 的悬停物理接管。新物理直接输出下一 tick
	 * 的期望位移，因此这里归零竖直速度，不再预留一份原版重力。
	 */
	public static void prepareHoverEngagement(Player player) {
		resetMotionSample(player);
		Vec3 current = player.getDeltaMovement();
		// 自动接管帧主动制动高速横移，避免玩家已经悬停但仍横向滑出着装区域。
		player.setDeltaMovement(current.x * 0.12D, 0.0D, current.z * 0.12D);
		player.fallDistance = 0.0F;
	}

	/**
	 * 战靴飞抵前的短暂防坠保护。它不建立飞行状态、也不处理方向输入，只限制继续下坠；
	 * 战靴锁定后仍必须由正式悬空状态机接管。
	 */
	public static void applyPreBootFallArrest(Player player) {
		Vec3 current = player.getDeltaMovement();
		double minimumVertical = player.isInWater() ? -0.03D : -0.16D;
		if (!player.onGround() && current.y < minimumVertical) {
			player.setDeltaMovement(current.x, minimumVertical, current.z);
		}
		player.fallDistance = 0.0F;
	}

	/** 战靴在地面完成锁定后先轻抬离地，下一 tick 再由正式悬停物理接管。 */
	public static void prepareBootLaunch(Player player) {
		resetMotionSample(player);
		Vec3 current = player.getDeltaMovement();
		double lift = player.isInWater() ? 0.22D : 0.34D;
		player.setDeltaMovement(current.x * 0.18D, Math.max(current.y, lift), current.z * 0.18D);
		player.fallDistance = 0.0F;
	}

	public static FlightStep apply(Player player, boolean diving, boolean boosting, boolean movingForward,
			boolean movingBackward, boolean movingLeft, boolean movingRight, boolean ascending, boolean descending) {
		return apply(player, player.getLookAngle(), diving, boosting, movingForward,
				movingBackward, movingLeft, movingRight, ascending, descending);
	}

	/**
	 * Shared deterministic steering entry used by client prediction and server authority.
	 * Supplying the same quantized look snapshot prevents a fast camera turn from producing two
	 * different velocity curves on the two logical sides.
	 */
	public static FlightStep apply(Player player, Vec3 steeringLook,
			boolean diving, boolean boosting, boolean movingForward,
			boolean movingBackward, boolean movingLeft, boolean movingRight,
			boolean ascending, boolean descending) {
		Vec3 look = isFinite(steeringLook) && steeringLook.lengthSqr() > MOTION_EPSILON
				? steeringLook.normalize() : player.getLookAngle();
		VariantFlightProfile profile = resolveFlightProfile(player);
		boolean highSpeed = diving;
		Vec3 current = sampleRealizedVelocity(player, profile);
		double currentSpeed = finiteLength(current);
		if (!highSpeed) clearHighSpeedControl(player);

		// 三种型号共用同一套真实速度算法；型号只提供目标、加速时间、制动时间和转向能力。
		VariantFlightProfile.Mode tuning = diving
				? profile.dive() : boosting ? profile.boost() : profile.cruise();
		double speed = highSpeed
				? updateHighSpeedCommand(player, currentSpeed, movingForward, movingBackward, boosting, tuning)
				: tuning.targetSpeed();

		Vec3 target;
		if (highSpeed) {
			Vec3 flightDirection = look.lengthSqr() > MOTION_EPSILON
					? look.normalize()
					: currentSpeed > MOTION_EPSILON ? current.scale(1.0D / currentSpeed) : Vec3.ZERO;
			int strafe = (movingRight ? 1 : 0) - (movingLeft ? 1 : 0);
			if (strafe != 0) {
				flightDirection = rotateAroundY(flightDirection,
						Math.toRadians(STRAFE_STEERING_DEGREES * strafe));
			}
			target = constrainedHighSpeedTarget(
					flightDirection, current, speed, tuning);
		} else {
			Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
			if (horizontalLook.lengthSqr() > 1.0E-4D) {
				horizontalLook = horizontalLook.normalize();
			}
			int forward = (movingForward ? 1 : 0) - (movingBackward ? 1 : 0);
			int strafe = (movingRight ? 1 : 0) - (movingLeft ? 1 : 0);
			Vec3 right = new Vec3(-horizontalLook.z, 0.0D, horizontalLook.x);
			target = horizontalLook.scale(speed * forward).add(right.scale(speed * strafe));
			if (target.lengthSqr() > speed * speed) {
				target = target.normalize().scale(speed);
			}
		}

		if (!highSpeed && ascending != descending) {
			target = target.add(0.0D, ascending ? tuning.verticalSpeed() : -tuning.verticalSpeed(), 0.0D);
		}
		if (!highSpeed) {
			target = new Vec3(target.x,
					Mth.clamp(target.y, tuning.minVerticalSpeed(),
							tuning.maxVerticalSpeed()), target.z);
		}

		boolean underwater = player.isInWater();
		// 下半身浸水、头部仍在外面 = 贴水面滑行，而不是真正入水。
		boolean skimmingWater = underwater && !player.isEyeInFluid(FluidTags.WATER);
		// 使用本 tick 已经真正发生的位移作为速度累计状态。deltaMovement 是下一 tick 的计划速度，
		// 会被碰撞、流体、服务器纠正和原版阻力改变，不能继续拿它冒充实际速度。
		double requestedSpeed = target.length();
		if (requestedSpeed > tuning.hardSpeedCap()) {
			target = target.scale(tuning.hardSpeedCap() / requestedSpeed);
			requestedSpeed = tuning.hardSpeedCap();
		}
		double headingError = headingErrorRadians(current, currentSpeed, target, requestedSpeed);
		if (highSpeed && requestedSpeed > MOTION_EPSILON) {
			double maneuverLimit = highSpeedManeuverSpeedLimit(
					profile, currentSpeed, requestedSpeed, headingError);
			if (maneuverLimit < requestedSpeed) {
				target = target.scale(maneuverLimit / requestedSpeed);
				requestedSpeed = maneuverLimit;
			}
		}

		double accelerationMultiplier = highSpeed && boosting ? 1.85D : 1.0D;
		double nextSpeed = approachSpeed(currentSpeed, requestedSpeed, tuning, profile,
				accelerationMultiplier);
		double turnRate = highSpeed
				? highSpeedTurnRate(profile, tuning, currentSpeed, headingError)
				: steeringTurnRate(profile, tuning, currentSpeed);
		Vec3 direction = nextDirection(current, currentSpeed, target, requestedSpeed, turnRate);
		Vec3 next = nextSpeed <= MOTION_EPSILON ? Vec3.ZERO : direction.scale(nextSpeed);
		if (underwater && !diving && ascending == descending) {
			// 反重力悬停直接覆盖下一 tick 的浮力，水中不会被缓慢推回水面。
			next = new Vec3(next.x, 0.0D, next.z);
		}
		if (skimmingWater && !descending) {
			// 水面滑行：水平速度原样保留，只禁止垂直分量继续向下，玩家贴在水面上掠过而不下沉。
			next = new Vec3(next.x, Math.max(next.y, 0.0D), next.z);
		}

		// 切出俯冲时保留既有惯性并按制动曲线下降；只有整套档案的俯冲上限是绝对安全边界。
		double absoluteCap = profile.dive().hardSpeedCap();
		double inheritedCap = Math.min(absoluteCap, Math.max(tuning.hardSpeedCap(), currentSpeed));
		double totalSpeed = finiteLength(next);
		if (totalSpeed > inheritedCap) {
			next = next.scale(inheritedCap / totalSpeed);
		}
		double globalMinVertical = Math.min(profile.cruise().minVerticalSpeed(),
				Math.min(profile.boost().minVerticalSpeed(), profile.dive().minVerticalSpeed()));
		double globalMaxVertical = Math.max(profile.cruise().maxVerticalSpeed(),
				Math.max(profile.boost().maxVerticalSpeed(), profile.dive().maxVerticalSpeed()));
		next = new Vec3(next.x, Mth.clamp(next.y, globalMinVertical, globalMaxVertical), next.z);
		totalSpeed = finiteLength(next);
		if (totalSpeed > absoluteCap) {
			next = next.scale(absoluteCap / totalSpeed);
		}
		/*
		 * 高速档撞上方块**不减速**。这一档的碰撞本来就是「撞穿」：撞击爆破会在撞击点掀开
		 * 一个球，若这里再按保留率把下一 tick 的计划速度削到 0.16 倍，玩家会在洞口自己
		 * 把自己刹停——表现就是「山明明炸开了，人却卡住了」。
		 * 悬停/巡航档保留原行为：贴着墙时收速才不会变成持续高速抖动。
		 */
		if (player.horizontalCollision && !diving) {
			double retention = profile.collisionHorizontalRetention();
			next = new Vec3(next.x * retention,
					Mth.clamp(next.y, -0.25D, 0.25D), next.z * retention);
		}
		player.setDeltaMovement(next);
		recordMotionSample(player, next);
		player.fallDistance = 0.0F;
		return new FlightStep(horizontalLength(current), finiteLength(current),
				highSpeed ? speed : 0.0D);
	}

	/**
	 * Completes an S-brake transition without snapping a still-moving player to a dead stop.
	 * The residual direction is retained, but both horizontal drift and vertical motion are
	 * constrained to a safe hover-entry envelope. Water hover explicitly cancels buoyancy.
	 */
	public static void settleBrakeToHover(Player player) {
		if (player == null) return;
		VariantFlightProfile profile = resolveFlightProfile(player);
		Vec3 current = sanitizedFallbackVelocity(player.getDeltaMovement(),
				profile.dive().hardSpeedCap());
		double horizontalSpeed = horizontalLength(current);
		double safeHorizontalSpeed = Math.min(BRAKE_TO_HOVER_HORIZONTAL_SPEED,
				Math.max(0.05D, profile.cruise().targetSpeed() * 0.18D));
		double horizontalScale = horizontalSpeed > safeHorizontalSpeed
				? safeHorizontalSpeed / horizontalSpeed : 1.0D;
		double vertical = player.isInWater()
				? 0.0D : Mth.clamp(current.y * 0.25D, -0.035D, 0.035D);
		Vec3 settled = new Vec3(current.x * horizontalScale, vertical, current.z * horizontalScale);
		resetMotionSample(player);
		player.setDeltaMovement(settled);
		player.fallDistance = 0.0F;
	}

	/**
	 * 清除某个实体的位移基线。停飞、切维度或自动悬停重新接管时必须调用，避免把非飞行位移
	 * 当成新会话的初速度。
	 */
	public static ImpactFrame impactFrame(Player player) {
		if (player == null) return null;
		MotionSample previous;
		synchronized (MOTION_SAMPLES) {
			previous = MOTION_SAMPLES.get(player);
		}
		if (previous == null || !previous.dimension().equals(player.level().dimension())
				|| player.tickCount - previous.tick() != 1) {
			return null;
		}
		Vec3 end = player.position();
		if (!isFinite(end) || !isFinite(previous.position())
				|| !isFinite(previous.plannedVelocity())) {
			return null;
		}
		return new ImpactFrame(previous.position(), end, previous.plannedVelocity());
	}
	public static void resetMotionSample(Player player) {
		if (player == null) return;
		synchronized (MOTION_SAMPLES) {
			MOTION_SAMPLES.remove(player);
		}
		synchronized (HIGH_SPEED_CONTROLS) {
			HIGH_SPEED_CONTROLS.remove(player);
		}
	}

	private static void clearHighSpeedControl(Player player) {
		synchronized (HIGH_SPEED_CONTROLS) {
			HIGH_SPEED_CONTROLS.remove(player);
		}
	}

	/** W/S changes commanded speed; Ctrl adds stronger engine thrust without an instant mode-speed jump. */
	private static double updateHighSpeedCommand(Player player, double currentSpeed,
			boolean movingForward, boolean movingBackward, boolean boosting,
			VariantFlightProfile.Mode tuning) {
		double commandedSpeed;
		synchronized (HIGH_SPEED_CONTROLS) {
			HighSpeedControl previous = HIGH_SPEED_CONTROLS.get(player);
			boolean continuing = previous != null
					&& previous.dimension().equals(player.level().dimension())
					&& player.tickCount - previous.tick() >= 0
					&& player.tickCount - previous.tick() <= 1;
			commandedSpeed = continuing
					? previous.commandedSpeed()
					: Mth.clamp(currentSpeed, 0.0D, tuning.hardSpeedCap());

			commandedSpeed = HighSpeedThrottle.advance(commandedSpeed,
					movingForward, movingBackward, boosting, tuning);
			HIGH_SPEED_CONTROLS.put(player, new HighSpeedControl(player.level().dimension(),
					player.tickCount, commandedSpeed));
		}
		return commandedSpeed;
	}

	/**
	 * 原版 {@code travel} 在实体真正移动前还会根据 W/A/S/D 再施加一次相对推力。战甲控制器在上一
	 * tick 已经给出完整推进向量，因此活动会话必须屏蔽这次重复推进，否则真实位移会把原版推力再次
	 * 累计到下一 tick，几秒加速曲线就会缩短成近乎瞬时加速。
	 */
	public static boolean isControllerActive(Player player) {
		if (player == null || player.isRemoved()) return false;
		MotionSample sample;
		synchronized (MOTION_SAMPLES) {
			sample = MOTION_SAMPLES.get(player);
		}
		if (sample == null || !sample.dimension().equals(player.level().dimension())) return false;
		int age = player.tickCount - sample.tick();
		// apply 在 END tick 写入样本，下一次实体 travel 时 age 为 1；超时会话自动失效，避免输入被误吞。
		return age >= 0 && age <= 1;
	}

	private static Vec3 sampleRealizedVelocity(Player player, VariantFlightProfile profile) {
		Vec3 position = player.position();
		ResourceKey<Level> dimension = player.level().dimension();
		MotionSample previous;
		synchronized (MOTION_SAMPLES) {
			previous = MOTION_SAMPLES.get(player);
		}
		if (previous != null && previous.dimension().equals(dimension)
				&& player.tickCount - previous.tick() == 1) {
			Vec3 displacement = position.subtract(previous.position());
			Vec3 planned = previous.plannedVelocity();
			double plannedSpeed = finiteLength(planned);
			double residualAllowance = 0.35D + plannedSpeed * 0.08D;
			double maximumPlausible = Math.min(profile.dive().hardSpeedCap() * 1.10D + 0.25D,
					plannedSpeed + residualAllowance);
			double displacementSpeed = finiteLength(displacement);
			boolean finiteDisplacement = isFinite(displacement);
			boolean collisionResponse = finiteDisplacement
					&& (player.horizontalCollision || player.verticalCollision)
					&& displacementSpeed <= maximumPlausible;
			boolean followsPlan = finiteDisplacement
					&& finiteLength(displacement.subtract(planned)) <= residualAllowance;
			if (finiteDisplacement && displacementSpeed <= maximumPlausible
					&& (collisionResponse || followsPlan)) {
				return displacement;
			}
		}
		// 同维传送或服务器位置纠正不能变成新的飞行惯性；优先沿用上一 tick 的控制器计划速度。
		Vec3 fallback = previous != null && previous.dimension().equals(dimension)
				? previous.plannedVelocity() : player.getDeltaMovement();
		return sanitizedFallbackVelocity(fallback, profile.dive().hardSpeedCap());
	}

	private static void recordMotionSample(Player player, Vec3 plannedVelocity) {
		MotionSample sample = new MotionSample(player.position(), player.level().dimension(),
				player.tickCount, plannedVelocity);
		synchronized (MOTION_SAMPLES) {
			MOTION_SAMPLES.put(player, sample);
		}
	}

	private static Vec3 sanitizedFallbackVelocity(Vec3 velocity, double absoluteCap) {
		if (!isFinite(velocity)) return Vec3.ZERO;
		double speed = velocity.length();
		if (!Double.isFinite(speed) || speed <= MOTION_EPSILON) return Vec3.ZERO;
		return speed > absoluteCap ? velocity.scale(absoluteCap / speed) : velocity;
	}

	private static double approachSpeed(double currentSpeed, double targetSpeed,
			VariantFlightProfile.Mode tuning, VariantFlightProfile profile,
			double accelerationMultiplier) {
		if (targetSpeed > currentSpeed + MOTION_EPSILON) {
			double baseAcceleration = tuning.targetSpeed()
					/ (tuning.accelerationSeconds() * TICKS_PER_SECOND);
			double progress = Mth.clamp(currentSpeed
					/ Math.max(tuning.targetSpeed(), targetSpeed), 0.0D, 1.0D);
			double step = baseAcceleration * accelerationEnvelope(progress)
					* Mth.clamp(accelerationMultiplier, 0.10D, 4.0D);
			return Math.min(targetSpeed, currentSpeed + step);
		}
		if (targetSpeed < currentSpeed - MOTION_EPSILON) {
			VariantFlightProfile.Mode brakingMode = brakingMode(profile, currentSpeed);
			double step = brakingMode.targetSpeed()
					/ (brakingMode.brakingSeconds() * TICKS_PER_SECOND);
			return Math.max(targetSpeed, currentSpeed - step);
		}
		return targetSpeed;
	}

	/** 慢起步、中段稳定推力、接近目标时柔和收束；曲线只看真实速度，不看按键持续时间。 */
	private static double accelerationEnvelope(double progress) {
		if (progress < 0.12D) {
			return Mth.lerp(smooth01(progress / 0.12D), 0.45D, 0.82D);
		}
		if (progress < 0.75D) {
			return Mth.lerp(smooth01((progress - 0.12D) / 0.63D), 0.82D, 1.12D);
		}
		return Mth.lerp(smooth01((progress - 0.75D) / 0.25D), 1.12D, 0.35D);
	}

	private static VariantFlightProfile.Mode brakingMode(VariantFlightProfile profile, double currentSpeed) {
		if (currentSpeed > profile.boost().hardSpeedCap() + MOTION_EPSILON) return profile.dive();
		if (currentSpeed > profile.cruise().hardSpeedCap() + MOTION_EPSILON) return profile.boost();
		return profile.cruise();
	}

	public static Vec3 lookDirection(float yawDegrees, float pitchDegrees) {
		float yaw = Mth.wrapDegrees(Float.isFinite(yawDegrees) ? yawDegrees : 0.0F)
				* Mth.DEG_TO_RAD;
		float pitch = Mth.clamp(Float.isFinite(pitchDegrees) ? pitchDegrees : 0.0F,
				-90.0F, 90.0F) * Mth.DEG_TO_RAD;
		float cosPitch = Mth.cos(pitch);
		return new Vec3(-Mth.sin(yaw) * cosPitch,
				-Mth.sin(pitch), Mth.cos(yaw) * cosPitch);
	}

	/**
	 * Clamps pitch without shortening the commanded three-dimensional speed. The previous code
	 * clipped Y after multiplying by speed, then treated the shorter vector as a braking command;
	 * merely looking upward could therefore remove most thrust.
	 */
	static Vec3 constrainedHighSpeedTarget(Vec3 desiredDirection, Vec3 current,
			double commandedSpeed, VariantFlightProfile.Mode tuning) {
		if (!isFinite(desiredDirection) || !Double.isFinite(commandedSpeed)
				|| commandedSpeed <= MOTION_EPSILON) {
			return Vec3.ZERO;
		}
		Vec3 desired = desiredDirection.normalize();
		double vertical = Mth.clamp(desired.y * commandedSpeed,
				tuning.minVerticalSpeed(), tuning.maxVerticalSpeed());
		vertical = Mth.clamp(vertical, -commandedSpeed, commandedSpeed);
		double horizontalSpeed = Math.sqrt(Math.max(0.0D,
				commandedSpeed * commandedSpeed - vertical * vertical));
		Vec3 horizontal = new Vec3(desired.x, 0.0D, desired.z);
		if (horizontal.lengthSqr() <= MOTION_EPSILON && isFinite(current)) {
			horizontal = new Vec3(current.x, 0.0D, current.z);
		}
		if (horizontal.lengthSqr() <= MOTION_EPSILON) {
			horizontal = new Vec3(0.0D, 0.0D, 1.0D);
		} else {
			horizontal = horizontal.normalize();
		}
		return horizontal.scale(horizontalSpeed).add(0.0D, vertical, 0.0D);
	}

	static double headingErrorRadians(Vec3 current, double currentSpeed,
			Vec3 target, double targetSpeed) {
		if (!isFinite(current) || !isFinite(target)
				|| currentSpeed <= MOTION_EPSILON || targetSpeed <= MOTION_EPSILON) {
			return 0.0D;
		}
		double dot = current.dot(target) / (currentSpeed * targetSpeed);
		return Math.acos(Mth.clamp(dot, -1.0D, 1.0D));
	}

	/**
	 * A sharp turn progressively lowers the temporary realized-speed target. W/Ctrl still own
	 * the persistent throttle command; once the armor realigns it accelerates back along the same
	 * long ramp. This produces a controllable banking arc instead of either an instant snap or a
	 * multi-thousand-block turn radius.
	 */
	static double highSpeedManeuverSpeedLimit(VariantFlightProfile profile,
			double currentSpeed, double requestedSpeed, double headingErrorRadians) {
		if (!Double.isFinite(requestedSpeed) || requestedSpeed <= MOTION_EPSILON) return 0.0D;
		double turnDemand = smooth01((headingErrorRadians - MANEUVER_DEADBAND_RADIANS)
				/ (MANEUVER_FULL_BRAKE_RADIANS - MANEUVER_DEADBAND_RADIANS));
		double speedPressure = smooth01((currentSpeed - profile.boost().targetSpeed())
				/ Math.max(MOTION_EPSILON,
						profile.dive().targetSpeed() - profile.boost().targetSpeed()));
		double maneuverDemand = turnDemand * speedPressure;
		double floor = Math.min(requestedSpeed, Math.max(profile.boost().targetSpeed(),
				profile.dive().targetSpeed() * MANEUVER_SPEED_FLOOR_FRACTION));
		return Mth.lerp(maneuverDemand, requestedSpeed, floor);
	}

	/**
	 * As the maneuver envelope sheds speed, steering authority returns continuously instead of
	 * remaining stuck at the high-speed mode's minimum turn rate.
	 */
	static double highSpeedTurnRate(VariantFlightProfile profile,
			VariantFlightProfile.Mode requestedMode, double currentSpeed,
			double headingErrorRadians) {
		double speedProgress = smooth01((currentSpeed - profile.boost().targetSpeed())
				/ Math.max(MOTION_EPSILON,
						profile.dive().targetSpeed() - profile.boost().targetSpeed()));
		double recoveredRate = Mth.lerp(speedProgress,
				profile.boost().turnRateDegreesPerSecond(),
				requestedMode.turnRateDegreesPerSecond());
		double turnDemand = smooth01((headingErrorRadians - MANEUVER_DEADBAND_RADIANS)
				/ (MANEUVER_FULL_BRAKE_RADIANS - MANEUVER_DEADBAND_RADIANS));
		double assisted = recoveredRate
				* Mth.lerp(turnDemand, 1.0D, MAXIMUM_SHARP_TURN_ASSIST);
		return Math.min(profile.boost().turnRateDegreesPerSecond(), assisted);
	}

	private static double steeringTurnRate(VariantFlightProfile profile,
			VariantFlightProfile.Mode requestedMode, double currentSpeed) {
		VariantFlightProfile.Mode speedBand = currentSpeed > profile.boost().hardSpeedCap()
				? profile.dive()
				: currentSpeed > profile.cruise().hardSpeedCap()
						? profile.boost() : profile.cruise();
		// 进入高速档和退出高速档都使用两侧较慢的转向率，避免切模式瞬间甩头或反向折返。
		return Math.min(requestedMode.turnRateDegreesPerSecond(),
				speedBand.turnRateDegreesPerSecond());
	}

	private static Vec3 nextDirection(Vec3 current, double currentSpeed, Vec3 target,
			double targetSpeed, double turnRateDegreesPerSecond) {
		if (targetSpeed <= MOTION_EPSILON) {
			return currentSpeed <= MOTION_EPSILON ? Vec3.ZERO : current.scale(1.0D / currentSpeed);
		}
		Vec3 targetDirection = target.scale(1.0D / targetSpeed);
		if (currentSpeed <= MOTION_EPSILON) return targetDirection;
		Vec3 currentDirection = current.scale(1.0D / currentSpeed);
		double maximumRadians = Math.toRadians(turnRateDegreesPerSecond / TICKS_PER_SECOND);
		return rotateToward(currentDirection, targetDirection, maximumRadians);
	}

	static Vec3 rotateToward(Vec3 current, Vec3 target, double maximumRadians) {
		double dot = Mth.clamp(current.dot(target), -1.0D, 1.0D);
		double angle = Math.acos(dot);
		if (!Double.isFinite(angle) || angle <= maximumRadians) return target;
		Vec3 axis = current.cross(target);
		if (axis.lengthSqr() <= 1.0E-10D) {
			// Exact horizontal reversal has no unique 3-D great-circle axis. Prefer a stable
			// world-up yaw so turning around can never become an accidental climb or dive.
			axis = Math.abs(current.y) < 0.96D
					? new Vec3(0.0D, 1.0D, 0.0D)
					: new Vec3(1.0D, 0.0D, 0.0D);
		} else {
			axis = axis.normalize();
		}
		double sin = Math.sin(maximumRadians);
		double cos = Math.cos(maximumRadians);
		Vec3 rotated = current.scale(cos)
				.add(axis.cross(current).scale(sin))
				.add(axis.scale(axis.dot(current) * (1.0D - cos)));
		return rotated.normalize();
	}

	static Vec3 rotateAroundY(Vec3 vector, double radians) {
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		return new Vec3(vector.x * cos + vector.z * sin,
				vector.y, vector.z * cos - vector.x * sin);
	}

	private static double smooth01(double value) {
		double clamped = Mth.clamp(value, 0.0D, 1.0D);
		return clamped * clamped * (3.0D - 2.0D * clamped);
	}

	private static boolean isFinite(Vec3 vector) {
		return vector != null && Double.isFinite(vector.x)
				&& Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}

	private static double finiteLength(Vec3 vector) {
		if (!isFinite(vector)) return 0.0D;
		double length = vector.length();
		return Double.isFinite(length) ? Math.max(0.0D, length) : 0.0D;
	}

	private static double horizontalLength(Vec3 vector) {
		if (!isFinite(vector)) return 0.0D;
		double squared = vector.x * vector.x + vector.z * vector.z;
		return Double.isFinite(squared) ? Math.sqrt(Math.max(0.0D, squared)) : 0.0D;
	}

	/** Authoritative server sample used by the sustained S-brake debounce. */
	public record ImpactFrame(Vec3 previousPosition, Vec3 currentPosition,
			Vec3 preContactVelocity) {
		public ImpactFrame {
			if (previousPosition == null || currentPosition == null || preContactVelocity == null) {
				throw new IllegalArgumentException("impact frame vectors cannot be null");
			}
		}

		public Vec3 displacement() {
			return currentPosition.subtract(previousPosition);
		}
	}
	public record FlightStep(double horizontalSpeed, double totalSpeed, double commandedSpeed) {
		public FlightStep {
			horizontalSpeed = finiteNonNegative(horizontalSpeed);
			totalSpeed = finiteNonNegative(totalSpeed);
			commandedSpeed = finiteNonNegative(commandedSpeed);
		}

		public boolean settledForHover() {
			return commandedSpeed <= BRAKE_TO_HOVER_COMMAND_SPEED
					&& horizontalSpeed <= BRAKE_TO_HOVER_HORIZONTAL_SPEED
					&& totalSpeed <= BRAKE_TO_HOVER_TOTAL_SPEED;
		}

		private static double finiteNonNegative(double value) {
			return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
		}
	}

	private record MotionSample(Vec3 position, ResourceKey<Level> dimension, int tick,
			Vec3 plannedVelocity) {
		private MotionSample {
			if (position == null || dimension == null || plannedVelocity == null) {
				throw new IllegalArgumentException("飞行位移样本不能为空");
			}
		}
	}

	private record HighSpeedControl(ResourceKey<Level> dimension, int tick, double commandedSpeed) {
		private HighSpeedControl {
			if (dimension == null || !Double.isFinite(commandedSpeed) || commandedSpeed < 0.0D) {
				throw new IllegalArgumentException("高速飞行控制样本无效");
			}
		}
	}

	/**
	 * 完整且同型号的四件套使用完整动力；单穿战靴或四件混装只使用战靴型号的降级动力。
	 * 客户端预测和服务端 tick 均调用本方法，因此不会维护两套可能漂移的型号参数。
	 */
	public static VariantFlightProfile resolveFlightProfile(LivingEntity entity) {
		var sentinelConfiguration = ArmorConfigurationAccess.configuredSentinel(entity);
		if (sentinelConfiguration.isPresent()) {
			return SentinelFlightProfiles.resolve(sentinelConfiguration.get().snapshot(),
					entity.level().getGameTime());
		}
		return ModItems.fullSetVariant(entity)
				.map(ArmorVariant::fullFlightProfile)
				.orElseGet(() -> ModItems.bootsVariant(entity)
						.map(ArmorVariant::bootsFlightProfile)
						.orElseGet(() -> ArmorVariant.DEFAULT.bootsFlightProfile()));
	}

	public static double blocksPerSecond(double blocksPerTick) {
		return FlightUnits.blocksPerSecond(blocksPerTick);
	}

	public static double metersPerSecond(double blocksPerTick) {
		return FlightUnits.metersPerSecond(blocksPerTick);
	}

	public static double kilometersPerHour(double blocksPerTick) {
		return FlightUnits.kilometersPerHour(blocksPerTick);
	}

	public static double machForBlocksPerTick(double blocksPerTick) {
		return machForBlocksPerTick(blocksPerTick, false);
	}

	/**
	 * 按游戏介质换算马赫数。空气中 0.25 格/tick（5 格/秒、10 米/秒）即 Mach 1，
	 * 水下阈值约 21.6 格/秒。
	 */
	public static double machForBlocksPerTick(double blocksPerTick, boolean underwater) {
		double soundSpeed = underwater
				? APPROXIMATE_WATER_SOUND_SPEED_BLOCKS_PER_SECOND
				: APPROXIMATE_SOUND_SPEED_BLOCKS_PER_SECOND;
		return blocksPerSecond(blocksPerTick) / soundSpeed;
	}

	/**
	 * 将真实速度映射到灯光、飞行声等表现层共用的 0～1 进度。
	 *
	 * <p>兼容入口使用先锋完整套装档案；实体入口会选择该实体真实启用的动力档案。曲线不读取
	 * 当前按键模式，因此切换常规、Ctrl 加速或俯冲时不会仅因模式位变化而让灯色或声音倒退。</p>
	 */
	public static double effectSpeedProgress(double blocksPerTick) {
		return effectSpeedProgress(ArmorVariant.DEFAULT.fullFlightProfile(), blocksPerTick);
	}

	/**
	 * 按实体当前实际启用的整甲/战靴动力档案归一化表现速度。重装和高速型不会再共用先锋的
	 * 三个分段上限，因此每种型号都能完整走完自身的巡航、加速和俯冲灯色/声场曲线。
	 */
	public static double effectSpeedProgress(LivingEntity entity, double blocksPerTick) {
		return effectSpeedProgress(resolveFlightProfile(entity), blocksPerTick);
	}

	private static double effectSpeedProgress(VariantFlightProfile profile, double blocksPerTick) {
		if (!Double.isFinite(blocksPerTick)) return 0.0D;
		double speed = Math.max(0.0D, blocksPerTick);
		final double idleDeadZone = 0.05D;
		final double cruiseProgress = 0.35D;
		final double boostProgress = 0.55D;
		double cruiseTarget = profile.cruise().targetSpeed();
		double boostTarget = profile.boost().targetSpeed();
		double diveTarget = profile.dive().targetSpeed();
		if (speed <= cruiseTarget) {
			return cruiseProgress * Mth.clamp(
					(speed - idleDeadZone) / Math.max(0.01D, cruiseTarget - idleDeadZone), 0.0D, 1.0D);
		}
		if (speed <= boostTarget) {
			double segment = (speed - cruiseTarget) / Math.max(0.01D, boostTarget - cruiseTarget);
			return Mth.lerp(Mth.clamp(segment, 0.0D, 1.0D), cruiseProgress, boostProgress);
		}
		// 俯冲拥有远大于 Ctrl 的速度范围，不能再把 88% 的灯色进度提前塞进低速档。
		double segment = (speed - boostTarget) / Math.max(0.01D, diveTarget - boostTarget);
		return Mth.lerp(Mth.clamp(segment, 0.0D, 1.0D), boostProgress, 1.0D);
	}

}
