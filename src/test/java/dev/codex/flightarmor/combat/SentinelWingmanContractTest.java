package dev.codex.flightarmor.combat;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline contract gate for real Sentinel wingman entities and hypersonic formation following. */
public final class SentinelWingmanContractTest {
	private SentinelWingmanContractTest() {
	}

	public static void main(String[] args) throws Exception {
		hypersonicOwnerVelocityIsNeverClamped();
		relativeCorrectionRemainsBounded();
		entityLifecycleIsServerAuthoritativeAndTransient();
		hKeyPrioritizesTheExactWornOverdriveSuit();
	}

	private static void hypersonicOwnerVelocityIsNeverClamped() {
		SentinelWingmanKinematics.Vector owner =
				new SentinelWingmanKinematics.Vector(36.0D, 0.0D, 0.0D);
		SentinelWingmanKinematics.Vector next = SentinelWingmanKinematics.steer(
				owner, new SentinelWingmanKinematics.Vector(0.8D, 0.1D, -0.2D), owner);
		require(next.x() > 35.9D,
				"full owner velocity feed-forward was clamped at hypersonic speed: " + next);
		SentinelWingmanKinematics.Vector errorAfterOneTick =
				new SentinelWingmanKinematics.Vector(0.8D, 0.1D, -0.2D)
						.add(next.add(owner.scale(-1.0D)).scale(-1.0D));
		require(errorAfterOneTick.length() < 1.0D,
				"relative formation error diverged at 36 blocks/tick: " + errorAfterOneTick);
		require(errorAfterOneTick.length() < 72.0D,
				"ordinary hypersonic following would trigger hard reposition");
	}

	private static void relativeCorrectionRemainsBounded() {
		SentinelWingmanKinematics.Vector owner =
				new SentinelWingmanKinematics.Vector(42.0D, 3.0D, -4.0D);
		SentinelWingmanKinematics.Vector next = SentinelWingmanKinematics.steer(
				SentinelWingmanKinematics.Vector.ZERO,
				new SentinelWingmanKinematics.Vector(50.0D, 10.0D, -20.0D), owner);
		double relative = next.add(owner.scale(-1.0D)).length();
		require(relative <= SentinelWingmanKinematics.MAXIMUM_RELATIVE_CORRECTION_SPEED + 1.0E-9D,
				"relative correction exceeded its cap: " + relative);
		require(next.length() > SentinelWingmanKinematics.MAXIMUM_RELATIVE_CORRECTION_SPEED,
				"absolute speed was incorrectly capped by the correction limit");
	}

	private static void entityLifecycleIsServerAuthoritativeAndTransient() throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String entity = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/combat/SentinelWingmanEntity.java"));
		String controller = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/combat/SentinelWingmanController.java"));
		String registration = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/combat/ModCombatEntities.java"));
		String client = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java"));
		String renderer = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/combat/SentinelWingmanRenderer.java"));
		String armorRenderer = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java"));
		String moduleMesh = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/render/obj/SentinelWingModuleMesh.java"));
		String configurationAccess = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/configuration/ArmorConfigurationAccess.java"));
		int clientVisualStart = entity.indexOf("private void tickClientVisualState()");
		int clientVisualEnd = entity.indexOf("private Vec3 formationAnchor", clientVisualStart);
		String clientVisualMethod = clientVisualStart >= 0 && clientVisualEnd > clientVisualStart
				? entity.substring(clientVisualStart, clientVisualEnd) : "";

