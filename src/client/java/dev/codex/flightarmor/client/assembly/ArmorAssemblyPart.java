package dev.codex.flightarmor.client.assembly;

import dev.codex.flightarmor.assembly.ArmorAssemblyTiming;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * 分件着甲的 13 个逻辑部件。
 *
 * <p>同一逻辑部件内的所有 OBJ group 共用时间段、飞入轨迹和旋转支点，避免把装甲拆成
 * 上百块碎片。P0、P1、旋转和 pivot 均使用对应 {@code ModelPart} 的模型像素坐标。</p>
 */
public enum ArmorAssemblyPart {
	LEFT_BOOT(0, 7,
			vector(24.0F, -5.0F, 16.0F), vector(14.0F, -12.0F, 7.0F),
			vector(18.0F, -34.0F, 24.0F), vector(0.0F, 9.0F, 0.0F)),
	RIGHT_BOOT(2, 9,
			vector(-25.0F, -3.0F, 14.0F), vector(-15.0F, -10.0F, 6.0F),
			vector(-16.0F, 32.0F, -22.0F), vector(0.0F, 9.0F, 0.0F)),
	LEFT_LEG(5, 14,
			vector(21.0F, -11.0F, 12.0F), vector(13.0F, -18.0F, 5.0F),
			vector(14.0F, -28.0F, 18.0F), vector(0.0F, 4.5F, 0.0F)),
	RIGHT_LEG(7, 16,
			vector(-22.0F, -8.0F, 13.0F), vector(-14.0F, -16.0F, 5.0F),
			vector(-12.0F, 27.0F, -17.0F), vector(0.0F, 4.5F, 0.0F)),
	BACK(7, 18,
			vector(0.0F, -19.0F, 29.0F), vector(-8.0F, -25.0F, 17.0F),
			vector(-24.0F, 0.0F, -12.0F), vector(0.0F, 5.0F, 2.5F)),
	TORSO(9, 20,
			vector(0.0F, -10.0F, -28.0F), vector(9.0F, -19.0F, -16.0F),
			vector(20.0F, 0.0F, 10.0F), vector(0.0F, 5.0F, -1.0F)),
	LEFT_FOREARM(9, 20,
			vector(29.0F, -8.0F, -14.0F), vector(18.0F, -18.0F, -8.0F),
			vector(28.0F, -32.0F, 32.0F), vector(0.0F, 9.0F, 0.0F)),
	RIGHT_FOREARM(11, 23,
			vector(-30.0F, -5.0F, -12.0F), vector(-19.0F, -15.0F, -7.0F),
			vector(-25.0F, 30.0F, -30.0F), vector(0.0F, 9.0F, 0.0F)),
	LEFT_UPPER_ARM(11, 23,
			vector(26.0F, -17.0F, 11.0F), vector(15.0F, -23.0F, 4.0F),
			vector(16.0F, -30.0F, 25.0F), vector(0.0F, 3.0F, 0.0F)),
	RIGHT_UPPER_ARM(14, 25,
			vector(-27.0F, -13.0F, 13.0F), vector(-16.0F, -21.0F, 5.0F),
			vector(-14.0F, 29.0F, -24.0F), vector(0.0F, 3.0F, 0.0F)),
	HELMET_SHELL(14, 27,
			vector(0.0F, -27.0F, 25.0F), vector(-11.0F, -33.0F, 13.0F),
			vector(-30.0F, 8.0F, -16.0F), vector(0.0F, -4.0F, 0.0F)),
	HELMET_FACE(18, 32,
			vector(0.0F, -13.0F, -30.0F), vector(8.0F, -25.0F, -17.0F),
			vector(28.0F, -6.0F, 12.0F), vector(0.0F, -3.5F, -3.8F)),
	CORE(25, 36,
			vector(0.0F, 8.0F, -25.0F), vector(-7.0F, -8.0F, -15.0F),
			vector(20.0F, 0.0F, -18.0F), vector(0.0F, 3.65F, -3.10F));

	public static final int TOTAL_DURATION_TICKS = ArmorAssemblyTiming.TOTAL_DURATION_TICKS;

