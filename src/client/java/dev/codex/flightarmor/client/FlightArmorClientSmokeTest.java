package dev.codex.flightarmor.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.item.ModItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Opt-in real-client smoke checks used by the local build pipeline.
 *
 * <p>Enable with {@code -DflightArmor.clientSmokeTest=true}. Normal players never execute this
 * path. The check deliberately uses an immediate buffer source, matching the inventory render
 * path which previously crashed when navigation-core base and emissive consumers were interleaved.</p>
 */
public final class FlightArmorClientSmokeTest {
	private static final String ENABLE_PROPERTY = "flightArmor.clientSmokeTest";
	private static final int RESOURCE_SETTLE_TICKS = 80;
	private static int elapsedTicks;
	private static boolean finished;

	private FlightArmorClientSmokeTest() {
	}

	public static void initialize() {
		if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
		ClientTickEvents.END_CLIENT_TICK.register(FlightArmorClientSmokeTest::tick);
		FlightArmorMod.LOGGER.info("FLIGHT_ARMOR_CLIENT_SMOKE_TEST armed");
	}

	private static void tick(Minecraft client) {
		if (finished || client.getOverlay() != null || ++elapsedTicks < RESOURCE_SETTLE_TICKS) return;
		finished = true;
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20)) {
			MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(bytes);
			client.getItemRenderer().renderStatic(
					new ItemStack(ModItems.ARMOR_NAVIGATION_CORE),
					ItemDisplayContext.GUI,
					LightTexture.FULL_BRIGHT,
					OverlayTexture.NO_OVERLAY,
					new PoseStack(),
					buffers,
					client.level,
					0);
			buffers.endBatch();
			FlightArmorMod.LOGGER.info("FLIGHT_ARMOR_CLIENT_SMOKE_TEST PASS navigation_core_gui");
		} catch (Throwable failure) {
			FlightArmorMod.LOGGER.error("FLIGHT_ARMOR_CLIENT_SMOKE_TEST FAIL navigation_core_gui", failure);
			if (failure instanceof RuntimeException runtime) throw runtime;
			if (failure instanceof Error error) throw error;
			throw new RuntimeException(failure);
		}
	}
}
