package dev.codex.flightarmor.core;

import dev.codex.flightarmor.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * SavedData-backed 的独立单格装备栏，死亡和重生不会从原版背包掉落。
 *
 * <p>这是服务器权威记录的一层投影，不是真正的物品容器：写入即落库，被拒即退还。
 * 原版槽位流程在调用 {@link #setItem} 之前就已经把物品从来源切走了，
 * 因此任何写入路径都必须保持「要么装进去、要么还给玩家」的契约，不允许静默丢弃。</p>
 */
public final class ArmorCoreInventory implements Container {
	private final Player owner;
	private ItemStack clientMirror = ItemStack.EMPTY;

	public ArmorCoreInventory(Player owner) {
		this.owner = owner;
	}

	private ArmorCoreSavedData data() {
		if (owner instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			return ArmorCoreSavedData.get(serverPlayer.getServer());
		}
		throw new IllegalStateException("Client-side armor core mirror has no SavedData");
	}

	private boolean isServerOwner() {
		return owner instanceof net.minecraft.server.level.ServerPlayer;
	}

	@Override public int getContainerSize() { return 1; }
	@Override public boolean isEmpty() { return getItem(0).isEmpty(); }

	@Override
	public ItemStack getItem(int slot) {
		if (slot != 0) return ItemStack.EMPTY;
		ItemStack stored = isServerOwner() ? data().record(owner.getUUID()).core : clientMirror;
		// Never expose the SavedData-backed stack for in-place shrinking. Slot click code
		// must commit through setItem/removeItem so a transfer cannot silently turn the
		// stored core into an empty mutable stack.
		return stored.isEmpty() ? ItemStack.EMPTY : stored.copyWithCount(1);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		if (slot != 0 || amount <= 0) return ItemStack.EMPTY;
		ItemStack current = getItem(0);
		if (current.isEmpty()) return ItemStack.EMPTY;
		if (isCoreChangeLocked()) {
			rejectLockedCoreChange();
			return ItemStack.EMPTY;
		}
		if (isServerOwner()) {
			data().setCore(owner.getUUID(), ItemStack.EMPTY);
			ArmorCoreController.sendSnapshot((net.minecraft.server.level.ServerPlayer) owner);
		} else {
			clientMirror = ItemStack.EMPTY;
		}
		return current.copyWithCount(1);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return removeItem(slot, 1);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot != 0) return;
		if (!isServerOwner()) {
			clientMirror = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
			return;
		}
		ItemStack requested = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
		// 下面每个被拒的分支都必须把物品还给玩家：原版已经把 requested 从来源槽切走，
		// 静默 return 等于把一件不可复制的核心永久销毁。
		if (isCoreChangeLocked() && !ItemStack.matches(getItem(0), requested)) {
			rejectLockedCoreChange();
			returnToOwner(requested);
			return;
		}

		if (stack.isEmpty()) {
			data().setCore(owner.getUUID(), ItemStack.EMPTY);
			ArmorCoreController.sendSnapshot((net.minecraft.server.level.ServerPlayer) owner);
			return;
		}
		if (!requested.is(ModItems.ARMOR_NAVIGATION_CORE)) {
			returnToOwner(requested);
			return;
		}
		ItemStack equipped = requested.copyWithCount(1);
		if (ArmorCoreIdentity.claim(equipped, owner.getUUID()).isEmpty()) {
			returnToOwner(requested);
			return;
		}
		data().setCore(owner.getUUID(), equipped);
		ArmorCoreController.sendSnapshot((net.minecraft.server.level.ServerPlayer) owner);
	}

	@Override public void setChanged() { if (isServerOwner()) data().setDirty(); }
	@Override public boolean stillValid(Player player) { return player.getUUID().equals(owner.getUUID()); }
	/**
	 * 清空槽位同样退还核心：内容只存在于 SavedData，直接抹掉就是让玩家凭空少一枚核心。
	 * 宁可退回背包（放不下则掉落），也不接受「容器被清空 = 核心消失」。
	 */
	@Override
	public void clearContent() {
		if (!isServerOwner()) {
			clientMirror = ItemStack.EMPTY;
			return;
		}
		ItemStack current = getItem(0);
		data().setCore(owner.getUUID(), ItemStack.EMPTY);
		returnToOwner(current);
		ArmorCoreController.sendSnapshot((net.minecraft.server.level.ServerPlayer) owner);
	}
	@Override public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == 0 && !isCoreChangeLocked() && stack.is(ModItems.ARMOR_NAVIGATION_CORE)
				&& ArmorCoreIdentity.read(stack)
				.map(identity -> identity.ownerId().equals(owner.getUUID())).orElse(true);
	}

	private boolean isCoreChangeLocked() {
		return owner instanceof ServerPlayer serverPlayer
				&& ArmorCoreController.isCoreChangeLocked(serverPlayer);
	}

	private void rejectLockedCoreChange() {
		if (!(owner instanceof ServerPlayer serverPlayer)) return;
		serverPlayer.displayClientMessage(Component.literal(
				"战甲移动事务进行中，完成或回滚前不能更换导航核心"), true);
		serverPlayer.inventoryMenu.sendAllDataToRemote();
	}

	/**
	 * 把一枚被拒的物品还给玩家，绝不静默丢弃。
	 *
	 * <p>先放背包，放不下再掉在脚下：两种结果都比"装备失败 = 物品消失"好。
	 * 刻意不使用 {@code placeItemBackInInventory}，它在循环里会触发
	 * {@code broadcastChanges}，会把一次槽位写入重入到同步流程中。</p>
	 */
	private void returnToOwner(ItemStack stack) {
		if (stack.isEmpty() || !(owner instanceof ServerPlayer serverPlayer)) return;
		serverPlayer.getInventory().add(stack);
		if (!stack.isEmpty()) serverPlayer.drop(stack, false);
	}
}