	private final int startTick;
	private final int endTick;
	private final Vector3f p0;
	private final Vector3f p1;
	private final Vector3f initialRotation;
	private final Vector3f pivot;

	ArmorAssemblyPart(int startTick, int endTick, Vector3f p0, Vector3f p1,
			Vector3f initialRotation, Vector3f pivot) {
		this.startTick = startTick;
		this.endTick = endTick;
		this.p0 = p0;
		this.p1 = p1;
		this.initialRotation = initialRotation;
		this.pivot = pivot;
	}

	public int startTick() {
		return startTick;
	}

	public int endTick() {
		return endTick;
	}

	public int durationTicks() {
		return endTick - startTick;
	}

	/** 返回副本，防止渲染阶段的向量运算修改枚举中的共享轨迹。 */
	public Vector3f p0() {
		return new Vector3f(p0);
	}

	public Vector3f p1() {
		return new Vector3f(p1);
	}

	/** 初始欧拉角，单位为度。 */
	public Vector3f initialRotation() {
		return new Vector3f(initialRotation);
	}

	/** 该逻辑部件所有 group 共用的旋转支点。 */
	public Vector3f pivot() {
		return new Vector3f(pivot);
	}

	/** 逻辑模型分组所属的真实装备槽；网络只需同步四位槽位掩码。 */
	public EquipmentSlot equipmentSlot() {
		return switch (this) {
			case LEFT_BOOT, RIGHT_BOOT -> EquipmentSlot.FEET;
			case LEFT_LEG, RIGHT_LEG -> EquipmentSlot.LEGS;
			case BACK, TORSO, LEFT_FOREARM, RIGHT_FOREARM, LEFT_UPPER_ARM, RIGHT_UPPER_ARM, CORE ->
					EquipmentSlot.CHEST;
			case HELMET_SHELL, HELMET_FACE -> EquipmentSlot.HEAD;
		};
	}

	/**
	 * 按 OBJ group 名称分类。匹配顺序是接口的一部分：战靴优先于腿甲、前臂优先于上臂、
	 * 面甲优先于头盔外壳。
	 */
	public static ArmorAssemblyPart fromGroupName(String rawName) {
		if (rawName == null || rawName.isBlank()) {
			return null;
		}
		String name = rawName.toLowerCase(Locale.ROOT);
		if (hasAny(name, "boot_left", "left_boot", "ankle_left", "left_ankle")) return LEFT_BOOT;
		if (hasAny(name, "boot_right", "right_boot", "ankle_right", "right_ankle")) return RIGHT_BOOT;
		if (hasAny(name, "leg_left", "left_leg")) return LEFT_LEG;
		if (hasAny(name, "leg_right", "right_leg")) return RIGHT_LEG;
		if (name.contains("arm_left_forearm") || name.contains("left_arm_forearm")) return LEFT_FOREARM;
		if (name.contains("arm_right_forearm") || name.contains("right_arm_forearm")) return RIGHT_FOREARM;
		if (hasAny(name, "arm_left_upper", "left_arm_upper")) return LEFT_UPPER_ARM;
		if (hasAny(name, "arm_right_upper", "right_arm_upper")) return RIGHT_UPPER_ARM;
		if (name.startsWith("core_") || name.contains("_core_")) return CORE;
		if (name.startsWith("visor_") || hasAny(name, "faceplate", "head_cheek")) return HELMET_FACE;
		if (hasAny(name, "head_", "helmet_", "temple", "brow_", "neck_seal")) return HELMET_SHELL;
		if (name.startsWith("vane_") || hasAny(name, "body_backplate", "body_spine", "flight_fin", "wing_")) return BACK;
		if (hasAny(name, "body_underlayer", "body_chest", "body_abdomen", "body_collar", "body_waist", "torso_")) return TORSO;
		return null;
	}

	private static boolean hasAny(String value, String... fragments) {
		for (String fragment : fragments) {
			if (value.contains(fragment)) return true;
		}
		return false;
	}

	private static Vector3f vector(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}
}