		require(entity.contains("extends Entity")
						&& (entity.contains("EntityDataSerializers.OPTIONAL_UUID") || entity.contains("EntityDataSerializers.STRING"))
						&& entity.contains("EntityDataSerializers.LONG")
						&& entity.contains("public boolean shouldBeSaved()")
						&& entity.contains("return false;"),
				"wingman identity must be synced, real and derived rather than world-persistent");
		require((entity.contains("if (level().isClientSide)") || entity.contains("if (level().isClientSide())"))
						&& entity.contains("tickClientVisualState()")
						&& entity.contains("getClientVisualYaw(float partialTick)")
						&& entity.contains("getClientVisualPitch(float partialTick)")
						&& entity.contains("SentinelWingmanController.isDeploymentAuthorized")
						&& entity.contains("SentinelWingmanController.isCombatAuthorized")
						&& entity.contains("CombatTargetPolicy.canDamage")
						&& entity.contains("ArmorCoreController.escortProfile")
						&& entity.contains("ArmorEscortProfile.CombatPolicy.OFF")
						&& entity.contains("ArmorEscortProfile.CombatPolicy.ALL")
						&& entity.contains("retaliationTargetId")
						&& entity.contains("PlayerLookup.tracking(this)"),
				"movement, core policy, target filtering, damage and FX must remain authoritative");
		require(!clientVisualMethod.isEmpty()
						&& !clientVisualMethod.contains("setPos(")
						&& !clientVisualMethod.contains("setDeltaMovement(")
						&& !clientVisualMethod.contains("setYRot(")
						&& registration.contains(".updateInterval(1)")
						&& entity.contains("getClientVisualRenderOffset(float partialTick)")
						&& renderer.contains("wingman.getClientVisualYaw(partialTick)")
						&& renderer.contains("wingman.getClientVisualPitch(partialTick)")
						&& renderer.contains("wingman.getClientVisualRenderOffset(partialTick)")
						&& !renderer.contains("wingman.getDeltaMovement()"),
				"client wingman rendering must smooth server observations without a second movement authority");
		require((entity.contains("getUUID(), level.dimension().location(), getShotSequence()")
						|| entity.contains("getUUID(), level.dimension().identifier(), getShotSequence()"))
						&& entity.contains("0, ArmorAttackType.CRUISE_CORE_BEAM"),
				"wingman FX identity must not collide with the worn player's combat lane");
		require(entity.contains("RECOMBINE_DURATION_TICKS = 30L")
						&& entity.contains("beginRecombine")
						&& entity.contains("resumeDeployment")
						&& entity.contains("dockingAnchor(owner)")
						&& entity.contains("PHASE_START_TICK")
						&& entity.contains("PHASE_DEPLOYING")
						&& entity.contains("PHASE_RETURNING"),
				"mode changes must deploy from and recombine into the back slot over time");
		require(renderer.contains("DEPLOY_HANDOFF_START_TICKS")
						&& renderer.contains("DEPLOY_EJECT_END_TICKS")
						&& renderer.contains("RECOMBINE_SHRINK_START_TICKS")
						&& !renderer.contains("poses.scale(deployment, deployment, deployment)")
						&& renderer.contains("SentinelWingModuleMesh.render(")
						&& renderer.contains("ArmorConfigurationAccess.wingmenEntityOwnsGeometry(")
						&& !renderer.contains("emitBox(")
						&& !renderer.contains("emitPlate(")
						&& !renderer.contains("emitWedge(")
						&& moduleMesh.contains("sentinel_wing_secondary")
						&& moduleMesh.contains("sentinel_wing_tip")
						&& moduleMesh.contains("sentinel_drone_pod")
						&& moduleMesh.contains("sentinel_wing_cannon")
						&& moduleMesh.contains("applyWornSocketArticulation")
						&& moduleMesh.contains("FLIGHT_CENTER_X = 5.10F")
						&& moduleMesh.contains("SECONDARY_HINGE_X = 3.51F")
						&& moduleMesh.contains("TIP_HINGE_X = 3.51F")
						&& moduleMesh.contains("POD_HINGE_X = 3.78F")
						&& moduleMesh.contains("CANNON_HINGE_X = 4.42F")
						&& !moduleMesh.contains("31.0F")
						&& !moduleMesh.contains("group.centerX()")
						&& moduleMesh.contains("new ArmorObjMesh.Color(96, 113, 126, 255)")
						&& armorRenderer.contains("if (!(entity instanceof Player)) return true;")
						&& armorRenderer.contains("sentinel_wing_secondary")
						&& armorRenderer.contains("sentinel_wing_cannon")
						&& armorRenderer.contains("wingmenEntityOwnsGeometry")
						&& armorRenderer.contains("WINGMEN_HANDOFF_START_TICKS")
						&& armorRenderer.contains(
								"Mth.lerp(wingmanFold, foldedSpan, 1.0F)")
						&& !armorRenderer.contains("Math.max(0.001F, visibility)")
						&& configurationAccess.contains("wingmenEntityOwnsGeometry")
						&& configurationAccess.contains(
								"elapsed >= WINGMEN_HANDOFF_START_TICKS")
						&& configurationAccess.contains("elapsed < 22.0D")
						&& configurationAccess.contains(
								"wingmenEntityOwnsGeometry(snapshot, gameTime) ? 1.0F : 0.0F")
						&& !configurationAccess.contains("Mth.lerp(handoff, start, target)"),
				"the same opaque OBJ module geometry must switch binary ownership without a fade");
		int shellConsumer = moduleMesh.indexOf("VertexConsumer shell = buffers.getBuffer(");
		int shellWrite = moduleMesh.indexOf(
				"mesh.render(group, poses.last(), shell, packedLight");
		int emissiveConsumer = moduleMesh.indexOf(
				"VertexConsumer emissive = buffers.getBuffer(");
		int emissiveWrite = moduleMesh.indexOf(
				"mesh.render(group, poses.last(), emissive, LightTexture.FULL_BRIGHT");
		boolean legacyPass = shellConsumer >= 0 && shellWrite > shellConsumer
						&& emissiveConsumer > shellWrite && emissiveWrite > emissiveConsumer;
		int shellSubmit = moduleMesh.indexOf("RenderTypes.armorCutoutNoCull(");
		int emissiveSubmit = moduleMesh.indexOf("RenderTypes.entityTranslucentEmissive(");
		boolean modernSubmitPass = shellSubmit >= 0 && emissiveSubmit > shellSubmit
				&& moduleMesh.contains("(pose, shell) -> mesh.render(group, pose, shell, packedLight")
				&& moduleMesh.contains("(pose, emissive) -> mesh.render(group, pose, emissive, FULL_BRIGHT");
		require(legacyPass || modernSubmitPass,
				"OBJ shell pass must finish before switching BufferSource to the emissive pass");
		require(controller.contains("SentinelArmorMode.OVERDRIVE_FLIGHT")
						&& controller.contains("configured.suitId()")
						&& controller.contains("payload.expectedSuitId()")
						&& controller.contains("boolean deploy = !snapshot.wingmenDeployed()")
						&& controller.contains("configured.snapshot().wingmenDeployed()")
						&& controller.contains("Spawn at command start")
						&& controller.contains("sorted(Comparator.comparing(Entity::getUUID))")
						&& controller.contains("LEFT_SIDE")
						&& controller.contains("RIGHT_SIDE"),
				"controller must authorize one deterministic entity per exact owner/suit/side key");
		require(registration.contains("SENTINEL_WINGMAN")
						&& registration.contains("sentinel_wingman")
						&& client.contains("SentinelWingmanRenderer::new"),
				"wingman entity and client renderer are not both registered");
	}

	private static void hKeyPrioritizesTheExactWornOverdriveSuit() throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."));
		String payload = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/network/SentinelWingmanCommandPayload.java"));
		String client = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/cruise/CruiseCombatClient.java"));
		String mod = Files.readString(root.resolve(
				"src/main/java/dev/codex/flightarmor/FlightArmorMod.java"));
		String zh = Files.readString(root.resolve(
				"src/main/resources/assets/flight_armor/lang/zh_cn.json"));
		String en = Files.readString(root.resolve(
				"src/main/resources/assets/flight_armor/lang/en_us.json"));

		require(payload.contains("UUID expectedSuitId")
						&& payload.contains("writeUUID(payload.expectedSuitId())")
						&& payload.contains("buffer.readUUID()")
						&& !payload.contains("boolean deploy"),
				"Sentinel H intent must carry an exact expected suit id, not a client-chosen state");
		int sentinelBranch = client.indexOf(
				"ArmorConfigurationAccess.configuredSentinel(client.player)");
		int sentinelSend = client.indexOf(
				"ClientPlayNetworking.send(new SentinelWingmanCommandPayload");
		int cruiseBranch = client.indexOf(
				"ArmorCruiseClient.CruiseState cruise = ArmorCruiseClient.stateForOwner");
		int cruiseSend = client.indexOf(
				"ClientPlayNetworking.send(new ArmorCruiseCombatCommandPayload");
		require(sentinelBranch >= 0
						&& client.contains("SentinelArmorMode.OVERDRIVE_FLIGHT")
						&& client.contains("!ArmorConfigurationAccess.isTransitioning")
						&& client.contains("client.player.getUUID().equals(sentinel.ownerId())")
						&& client.contains("clientSequence, sentinel.suitId()")
						&& client.contains("ArmorAssemblyClient.captureOperationThisTick(client)")
						&& sentinelSend > sentinelBranch
						&& cruiseBranch > sentinelSend
						&& cruiseSend > cruiseBranch,
				"stable worn Sentinel overdrive must claim H before the unchanged cruise-guard branch");
		require((mod.contains("PayloadTypeRegistry.playC2S().register(SentinelWingmanCommandPayload.TYPE")
						|| mod.contains("PayloadTypeRegistry.serverboundPlay().register(SentinelWingmanCommandPayload.TYPE"))
						&& mod.contains("ServerPlayNetworking.registerGlobalReceiver("
								+ "SentinelWingmanCommandPayload.TYPE")
						&& mod.contains("SentinelWingmanController.handleCommand("
								+ "context.player(), payload)"),
				"Sentinel H payload is not registered and routed to the server authority");
		for (String language : new String[] { zh, en }) {
			require(language.contains("\"message.flight_armor.wingmen_busy\"")
							&& language.contains("\"message.flight_armor.wingmen_deploying\"")
							&& language.contains("\"message.flight_armor.wingmen_recalling\"")
							&& language.contains("\"key.flight_armor.cruise_guard\"")
							&& language.contains("\"tooltip.flight_armor.controls\""),
					"Sentinel H feedback or contextual help is missing from a language file");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
