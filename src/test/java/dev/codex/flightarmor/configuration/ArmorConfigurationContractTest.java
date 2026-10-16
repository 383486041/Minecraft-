package dev.codex.flightarmor.configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Static and pure-state contract for the Sentinel-only two-form foundation. */
public final class ArmorConfigurationContractTest {
	private ArmorConfigurationContractTest() {
	}

	public static void main(String[] args) throws Exception {
		verifyModes();
		verifyOpaqueWingmanOwnershipClock();

		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String data = read(root,
				"src/main/java/dev/codex/flightarmor/configuration/ArmorConfigurationData.java");
		String controller = read(root,
				"src/main/java/dev/codex/flightarmor/configuration/ArmorConfigurationController.java");
		String payload = read(root,
				"src/main/java/dev/codex/flightarmor/network/ArmorConfigurationTogglePayload.java");
		String client = read(root,
				"src/client/java/dev/codex/flightarmor/client/configuration/ArmorConfigurationClient.java");
		String initializer = read(root,
				"src/main/java/dev/codex/flightarmor/FlightArmorMod.java");
		String clientInitializer = read(root,
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java");
		String hud = read(root,
				"src/client/java/dev/codex/flightarmor/client/core/ArmorCoreHud.java");
		String english = read(root,
				"src/main/resources/assets/flight_armor/lang/en_us.json");
		String chinese = read(root,
				"src/main/resources/assets/flight_armor/lang/zh_cn.json");

		require(data.contains("FlightArmorConfiguration")
						&& data.contains("DataComponents.CUSTOM_DATA")
						&& data.contains("oldData.copyTag()")
						&& data.contains("customRoot.put(ROOT_KEY, root)")
						&& data.contains("EquipmentSlot.CHEST")
						&& data.contains("ArmorVariant.SENTINEL"),
				"configuration must persist on the Sentinel chest without replacing sibling custom data");
		require(controller.contains("ModItems.fullSetVariant(player)")
						&& controller.contains("fullSet.get() != ArmorVariant.SENTINEL")
						&& controller.contains("hasCompleteOwnedSuitIdentity(player)")
						&& controller.contains("ArmorIdentity.read(stack)")
						&& controller.contains("suitId.equals(identity.suitId())")
						&& controller.contains("ChargingPlatformController.canUseEquippedFlightArmor(player)")
						&& controller.contains("ArmorAssemblyController.isAnimating(player)")
						&& controller.contains("ArmorCoreController.hasActiveQuickSwitch(player)")
						&& controller.contains("acceptClientSequence")
						&& controller.contains("ArmorConfigurationData.set(chest")
						&& !controller.contains("ensureOwnedEquippedSet"),
				"server toggle must fail closed without creating or rewriting suit identity");
		require(payload.contains("long clientSequence")
						&& payload.contains("armor_configuration_toggle")
						&& !payload.contains("SentinelArmorMode"),
				"client packet must carry intent and sequence only, never the requested result");
		require(client.contains("GLFW.GLFW_KEY_M")
						&& client.indexOf("consumeClick()") < client.indexOf("client.screen != null")
						&& client.contains("ArmorAssemblyClient.isOperationCapturedThisTick(client)")
						&& client.contains("ArmorAssemblyClient.captureOperationThisTick(client)")
						&& client.contains("new ArmorConfigurationTogglePayload(sequence)")
						&& !client.contains("ArmorAttackIntentPayload"),
				"M must be drained safely and routed through its dedicated operation lane");
		require(initializer.contains("playC2S().register(ArmorConfigurationTogglePayload.TYPE")
						&& initializer.contains("ArmorConfigurationController.handleToggle")
						&& initializer.contains("ArmorConfigurationController.remove")
						&& initializer.contains("ArmorConfigurationController.clearAll"),
				"server payload and lifecycle wiring");
		require(clientInitializer.contains("ArmorConfigurationClient.initialize()")
						&& clientInitializer.contains("ArmorConfigurationClient.tick(client)"),
				"central client initialization and arbitration wiring");
		require(hud.contains("entry.status() == ArmorCoreSnapshotPayload.Status.WORN")
						&& hud.contains("identity.matches(client.player.getUUID(), entry.suitId()")
						&& hud.contains("ArmorConfigurationData.equippedSentinel")
						&& hud.contains("worn_overdrive")
						&& hud.contains("worn_breach"),
				"core-synchronized HUD must specialize only the exact worn Sentinel suit");
		for (String key : new String[] {
				"key.flight_armor.toggle_configuration",
				"message.flight_armor.configuration.overdrive",
				"message.flight_armor.configuration.breach",
				"hud.flight_armor.armor_list.status.worn_overdrive",
				"hud.flight_armor.armor_list.status.worn_breach"
		}) {
			require(english.contains("\"" + key + "\"")
							&& chinese.contains("\"" + key + "\""),
					"missing bilingual configuration translation: " + key);
		}
	}

	private static void verifyModes() {
		Set<String> names = java.util.Arrays.stream(SentinelArmorMode.values())
				.map(Enum::name).collect(Collectors.toSet());
		require(names.equals(Set.of("OVERDRIVE_FLIGHT", "BREACH_MELEE")),
				"Sentinel must have exactly the requested two forms");
		require(SentinelArmorMode.DEFAULT == SentinelArmorMode.OVERDRIVE_FLIGHT,
				"flight form is the safe/default legacy state");
		require(SentinelArmorMode.OVERDRIVE_FLIGHT.next() == SentinelArmorMode.BREACH_MELEE
						&& SentinelArmorMode.BREACH_MELEE.next()
								== SentinelArmorMode.OVERDRIVE_FLIGHT,
				"two-way M sequence");
		require(SentinelArmorMode.fromSerializedName("BREACH_MELEE")
						== SentinelArmorMode.BREACH_MELEE
						&& SentinelArmorMode.fromSerializedName("unknown")
								== SentinelArmorMode.DEFAULT,
				"stable case-insensitive decode and unknown-data fallback");
	}

	private static void verifyOpaqueWingmanOwnershipClock() {
		ArmorConfigurationData.Snapshot deploying = new ArmorConfigurationData.Snapshot(
				SentinelArmorMode.OVERDRIVE_FLIGHT, 1L, 0L,
				true, 1L, 100L, 0.0F);
		require(!ArmorConfigurationAccess.wingmenEntityOwnsGeometry(deploying, 109.999D),
				"worn renderer must own the module until the deploy hand-off");
		require(ArmorConfigurationAccess.wingmenEntityOwnsGeometry(deploying, 110.0D),
				"entity renderer must take the module on deploy tick 10");

		ArmorConfigurationData.Snapshot recalling = new ArmorConfigurationData.Snapshot(
				SentinelArmorMode.OVERDRIVE_FLIGHT, 1L, 0L,
				false, 2L, 200L, 1.0F);
		require(ArmorConfigurationAccess.wingmenEntityOwnsGeometry(recalling, 221.999D),
				"entity must retain the packed module before recall tick 22");
		require(!ArmorConfigurationAccess.wingmenEntityOwnsGeometry(recalling, 222.0D),
				"worn renderer must retake the module on recall tick 22");
		require(ArmorConfigurationAccess.wingmenEntityOwnership(deploying, 109.999D) == 0.0F
						&& ArmorConfigurationAccess.wingmenEntityOwnership(deploying, 110.0D)
								== 1.0F,
				"compatibility ownership must remain binary instead of becoming alpha");
	}

	private static String read(Path root, String relative) throws Exception {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
