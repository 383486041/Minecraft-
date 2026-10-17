package dev.codex.flightarmor.client;

import dev.codex.flightarmor.combat.SentinelBreakerTimeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline source/resource contract for the suit terminal, dorsal arm blade and
 * Sentinel segmented back assembly. It deliberately avoids loading Minecraft
 * client classes so it can run in the normal verification JVM.
 */
public final class ClientVisualContractTest {
	private static final Path PROJECT = Path.of(
			System.getProperty("flightArmor.projectDir", ".")).toAbsolutePath().normalize();

	private ClientVisualContractTest() {
	}

	public static void main(String[] args) throws Exception {
		suitTerminalKeepsAStableReadableHierarchy();
		bladeRemainsAnArmMountedDorsalWeapon();
		sentinelUsesADedicatedHeavyWeaponForm();
		sentinelBreakerTimelineStagesMechanicalPartsBeforeEnergy();
		coreHudReusesTheAuthoritativeSnapshot();
		sentinelBackAssemblyUsesPairedSegmentedGroups();
		sentinelTransformationKeepsPhaseColorsAndSegmentMotion();
		sentinelPodsObeyTheAuthoritativeOverdriveConfiguration();
		suitPanelKeepsTheWorldVisible();
	}

	private static void suitPanelKeepsTheWorldVisible() throws IOException {
		String screen = Files.readString(Path.of(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelScreen.java"));
		require(!screen.contains("super.render(graphics, mouseX, mouseY, partialTick)"),
				"nearby suit panel must not re-enter Screen.render and blur its own telemetry");
		require((screen.contains("public void renderBackground(GuiGraphics graphics")
						|| screen.contains("public void extractBackground(GuiGraphicsExtractor graphics")),
				"nearby suit panel lost non-blurred layered renderer background marker");
		for (String marker : List.of(
				"private final class PanelButton extends AbstractButton",
				"graphics.fill(0, 0, width, height, 0x28000000)")) {
			require(screen.contains(marker),
					"nearby suit panel lost non-blurred layered renderer marker: " + marker);
		}
		require(!screen.contains("Button.builder("),
				"nearby suit panel regressed to vanilla-styled buttons");
	}

	private static void coreHudReusesTheAuthoritativeSnapshot() throws IOException {
		String hud = source(
				"src/client/java/dev/codex/flightarmor/client/core/ArmorCoreHud.java");
		String client = source(
				"src/client/java/dev/codex/flightarmor/client/FlightArmorClient.java");
		String server = source(
				"src/main/java/dev/codex/flightarmor/core/ArmorCoreController.java");
		for (String marker : List.of(
				"MAX_VISIBLE_ROWS = 4",
				"ArmorCoreClient.snapshot()",
				"snapshot.coreEquipped()",
				"snapshot.primarySuitId()",
				"entry.displayName()",
				"statusLabel(entry.status())")) {
			require(hud.contains(marker), "compact core HUD lost marker: " + marker);
		}
		require(client.contains("ArmorCoreHud.initialize()"),
				"core HUD is no longer initialized");
		require(server.contains("now % WORN_RECONCILE_INTERVAL_TICKS == 0L")
						&& server.contains("!(player.containerMenu instanceof ArmorCoreMenu)")
						&& server.contains("sendSnapshot(player);"),
				"core HUD no longer receives safe periodic authoritative snapshots");
	}

	private static void suitTerminalKeepsAStableReadableHierarchy() throws IOException {
		String source = source(
				"src/client/java/dev/codex/flightarmor/client/suitpanel/ArmorSuitPanelScreen.java");
		for (String marker : List.of(
				"PANEL_WIDTH = 520",
				"PANEL_HEIGHT = 352",
				"drawStatusStrip(graphics, accent)",
				"drawPhysicalCard(graphics)",
				"drawIntegrityCard(graphics)",
				"drawStateMatrix(graphics, accent)",
				"drawCommandCard(graphics, accent)",
				"drawKeyCell(graphics",
				"\"M\", \"形态切换\"",
				"\"R\", \"腕炮 / 准星\"",
				"\"G\", \"胸炮 / 准星\"",
				"\"X\", \"近战 / 贴地\"",
				"\"J\", \"近战大招\"",
				"\"打开统一指令中心\"",
				"OPEN_COMMAND_CENTER")) {
			require(source.contains(marker), "suit terminal lost visual/control marker: " + marker);
		}
		require(source.contains("buttonWidth = (available")
						&& !source.contains("Math.min(132,"),
				"action buttons no longer share the available row predictably");
	}

	private static void bladeRemainsAnArmMountedDorsalWeapon() throws IOException {
		String source = source(
				"src/client/java/dev/codex/flightarmor/client/render/obj/ArmMountedBladeRenderer.java");
		String worldFx = source(
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorCombatFxRenderer.java");
		String firstPerson = source(
				"src/client/java/dev/codex/flightarmor/mixin/FirstPersonWeaponRecoilMixin.java");
		String armInvoker = source(
				"src/client/java/dev/codex/flightarmor/mixin/ItemInHandRendererInvoker.java");
		require(source.contains("return progress > 0.001F && !player.isInvisible()")
						&& source.contains("!leftArm || ArmorCombatClient.bladeVariant(player) == ArmorVariant.SWIFT"),
				"both arm-mounted blades must render for every armor variant");
		for (String marker : List.of(
				"BladeCameraPose.deployment(bladeDeployment, left, bladeVariant)",
				"private static BladeCameraPose deployment(",
				"case VANGUARD -> new BladeCameraPose",
				"case SWIFT -> new BladeCameraPose",
				"case SENTINEL -> new BladeCameraPose",
				"boolean attackingArm = left == attackingLeft",
				"bladeVariant == ArmorVariant.SWIFT",
				"renderSyntheticSupportArm(",
				"player.getMainArm().getOpposite()",
				"player.getOffhandItem().isEmpty()",
				"supportLeft == attackingLeft",
				"flightArmor$renderPlayerArm(")) {
			require(firstPerson.contains(marker),
					"first-person dual-blade animation lost marker: " + marker);
		}
		require(armInvoker.contains("@Invoker(\"renderPlayerArm\")")
				&& armInvoker.contains("void flightArmor$renderPlayerArm("),
				"synthetic support arm must reuse vanilla's mirrored first-person transform");
		require(!firstPerson.contains("renderer.renderLeftHand(")
				&& !firstPerson.contains("renderer.renderRightHand("),
				"first-person support arms must never be rendered directly at the camera root");
		for (String marker : List.of(
				"FIRST_PERSON_VANGUARD",
				"WORLD_VANGUARD",
				"MountProfile.resolve(view, variant)",
				"mount.sparLengthRatio()",
				"double sparProgress",
				"renderDorsalBlade(",
				"renderBladeInsetStrip(",
				"renderBladeEdgeStrip(",
				"renderOrientedBox(metal",
				"Vec3 wrist = new Vec3(0.0D, mount.startY(), mount.startZ())",
				"Vec3 bladeSide = new Vec3(1.0D, 0.0D, 0.0D)",
				"direction.cross(new Vec3(1.0D, 0.0D, 0.0D))",
				"Vec3 left0 = center0.subtract(side.scale(width0))",
				"Vec3 right0 = center0.add(side.scale(width0))",
				"Vec3 leftOuter0",
				"Vec3 rightOuter0",
				"private record BladeShape(double[] stations, double[] widths, double thickness)",
				"VertexFormat.Mode.QUADS")) {
			require(source.contains(marker) || (marker.equals("VertexFormat.Mode.QUADS") && source.contains("RenderTypes.debugQuads()")),
					"dorsal blade lost mechanical/profile marker: " + marker);
		}
		require(source.contains("new MountProfile(13.6D")
						&& source.contains("new MountProfile(21.0D")
						&& source.contains("8.72D, -3.52D, 0.999D, -0.045D, 0.56D"),
				"first-person and world light-blade profiles were collapsed");
		require(!source.contains("shape.curves()")
						&& !source.contains("Math.min(width0 * 0.18D, 0.055D)"),
				"light blade drifted off the hand-back centerline into a one-sided held knife");
		require(!source.contains("FIRST_PERSON_SENTINEL")
						&& !source.contains("WORLD_SENTINEL")
						&& !source.contains("new MountProfile(19.2D"),
				"Sentinel regressed to a scaled generic light-blade profile");
		require(!source.contains("VertexFormat.Mode.TRIANGLES"),
				"arm blade regressed to a broad triangle effect plane");
		require(!source.contains("renderBladeVolume(")
						&& !source.contains("BladeShape coreShape")
						&& !source.contains("private static final Rgba WHITE"),
				"light blade regressed to a double-sided near-white energy wedge");
		require(count(worldFx, "renderDeployedBlade(") <= 1,
				"obsolete world-space palm blade was reconnected alongside the arm-local layer");
		requireMetalBeforeEnergy(methodSection(source,
				"private static void renderDorsalBlade(",
				"private static void renderSentinelBreaker("), "dorsal light blade");
	}

	private static void sentinelUsesADedicatedHeavyWeaponForm() throws IOException {
		String source = source(
				"src/client/java/dev/codex/flightarmor/client/render/obj/ArmMountedBladeRenderer.java");
		String armorRenderer = source(
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java");
		for (String marker : List.of(
				"if (variant == ArmorVariant.SENTINEL)",
				"renderSentinelBreaker(",
				"FIRST_PERSON_BREAKER",
				"WORLD_BREAKER",
				"FIRST_PERSON_BREAKER_SHAPE",
				"WORLD_BREAKER_SHAPE",
				"SentinelBreakerTimeline.sample(progress)",
				"renderReceiverSleeveSegment(",
				"receiverRootReveal",
				"receiverMiddleReveal",
				"receiverFrontReveal",
				"jawReveal",
				"jawRear.lerp(jawTarget, easeOut(jawReveal))",
				"carriageReveal",
				"(0.46D + carriageTravel) * carriageEase",
				"renderBreakerSpineSegment(",
				"renderBreakerEnergyField(",
				"renderBreakerEdgeInset(",
				"BREAKER_EDGE_BAND_WIDTH = 0.14D",
				"BREAKER_LOCK = new Rgba(220, 252, 255, 236)",
				"profile.mechanicalSpineRatio()",
				"rotateAroundAxis(",
				"private record BreakerShape(double[] stations, double[] centerOffsets, double[] halfWidths",
				"private record BreakerSection(double station, double centerOffset, double halfWidth)",
				"section0.centerOffset()",
				"section1.centerOffset()",
				"Vec3 mechanicalSide = receiverSide",
				"Vec3 receiverNormal = receiverSide.cross(receiverDirection).normalize()",
				"Vec3 mechanicalNormal = mechanicalSide.cross(bladeDirection).normalize()",
				"Vec3 energySide = rotateAroundAxis(mechanicalSide, bladeDirection",
				"Vec3 energyNormal = energySide.cross(bladeDirection).normalize()",
				"bladeDirection, mechanicalSide, mechanicalNormal",
				"bladeDirection, energySide, energyNormal, profile.bladeLength()",
				"center0.subtract(side.scale(section0.halfWidth()))",
				"center0.add(side.scale(section0.halfWidth()))",
				"Sentinel uses its dedicated breaker renderer")) {
			require(source.contains(marker), "Sentinel breaker lost dedicated geometry marker: " + marker);
		}
		String firstPersonProfile = methodSection(source,
				"private static final BreakerProfile FIRST_PERSON_BREAKER =",
				"private static final BreakerProfile WORLD_BREAKER =");
		String worldProfile = methodSection(source,
				"private static final BreakerProfile WORLD_BREAKER =",
				"private ArmMountedBladeRenderer()");
		String firstPersonShape = methodSection(source,
				"private static final BreakerShape FIRST_PERSON_BREAKER_SHAPE =",
				"private static final BreakerShape WORLD_BREAKER_SHAPE =");
		String worldShape = methodSection(source,
				"private static final BreakerShape WORLD_BREAKER_SHAPE =",
				"private static final BreakerProfile FIRST_PERSON_BREAKER =");
		double[] fpShape = sourceDoubles(firstPersonShape);
		double[] worldShapeValues = sourceDoubles(worldShape);
		double[] fpProfile = sourceDoubles(firstPersonProfile);
		double[] worldProfileValues = sourceDoubles(worldProfile);
		requireNumbers(fpShape, new double[] {
				0.0D, 0.08D, 0.18D, 0.28D, 0.68D, 0.82D, 0.94D, 1.0D,
				0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
				0.16D, 0.34D, 0.72D, 1.00D, 1.00D, 0.74D, 0.32D, 0.0D,
				0.046D
		}, "first-person dorsal breaker shape");
		requireNumbers(worldShapeValues, new double[] {
				0.0D, 0.08D, 0.18D, 0.28D, 0.68D, 0.82D, 0.94D, 1.0D,
				0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
				0.176D, 0.374D, 0.792D, 1.100D, 1.100D, 0.814D, 0.352D, 0.0D,
				0.054D
		}, "world dorsal breaker shape");
		requireNumbers(fpProfile, new double[] {
				12.8D, 0.25D, 2.15D, 0.34D, 0.38D, 0.14D, -1.0D,
				6.25D, 3.10D, 9.65D, 3.20D, 0.930D, -0.367D, 24.0D, 0.42D
		}, "first-person breaker profile");
		requireNumbers(worldProfileValues, new double[] {
				15.8D, 0.25D, 2.25D, 0.38D, 0.42D, 0.16D, -1.0D,
				6.20D, 3.15D, 9.90D, 3.30D, 0.985D, -0.174D, -1.0D, 0.44D
		}, "world breaker profile");
		require(fpProfile[0] <= 13.0D, "first-person breaker is too long");
		require(Math.abs(fpProfile[12]) <= 0.60D,
				"first-person breaker points too far sideways");
		require(fpProfile[14] <= 0.45D,
				"first-person mechanical spine extends too far past the forearm");
		require(fpShape[16] <= 0.22D,
				"first-person breaker root is too wide to emerge from the emitter slot");
		require(fpProfile[9] <= 9.80D && fpProfile[1] <= 0.30D,
				"first-person breaker root no longer begins over the hand back");
		require(fpProfile[8] > 0.0D && fpProfile[10] > 0.0D
						&& worldProfileValues[8] > 0.0D && worldProfileValues[10] > 0.0D,
				"Sentinel breaker receiver must remain anchored to the positive-Z hand back");
		require(!source.contains("renderBreakerPrism(")
						&& !source.contains("private static final Rgba WHITE")
						&& !source.contains("BreakerShape coreShape"),
				"Sentinel regressed to a broad solid or white breaker wedge");
		String breaker = methodSection(source,
				"private static void renderSentinelBreaker(",
				"private static void renderBreakerSpineSegment(");
		requireMetalBeforeEnergy(breaker, "Sentinel breaker");
		require(breaker.contains("receiverDirection.scale((0.46D + carriageTravel) * carriageEase)")
						&& !breaker.contains("bladeDirection.scale((0.46D + carriageTravel) * carriageEase)"),
				"Sentinel carriage must slide along the hand back before the blade changes axis");
		int fieldConsumer = breaker.indexOf(
				"buffers.getBuffer(ArmorEnergyRenderType.FIELD_ALPHA)");
		int additiveConsumer = breaker.indexOf(
				"buffers.getBuffer(ArmorEnergyRenderType.INSTANCE)");
		require(fieldConsumer > 0 && additiveConsumer > fieldConsumer,
				"Sentinel breaker must submit alpha field before additive node/edges");
		String receiverSleeve = methodSection(source,
				"private static void renderReceiverSleeveSegment(",
				"private static void renderBreakerSpineSegment(");
		require(receiverSleeve.contains("if (reveal <= 0.001F) return;")
						&& receiverSleeve.contains("easeOut(reveal)"),
				"Sentinel receiver segments must reveal progressively instead of popping in");
		String breakerNode = methodSection(source,
				"private static void renderBreakerNode(",
				"private static BreakerSection breakerSectionAt(");
		require(breakerNode.contains("if (reveal <= 0.001F) return;")
						&& breakerNode.contains("halfWidth * revealScale")
						&& breakerNode.contains("0.22D * revealScale"),
				"Sentinel emitter node must grow with carriage deployment");
		String energyFace = methodSection(source,
				"private static void renderBreakerEnergyField(",
				"private static void renderBreakerEdgeInset(");
		require(count(energyFace, "emitQuad(") == 1
						&& !energyFace.contains("renderOrientedBox(")
						&& !energyFace.contains("WHITE"),
				"Sentinel energy face must remain one translucent non-solid surface");
		String energyInset = methodSection(source,
				"private static void renderBreakerEdgeInset(",
				"private static void renderBreakerNode(");
		require(count(energyInset, "emitQuad(") == 2
						&& energyInset.contains("BREAKER_EDGE_BAND_WIDTH")
						&& !energyInset.contains("subtract(depth)")
						&& !energyInset.contains("WHITE"),
				"Sentinel breaker must retain two narrow, non-white edge bands");

		for (String marker : List.of(
				"import dev.codex.flightarmor.combat.SentinelBreakerTimeline",
				"applySentinelBladeDeploymentTransform(",
				"SentinelBreakerTimeline.Sample deployment",
				"name.contains(\"cover_outer\")",
				"name.contains(\"cover_inner\")",
				"name.contains(\"hand_shell_outer\")",
				"name.contains(\"hand_shell_inner\")",
				"name.contains(\"emitter_shutter\")",
				"name.contains(\"wrist_lock\")",
				"name.contains(\"emitter_track\")",
				"name.contains(\"forearm_underlayer\")",
				"rotateAroundFixedPivot(poseStack",
				"left ? 3.08F : -3.08F",
				"-side * 18.0F * jawOpen",
				"0.34F * jawOpen, 0.38F * carriage",
				"0.24F * jawOpen, 0.34F * carriage",
				"0.78F * carriage, 0.22F * jawOpen")) {
				require(armorRenderer.contains(marker),
						"Sentinel forearm receiver lost V3 transform marker: " + marker);
		}
		String sentinelReceiverTransform = methodSection(armorRenderer,
				"private static void applySentinelBladeDeploymentTransform(",
				"private static float staged(");
		require(!sentinelReceiverTransform.contains("-2.30F"),
				"the hand-back forearm brace must remain fixed while only the new cover opens");
		for (String marker : List.of(
				"boolean forearmWeaponLed = groupName.contains(\"forearm_energy_trace\")",
				"groupName.contains(\"forearm_node\")",
				"groupName.contains(\"emitter_track\")",
				"if (forearmWeaponLed)",
				"if (!forearmWeaponLed) return baseColor",
				"case SENTINEL -> new ArmorObjMesh.Color(38, 238, 255, 255)")) {
			require(armorRenderer.contains(marker),
					"Sentinel emitter-track lighting lost blade/recoil coupling: " + marker);
		}
		String energyTypes = source(
				"src/client/java/dev/codex/flightarmor/client/combat/ArmorEnergyRenderType.java");
		require(energyTypes.contains("public static final RenderType FIELD_ALPHA")
						&& energyTypes.contains("public static final RenderType INSTANCE"),
				"armor energy render layers lost alpha/additive bootstrap contract");
	}

	private static void sentinelBreakerTimelineStagesMechanicalPartsBeforeEnergy() {
		SentinelBreakerTimeline.Sample start = SentinelBreakerTimeline.sample(0.0F);
		SentinelBreakerTimeline.Sample receiver = SentinelBreakerTimeline.sample(0.28F);
		SentinelBreakerTimeline.Sample spine = SentinelBreakerTimeline.sample(0.56F);
		SentinelBreakerTimeline.Sample surface = SentinelBreakerTimeline.sample(0.64F);
		SentinelBreakerTimeline.Sample edge = SentinelBreakerTimeline.sample(0.80F);
		SentinelBreakerTimeline.Sample complete = SentinelBreakerTimeline.sample(1.0F);

		require(start.unlock() == 0.0F && start.energyEdge() == 0.0F,
				"Sentinel breaker must start fully stowed");
		require(receiver.jawOpen() > 0.0F && receiver.carriage() > 0.0F
						&& receiver.energyEdge() == 0.0F,
				"receiver and armor jaws must deploy before the energy edge");
		require(spine.spineBase() > spine.spineMiddle()
						&& spine.spineMiddle() > spine.spineTip()
						&& spine.energyEdge() == 0.0F,
				"three load-bearing spine stages lost their base-to-tip order");
		require(surface.breakerHead() > 0.0F && surface.energyEdge() == 0.0F,
				"translucent energy face must appear before either cutting edge intensifies");
		require(edge.breakerHead() > 0.0F && edge.energyEdge() > 0.0F,
				"cutting edges never followed the projected energy face");
		require(complete.unlock() == 1.0F && complete.breakerHead() == 1.0F
						&& complete.energyEdge() == 1.0F && complete.lock() == 1.0F,
				"Sentinel breaker never reaches its mechanically locked final form");
	}

	private static void sentinelBackAssemblyUsesPairedSegmentedGroups() throws IOException {
		Path objPath = PROJECT.resolve(
				"src/main/resources/assets/flight_armor/models/armor/flight_armor_sentinel.obj");
		List<String> groups = Files.readAllLines(objPath, StandardCharsets.UTF_8).stream()
				.filter(line -> line.startsWith("g vane_") && line.contains("_sentinel_"))
				.map(line -> line.substring(2).trim())
				.toList();
		require(groups.size() == 38,
				"Sentinel back assembly must keep nineteen independently named groups per side");
		for (String side : List.of("left", "right")) {
			for (String group : List.of(
					"wing_root_shell_titanium",
					"wing_root_frame_gunmetal",
					"wing_primary_shell_titanium",
					"wing_primary_frame_gunmetal",
					"wing_secondary_shell_titanium",
					"wing_secondary_frame_gunmetal",
					"wing_tip_shell_titanium",
					"wing_tip_frame_gunmetal",
					"drone_pod_shell_titanium",
					"drone_pod_frame_gunmetal",
					"wing_cannon_housing_titanium",
					"wing_cannon_barrel_gunmetal",
					"wing_cannon_muzzle_led",
					"wing_root_node_led",
					"wing_primary_node_led",
					"wing_secondary_node_led",
					"wing_tip_node_led",
					"drone_pod_node_led",
					"drone_pod_status_led")) {
				require(groups.contains("vane_" + side + "_sentinel_" + group),
						"Sentinel back assembly lost paired group: " + side + "/" + group);
			}
		}
	}

	private static void sentinelTransformationKeepsPhaseColorsAndSegmentMotion() throws IOException {
		String source = source(
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java");
		for (String marker : List.of(
				"applySentinelConfigurationTransform(",
				"sentinelOverdriveProgress(entity, animationTime)",
				"float tier = sentinelWingTier(name)",
				"float roll = Mth.lerp(tier, -58.0F, -108.0F)",
				"float direction = left ? 1.0F : right ? -1.0F : 0.0F",
				"ArmorConfigurationAccess.TRANSITION_DURATION_TICKS",
				"float headPhase = smoothstep(0.02F, 0.48F, streamline)",
				"float torsoPhase = smoothstep(0.08F, 0.64F, streamline)",
				"float armPhase = smoothstep(0.14F, 0.74F, streamline)",
				"float legPhase = smoothstep(0.24F, 0.86F, streamline)",
				"float bootPhase = smoothstep(0.32F, 0.94F, streamline)",
				"name.contains(\"waist_apron\")",
				"boolean knee = name.contains(\"_knee_\")",
				"boolean toe = name.contains(\"toe_ram\")",
				"name.contains(\"spine_sentinel_segment\")",
				"name.contains(\"wing_cannon_muzzle\")",
				"name.contains(\"drone_pod_status\")",
				"ArmorObjMesh.Color phaseAwareIdle = mixColor(base, idle, 0.34F)")) {
			require(source.contains(marker), "Sentinel visual state lost marker: " + marker);
		}
	}

	private static void sentinelPodsObeyTheAuthoritativeOverdriveConfiguration() throws IOException {
		String renderer = source(
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java");
		for (String marker : List.of(
				"applySentinelDronePodTransform(",
				"ArmorConfigurationAccess.configuredSentinel(entity)",
				"if (!(entity instanceof Player)) return true;",
				"name.contains(\"sentinel_wing_secondary\")",
				"name.contains(\"sentinel_wing_cannon\")",
				"ArmorConfigurationAccess.wingmenEntityOwnsGeometry",
				"WINGMEN_HANDOFF_START_TICKS")) {
			require(renderer.contains(marker), "Sentinel pod detach visual lost marker: " + marker);
		}
		require(!renderer.contains("float safeScale = Math.max(0.001F, visibility)")
						&& !renderer.contains(
								"scaleAround(poseStack, group, safeScale, safeScale, safeScale)"),
				"detachable modules must switch ownership at full size instead of fading/scaling");
		require(!renderer.contains("SentinelPodAnimation"),
				"body renderer must not animate fake drone followers after real wingmen exist");
	}

	private static String source(String relative) throws IOException {
		Path path = PROJECT.resolve(relative).normalize();
		require(path.startsWith(PROJECT), "source path escaped project root");
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static String methodSection(String source, String startMarker, String endMarker) {
		int start = source.indexOf(startMarker);
		int end = source.indexOf(endMarker, start + startMarker.length());
		require(start >= 0 && end > start,
				"unable to isolate source section: " + startMarker + " -> " + endMarker);
		return source.substring(start, end);
	}

	private static double[] sourceDoubles(String source) {
		Matcher matcher = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?D").matcher(source);
		List<Double> values = new ArrayList<>();
		while (matcher.find()) {
			values.add(Double.parseDouble(
					matcher.group().substring(0, matcher.group().length() - 1)));
		}
		return values.stream().mapToDouble(Double::doubleValue).toArray();
	}

	private static void requireNumbers(double[] actual, double[] expected, String label) {
		require(actual.length == expected.length,
				label + " value count changed: " + actual.length + " != " + expected.length);
		for (int index = 0; index < expected.length; index++) {
			require(Math.abs(actual[index] - expected[index]) <= 1.0E-9D,
					label + " value " + index + " changed: " + actual[index]
							+ " != " + expected[index]);
		}
	}

	private static void requireMetalBeforeEnergy(String source, String label) {
		int additiveConsumer = source.indexOf(
				"VertexConsumer energy = buffers.getBuffer(ArmorEnergyRenderType.INSTANCE)");
		int fieldConsumer = source.indexOf(
				"buffers.getBuffer(ArmorEnergyRenderType.FIELD_ALPHA)");
		int energyConsumer = additiveConsumer < 0 ? fieldConsumer
				: fieldConsumer < 0 ? additiveConsumer
				: Math.min(additiveConsumer, fieldConsumer);
		int lastMetalWrite = Math.max(
				source.lastIndexOf("renderOrientedBox(metal"),
				Math.max(source.lastIndexOf("renderReceiverSleeveSegment(metal"),
						source.lastIndexOf("renderBreakerSpineSegment(metal")));
		require(energyConsumer > 0 && lastMetalWrite > 0 && lastMetalWrite < energyConsumer,
				label + " would close the metal consumer before its final write");
	}

	private static int count(String source, String marker) {
		int total = 0;
		int cursor = 0;
		while ((cursor = source.indexOf(marker, cursor)) >= 0) {
			total++;
			cursor += marker.length();
		}
		return total;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
