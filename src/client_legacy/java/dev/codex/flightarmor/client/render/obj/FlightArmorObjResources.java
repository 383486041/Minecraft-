package dev.codex.flightarmor.client.render.obj;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理 OBJ 的缓存与 F3+T 客户端资源重载。 */
public final class FlightArmorObjResources {
	private static final Map<ArmorVariant, ResourceLocation> MODELS = Map.of(
			ArmorVariant.VANGUARD, FlightArmorMod.id("models/armor/flight_armor_vanguard.obj"),
			ArmorVariant.SWIFT, FlightArmorMod.id("models/armor/flight_armor_swift.obj"),
			ArmorVariant.SENTINEL, FlightArmorMod.id("models/armor/flight_armor_sentinel.obj"));
	private static final ResourceLocation RELOAD_LISTENER_ID =
			FlightArmorMod.id("flight_armor_obj_mesh");
	private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean();

	private static volatile Map<ArmorVariant, ArmorObjMesh> meshes = Map.of();
	private static volatile boolean loaded;

	private FlightArmorObjResources() {
	}

	/** 必须在客户端初始化阶段调用一次。 */
	public static void registerReloadListener() {
		if (!LISTENER_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
				.registerReloadListener(new ReloadListener());
	}

	/** 允许开发期或测试代码主动重载，不必重启游戏。 */
	public static void reload(ResourceManager manager) {
		EnumMap<ArmorVariant, ArmorObjMesh> next = new EnumMap<>(ArmorVariant.class);
		for (ArmorVariant variant : ArmorVariant.values()) {
			ResourceLocation model = MODELS.get(variant);
			ArmorObjMesh variantMesh = ArmorObjMesh.empty();
			try {
				var resource = manager.getResource(model).orElseThrow(
						() -> new IOException("Missing armor OBJ resource " + model));
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(
						resource.open(), StandardCharsets.UTF_8))) {
					variantMesh = ArmorObjMesh.parse(reader);
				}
				FlightArmorMod.LOGGER.info("Loaded {} armor OBJ: {} groups, {} triangles",
						variant.serializedName(), variantMesh.groups().size(), variantMesh.faceCount());
			} catch (Exception ex) {
				FlightArmorMod.LOGGER.error("Failed to load {} armor OBJ {}",
						variant.serializedName(), model, ex);
			}
			next.put(variant, variantMesh);
		}
		meshes = Map.copyOf(next);
		loaded = true;
	}

	static ArmorObjMesh mesh(ArmorVariant variant) {
		if (!loaded) {
			reload(Minecraft.getInstance().getResourceManager());
		}
		return meshes.getOrDefault(variant == null ? ArmorVariant.DEFAULT : variant, ArmorObjMesh.empty());
	}

	private static final class ReloadListener implements SimpleSynchronousResourceReloadListener {
		@Override
		public ResourceLocation getFabricId() {
			return RELOAD_LISTENER_ID;
		}

		@Override
		public void onResourceManagerReload(ResourceManager manager) {
			FlightArmorObjResources.reload(manager);
		}
	}
}
