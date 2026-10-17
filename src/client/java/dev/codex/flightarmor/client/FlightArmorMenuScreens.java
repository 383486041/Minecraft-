package dev.codex.flightarmor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Registers client-side screens for custom container menu types in MC 26.3.
 */
public final class FlightArmorMenuScreens {
	private FlightArmorMenuScreens() {
	}

	@SuppressWarnings("unchecked")
	public static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(
			MenuType<? extends M> type, ScreenConstructor<M, U> factory) {
		try {
			Field field = MenuScreens.class.getDeclaredField("SCREENS");
			field.setAccessible(true);
			Map<Object, Object> screens = (Map<Object, Object>) field.get(null);
			Class<?> constructorClass = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
			Object proxy = Proxy.newProxyInstance(
					constructorClass.getClassLoader(),
					new Class<?>[]{constructorClass},
					(p, method, args) -> {
						if ("create".equals(method.getName())) {
							return factory.create((M) args[0], (Inventory) args[1], (Component) args[2]);
						}
						if ("fromPacket".equals(method.getName())) {
							Component title = (Component) args[0];
							MenuType<M> menuType = (MenuType<M>) args[1];
							Minecraft client = (Minecraft) args[2];
							int containerId = (int) args[3];
							M menu = menuType.create(containerId, client.player.getInventory());
							U screen = factory.create(menu, client.player.getInventory(), title);
							client.player.containerMenu = screen.getMenu();
							client.gui.setScreen(screen);
							return null;
						}
						return null;
					}
			);
			screens.put(type, proxy);
		} catch (Exception e) {
			throw new RuntimeException("Failed to register menu screen", e);
		}
	}

	@FunctionalInterface
	public interface ScreenConstructor<M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> {
		U create(M menu, Inventory inventory, Component title);
	}
}
