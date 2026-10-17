package dev.codex.flightarmor.combat;

import dev.codex.flightarmor.configuration.ArmorConfigurationAccess;
import dev.codex.flightarmor.configuration.ArmorConfigurationData;
import dev.codex.flightarmor.configuration.SentinelArmorMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline profile boundaries plus source contracts for the Sentinel G salvo. */
public final class SentinelOverdriveSalvoTest {
	private SentinelOverdriveSalvoTest() {
	}

	public static void main(String[] args) throws Exception {
		profileBoundariesAreFiniteAndDistinct();
		energyAuthorityCannotGrantAFreeCataclysm();
		wingOwnershipSelectsExactlyOneOrThreeLanes();
		layoutCommitUsesExistingProtocolAndProtectedDestruction();
		System.out.println("SentinelOverdriveSalvoTest passed");
	}

	private static void profileBoundariesAreFiniteAndDistinct() {
		SentinelOverdriveSalvo.Profile minimum = SentinelOverdriveSalvo.profile(0.0F);
		SentinelOverdriveSalvo.Profile ordinary =
				SentinelOverdriveSalvo.profile(
						SentinelOverdriveSalvo.CATACLYSM_CHARGE_THRESHOLD - 0.001F);
		SentinelOverdriveSalvo.Profile cataclysm =
				SentinelOverdriveSalvo.profile(
						SentinelOverdriveSalvo.CATACLYSM_CHARGE_THRESHOLD);
		SentinelOverdriveSalvo.Profile clamped =
				SentinelOverdriveSalvo.profile(Float.POSITIVE_INFINITY);

		require(!minimum.cataclysm() && !ordinary.cataclysm() && cataclysm.cataclysm(),
				"full-charge boundary did not separate ordinary and cataclysm salvos");
		require(minimum.energyCost() == SentinelOverdriveSalvo.MINIMUM_ENERGY_COST
						&& minimum.wingExplosionPower() == 0.0F,
				"tap/low-charge salvo lost its minimum cost or non-explosive wing identity");
		require(cataclysm.energyCost() == SentinelOverdriveSalvo.MAXIMUM_ENERGY_COST
						&& cataclysm.heat() == SentinelOverdriveSalvo.MAXIMUM_HEAT
						&& cataclysm.cooldownTicks()
								== SentinelOverdriveSalvo.MAXIMUM_COOLDOWN_TICKS,
				"cataclysm resource limits changed");
		require(cataclysm.chestExplosionPower()
								<= SentinelOverdriveSalvo.MAXIMUM_CHEST_EXPLOSION_POWER
						&& cataclysm.wingExplosionPower()
								<= SentinelOverdriveSalvo.MAXIMUM_WING_EXPLOSION_POWER,
				"native explosion power exceeded its hard cap");
		require(clamped.charge() == 0.0F && !clamped.cataclysm(),
				"non-finite client-like input did not fail closed");
	}

	private static void energyAuthorityCannotGrantAFreeCataclysm() {
		require(SentinelOverdriveSalvo.affordableCharge(1.0F,
						SentinelOverdriveSalvo.MINIMUM_ENERGY_COST - 0.01F) < 0.0F,
				"insufficient energy still authorized a three-cannon release");
		float downgraded = SentinelOverdriveSalvo.affordableCharge(
				1.0F, SentinelOverdriveSalvo.MAXIMUM_ENERGY_COST - 1.0F);
		require(downgraded >= 0.0F
						&& downgraded < SentinelOverdriveSalvo.CATACLYSM_CHARGE_THRESHOLD,
				"partial reserves received a free full-charge discontinuity");
		require(SentinelOverdriveSalvo.affordableCharge(
						1.0F, SentinelOverdriveSalvo.MAXIMUM_ENERGY_COST) == 1.0F,
				"exact full reserve failed to authorize full charge");
	}

