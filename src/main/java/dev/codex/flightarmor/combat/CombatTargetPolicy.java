package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.cruise.ArmorCruiseController;
import dev.codex.flightarmor.cruise.ArmorEscortController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;

/** 集中处理 PVP、队伍、驯服生物和巡航载体过滤。 */
public final class CombatTargetPolicy {
	private CombatTargetPolicy() {
	}

	/** 物理射线的遮挡候选；友军和宠物仍会挡住能束，但不会受伤。 */
	public static boolean canOcclude(ServerPlayer attacker, Entity candidate) {
		if (candidate == attacker || !(candidate instanceof LivingEntity living)
				|| !living.isAlive() || living.isSpectator()
				|| !living.isPickable() || !living.canBeHitByProjectile()) {
			return false;
		}
		// 巡航战甲第一版既不受伤也不阻挡射线，避免成为永久无敌盾牌。
		if (living instanceof ArmorStand stand
				&& (stand.getTags().contains(ArmorCruiseController.CARRIER_TAG)
				|| stand.getTags().contains(ArmorEscortController.CARRIER_TAG))) {
			return false;
		}
		return true;
	}

	/** 命中最近物理实体后再判断伤害资格，禁止穿过不可伤友军攻击其身后目标。 */
	public static boolean canDamage(ServerPlayer attacker, Entity candidate) {
		if (!canOcclude(attacker, candidate) || !(candidate instanceof LivingEntity living)
				|| !living.isAttackable()) {
			return false;
		}
		if (living instanceof ServerPlayer targetPlayer) {
			return attacker.getServer().isPvpAllowed() && attacker.canHarmPlayer(targetPlayer);
		}
		if (attacker.isAlliedTo(living)) return false;
		if (living instanceof TamableAnimal tamable
				&& attacker.getUUID().equals(tamable.getOwnerUUID())) {
			return false;
		}
		return true;
	}
}
