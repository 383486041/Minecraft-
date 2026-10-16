package dev.codex.flightarmor.platform;

import com.mojang.datafixers.util.Pair;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台服务器事务入口。B 键处理器只需调用 {@link #tryRecallToPlayer(ServerPlayer)}，
 * 然后根据成功结果启动“平台到玩家”的高速飞入动画。
 */
public final class ChargingPlatformController {
	private static final AtomicLong NEXT_RECALL_SEQUENCE = new AtomicLong();

	private ChargingPlatformController() {
	}

	/** 主 Mod 初始化入口调用一次即可注册方块、物品和方块实体。 */
	public static void initialize() {
		ModPlatformContent.initialize();
	}

	public static boolean hasRecallableSuit(ServerPlayer player) {
		return !ArmorOwnershipSavedData.findPlatformBindings(player.getUUID(), player.getServer()).isEmpty();
	}

	/** 不加载区块的权威位置账本投影。 */
	public static Optional<OwnershipRuntimeView> ownershipRuntimeView(ServerPlayer player, UUID suitId) {
		if (player == null || suitId == null) return Optional.empty();
		return ArmorOwnershipSavedData.findBySuit(suitId, player.getServer())
				.map(binding -> new OwnershipRuntimeView(binding.ownerId(), binding.suitId(),
						OwnershipLocation.valueOf(binding.location().name()), binding.dimension(),
						binding.pos(), binding.revision()));
	}

	/**
	 * Lifecycle tombstone hook. This is intentionally separate from panel-level "forget" so a
	 * player can hide a missing core entry without erasing ownership of an item that may reappear.
	 */
	public static boolean removeConfirmedDestroyedSuit(
			MinecraftServer server, UUID ownerId, UUID suitId) {
		return ArmorOwnershipSavedData.removeConfirmedDestroyedSuit(server, ownerId, suitId);
	}

	/** 只查看已经加载的平台；面板刷新绝不会借此加载远端区块。 */
	public static PlatformRuntimeView platformRuntimeView(ServerPlayer player, UUID suitId,
			UUID platformId, ResourceKey<Level> dimension, BlockPos pos) {
		if (player == null || suitId == null || platformId == null || dimension == null || pos == null) {
			return new PlatformRuntimeView(PlatformHealth.UNBOUND, false, false, null, null);
		}
		ServerLevel level = player.getServer().getLevel(dimension);
		if (level == null) {
			return new PlatformRuntimeView(PlatformHealth.DIMENSION_UNAVAILABLE,
					false, false, null, null);
		}
		if (!level.hasChunkAt(pos)) {
			return new PlatformRuntimeView(PlatformHealth.UNLOADED, false, false, null, null);
		}
		if (!(level.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(platformId)) {
			return new PlatformRuntimeView(PlatformHealth.BROKEN, true, false, null, null);
		}
		UUID observedOwner = platform.getOwnerUuid().orElse(null);
		UUID observedSuit = platform.getSuitId().orElse(null);
		if (platform.isEmpty() && observedOwner == null && observedSuit == null) {
			return new PlatformRuntimeView(PlatformHealth.VALID_EMPTY_RESERVED,
					true, false, null, null);
		}
		if (!player.getUUID().equals(observedOwner) || !suitId.equals(observedSuit)) {
			return new PlatformRuntimeView(PlatformHealth.IDENTITY_CONFLICT,
					true, platform.isComplete(), observedOwner, observedSuit);
		}
		return new PlatformRuntimeView(platform.isComplete()
				? PlatformHealth.VALID_OCCUPIED : PlatformHealth.INCOMPLETE,
				true, platform.isComplete(), observedOwner, observedSuit);
	}

	/** 玩家装备槽中带战甲身份的逐部件只读证据。 */
	public static List<WornPartRuntimeView> wornRuntimeView(ServerPlayer player) {
		if (player == null) return List.of();
		List<WornPartRuntimeView> result = new ArrayList<>(4);
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			ArmorIdentity.read(stack).ifPresent(identity -> result.add(new WornPartRuntimeView(
					identity.ownerId(), identity.suitId(), slot, identity.slot(),
					stack.is(ModItems.flightArmorForSlot(slot)), ArmorVariantData.get(stack))));
		}
		return List.copyOf(result);
	}

	public static boolean canUseEquippedFlightArmor(ServerPlayer player) {
		boolean hasClaimedPart = false;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.is(ModItems.flightArmorForSlot(slot)) && ArmorIdentity.read(stack).isPresent()) {
				hasClaimedPart = true;
				break;
			}
		}
		if (!hasClaimedPart) {
			for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
				ItemStack stack = player.getItemBySlot(slot);
				if (stack.is(ModItems.flightArmorForSlot(slot))
						&& !ArmorIdentity.canBeUsedBy(stack, player.getUUID(), slot)) return false;
			}
			return true;
		}

		UUID equippedSuitId = null;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.is(ModItems.flightArmorForSlot(slot))) continue;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
			if (identity.isEmpty() || !identity.get().ownerId().equals(player.getUUID())
					|| identity.get().slot() != slot) return false;
			if (equippedSuitId != null && !equippedSuitId.equals(identity.get().suitId())) return false;
			equippedSuitId = identity.get().suitId();
		}
		if (equippedSuitId == null) return false;
		ArmorOwnershipSavedData.Binding binding = ArmorOwnershipSavedData
				.findBySuit(equippedSuitId, player.getServer()).orElse(null);
		if (binding == null || binding.location() != ArmorOwnershipSavedData.Location.CARRIED) return false;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.is(ModItems.flightArmorForSlot(slot))) continue;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
			if (identity.isEmpty() || !identity.get().ownerId().equals(player.getUUID())
					|| identity.get().slot() != slot
					|| !identity.get().suitId().equals(binding.suitId())) return false;
		}
		return true;
	}


	/** 给玩家当前穿着的完整套装认主。已有身份必须四件完全一致。 */
	public static OwnershipResult ensureOwnedEquippedSet(ServerPlayer player) {
		if (!ModItems.hasFullSet(player)) return OwnershipResult.denied("需要先穿齐四件战甲");
		UUID requestedSuit = null;
		ArmorVariant expectedVariant = null;
		boolean hasUnclaimedPart = false;
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack equipped = player.getItemBySlot(slot);
			if (!ArmorIdentity.canBeUsedBy(equipped, player.getUUID(), slot)) {
				return OwnershipResult.denied("身上的战甲包含属于他人或身份损坏的部件");
			}
			ArmorVariant variant = ArmorVariantData.get(equipped);
			if (expectedVariant != null && variant != expectedVariant) {
				return OwnershipResult.denied("四件战甲的型号必须一致");
			}
			expectedVariant = variant;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(equipped);
			if (identity.isEmpty()) {
				hasUnclaimedPart = true;
				continue;
			}
			if (requestedSuit != null && !requestedSuit.equals(identity.get().suitId())) {
				return OwnershipResult.denied("四件战甲来自不同的认主套装");
			}
			requestedSuit = identity.get().suitId();
		}

		ArmorOwnershipSavedData.Binding present = requestedSuit == null ? null
				: ArmorOwnershipSavedData.findBySuit(requestedSuit, player.getServer()).orElse(null);
		// Periodic core reconciliation reaches this method every second. A fully identified set
		// whose authoritative ledger is already CARRIED is an idempotent read, not a new claim:
		// do not increment the ownership revision, replace ItemStacks, or rebroadcast equipment.
		if (requestedSuit != null && !hasUnclaimedPart && present != null
				&& present.ownerId().equals(player.getUUID())
				&& present.location() == ArmorOwnershipSavedData.Location.CARRIED) {
			return OwnershipResult.owned(requestedSuit);
		}
		if (requestedSuit != null && hasUnclaimedPart) {
			return OwnershipResult.denied("已有认主套装不能混入无身份替换件，请找回原部件");
		}
		if (requestedSuit == null) {
			// 兼容旧世界：2.16 可能留下 CARRIED 索引，但创造模式换出的四件没有身份。
			// 仅当恰好有一个携带态索引时尝试恢复该 suitId；重复部件检查仍会阻止复制。
			List<ArmorOwnershipSavedData.Binding> carried = ArmorOwnershipSavedData
					.findAll(player.getUUID(), player.getServer()).stream()
					.filter(binding -> binding.location() == ArmorOwnershipSavedData.Location.CARRIED)
					.toList();
			if (carried.size() == 1) {
				requestedSuit = carried.getFirst().suitId();
				present = carried.getFirst();
			} else {
				requestedSuit = UUID.randomUUID();
			}
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack equipped = player.getItemBySlot(slot);
			if (hasOtherClaimedPart(player, equipped, requestedSuit, slot)) {
				return OwnershipResult.denied("背包或装备栏仍有这套战甲的另一个认主"
						+ partName(slot) + "，已拒绝生成重复身份");
			}
		}

		EnumMap<EquipmentSlot, ItemStack> claimedCopies = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack claimed = player.getItemBySlot(slot).copy();
			if (!ArmorIdentity.claim(claimed, player.getUUID(), requestedSuit, slot)) {
				return OwnershipResult.denied("写入战甲身份时校验失败");
			}
			claimedCopies.put(slot, claimed);
		}

		ArmorOwnershipSavedData.DepositDecision decision = ArmorOwnershipSavedData.prepareCarriedClaim(
				player.getServer(), player.getUUID(), Optional.of(requestedSuit));
		if (!decision.allowed()) return OwnershipResult.denied(decision.message());
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			player.setItemSlot(slot, claimedCopies.get(slot));
		}
		player.inventoryMenu.broadcastChanges();
		return OwnershipResult.owned(decision.suitId());
	}

	private static boolean hasOtherClaimedPart(ServerPlayer player, ItemStack equipped,
			UUID suitId, EquipmentSlot slot) {
		for (EquipmentSlot equippedSlot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack candidate = player.getItemBySlot(equippedSlot);
			if (candidate != equipped && matchesClaimedPart(candidate, player.getUUID(), suitId, slot)) {
				return true;
			}
		}
		for (ItemStack candidate : player.getInventory().items) {
			if (candidate != equipped && matchesClaimedPart(candidate, player.getUUID(), suitId, slot)) {
				return true;
			}
		}
		ItemStack offhand = player.getOffhandItem();
		return offhand != equipped && matchesClaimedPart(offhand, player.getUUID(), suitId, slot);
	}

	private static boolean matchesClaimedPart(ItemStack stack, UUID ownerId,
			UUID suitId, EquipmentSlot slot) {
		return ArmorIdentity.read(stack)
				.map(identity -> identity.matches(ownerId, suitId, slot))
				.orElse(false);
	}

	private static String partName(EquipmentSlot slot) {
		return switch (slot) {
			case FEET -> "战靴";
			case LEGS -> "护腿";
			case CHEST -> "胸甲";
			case HEAD -> "头盔";
			default -> "装甲";
		};
	}

	/**
	 * 从任意维度的权威平台原子召回四件装甲。
	 *
	 * <p>成功时四件物品已经装备到玩家身上，调用者只负责广播视觉状态。失败时平台和玩家均不变。
	 * 为避免覆盖其他装备，四个装甲槽必须全部为空。</p>
	 */
	public static RecallResult tryRecallToPlayer(ServerPlayer player) {
		ArmorOwnershipSavedData.Binding first = ArmorOwnershipSavedData
				.findPlatformBindings(player.getUUID(), player.getServer()).stream().findFirst().orElse(null);
		return first == null
				? RecallResult.failed(RecallFailure.NOT_ON_PLATFORM, "没有找到属于你的平台待机战甲")
				: tryRecallToPlayer(player, first.suitId());
	}

	/** 从核心菜单明确选择 suitId，禁止多平台时误召回另一套战甲。 */
	public static RecallResult tryRecallToPlayer(ServerPlayer player, UUID requestedSuitId) {
		if (!player.isAlive() || player.isSpectator()) {
			return RecallResult.failed(RecallFailure.PLAYER_UNAVAILABLE, "当前状态无法召回战甲");
		}
		ArmorOwnershipSavedData.Binding binding = ArmorOwnershipSavedData
				.findBySuit(requestedSuitId, player.getServer()).orElse(null);
		if (binding == null || binding.location() != ArmorOwnershipSavedData.Location.PLATFORM
				|| !binding.ownerId().equals(player.getUUID())
				|| binding.dimension() == null || binding.pos() == null) {
			return RecallResult.failed(RecallFailure.NOT_ON_PLATFORM, "没有找到属于你的平台待机战甲");
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			if (!player.getItemBySlot(slot).isEmpty()) {
				return RecallResult.failed(RecallFailure.ARMOR_SLOT_OCCUPIED,
						"召回前需要清空头盔、胸甲、护腿和战靴槽位");
			}
		}

		ServerLevel originLevel = player.getServer().getLevel(binding.dimension());
		if (originLevel == null) {
			return RecallResult.failed(RecallFailure.PLATFORM_UNAVAILABLE, "充能平台所在维度当前不可用");
		}
		// 仅在拥有者主动按 B 时同步加载一个已索引区块，不建立永久区块票据。
		originLevel.getChunkAt(binding.pos());
		if (!(originLevel.getBlockEntity(binding.pos()) instanceof ChargingPlatformBlockEntity platform)
				|| platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()
				|| platform.getSuitId().filter(binding.suitId()::equals).isEmpty()) {
			ArmorOwnershipSavedData.markLooseIfMatches(player.getServer(), player.getUUID(), binding.suitId(),
					binding.dimension(), binding.pos());
			return RecallResult.failed(RecallFailure.PLATFORM_INVALID,
					"平台索引与实际战甲不一致，已停止召回以防复制");
		}
		if (!platform.isComplete()) {
			return RecallResult.failed(RecallFailure.INCOMPLETE_SET, "充能平台上的四件战甲尚未放齐");
		}

		ChargingPlatformBlockEntity.ExtractedArmor extracted =
				platform.extractForRecall(player.getUUID(), binding.suitId());
		if (extracted == null) {
			return RecallResult.failed(RecallFailure.PLATFORM_INVALID, "平台战甲身份校验失败");
		}

		List<EquipmentSlot> committed = new ArrayList<>(4);
		try {
			for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
				EquipmentSlot slot = ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index);
				ItemStack stack = extracted.items().get(index);
				if (!player.getItemBySlot(slot).isEmpty()
						|| !ArmorIdentity.belongsTo(stack, player.getUUID(), binding.suitId(), slot)) {
					throw new IllegalStateException("召回提交前槽位或身份发生变化");
				}
				player.setItemSlot(slot, stack);
				committed.add(slot);
			}
		} catch (RuntimeException exception) {
			for (EquipmentSlot slot : committed) player.setItemSlot(slot, ItemStack.EMPTY);
			platform.restoreAfterFailedRecall(extracted);
			FlightArmorMod.LOGGER.error("平台召回事务异常，四件战甲已回滚 owner={}", player.getUUID(), exception);
			return RecallResult.failed(RecallFailure.TRANSACTION_FAILED, "战甲召回失败，物品已安全返回平台");
		}

		if (!ArmorOwnershipSavedData.markCarriedIfMatches(
				player.getServer(), player.getUUID(), binding.suitId(), binding.dimension(), binding.pos())) {
			for (EquipmentSlot slot : committed) player.setItemSlot(slot, ItemStack.EMPTY);
			platform.restoreAfterFailedRecall(extracted);
			syncEquipment(player);
			return RecallResult.failed(RecallFailure.TRANSACTION_FAILED,
					"\u6218\u7532\u6240\u6709\u6743\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u53ec\u56de\u5df2\u56de\u6eda\u81f3\u5e73\u53f0");
		}
		syncEquipment(player);
		long sequenceId = NEXT_RECALL_SEQUENCE.incrementAndGet();
		// 与整甲平台预览的固定根高度一致，召回首帧不会先向下跳再垂直升空。
		Vec3 origin = Vec3.atBottomCenterOf(binding.pos()).add(0.0D, 0.625D, 0.0D);
		return RecallResult.success(sequenceId, binding.suitId(), binding.dimension(), origin,
				extracted.displayMode(), player.serverLevel().getGameTime());
	}

	public static boolean rollbackRecallToPlatform(ServerPlayer player, RecallResult result) {
		if (result == null || !result.success() || result.suitId() == null
				|| result.originDimension() == null || result.origin() == null
				|| result.displayMode() == null) {
			return false;
		}
		ArmorOwnershipSavedData.Binding binding = ArmorOwnershipSavedData
				.findBySuit(result.suitId(), player.getServer()).orElse(null);
		if (binding == null || binding.location() != ArmorOwnershipSavedData.Location.CARRIED
				|| !binding.ownerId().equals(player.getUUID())
				|| !binding.suitId().equals(result.suitId())) {
			return false;
		}
		ServerLevel originLevel = player.getServer().getLevel(result.originDimension());
		if (originLevel == null) return false;
		BlockPos originPos = BlockPos.containing(result.origin());
		originLevel.getChunkAt(originPos);
		if (!(originLevel.getBlockEntity(originPos) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.isEmpty() || platform.getOwnerUuid().isPresent()
				|| platform.getSuitId().isPresent()) {
			return false;
		}

		List<ItemStack> items = new ArrayList<>(ChargingPlatformBlockEntity.ARMOR_SLOTS.size());
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!ArmorIdentity.belongsTo(stack, player.getUUID(), result.suitId(), slot)) {
				return false;
			}
			items.add(stack);
		}
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			player.setItemSlot(slot, ItemStack.EMPTY);
		}

		ChargingPlatformBlockEntity.ExtractedArmor extracted =
				new ChargingPlatformBlockEntity.ExtractedArmor(
						player.getUUID(), result.suitId(), result.displayMode(), List.copyOf(items));
		try {
			platform.restoreAfterFailedRecall(extracted);
		} catch (RuntimeException exception) {
			for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
				player.setItemSlot(ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index), items.get(index));
			}
			syncEquipment(player);
			FlightArmorMod.LOGGER.error("Platform recall rollback failed before restore owner={}",
					player.getUUID(), exception);
			return false;
		}

		if (!ArmorOwnershipSavedData.markPlatformIfCarried(player.getServer(), player.getUUID(),
				result.suitId(), result.originDimension(), originPos)) {
			ChargingPlatformBlockEntity.ExtractedArmor returned =
					platform.extractForRecall(player.getUUID(), result.suitId());
			if (returned != null) {
				for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
					player.setItemSlot(ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index),
							returned.items().get(index));
				}
			}
			syncEquipment(player);
			return false;
		}
		syncEquipment(player);
		return true;
	}

	/** Position-ledger handoff used when a worn suit becomes a quick-switch escort carrier. */
	public static boolean markCarriedSuitCruising(ServerPlayer player, UUID suitId) {
		return player != null && suitId != null && ArmorOwnershipSavedData.markCruiseIfCarried(
				player.getServer(), player.getUUID(), suitId);
	}

	public static boolean rollbackCarriedSuitCruising(ServerPlayer player, UUID suitId) {
		return player != null && suitId != null && ArmorOwnershipSavedData.markCarriedIfCruise(
				player.getServer(), player.getUUID(), suitId);
	}

	/** Repairs old independent-carrier saves and admits only CARRIED -> CRUISE. */
	public static boolean ensureSuitCruising(MinecraftServer server, UUID ownerId, UUID suitId) {
		if (server == null || ownerId == null || suitId == null) return false;
		ArmorOwnershipSavedData.Binding binding = ArmorOwnershipSavedData
				.findBySuit(suitId, server).orElse(null);
		if (binding == null || !ownerId.equals(binding.ownerId())) return false;
		if (binding.location() == ArmorOwnershipSavedData.Location.CRUISE) return true;
		return binding.location() == ArmorOwnershipSavedData.Location.CARRIED
				&& ArmorOwnershipSavedData.markCruiseIfCarried(server, ownerId, suitId);
	}

	/** Commits an independent carrier back to player possession. */
	public static boolean markCruisingSuitCarried(MinecraftServer server, UUID ownerId, UUID suitId) {
		return server != null && ownerId != null && suitId != null
				&& ArmorOwnershipSavedData.markCarriedIfCruise(server, ownerId, suitId);
	}

	/** Rollback used when a physical carrier-to-player transfer could not commit. */
	public static boolean rollbackSuitToCruising(MinecraftServer server, UUID ownerId, UUID suitId) {
		return server != null && ownerId != null && suitId != null
				&& ArmorOwnershipSavedData.markCruiseIfCarried(server, ownerId, suitId);
	}

	/** 将副战甲的四个真实物品从指定平台移交给 EscortController。 */
	public static EscortTransfer extractForEscort(ServerPlayer player, UUID requestedSuitId, UUID expectedPlatformId) {
		ArmorOwnershipSavedData.Binding binding = ArmorOwnershipSavedData
				.findBySuit(requestedSuitId, player.getServer()).orElse(null);
		if (binding == null || !binding.ownerId().equals(player.getUUID())
				|| binding.location() != ArmorOwnershipSavedData.Location.PLATFORM
				|| binding.dimension() == null || binding.pos() == null) {
			return EscortTransfer.failed("副战甲不在可召回的平台上");
		}
		ServerLevel originLevel = player.getServer().getLevel(binding.dimension());
		if (originLevel == null) return EscortTransfer.failed("副战甲所在维度不可用");
		originLevel.getChunkAt(binding.pos());
		if (!(originLevel.getBlockEntity(binding.pos()) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(expectedPlatformId)
				|| platform.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()
				|| platform.getSuitId().filter(requestedSuitId::equals).isEmpty()
				|| !platform.isComplete()) {
			return EscortTransfer.failed("平台身份、位置或四件战甲状态已变化");
		}
		ChargingPlatformBlockEntity.ExtractedArmor extracted =
				platform.extractForRecall(player.getUUID(), requestedSuitId);
		if (extracted == null) return EscortTransfer.failed("副战甲平台提取失败");
		if (!ArmorOwnershipSavedData.markCruiseIfMatches(player.getServer(), player.getUUID(),
				requestedSuitId, binding.dimension(), binding.pos())) {
			platform.restoreAfterFailedRecall(extracted);
			return EscortTransfer.failed("副战甲位置账本冲突，物品已返回平台");
		}
		return EscortTransfer.success(player.getUUID(), requestedSuitId, expectedPlatformId,
				binding.dimension(), binding.pos(), extracted.displayMode(), extracted.items());
	}

	public static boolean rollbackEscortTransfer(ServerPlayer player, EscortTransfer transfer) {
		if (transfer == null || !transfer.success() || transfer.items() == null) return false;
		ServerLevel originLevel = player.getServer().getLevel(transfer.originDimension());
		if (originLevel == null) return false;
		originLevel.getChunkAt(transfer.originPos());
		if (!(originLevel.getBlockEntity(transfer.originPos()) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(transfer.platformId()) || !platform.isEmpty()) return false;
		platform.restoreAfterFailedRecall(new ChargingPlatformBlockEntity.ExtractedArmor(
				transfer.ownerId(), transfer.suitId(), transfer.displayMode(), transfer.items()));
		return ArmorOwnershipSavedData.markPlatformIfCruise(player.getServer(), transfer.ownerId(),
				transfer.suitId(), transfer.originDimension(), transfer.originPos());
	}

	/**
	 * 护卫载体抵达原绑定平台后的唯一入库事务。平台只接收空置且 platformId 一致的目标，
	 * 物品身份与位置账本必须同时提交成功；任一步失败都会把平台恢复为空。
	 */
	public static EscortReturnResult returnEscortToPlatform(ServerPlayer owner, UUID suitId,
			UUID expectedPlatformId, ResourceKey<Level> dimension, BlockPos pos,
			ChargingPlatformDisplayMode displayMode, List<ItemStack> items) {
		if (owner == null || suitId == null || expectedPlatformId == null || dimension == null
				|| pos == null || displayMode == null || items == null
				|| items.size() != ChargingPlatformBlockEntity.ARMOR_SLOTS.size()) {
			return EscortReturnResult.failed("护卫返航数据不完整");
		}
		ServerLevel level = owner.getServer().getLevel(dimension);
		if (level == null) return EscortReturnResult.failed("绑定平台所在维度不可用");
		level.getChunkAt(pos);
		if (!(level.getBlockEntity(pos) instanceof ChargingPlatformBlockEntity platform)
				|| !platform.getPlatformId().equals(expectedPlatformId)) {
			return EscortReturnResult.failed("原绑定平台已不存在或实例已更换");
		}
		if (!platform.isEmpty() || platform.getOwnerUuid().isPresent()
				|| platform.getSuitId().isPresent()) {
			return EscortReturnResult.failed("原绑定平台已被占用，护卫保持巡航");
		}
		for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
			EquipmentSlot slot = ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index);
			if (!ArmorIdentity.belongsTo(items.get(index), owner.getUUID(), suitId, slot)) {
				return EscortReturnResult.failed("护卫战甲部件身份不一致，已停止入库");
			}
		}

		ChargingPlatformBlockEntity.ExtractedArmor returned =
				new ChargingPlatformBlockEntity.ExtractedArmor(
						owner.getUUID(), suitId, displayMode, List.copyOf(items));
		try {
			platform.restoreAfterFailedRecall(returned);
			if (!ArmorOwnershipSavedData.markPlatformIfCruise(owner.getServer(), owner.getUUID(),
					suitId, dimension, pos)) {
				platform.extractForRecall(owner.getUUID(), suitId);
				return EscortReturnResult.failed("护卫位置账本冲突，平台入库已回滚");
			}
			return EscortReturnResult.returned();
		} catch (RuntimeException exception) {
			if (platform.getSuitId().filter(suitId::equals).isPresent()) {
				platform.extractForRecall(owner.getUUID(), suitId);
			}
			FlightArmorMod.LOGGER.error("Escort platform return rolled back owner={} suit={}",
					owner.getUUID(), suitId, exception);
			return EscortReturnResult.failed("护卫入库失败，战甲仍由巡航载体保管");
		}
	}

	/** 护卫载体贴近主人后，将四件真实物品原子交给玩家装备槽。 */
	public static EscortReturnResult equipEscortToOwner(ServerPlayer owner, UUID suitId,
			List<ItemStack> items) {
		if (owner == null || suitId == null || items == null
				|| items.size() != ChargingPlatformBlockEntity.ARMOR_SLOTS.size()) {
			return EscortReturnResult.failed("护卫着装数据不完整");
		}
		for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
			EquipmentSlot slot = ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index);
			if (!owner.getItemBySlot(slot).isEmpty()) {
				return EscortReturnResult.failed("请先清空四个装甲槽再召回主甲");
			}
			if (!ArmorIdentity.belongsTo(items.get(index), owner.getUUID(), suitId, slot)) {
				return EscortReturnResult.failed("护卫战甲部件身份不一致，无法着装");
			}
		}

		List<EquipmentSlot> committed = new ArrayList<>(4);
		try {
			for (int index = 0; index < ChargingPlatformBlockEntity.ARMOR_SLOTS.size(); index++) {
				EquipmentSlot slot = ChargingPlatformBlockEntity.ARMOR_SLOTS.get(index);
				owner.setItemSlot(slot, items.get(index));
				committed.add(slot);
			}
			if (!ArmorOwnershipSavedData.markCarriedIfCruise(
					owner.getServer(), owner.getUUID(), suitId)) {
				throw new IllegalStateException("escort ownership ledger is not in cruise state");
			}
			syncEquipment(owner);
			return EscortReturnResult.returned();
		} catch (RuntimeException exception) {
			for (EquipmentSlot slot : committed) owner.setItemSlot(slot, ItemStack.EMPTY);
			syncEquipment(owner);
			FlightArmorMod.LOGGER.error("Escort wear transfer rolled back owner={} suit={}",
					owner.getUUID(), suitId, exception);
			return EscortReturnResult.failed("护卫着装事务失败，战甲仍由载体保管");
		}
	}

	private static void syncEquipment(ServerPlayer player) {
		List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(4);
		for (EquipmentSlot slot : ChargingPlatformBlockEntity.ARMOR_SLOTS) {
			equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
		}
		player.serverLevel().getChunkSource().broadcastAndSend(
				player, new ClientboundSetEquipmentPacket(player.getId(), equipment));
		player.inventoryMenu.broadcastChanges();
	}

	public enum OwnershipLocation {
		CARRIED,
		PLATFORM,
		CRUISE,
		LOOSE
	}

	public enum PlatformHealth {
		VALID_OCCUPIED,
		VALID_EMPTY_RESERVED,
		UNLOADED,
		UNBOUND,
		INCOMPLETE,
		BROKEN,
		DIMENSION_UNAVAILABLE,
		IDENTITY_CONFLICT
	}

	public record OwnershipRuntimeView(UUID ownerId, UUID suitId, OwnershipLocation location,
			ResourceKey<Level> dimension, BlockPos pos, long revision) {
		public OwnershipRuntimeView {
			pos = pos == null ? null : pos.immutable();
		}
	}

	public record PlatformRuntimeView(PlatformHealth health, boolean chunkLoaded, boolean complete,
			UUID observedOwnerId, UUID observedSuitId) {
	}

	public record WornPartRuntimeView(UUID ownerId, UUID suitId, EquipmentSlot equippedSlot,
			EquipmentSlot identitySlot, boolean expectedItem, ArmorVariant variant) {
	}

	public enum RecallFailure {
		NONE,
		NOT_ON_PLATFORM,
		PLAYER_UNAVAILABLE,
		ARMOR_SLOT_OCCUPIED,
		PLATFORM_UNAVAILABLE,
		PLATFORM_INVALID,
		INCOMPLETE_SET,
		TRANSACTION_FAILED
	}

	public record RecallResult(boolean success, RecallFailure failure, String message, long sequenceId,
			UUID suitId, ResourceKey<Level> originDimension, Vec3 origin,
			ChargingPlatformDisplayMode displayMode, long startGameTime) {
		static RecallResult failed(RecallFailure failure, String message) {
			return new RecallResult(false, failure, message, 0L, null, null, null, null, 0L);
		}

		static RecallResult success(long sequenceId, UUID suitId, ResourceKey<Level> originDimension,
				Vec3 origin, ChargingPlatformDisplayMode displayMode, long startGameTime) {
			return new RecallResult(true, RecallFailure.NONE, "", sequenceId, suitId, originDimension,
					origin, displayMode, startGameTime);
		}
	}

	public record OwnershipResult(boolean success, UUID suitId, String message) {
		static OwnershipResult owned(UUID suitId) {
			return new OwnershipResult(true, suitId, "");
		}

		static OwnershipResult denied(String message) {
			return new OwnershipResult(false, null, message);
		}
	}

	public record EscortTransfer(boolean success, String message, UUID ownerId, UUID suitId,
			UUID platformId, ResourceKey<Level> originDimension, BlockPos originPos,
			ChargingPlatformDisplayMode displayMode, List<ItemStack> items) {
		static EscortTransfer failed(String message) {
			return new EscortTransfer(false, message, null, null, null, null, null, null, List.of());
		}
		static EscortTransfer success(UUID ownerId, UUID suitId, UUID platformId,
				ResourceKey<Level> dimension, BlockPos pos, ChargingPlatformDisplayMode displayMode,
				List<ItemStack> items) {
			return new EscortTransfer(true, "", ownerId, suitId, platformId, dimension,
					pos.immutable(), displayMode, List.copyOf(items));
		}
	}

	public record EscortReturnResult(boolean success, String message) {
		static EscortReturnResult returned() { return new EscortReturnResult(true, ""); }
		static EscortReturnResult failed(String message) {
			return new EscortReturnResult(false, message);
		}
	}
}