	private static void wingOwnershipSelectsExactlyOneOrThreeLanes() {
		long transitionStart = 400L;
		ArmorConfigurationData.Snapshot stableDocked =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.OVERDRIVE_FLIGHT, 0L, 0L,
						false, 0L, 0L, 0.0F);
		ArmorConfigurationData.Snapshot stableDeployed =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.OVERDRIVE_FLIGHT, 0L, 0L,
						true, 0L, 0L, 1.0F);
		ArmorConfigurationData.Snapshot deploying =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.OVERDRIVE_FLIGHT, 0L, 0L,
						true, 1L, transitionStart, 0.0F);
		ArmorConfigurationData.Snapshot recalling =
				new ArmorConfigurationData.Snapshot(
						SentinelArmorMode.OVERDRIVE_FLIGHT, 0L, 0L,
						false, 2L, transitionStart, 1.0F);

		require(SentinelOverdriveSalvo.layoutFor(stableDocked, transitionStart)
						== SentinelOverdriveSalvo.FireLayout.DOCKED_TRIPLE,
				"a fully docked stable wing group did not authorize three wearable lanes");
		require(SentinelOverdriveSalvo.layoutFor(stableDeployed, transitionStart)
						== SentinelOverdriveSalvo.FireLayout.CHEST_ONLY,
				"deployed wingmen still authorized imaginary wearable wing cannons");
		require(SentinelOverdriveSalvo.layoutFor(deploying, transitionStart + 5L)
						== SentinelOverdriveSalvo.FireLayout.CHEST_ONLY,
				"deployment hand-off did not fail closed to the chest lane");
		require(SentinelOverdriveSalvo.layoutFor(recalling,
						transitionStart
								+ ArmorConfigurationAccess.WINGMEN_TRANSITION_DURATION_TICKS - 1L)
						== SentinelOverdriveSalvo.FireLayout.CHEST_ONLY,
				"recalling wingmen granted wearable cannons before physical hand-off completed");
		require(SentinelOverdriveSalvo.layoutFor(recalling,
						transitionStart
								+ ArmorConfigurationAccess.WINGMEN_TRANSITION_DURATION_TICKS)
						== SentinelOverdriveSalvo.FireLayout.DOCKED_TRIPLE,
				"completed recall did not restore the three-lane docked salvo");
		require(SentinelOverdriveSalvo.layoutFor(null, transitionStart)
						== SentinelOverdriveSalvo.FireLayout.CHEST_ONLY,
				"missing configuration did not fail closed to chest-only layout");
	}

	private static void layoutCommitUsesExistingProtocolAndProtectedDestruction()
			throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		String salvo = source(root,
				"src/main/java/dev/codex/flightarmor/combat/SentinelOverdriveSalvo.java");
		String controller = source(root,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatController.java");
		String resolver = source(root,
				"src/main/java/dev/codex/flightarmor/combat/ArmorVariantAttackResolver.java");
		String gate = source(root,
				"src/main/java/dev/codex/flightarmor/combat/ArmorCombatGate.java");
		String destructive = source(root,
				"src/main/java/dev/codex/flightarmor/combat/DestructiveLaserController.java");
		String terrainGuard = source(root,
				"src/main/java/dev/codex/flightarmor/combat/TerrainDestructionGuard.java");
		String fx = source(root,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxController.java");
		String renderer = source(root,
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxRenderer.java");
		String armorRenderer = source(root,
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java");

		for (String authority : new String[] {
				"ArmorConfigurationAccess.configuredSentinel(attacker)",
				"configured.ownerId().equals(attacker.getUUID())",
				"SentinelArmorMode.OVERDRIVE_FLIGHT",
				"ArmorConfigurationAccess.isTransitioning(configured.snapshot(), gameTime)",
				"ArmorConfigurationAccess.allowsRangedCombat(attacker, gameTime)"
		}) {
			require(salvo.contains(authority),
					"salvo lost exact-set/form authority: " + authority);
		}
		require(salvo.contains("resolveLane(attacker, 0, false")
						&& salvo.contains("resolveLane(attacker, 1, true")
						&& salvo.contains("resolveLane(attacker, 2, false")
						&& salvo.contains("List.of(chest)")
						&& salvo.contains("List.of(chest, left, rightShot)")
						&& salvo.contains("ArmorConfigurationAccess.isWingmenTransitioning")
						&& salvo.contains("FireLayout.DOCKED_TRIPLE")
						&& salvo.contains("FireLayout.CHEST_ONLY"),
				"salvo no longer emits chest 0 alone or docked 0/1/2 lanes from server ownership");
		require(controller.contains(
						"SentinelOverdriveSalvo.fire(player, combatAnchorPose(player)")
						&& controller.contains("SentinelOverdriveSalvo.authorizedLayout(player, now)")
						&& controller.contains("ArmorAttackType.CORE_BEAM, ArmorVariant.SENTINEL")
						&& controller.contains("for (SentinelOverdriveSalvo.Shot shot"),
				"existing G CORE_BEAM protocol is not connected to the authoritative variable FX lanes");
		require(gate.contains("evaluateLifecycle(player, type, now)")
						&& gate.contains("allowsSentinelOverdriveCoreInHighSpeedFlight")
						&& gate.contains("activeType == ArmorAttackType.CORE_BEAM")
						&& gate.contains("ArmorConfigurationAccess.allowsRangedCombat(player, now)")
						&& gate.contains("FlightController.isBoostActive(player)")
						&& controller.contains("player, state.attackType, now")
						&& controller.contains("state.attackType == ArmorAttackType.CORE_BEAM")
						&& controller.contains("FlightController.isBoostActive(player)"),
				"high-speed Sentinel G lifecycle or independent Ctrl-boost cancellation regressed");
		require(salvo.contains("DestructiveLaserController.resolveCoreBeam(")
						&& destructive.contains("TerrainDestructionGuard.terrainGriefAllowed(")
						&& (terrainGuard.contains("GameRules.RULE_MOBGRIEFING") || terrainGuard.contains("GameRules.MOB_GRIEFING"))
						&& terrainGuard.contains("PlayerBlockBreakEvents.BEFORE")
						&& terrainGuard.contains("PROTECTED"),
				"wing/chest corridors bypassed bounded mobGriefing or protection hooks");
		require(resolver.contains("applySentinelOverdriveExplosion")
						&& resolver.contains("Level.ExplosionInteraction.NONE"),
				"native explosion can bypass the protected corridor with spherical block griefing");
		require(fx.contains("if (payload.eventIndex() == 0)")
						&& renderer.contains("beam.eventIndex > 0")
						&& renderer.contains("boolean cataclysm = strength >= 0.96F")
						&& renderer.contains("double laneScale = eventIndex == 0"),
				"three beams lost release-sound dedupe or first-person wing origin separation");
		require(armorRenderer.contains("boolean stableSentinelOverdrive")
						&& armorRenderer.contains("groupName.contains(\"wing_cannon_muzzle\")")
						&& armorRenderer.contains("groupName.contains(\"wing_primary_node\")")
						&& armorRenderer.contains("ArmorVariant.SENTINEL")
						&& armorRenderer.contains("whiteViolet"),
				"stable Sentinel wing cannons no longer share the chest G-charge color curve");
	}

	private static String source(Path root, String relative) throws Exception {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
