package dev.codex.flightarmor.platform;

import com.mojang.datafixers.util.Pair;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.core.ArmorCoreController;
import dev.codex.flightarmor.item.ModItems;
import dev.codex.flightarmor.integrity.ArmorIntegrityController;
import dev.codex.flightarmor.integrity.ArmorIntegrityRules;
import dev.codex.flightarmor.variant.ArmorVariant;
import dev.codex.flightarmor.variant.ArmorVariantData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Container;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 四槽充能平台。GUI、方块展示和召回事务始终读取同一组真实 ItemStack。 */
public final class ChargingPlatformBlockEntity extends BlockEntity implements Container, MenuProvider {
	/** 序号同时用于 NBT 和渲染：战靴、护腿、胸甲、头盔。 */
	public static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.FEET,
			EquipmentSlot.LEGS,
			EquipmentSlot.CHEST,
			EquipmentSlot.HEAD);

	private final NonNullList<ItemStack> armorItems = NonNullList.withSize(ARMOR_SLOTS.size(), ItemStack.EMPTY);
	private UUID platformId = UUID.randomUUID();
	private UUID draftOwnerUuid;
	private UUID ownerUuid;
	private UUID suitId;
	private ChargingPlatformDisplayMode displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
	private boolean integrityRepairing;
	private int displayedIntegrity = ArmorIntegrityRules.MAX_INTEGRITY;

	public ChargingPlatformBlockEntity(BlockPos pos, BlockState state) {
		super(ModPlatformContent.CHARGING_PLATFORM_BLOCK_ENTITY, pos, state);
	}

	public ChargingPlatformDisplayMode getDisplayMode() {
		return displayMode;
	}

	/** 渲染端只能得到副本，不能意外修改服务器/客户端方块实体的真实物品。 */
	public List<ItemStack> getArmorItems() {
		return armorItems.stream().map(ItemStack::copy).toList();
	}

	public Optional<UUID> getOwnerUuid() {
		return Optional.ofNullable(ownerUuid);
	}

	public Optional<UUID> getSuitId() {
		return Optional.ofNullable(suitId);
	}

	public boolean isIntegrityRepairing() {
		return integrityRepairing;
	}

	public int getDisplayedIntegrity() {
		return displayedIntegrity;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state,
			ChargingPlatformBlockEntity platform) {
		if (!(level instanceof ServerLevel)) return;
		ArmorIntegrityController.PlatformRepair repair =
				ArmorIntegrityController.repairPlatform(platform);
		platform.integrityRepairing = repair.repairing();
		platform.displayedIntegrity = repair.integrity();
	}

	public boolean isComplete() {
		if (ownerUuid == null || suitId == null) return false;
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			EquipmentSlot slot = ARMOR_SLOTS.get(index);
			if (!ArmorIdentity.belongsTo(armorItems.get(index), ownerUuid, suitId, slot)) return false;
		}
		return true;
	}

	public UUID getPlatformId() {
		return platformId;
	}

	@Override
	public Component getDisplayName() {
		return Component.literal("战甲充能平台");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new ChargingPlatformMenu(containerId, inventory, this);
	}

	@Override
	public int getContainerSize() {
		return armorItems.size();
	}

	@Override
	public ItemStack getItem(int slot) {
		return slot >= 0 && slot < armorItems.size() ? armorItems.get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack removed = ContainerHelper.removeItem(armorItems, slot, amount);
		if (!removed.isEmpty()) syncChanged();
		return removed;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (slot < 0 || slot >= armorItems.size()) return ItemStack.EMPTY;
		ItemStack removed = armorItems.get(slot);
		armorItems.set(slot, ItemStack.EMPTY);
		return removed;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot < 0 || slot >= armorItems.size()) return;
		ItemStack stored = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
		armorItems.set(slot, stored);
		syncChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		for (int index = 0; index < armorItems.size(); index++) armorItems.set(index, ItemStack.EMPTY);
		syncChanged();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot >= 0 && slot < ARMOR_SLOTS.size()
				&& ModItems.isExactFlightArmor(stack, ARMOR_SLOTS.get(slot));
	}

	boolean canInsertFromMenu(ServerPlayer player, int index, ItemStack stack) {
		if (!canPlaceItem(index, stack) || stack.getCount() < 1) return false;
		EquipmentSlot slot = ARMOR_SLOTS.get(index);
		if (!ArmorIdentity.canBeUsedBy(stack, player.getUUID(), slot)) return false;
		ArmorVariant incoming = ArmorVariantData.get(stack);
		for (ItemStack stored : armorItems) {
			if (!stored.isEmpty() && ArmorVariantData.get(stored) != incoming) return false;
		}
		Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
		if (ownerUuid == null && draftOwnerUuid != null && !draftOwnerUuid.equals(player.getUUID())) return false;
		if (ownerUuid != null || suitId != null) {
			return ownerUuid != null && suitId != null && ownerUuid.equals(player.getUUID())
					&& identity.filter(value -> value.matches(ownerUuid, suitId, slot)).isPresent();
		}
		UUID expectedSuit = null;
		boolean hasClaimedPart = false;
		boolean hasUnclaimedPart = false;
		for (ItemStack stored : armorItems) {
			if (stored.isEmpty()) continue;
			Optional<ArmorIdentity.Identity> storedIdentity = ArmorIdentity.read(stored);
			if (storedIdentity.isEmpty()) {
				hasUnclaimedPart = true;
				continue;
			}
			hasClaimedPart = true;
			if (!storedIdentity.get().ownerId().equals(player.getUUID())) return false;
			if (expectedSuit != null && !expectedSuit.equals(storedIdentity.get().suitId())) return false;
			expectedSuit = storedIdentity.get().suitId();
		}
		if (hasClaimedPart && hasUnclaimedPart) return false;
		if (hasClaimedPart) {
			UUID requiredSuit = expectedSuit;
			return identity.filter(value -> value.ownerId().equals(player.getUUID())
					&& value.suitId().equals(requiredSuit) && value.slot() == slot).isPresent();
		}
		if (hasUnclaimedPart) return identity.isEmpty();
		return true;
	}

	boolean canTakeFromMenu(Player player) {
		if (ownerUuid != null) return ownerUuid.equals(player.getUUID());
		return draftOwnerUuid == null || draftOwnerUuid.equals(player.getUUID());
	}

	/**
	 * 容器每次变更后的服务端收口。前三件可以作为未认主半成品存放；放齐第四件时才一次性
	 * 给四件写入同一 suitId，并提交多套所有权索引，避免逐件生成身份造成复制窗口。
	 */
	void reconcileMenuContents(ServerPlayer player) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		if (isEmpty()) {
			if (ownerUuid != null && suitId != null) {
				ArmorOwnershipSavedData.markLooseIfMatches(serverLevel.getServer(), ownerUuid, suitId,
						serverLevel.dimension(), worldPosition);
			}
			ownerUuid = null;
			suitId = null;
			draftOwnerUuid = null;
			displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
			syncChanged();
			return;
		}
		if (isComplete()) {
			if (draftOwnerUuid != null) {
				draftOwnerUuid = null;
				syncChanged();
			}
			if (ownerUuid.equals(player.getUUID())) ArmorCoreController.bindPlatformIfCoreEquipped(player, this);
			return;
		}
		if (draftOwnerUuid == null) draftOwnerUuid = player.getUUID();
		if (!draftOwnerUuid.equals(player.getUUID()) || !rawSlotsComplete()) return;

		UUID candidateSuit = null;
		ArmorVariant candidateVariant = null;
		int claimedPartCount = 0;
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			ItemStack stack = armorItems.get(index);
			EquipmentSlot slot = ARMOR_SLOTS.get(index);
			if (!ModItems.isExactFlightArmor(stack, slot)
					|| !ArmorIdentity.canBeUsedBy(stack, player.getUUID(), slot)) return;
			ArmorVariant variant = ArmorVariantData.get(stack);
			if (candidateVariant != null && candidateVariant != variant) return;
			candidateVariant = variant;
			Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
			if (identity.isPresent()) {
				claimedPartCount++;
				if (candidateSuit != null && !candidateSuit.equals(identity.get().suitId())) return;
				candidateSuit = identity.get().suitId();
			}
		}
		if (claimedPartCount != 0 && claimedPartCount != ARMOR_SLOTS.size()) {
			player.sendOverlayMessage(Component.literal(
					"平台不能混放认主与无身份部件：请放入同一套四件，或四件全部使用新装甲"));
			return;
		}
		if (claimedPartCount == ARMOR_SLOTS.size()) {
			for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
				if (hasOtherClaimedPart(player, armorItems.get(index), candidateSuit, ARMOR_SLOTS.get(index))) {
					player.sendOverlayMessage(Component.literal(
							"背包或装备栏仍有同一套战甲的重复" + partName(ARMOR_SLOTS.get(index))
									+ "，平台已拒绝接管"));
					return;
				}
			}
		}
		ArmorOwnershipSavedData.DepositPlan plan = ArmorOwnershipSavedData.planCompleteDeposit(
				serverLevel.getServer(), player.getUUID(), Optional.ofNullable(candidateSuit),
				serverLevel.dimension(), worldPosition);
		if (!plan.allowed()) {
			player.sendOverlayMessage(Component.literal(plan.message()));
			return;
		}
		NonNullList<ItemStack> claimed = NonNullList.withSize(ARMOR_SLOTS.size(), ItemStack.EMPTY);
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			ItemStack copy = armorItems.get(index).copyWithCount(1);
			if (!ArmorIdentity.claim(copy, player.getUUID(), plan.suitId(), ARMOR_SLOTS.get(index))) return;
			claimed.set(index, copy);
		}
		if (!ArmorOwnershipSavedData.commitCompleteDeposit(serverLevel.getServer(), plan)) {
			player.sendOverlayMessage(Component.literal("平台身份刚刚发生变化，请重试"));
			return;
		}
		ownerUuid = player.getUUID();
		suitId = plan.suitId();
		draftOwnerUuid = null;
		for (int index = 0; index < claimed.size(); index++) armorItems.set(index, claimed.get(index));
		displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
		syncChanged();
		ArmorCoreController.bindPlatformIfCoreEquipped(player, this);
		player.sendOverlayMessage(Component.literal("完整战甲已认主并接入充能平台"));
	}

	private boolean rawSlotsComplete() {
		for (ItemStack stack : armorItems) if (stack.isEmpty()) return false;
		return true;
	}

	CompleteInsertResult insertCompleteSet(ServerPlayer player, ItemStack preferredHeld) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return CompleteInsertResult.denied("\u5145\u80fd\u5e73\u53f0\u5c1a\u672a\u5c31\u7eea");
		}
		if (!isEmpty() || ownerUuid != null || suitId != null) {
			return CompleteInsertResult.denied("\u53ea\u80fd\u5411\u5b8c\u5168\u7a7a\u7f6e\u7684\u5145\u80fd\u5e73\u53f0\u6574\u5957\u5165\u5e93");
		}

		List<ArmorCandidate> candidates = collectCandidates(player);
		ArmorCandidate preferred = findPreferredCandidate(player, candidates, preferredHeld);
		if (!preferredHeld.isEmpty() && ArmorIdentity.armorSlot(preferredHeld).isPresent()) {
			if (preferred == null) {
				return CompleteInsertResult.denied("\u624b\u6301\u6218\u7532\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
			}
			if (!preferred.usable()) {
				return CompleteInsertResult.denied("\u624b\u6301\u6218\u7532\u5df2\u8ba4\u4e3b\u7ed9\u5176\u4ed6\u73a9\u5bb6\uff0c\u6216\u8eab\u4efd\u6570\u636e\u5df2\u635f\u574f");
			}
		}

		SelectionSearch search = findCompleteSelection(serverLevel, player, candidates, preferred);
		if (search.selection() == null) {
			return search.fallbackToSingle()
					? CompleteInsertResult.missing(search.message())
					: CompleteInsertResult.denied(search.message());
		}

		List<PlannedPart> planned = new ArrayList<>(ARMOR_SLOTS.size());
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ArmorCandidate candidate = search.selection().parts().get(slot);
			ItemStack deposited = candidate.snapshot().copyWithCount(1);
			if (!ArmorIdentity.claim(deposited, player.getUUID(),
					search.selection().ownershipPlan().suitId(), slot)) {
				return CompleteInsertResult.denied("\u6574\u5957\u6218\u7532\u8eab\u4efd\u6821\u9a8c\u5931\u8d25");
			}
			planned.add(new PlannedPart(candidate.source(), slot, candidate.snapshot(), deposited));
		}

		for (PlannedPart part : planned) {
			if (!part.source().matches(player, part.original())) {
				return CompleteInsertResult.denied("\u6218\u7532\u4f4d\u7f6e\u6216\u6570\u636e\u521a\u521a\u53d1\u751f\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
			}
		}
		if (!isEmpty() || ownerUuid != null || suitId != null) {
			return CompleteInsertResult.denied("\u5145\u80fd\u5e73\u53f0\u521a\u521a\u88ab\u5360\u7528\uff0c\u672a\u79fb\u52a8\u4efb\u4f55\u6218\u7532");
		}

		ChargingPlatformDisplayMode originalMode = displayMode;
		boolean ownershipCommitted = false;
		try {
			for (PlannedPart part : planned) part.source().removeOne(player);
			ownerUuid = player.getUUID();
			suitId = search.selection().ownershipPlan().suitId();
			displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
			for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
				armorItems.set(index, planned.get(index).deposited());
			}

			if (!ArmorOwnershipSavedData.commitCompleteDeposit(
					serverLevel.getServer(), search.selection().ownershipPlan())) {
				throw new IllegalStateException("stale armor ownership deposit plan");
			}
			ownershipCommitted = true;
			player.getInventory().setChanged();
			syncPlayerAfterDeposit(player);
			syncChanged();
			return CompleteInsertResult.inserted();
		} catch (RuntimeException exception) {
			for (int index = 0; index < armorItems.size(); index++) armorItems.set(index, ItemStack.EMPTY);
			ownerUuid = null;
			suitId = null;
			displayMode = originalMode;
			for (PlannedPart part : planned) part.source().restore(player, part.original());
			if (ownershipCommitted) {
				ArmorOwnershipSavedData.rollbackCompleteDeposit(
						serverLevel.getServer(), search.selection().ownershipPlan());
			}
			player.getInventory().setChanged();
			syncPlayerAfterDeposit(player);
			syncChanged();
			FlightArmorMod.LOGGER.error("Complete armor platform deposit rolled back owner={}",
					player.getUUID(), exception);
			return CompleteInsertResult.denied("\u6574\u5957\u5165\u5e93\u5931\u8d25\uff0c\u73a9\u5bb6\u4e0e\u5e73\u53f0\u7269\u54c1\u5df2\u539f\u6837\u56de\u6eda");
		}
	}

	private SelectionSearch findCompleteSelection(ServerLevel serverLevel, ServerPlayer player,
			List<ArmorCandidate> candidates, ArmorCandidate preferred) {
		Set<SelectionKey> keys = new LinkedHashSet<>();
		if (preferred != null && preferred.identity() != null) {
			keys.add(new SelectionKey(preferred.variant(), preferred.identity().suitId()));
		} else {
			for (ArmorCandidate candidate : candidates) {
				if (candidate.usable() && candidate.identity() != null
						&& (preferred == null || candidate.variant() == preferred.variant())) {
					keys.add(new SelectionKey(candidate.variant(), candidate.identity().suitId()));
				}
			}
			for (ArmorCandidate candidate : candidates) {
				if (candidate.usable() && candidate.identity() == null
						&& (preferred == null || candidate.variant() == preferred.variant())) {
					keys.add(new SelectionKey(candidate.variant(), null));
				}
			}
		}

		String lastOwnershipDenial = "";
		for (SelectionKey key : keys) {
			if (preferred != null && !compatible(preferred, key)) continue;
			if (key.suitId() != null && hasDuplicateClaimedSlot(candidates, key)) {
				lastOwnershipDenial = "检测到同一认主套装的重复槽位部件，已拒绝入库以防继续复制身份";
				continue;
			}
			Map<EquipmentSlot, ArmorCandidate> parts = new EnumMap<>(EquipmentSlot.class);
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ArmorCandidate claimedOriginal = key.suitId() == null ? null
						: selectClaimedCandidateForSuit(candidates, slot, key.suitId());
				if (claimedOriginal != null && claimedOriginal.variant() != key.variant()) {
					lastOwnershipDenial = "同一认主套装内存在型号不一致的" + partName(slot)
							+ "，已拒绝生成重复槽位身份";
					parts.clear();
					break;
				}
				ArmorCandidate selected = claimedOriginal != null
						? claimedOriginal : selectCandidate(candidates, slot, key);
				// 手持未认主部件只锚定型号；已有同 suit/slot 原件时必须选择原件，不能再复制一份身份。
				boolean claimedOriginalExists = claimedOriginal != null;
				if (preferred != null && preferred.slot() == slot
						&& !(preferred.identity() == null && claimedOriginalExists)) {
					selected = preferred;
				}
				if (key.suitId() != null && selected != null && selected.identity() == null) {
					lastOwnershipDenial = "已有认主套装缺少原始" + partName(slot)
							+ "，不能用无身份部件复制替换";
					parts.clear();
					break;
				}
				if (selected == null || !compatible(selected, key)) {
					parts.clear();
					break;
				}
				parts.put(slot, selected);
			}
			if (parts.size() != ARMOR_SLOTS.size()) continue;

			ArmorOwnershipSavedData.DepositPlan ownershipPlan =
					ArmorOwnershipSavedData.planCompleteDeposit(
							serverLevel.getServer(), player.getUUID(), Optional.ofNullable(key.suitId()),
							serverLevel.dimension(), worldPosition);
			if (ownershipPlan.allowed()) {
				return SelectionSearch.found(new CompleteSelection(
						Map.copyOf(parts), key.variant(), ownershipPlan));
			}
			lastOwnershipDenial = ownershipPlan.message();
		}

		if (!lastOwnershipDenial.isBlank()) return SelectionSearch.denied(lastOwnershipDenial);
		return diagnoseIncompleteSet(candidates, preferred);
	}

	private static ArmorCandidate selectCandidate(List<ArmorCandidate> candidates,
			EquipmentSlot slot, SelectionKey key) {
		ArmorCandidate unclaimed = null;
		for (ArmorCandidate candidate : candidates) {
			if (candidate.slot() != slot || !compatible(candidate, key)) continue;
			if (candidate.identity() != null) return candidate;
			if (unclaimed == null) unclaimed = candidate;
		}
		return unclaimed;
	}

	private static ArmorCandidate selectClaimedCandidateForSuit(List<ArmorCandidate> candidates,
			EquipmentSlot slot, UUID expectedSuit) {
		for (ArmorCandidate candidate : candidates) {
			if (candidate.slot() == slot && candidate.usable() && candidate.identity() != null
					&& expectedSuit.equals(candidate.identity().suitId())) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean hasDuplicateClaimedSlot(List<ArmorCandidate> candidates, SelectionKey key) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			int matches = 0;
			for (ArmorCandidate candidate : candidates) {
				if (candidate.slot() != slot || !candidate.usable() || candidate.identity() == null
						|| !key.suitId().equals(candidate.identity().suitId())) continue;
				matches++;
				if (matches > 1) return true;
			}
		}
		return false;
	}

	private static boolean compatible(ArmorCandidate candidate, SelectionKey key) {
		if (!candidate.usable() || candidate.variant() != key.variant()) return false;
		if (candidate.identity() == null) return true;
		return key.suitId() != null && key.suitId().equals(candidate.identity().suitId());
	}

	private static SelectionSearch diagnoseIncompleteSet(List<ArmorCandidate> candidates,
			ArmorCandidate preferred) {
		List<String> missing = new ArrayList<>();
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (candidates.stream().noneMatch(candidate -> candidate.slot() == slot)) {
				missing.add(partName(slot));
			}
		}
		if (!missing.isEmpty()) {
			return SelectionSearch.missing("\u627e\u4e0d\u5230\u5b8c\u6574\u5957\u88c5\uff0c\u7f3a\u5c11\uff1a"
					+ String.join("\u3001", missing));
		}

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (candidates.stream().noneMatch(candidate -> candidate.slot() == slot && candidate.usable())) {
				return SelectionSearch.denied("\u5df2\u6709\u90e8\u4ef6\u5305\u542b\u4ed6\u4eba\u8ba4\u4e3b\u6216\u635f\u574f\u7684\u8eab\u4efd\u6570\u636e");
			}
		}

		if (preferred != null) {
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				boolean hasSameVariant = candidates.stream().anyMatch(candidate ->
						candidate.slot() == slot && candidate.usable()
								&& candidate.variant() == preferred.variant());
				if (!hasSameVariant) {
					return SelectionSearch.denied("\u6574\u5957\u5165\u5e93\u5df2\u62d2\u7edd\uff1a\u56db\u4ef6\u6218\u7532\u578b\u53f7\u4e0d\u4e00\u81f4");
				}
			}
			return SelectionSearch.denied("\u6574\u5957\u5165\u5e93\u5df2\u62d2\u7edd\uff1a\u56db\u4ef6\u6218\u7532\u6765\u81ea\u4e0d\u540c\u7684\u8ba4\u4e3b\u5957\u88c5");
		}

		for (ArmorVariant variant : ArmorVariant.values()) {
			boolean allSlots = true;
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				if (candidates.stream().noneMatch(candidate ->
						candidate.slot() == slot && candidate.usable() && candidate.variant() == variant)) {
					allSlots = false;
					break;
				}
			}
			if (allSlots) {
				return SelectionSearch.denied("\u6574\u5957\u5165\u5e93\u5df2\u62d2\u7edd\uff1a\u56db\u4ef6\u6218\u7532\u6765\u81ea\u4e0d\u540c\u7684\u8ba4\u4e3b\u5957\u88c5");
			}
		}
		return SelectionSearch.denied("\u6574\u5957\u5165\u5e93\u5df2\u62d2\u7edd\uff1a\u56db\u4ef6\u6218\u7532\u578b\u53f7\u4e0d\u4e00\u81f4");
	}

	private static List<ArmorCandidate> collectCandidates(ServerPlayer player) {
		List<ArmorCandidate> candidates = new ArrayList<>();
		int priority = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			addCandidate(candidates, player, ArmorSource.equipment(slot, priority++));
		}
		for (int index = 0; index < player.getInventory().getNonEquipmentItems().size(); index++) {
			addCandidate(candidates, player, ArmorSource.inventory(index, priority++));
		}
		addCandidate(candidates, player, ArmorSource.offhand(priority));
		return candidates;
	}

	private static void addCandidate(List<ArmorCandidate> candidates,
			ServerPlayer player, ArmorSource source) {
		ItemStack stack = source.current(player);
		Optional<EquipmentSlot> slot = ArmorIdentity.armorSlot(stack);
		if (slot.isEmpty()) return;
		Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(stack);
		candidates.add(new ArmorCandidate(source, slot.get(), ArmorVariantData.get(stack),
				identity.orElse(null),
				ArmorIdentity.canBeUsedBy(stack, player.getUUID(), slot.get()),
				stack.copy()));
	}

	private static ArmorCandidate findPreferredCandidate(ServerPlayer player,
			List<ArmorCandidate> candidates, ItemStack preferredHeld) {
		if (preferredHeld == null || preferredHeld.isEmpty()) return null;
		for (ArmorCandidate candidate : candidates) {
			if (candidate.source().current(player) == preferredHeld) return candidate;
		}
		return null;
	}

	private static void syncPlayerAfterDeposit(ServerPlayer player) {
		List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(ARMOR_SLOTS.size());
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
		}
		ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(player.getId(), equipment);
		player.connection.send(packet);
		for (ServerPlayer trackingPlayer : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(player)) {
			if (trackingPlayer != player) trackingPlayer.connection.send(packet);
		}
		player.inventoryMenu.broadcastChanges();
	}

	CompleteInsertResult fillMissingSetAtomically(ServerPlayer player) {
		if (!(level instanceof ServerLevel)) {
			return CompleteInsertResult.denied("\u5145\u80fd\u5e73\u53f0\u5c1a\u672a\u5c31\u7eea");
		}
		if (isEmpty() || isComplete() || ownerUuid == null || suitId == null) {
			return CompleteInsertResult.denied("\u5e73\u53f0\u4e0d\u662f\u53ef\u81ea\u52a8\u8865\u9f50\u7684\u534a\u6210\u54c1\u72b6\u6001");
		}
		if (!ownerUuid.equals(player.getUUID())) {
			return CompleteInsertResult.denied("\u8be5\u5145\u80fd\u5e73\u53f0\u4e0a\u7684\u6218\u7532\u4e0d\u5c5e\u4e8e\u4f60");
		}

		List<ItemStack> platformSnapshot = getArmorItems();
		UUID expectedOwner = ownerUuid;
		UUID expectedSuit = suitId;
		ChargingPlatformDisplayMode expectedMode = displayMode;
		ArmorVariant platformVariant = null;
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			ItemStack stored = platformSnapshot.get(index);
			if (stored.isEmpty()) continue;
			EquipmentSlot slot = ARMOR_SLOTS.get(index);
			if (!ArmorIdentity.belongsTo(stored, expectedOwner, expectedSuit, slot)) {
				return CompleteInsertResult.denied("\u5e73\u53f0\u5185\u5df2\u5b58\u90e8\u4ef6\u7684\u8ba4\u4e3b\u8eab\u4efd\u5f02\u5e38\uff0c\u5df2\u505c\u6b62\u8865\u9f50");
			}
			ArmorVariant storedVariant = ArmorVariantData.get(stored);
			if (platformVariant != null && platformVariant != storedVariant) {
				return CompleteInsertResult.denied("\u5e73\u53f0\u5185\u5df2\u5b58\u90e8\u4ef6\u578b\u53f7\u4e0d\u4e00\u81f4\uff0c\u5df2\u505c\u6b62\u8865\u9f50");
			}
			platformVariant = storedVariant;
		}
		if (platformVariant == null) {
			return CompleteInsertResult.denied("\u5e73\u53f0\u6ca1\u6709\u53ef\u7528\u7684\u5df2\u5b58\u6218\u7532\u90e8\u4ef6");
		}

		ArmorVariant expectedVariant = platformVariant;
		List<ArmorCandidate> candidates = collectCandidates(player);
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			EquipmentSlot slot = ARMOR_SLOTS.get(index);
			long claimedCopies = candidates.stream().filter(candidate ->
					candidate.slot() == slot && candidate.identity() != null
							&& candidate.identity().ownerId().equals(expectedOwner)
							&& candidate.identity().suitId().equals(expectedSuit)
							&& candidate.identity().slot() == slot).count();
			long allowedCopies = platformSnapshot.get(index).isEmpty() ? 1L : 0L;
			if (claimedCopies > allowedCopies) {
				return CompleteInsertResult.denied(
						"检测到这套战甲的重复" + partName(slot)
								+ "身份，已拒绝自动补齐以防复制继续扩散");
			}
		}
		List<PlannedPart> planned = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) {
			if (!platformSnapshot.get(index).isEmpty()) continue;
			EquipmentSlot slot = ARMOR_SLOTS.get(index);
			ArmorCandidate claimedOriginal =
					selectClaimedCandidateForSuit(candidates, slot, expectedSuit);
			if (claimedOriginal != null && claimedOriginal.variant() != expectedVariant) {
				return CompleteInsertResult.denied("自动补齐已拒绝：同一认主套装的"
						+ partName(slot) + "型号与平台不一致");
			}
			if (claimedOriginal == null && candidates.stream().anyMatch(existing ->
					existing.slot() == slot && existing.usable() && existing.identity() == null)) {
				return CompleteInsertResult.denied("自动补齐已拒绝：已有认主套装缺少原始"
						+ partName(slot) + "，不能用无身份部件替换");
			}
			ArmorCandidate candidate = claimedOriginal;
			if (candidate == null) {
				boolean hasDifferentVariant = candidates.stream().anyMatch(existing ->
						existing.slot() == slot && existing.usable()
								&& existing.variant() != expectedVariant);
				if (hasDifferentVariant) {
					return CompleteInsertResult.denied("\u81ea\u52a8\u8865\u9f50\u5df2\u62d2\u7edd\uff1a\u7f3a\u5931\u90e8\u4ef6\u4e0e\u5e73\u53f0\u6218\u7532\u578b\u53f7\u4e0d\u4e00\u81f4");
				}
				boolean hasWrongIdentity = candidates.stream().anyMatch(existing ->
						existing.slot() == slot && !existing.usable()
								|| existing.slot() == slot && existing.identity() != null
								&& !existing.identity().suitId().equals(expectedSuit));
				if (hasWrongIdentity) {
					return CompleteInsertResult.denied("\u81ea\u52a8\u8865\u9f50\u5df2\u62d2\u7edd\uff1a\u7f3a\u5931\u90e8\u4ef6\u5c5e\u4e8e\u4ed6\u4eba\u6216\u53e6\u4e00\u5957\u6218\u7532");
				}
				missing.add(partName(slot));
				continue;
			}
			ItemStack deposited = candidate.snapshot().copyWithCount(1);
			if (!ArmorIdentity.claim(deposited, expectedOwner, expectedSuit, slot)) {
				return CompleteInsertResult.denied("\u8865\u9f50\u90e8\u4ef6\u8eab\u4efd\u6821\u9a8c\u5931\u8d25");
			}
			planned.add(new PlannedPart(candidate.source(), slot, candidate.snapshot(), deposited));
		}
		if (!missing.isEmpty()) {
			return CompleteInsertResult.missing("\u81ea\u52a8\u8865\u9f50\u672a\u79fb\u52a8\u4efb\u4f55\u7269\u54c1\uff0c\u4ecd\u7f3a\u5c11\uff1a"
					+ String.join("\u3001", missing));
		}

		for (PlannedPart part : planned) {
			if (!part.source().matches(player, part.original())) {
				return CompleteInsertResult.denied("\u6218\u7532\u4f4d\u7f6e\u6216\u6570\u636e\u521a\u521a\u53d1\u751f\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
			}
		}
		if (!platformMatchesSnapshot(platformSnapshot, expectedOwner, expectedSuit, expectedMode)) {
			return CompleteInsertResult.denied("\u5e73\u53f0\u72b6\u6001\u521a\u521a\u53d1\u751f\u53d8\u5316\uff0c\u672a\u79fb\u52a8\u4efb\u4f55\u7269\u54c1");
		}

		try {
			for (PlannedPart part : planned) part.source().removeOne(player);
			for (PlannedPart part : planned) {
				armorItems.set(ARMOR_SLOTS.indexOf(part.slot()), part.deposited());
			}
			displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
			player.getInventory().setChanged();
			syncPlayerAfterDeposit(player);
			syncChanged();
			return CompleteInsertResult.inserted();
		} catch (RuntimeException exception) {
			for (int index = 0; index < armorItems.size(); index++) {
				armorItems.set(index, platformSnapshot.get(index).copy());
			}
			ownerUuid = expectedOwner;
			suitId = expectedSuit;
			displayMode = expectedMode;
			for (PlannedPart part : planned) part.source().restore(player, part.original());
			player.getInventory().setChanged();
			syncPlayerAfterDeposit(player);
			syncChanged();
			FlightArmorMod.LOGGER.error("Partial armor platform fill rolled back owner={}",
					player.getUUID(), exception);
			return CompleteInsertResult.denied("\u81ea\u52a8\u8865\u9f50\u5931\u8d25\uff0c\u73a9\u5bb6\u4e0e\u5e73\u53f0\u7269\u54c1\u5df2\u539f\u6837\u56de\u6eda");
		}
	}


	private boolean platformMatchesSnapshot(List<ItemStack> expectedItems,
			UUID expectedOwner, UUID expectedSuit, ChargingPlatformDisplayMode expectedMode) {
		if (!expectedOwner.equals(ownerUuid) || !expectedSuit.equals(suitId)
				|| expectedMode != displayMode || expectedItems.size() != armorItems.size()) {
			return false;
		}
		for (int index = 0; index < armorItems.size(); index++) {
			ItemStack current = armorItems.get(index);
			ItemStack expected = expectedItems.get(index);
			if (current.isEmpty() && expected.isEmpty()) continue;
			if (current.getCount() != expected.getCount()
					|| !ItemStack.isSameItemSameComponents(current, expected)) return false;
		}
		return true;
	}

	public boolean isEmpty() {
		return armorItems.stream().allMatch(ItemStack::isEmpty);
	}

	/** 服务端右键放入一个部件；写入身份和扣除手持物在同一个服务端 tick 中完成。 */
	InsertResult insertFromHand(ServerPlayer player, ItemStack held) {
		if (!(level instanceof ServerLevel serverLevel)) return InsertResult.denied("充能平台尚未就绪");
		Optional<EquipmentSlot> slotOptional = ArmorIdentity.armorSlot(held);
		if (slotOptional.isEmpty()) return InsertResult.notArmor();
		EquipmentSlot slot = slotOptional.get();
		int index = ARMOR_SLOTS.indexOf(slot);
		if (!armorItems.get(index).isEmpty()) return InsertResult.denied(partName(slot) + "展示位已被占用");
		if (ownerUuid != null && !ownerUuid.equals(player.getUUID())) {
			return InsertResult.denied("该充能平台上的战甲不属于你");
		}
		ArmorVariant incomingVariant = ArmorVariantData.get(held);
		for (ItemStack stored : armorItems) {
			if (!stored.isEmpty() && ArmorVariantData.get(stored) != incomingVariant) {
				return InsertResult.denied("\u540c\u4e00\u5145\u80fd\u5e73\u53f0\u4e0d\u80fd\u6df7\u653e\u4e0d\u540c\u578b\u53f7\u7684\u6218\u7532\u90e8\u4ef6");
			}
		}


		if (!ArmorIdentity.canBeUsedBy(held, player.getUUID(), slot)) {
			return InsertResult.denied("该战甲部件的认主身份属于他人或已经损坏");
		}
		Optional<ArmorIdentity.Identity> identity = ArmorIdentity.read(held);
		if (identity.isEmpty()) {
			return InsertResult.denied("无身份部件请凑齐同型号四件后整套入库，不能逐件创建认主身份");
		}
		if (identity.isPresent() && !identity.get().ownerId().equals(player.getUUID())) {
			return InsertResult.denied("该战甲部件已经认主，无法由其他玩家存放");
		}
		if (suitId != null && identity.isPresent() && !identity.get().suitId().equals(suitId)) {
			return InsertResult.denied("该部件不属于平台上的这套战甲");
		}
		if (suitId != null && hasOtherClaimedPart(player, held, suitId, slot)) {
			return InsertResult.denied(
					"背包或装备栏里仍有这套战甲的另一个认主" + partName(slot)
							+ "，已拒绝写入重复槽位身份");
		}

		Optional<UUID> requestedSuit = identity.map(ArmorIdentity.Identity::suitId);
		if (requestedSuit.isEmpty() && suitId != null) requestedSuit = Optional.of(suitId);
		ArmorOwnershipSavedData.DepositDecision decision = ArmorOwnershipSavedData.prepareDeposit(
				serverLevel.getServer(), player.getUUID(), requestedSuit,
				serverLevel.dimension(), worldPosition, ownerUuid != null && !isEmpty());
		if (!decision.allowed()) return InsertResult.denied(decision.message());

		ItemStack deposited = held.copyWithCount(1);
		if (!ArmorIdentity.claim(deposited, player.getUUID(), decision.suitId(), slot)) {
			return InsertResult.denied("战甲身份校验失败");
		}
		ownerUuid = player.getUUID();
		suitId = decision.suitId();
		armorItems.set(index, deposited);
		held.shrink(1);
		syncChanged();
		return InsertResult.inserted(slot, isComplete());
	}

	private static boolean hasOtherClaimedPart(ServerPlayer player, ItemStack held,
			UUID expectedSuit, EquipmentSlot expectedSlot) {
		for (ArmorCandidate candidate : collectCandidates(player)) {
			if (candidate.source().current(player) == held || candidate.identity() == null) continue;
			ArmorIdentity.Identity identity = candidate.identity();
			if (identity.ownerId().equals(player.getUUID())
					&& identity.suitId().equals(expectedSuit)
					&& identity.slot() == expectedSlot) {
				return true;
			}
		}
		return false;
	}

	public ToggleResult toggleDisplayMode(ServerPlayer player) {
		if (ownerUuid == null || suitId == null || !isComplete()) {
			return ToggleResult.denied("需要先在平台上放齐四件战甲");
		}
		if (!ownerUuid.equals(player.getUUID())) return ToggleResult.denied("只有战甲拥有者可以切换陈列模式");
		displayMode = displayMode.toggled();
		syncChanged();
		return ToggleResult.changed(displayMode);
	}

	VariantResult applyVariant(ServerPlayer player, ArmorVariant variant) {
		if (!isComplete()) {
			return VariantResult.denied("\u9700\u8981\u5148\u5728\u5e73\u53f0\u4e0a\u653e\u9f50\u56db\u4ef6\u6218\u7532");
		}
		if (ownerUuid == null || !ownerUuid.equals(player.getUUID())) {
			return VariantResult.denied("\u53ea\u6709\u6218\u7532\u62e5\u6709\u8005\u53ef\u4ee5\u91cd\u6784\u578b\u53f7");
		}
		if (armorItems.stream().allMatch(item -> ArmorVariantData.get(item) == variant)) {
			return VariantResult.unchanged(variant);
		}
		for (ItemStack armorItem : armorItems) {
			if (!ArmorVariantData.set(armorItem, variant)) {
				return VariantResult.denied("\u578b\u53f7\u91cd\u6784\u5931\u8d25\uff0c\u7269\u54c1\u6570\u636e\u672a\u66f4\u6539");
			}
		}
		syncChanged();
		ArmorCoreController.refreshPlatformBindingIfCoreEquipped(player, this);
		return VariantResult.changed(variant);
	}

	/** 仅供服务端召回事务使用；调用者必须在成功提交后更新 SavedData。 */
	ExtractedArmor extractForRecall(UUID expectedOwner, UUID expectedSuit) {
		if (!isComplete() || !expectedOwner.equals(ownerUuid) || !expectedSuit.equals(suitId)) return null;
		List<ItemStack> extracted = new ArrayList<>(ARMOR_SLOTS.size());
		for (int index = 0; index < armorItems.size(); index++) {
			extracted.add(armorItems.get(index));
			armorItems.set(index, ItemStack.EMPTY);
		}
		ExtractedArmor result = new ExtractedArmor(expectedOwner, expectedSuit, displayMode,
				List.copyOf(extracted));
		ownerUuid = null;
		suitId = null;
		draftOwnerUuid = null;
		displayMode = ChargingPlatformDisplayMode.ASSEMBLED;
		syncChanged();
		return result;
	}

	/** 召回装备提交失败时原样回滚，不创建任何物品副本。 */
	void restoreAfterFailedRecall(ExtractedArmor extracted) {
		if (!isEmpty() || ownerUuid != null || suitId != null || extracted.items().size() != ARMOR_SLOTS.size()) {
			throw new IllegalStateException("充能平台召回回滚目标已被占用");
		}
		ownerUuid = extracted.ownerId();
		suitId = extracted.suitId();
		draftOwnerUuid = null;
		displayMode = extracted.displayMode();
		for (int index = 0; index < armorItems.size(); index++) armorItems.set(index, extracted.items().get(index));
		syncChanged();
	}

	/** 方块被破坏时把真实物品交给世界掉落实体，并将索引降级为 LOOSE。 */
	List<ItemStack> releaseForBlockRemoval() {
		List<ItemStack> released = new ArrayList<>();
		for (int index = 0; index < armorItems.size(); index++) {
			ItemStack stack = armorItems.get(index);
			if (!stack.isEmpty()) released.add(stack);
			armorItems.set(index, ItemStack.EMPTY);
		}
		if (level instanceof ServerLevel serverLevel && ownerUuid != null && suitId != null) {
			ArmorOwnershipSavedData.markLooseIfMatches(serverLevel.getServer(), ownerUuid, suitId,
					serverLevel.dimension(), worldPosition);
		}
		ownerUuid = null;
		suitId = null;
		draftOwnerUuid = null;
		return released;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		for (int index = 0; index < ARMOR_SLOTS.size(); index++) armorItems.set(index, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, armorItems);
		platformId = input.read("PlatformId", UUIDUtil.CODEC).orElseGet(UUID::randomUUID);
		draftOwnerUuid = input.read("DraftOwner", UUIDUtil.CODEC).orElse(null);
		ownerUuid = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		suitId = input.read("Suit", UUIDUtil.CODEC).orElse(null);
		displayMode = ChargingPlatformDisplayMode.fromName(input.getStringOr("DisplayMode", ""));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, armorItems, true);
		output.store("PlatformId", UUIDUtil.CODEC, platformId);
		if (draftOwnerUuid != null) output.store("DraftOwner", UUIDUtil.CODEC, draftOwnerUuid);
		if (ownerUuid != null) output.store("Owner", UUIDUtil.CODEC, ownerUuid);
		if (suitId != null) output.store("Suit", UUIDUtil.CODEC, suitId);
		output.putString("DisplayMode", displayMode.name());
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	private void syncChanged() {
		setChanged();
		if (level == null) return;
		BlockState state = getBlockState();
		boolean parts = displayMode == ChargingPlatformDisplayMode.PARTS;
		if (state.hasProperty(ChargingPlatformBlock.PARTS) && state.getValue(ChargingPlatformBlock.PARTS) != parts) {
			level.setBlock(worldPosition, state.setValue(ChargingPlatformBlock.PARTS, parts), Block.UPDATE_CLIENTS);
			state = getBlockState();
		}
		level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
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

	private enum SourceKind {
		EQUIPMENT,
		INVENTORY,
		OFFHAND
	}

	private record ArmorSource(SourceKind kind, EquipmentSlot equipmentSlot,
			int inventoryIndex, int priority) {
		static ArmorSource equipment(EquipmentSlot slot, int priority) {
			return new ArmorSource(SourceKind.EQUIPMENT, slot, -1, priority);
		}

		static ArmorSource inventory(int index, int priority) {
			return new ArmorSource(SourceKind.INVENTORY, null, index, priority);
		}

		static ArmorSource offhand(int priority) {
			return new ArmorSource(SourceKind.OFFHAND, null, -1, priority);
		}

		ItemStack current(ServerPlayer player) {
			return switch (kind) {
				case EQUIPMENT -> player.getItemBySlot(equipmentSlot);
				case INVENTORY -> player.getInventory().getItem(inventoryIndex);
				case OFFHAND -> player.getOffhandItem();
			};
		}

		boolean matches(ServerPlayer player, ItemStack expected) {
			ItemStack current = current(player);
			return current.getCount() == expected.getCount()
					&& ItemStack.isSameItemSameComponents(current, expected);
		}

		void removeOne(ServerPlayer player) {
			ItemStack current = current(player);
			if (current.isEmpty()) throw new IllegalStateException("armor source became empty");
			if (current.getCount() == 1) {
				set(player, ItemStack.EMPTY);
			} else {
				ItemStack remaining = current.copy();
				remaining.shrink(1);
				set(player, remaining);
			}
		}

		void restore(ServerPlayer player, ItemStack original) {
			set(player, original.copy());
		}

		private void set(ServerPlayer player, ItemStack stack) {
			switch (kind) {
				case EQUIPMENT -> player.setItemSlot(equipmentSlot, stack);
				case INVENTORY -> player.getInventory().setItem(inventoryIndex, stack);
				case OFFHAND -> player.setItemInHand(InteractionHand.OFF_HAND, stack);
			}
		}
	}

	private record ArmorCandidate(ArmorSource source, EquipmentSlot slot, ArmorVariant variant,
			ArmorIdentity.Identity identity, boolean usable, ItemStack snapshot) {
	}

	private record SelectionKey(ArmorVariant variant, UUID suitId) {
	}

	private record CompleteSelection(Map<EquipmentSlot, ArmorCandidate> parts, ArmorVariant variant,
			ArmorOwnershipSavedData.DepositPlan ownershipPlan) {
	}

	private record SelectionSearch(CompleteSelection selection, boolean fallbackToSingle, String message) {
		static SelectionSearch found(CompleteSelection selection) {
			return new SelectionSearch(selection, false, "");
		}

		static SelectionSearch missing(String message) {
			return new SelectionSearch(null, true, message);
		}

		static SelectionSearch denied(String message) {
			return new SelectionSearch(null, false, message);
		}
	}

	private record PlannedPart(ArmorSource source, EquipmentSlot slot,
			ItemStack original, ItemStack deposited) {
	}

	record CompleteInsertResult(boolean success, boolean fallbackToSingle, String message) {
		static CompleteInsertResult inserted() {
			return new CompleteInsertResult(true, false, "");
		}

		static CompleteInsertResult missing(String message) {
			return new CompleteInsertResult(false, true, message);
		}

		static CompleteInsertResult denied(String message) {
			return new CompleteInsertResult(false, false, message);
		}
	}

	record ExtractedArmor(UUID ownerId, UUID suitId, ChargingPlatformDisplayMode displayMode,
			List<ItemStack> items) {
	}

	record InsertResult(boolean handled, boolean success, EquipmentSlot slot, boolean complete, String message) {
		static InsertResult notArmor() {
			return new InsertResult(false, false, null, false, "");
		}

		static InsertResult denied(String message) {
			return new InsertResult(true, false, null, false, message);
		}

		static InsertResult inserted(EquipmentSlot slot, boolean complete) {
			return new InsertResult(true, true, slot, complete, "");
		}
	}

	public record ToggleResult(boolean success, ChargingPlatformDisplayMode mode, String message) {
		static ToggleResult changed(ChargingPlatformDisplayMode mode) {
			return new ToggleResult(true, mode, "");
		}

		static ToggleResult denied(String message) {
			return new ToggleResult(false, null, message);
		}
	}

	record VariantResult(boolean success, boolean changed, ArmorVariant variant, String message) {
		static VariantResult changed(ArmorVariant variant) {
			return new VariantResult(true, true, variant, "");
		}

		static VariantResult unchanged(ArmorVariant variant) {
			return new VariantResult(true, false, variant, "");
		}

		static VariantResult denied(String message) {
			return new VariantResult(false, false, null, message);
		}
	}
}
