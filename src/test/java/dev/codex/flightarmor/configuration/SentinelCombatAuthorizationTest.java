package dev.codex.flightarmor.configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pure timing checks plus source contracts for every Sentinel damage lane. */
public final class SentinelCombatAuthorizationTest {
	private SentinelCombatAuthorizationTest() {
	}

	public static void main(String[] args) throws Exception {
		transitionWindowIsStableAndBounded();
		combatLanesUseTheSharedAuthority();
		System.out.println("SentinelCombatAuthorizationTest passed");
	}

	private static void transitionWindowIsStableAndBounded() {
		require(ArmorConfigurationAccess.TRANSITION_DURATION_TICKS == 30L,
				"Sentinel mechanical transition must last exactly 30 ticks");

		ArmorConfigurationData.Snapshot defaultSnapshot =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.OVERDRIVE_FLIGHT, 0L, 0L);
		require(!ArmorConfigurationAccess.isTransitioning(defaultSnapshot, 0L)
						&& ArmorConfigurationAccess.transitionProgress(defaultSnapshot, 0L) == 1.0F,
				"legacy/default Sentinel data must start in a stable flight form");

		ArmorConfigurationData.Snapshot switching =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.BREACH_MELEE, 4L, 120L);
		require(ArmorConfigurationAccess.isTransitioning(switching, 119L)
						&& ArmorConfigurationAccess.transitionProgress(switching, 119L) == 0.0F,
				"clock skew must not complete a transition early");
		require(ArmorConfigurationAccess.isTransitioning(switching, 149L)
						&& ArmorConfigurationAccess.transitionTicksRemaining(switching, 149L) == 1L,
				"tick 29 remains mechanically locked");
		require(!ArmorConfigurationAccess.isTransitioning(switching, 150L)
						&& ArmorConfigurationAccess.transitionProgress(switching, 150L) == 1.0F
						&& ArmorConfigurationAccess.transitionTicksRemaining(switching, 150L) == 0L,
				"tick 30 atomically unlocks the selected form");
	}

	private static void combatLanesUseTheSharedAuthority() throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String access = read(root,
				"src/main/java/dev/codex/flightarmor/configuration/ArmorConfigurationAccess.java");
		String gate = read(root,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatGate.java");
		String controller = read(root,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java");
		String configurationController = read(root,
				"src/main/java/dev/codex/flightarmor/configuration/ArmorConfigurationController.java");

		for (String marker : new String[] {
				"ModItems.fullSetVariant(entity)",
				"ArmorVariant.SENTINEL",
				"ChargingPlatformBlockEntity.ARMOR_SLOTS",
				"ArmorIdentity.read(stack)",
				"identity.slot() != slot",
				"!ownerId.equals(identity.ownerId())",
				"!suitId.equals(identity.suitId())"
		}) {
			require(access.contains(marker),
					"configured Sentinel authority lost exact-set check: " + marker);
		}
		require(access.contains("requiredMode")
						&& access.contains("SentinelArmorMode.BREACH_MELEE")
						&& access.contains("SentinelArmorMode.OVERDRIVE_FLIGHT")
						&& access.contains("!isTransitioning(configured.snapshot(), gameTime)"),
				"form abilities must be mutually exclusive and transition locked");

		require(gate.contains("ArmorConfigurationAccess.allowsHeavyMelee(player, now)")
						&& gate.contains("ArmorConfigurationAccess.allowsRangedCombat(player, now)")
						&& gate.contains("ArmorConfigurationAccess.isTransitioning("),
				"worn X/blade and R/G lanes must use the same server authority");
		require(configurationController.contains(
						"ArmorConfigurationAccess.isTransitioning(current, now)"),
				"a second form toggle must not interrupt an active mechanical transition");

		require(count(controller, "allowsCruiseRanged(carrier, now)") >= 4,
				"cruise wrist, core, drone and manual core lanes must all revalidate flight form");
		require(count(controller,
						"allowsCruiseHeavyMelee(carrier, combatTime(owner.getServer()))") >= 2,
				"remote X basic melee and J ultimate must both revalidate breach form");
		require(controller.contains(
						"ArmorConfigurationAccess.allowsRangedCombat(living, gameTime)")
						&& controller.contains(
								"ArmorConfigurationAccess.allowsHeavyMelee(living, gameTime)"),
				"carrier attacks must delegate to the shared exact-suit authority");
	}

	private static int count(String text, String marker) {
		int count = 0;
		for (int index = text.indexOf(marker); index >= 0;
				index = text.indexOf(marker, index + marker.length())) {
			count++;
		}
		return count;
	}

	private static String read(Path root, String relative) throws Exception {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
