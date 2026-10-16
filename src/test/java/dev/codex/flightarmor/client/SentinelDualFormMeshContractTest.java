package dev.codex.flightarmor.client;

import dev.codex.flightarmor.configuration.SentinelVisualZone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline geometry/renderer contract for Sentinel's two complete suit configurations. */
public final class SentinelDualFormMeshContractTest {
	private SentinelDualFormMeshContractTest() {
	}

	public static void main(String[] args) throws Exception {
		Path root = Path.of(System.getProperty("flightArmor.projectDir", "."))
				.toAbsolutePath().normalize();
		Path armor = root.resolve("src/main/resources/assets/flight_armor/models/armor");
		ObjSummary sentinel = read(armor.resolve("flight_armor_sentinel.obj"));
		ObjSummary vanguard = read(armor.resolve("flight_armor_vanguard.obj"));

		for (String group : List.of(
				"head_faceplate_sentinel_crown_bastion_titanium",
				"body_sentinel_chest_bastion_titanium",
				"body_sentinel_abdominal_bulwark_titanium",
				"body_sentinel_waist_apron_titanium",
				"arm_left_sentinel_upper_bastion_titanium",
				"arm_right_sentinel_forearm_bulwark_titanium",
				"leg_left_sentinel_thigh_bastion_titanium",
				"leg_right_sentinel_knee_lock_titanium",
				"boot_left_sentinel_calf_bastion_titanium",
				"boot_right_sentinel_toe_ram_titanium",
				"core_reactor_sentinel_led")) {
			require(sentinel.groups().contains(group), "Sentinel lost independent shell group: " + group);
		}
		for (String part : List.of(
				"cover_outer_titanium",
				"cover_inner_titanium",
				"hand_shell_outer_titanium",
				"hand_shell_inner_titanium",
				"wrist_lock_gunmetal",
				"emitter_shutter_gunmetal",
				"emitter_track_led")) {
			String group = "arm_right_sentinel_forearm_" + part;
			assertAuthoredForearmWeaponShell(sentinel, group);
			assertZone(SentinelVisualZone.ARM, group);
			require(!sentinel.groups().contains("arm_left_sentinel_forearm_" + part),
					"Sentinel must keep its heavy breaker on the right arm only: " + part);
		}
		require(!sentinel.groups().contains("arm_right_sentinel_forearm_node_led"),
				"Sentinel right arm retained the superseded palm-side weapon light");

		assertZone(SentinelVisualZone.HEAD,
				"head_faceplate_sentinel_crown_bastion_titanium");
		assertZone(SentinelVisualZone.HEAD, "head_faceplate_gunmetal");
		assertZone(SentinelVisualZone.HEAD, "visor_lens_visor");
		assertZone(SentinelVisualZone.HEAD, "head_rear_left_gunmetal");
		assertZone(SentinelVisualZone.HEAD, "head_rear_right_gunmetal");
		assertZone(SentinelVisualZone.TORSO,
				"body_sentinel_chest_bastion_titanium");
		assertZone(SentinelVisualZone.TORSO,
				"body_sentinel_waist_apron_titanium");
		assertZone(SentinelVisualZone.ARM,
				"arm_left_sentinel_upper_bastion_titanium");
		assertZone(SentinelVisualZone.ARM,
				"arm_right_sentinel_forearm_bulwark_titanium");
		assertZone(SentinelVisualZone.LEG,
				"leg_left_sentinel_thigh_bastion_titanium");
		assertZone(SentinelVisualZone.LEG,
				"leg_right_sentinel_knee_lock_titanium");
		assertZone(SentinelVisualZone.BOOT,
				"boot_left_sentinel_calf_bastion_titanium");
		assertZone(SentinelVisualZone.BOOT,
				"boot_right_sentinel_toe_ram_titanium");
		assertZone(SentinelVisualZone.WING,
				"vane_left_sentinel_wing_primary_shell_titanium");
		assertZone(SentinelVisualZone.DRONE_POD,
				"vane_right_sentinel_drone_pod_shell_titanium");

		for (String forbidden : List.of(
				"body_chest_titanium",
				"body_abdomen_titanium",
				"arm_left_upper_pauldron_titanium",
				"arm_right_forearm_plate_titanium",
				"leg_left_thigh_plate_titanium",
				"boot_right_calf_plate_titanium")) {
			require(!sentinel.groups().contains(forbidden),
					"Sentinel regressed to a visible Vanguard shell: " + forbidden);
		}

		Set<String> shared = new HashSet<>(sentinel.groups());
		shared.retainAll(vanguard.groups());
		require(shared.size() <= 20,
				"Sentinel shares too many rendered groups with Vanguard: " + shared.size());
		long authored = sentinel.groups().stream().filter(name -> name.contains("_sentinel_")).count();
		require(authored >= 90, "Sentinel needs a predominantly independent authored shell");

		for (String side : List.of("left", "right")) {
			assertLongWingLeaf(sentinel,
					"vane_" + side + "_sentinel_wing_root_shell_titanium", 12.0D);
			assertLongWingLeaf(sentinel,
					"vane_" + side + "_sentinel_wing_primary_shell_titanium", 18.0D);
			assertLongWingLeaf(sentinel,
					"vane_" + side + "_sentinel_wing_secondary_shell_titanium", 20.0D);
			assertLongWingLeaf(sentinel,
					"vane_" + side + "_sentinel_wing_tip_shell_titanium", 22.0D);
			for (String cannon : List.of(
					"cannon_housing_titanium",
					"cannon_barrel_gunmetal",
					"cannon_muzzle_led")) {
				require(sentinel.groups().contains(
								"vane_" + side + "_sentinel_wing_" + cannon),
						"Sentinel wing lost a visible cannon part: " + side + "/" + cannon);
			}
		}

		assertWholeSuitTransformationContract(root);
	}

	private static void assertAuthoredForearmWeaponShell(ObjSummary summary, String group) {
		List<double[]> vertices = summary.vertices().get(group);
		require(vertices != null && !vertices.isEmpty(),
				"Sentinel lost integrated forearm weapon-shell group: " + group);
		double minY = vertices.stream().mapToDouble(vertex -> vertex[1]).min().orElseThrow();
		double maxY = vertices.stream().mapToDouble(vertex -> vertex[1]).max().orElseThrow();
		double minZ = vertices.stream().mapToDouble(vertex -> vertex[2]).min().orElseThrow();
		require(minY >= 6.02D,
				"Sentinel forearm weapon shell crosses the elbow seam: " + group + " minY=" + minY);
		require(maxY <= 12.40D,
				"Sentinel forearm weapon shell extends beyond the fist: " + group + " maxY=" + maxY);
		require(minZ >= 2.50D,
				"Sentinel forearm weapon shell left the positive-Z hand back: "
						+ group + " minZ=" + minZ);
	}

	private static void assertZone(SentinelVisualZone expected, String group) {
		SentinelVisualZone actual = SentinelVisualZone.fromGroup(group);
		require(actual == expected,
				"generated Sentinel group does not enter its transformation branch: "
						+ group + " expected=" + expected + " actual=" + actual);
	}

	private static void assertWholeSuitTransformationContract(Path root) throws Exception {
		String renderer = Files.readString(root.resolve(
				"src/client/java/dev/codex/flightarmor/client/render/obj/FlightObjArmorRenderer.java"),
				StandardCharsets.UTF_8);
		for (String marker : List.of(
				"float headPhase = smoothstep(0.02F, 0.48F, streamline)",
				"name.contains(\"jaw_guard\")",
				"float direction = left ? 1.0F : right ? -1.0F : 0.0F",
				"float torsoPhase = smoothstep(0.08F, 0.64F, streamline)",
				"name.contains(\"waist_apron\")",
				"float armPhase = smoothstep(0.14F, 0.74F, streamline)",
				"float legPhase = smoothstep(0.24F, 0.86F, streamline)",
				"boolean knee = name.contains(\"_knee_\")",
				"float bootPhase = smoothstep(0.32F, 0.94F, streamline)",
				"boolean toe = name.contains(\"toe_ram\")",
				"float tier = sentinelWingTier(name)",
				"name.contains(\"_cannon_\")",
				"boolean detachableModule = isSentinelDetachableModule(name)",
				"ArmorConfigurationAccess.wingmenEntityOwnsGeometry")) {
			require(renderer.contains(marker),
					"Sentinel whole-suit transformation lost renderer marker: " + marker);
		}
	}

	private static void assertLongWingLeaf(ObjSummary summary, String group, double minimumSpan) {
		List<double[]> vertices = summary.vertices().get(group);
		require(vertices != null && !vertices.isEmpty(), "missing Sentinel wing leaf: " + group);
		double minY = vertices.stream().mapToDouble(vertex -> vertex[1]).min().orElseThrow();
		double maxY = vertices.stream().mapToDouble(vertex -> vertex[1]).max().orElseThrow();
		require(maxY - minY >= minimumSpan,
				"Sentinel wing leaf is still too small: " + group + " span=" + (maxY - minY));
	}

	private static ObjSummary read(Path path) throws Exception {
		Set<String> groups = new HashSet<>();
		Map<String, List<double[]>> vertices = new HashMap<>();
		String current = null;
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (line.startsWith("g ")) {
				current = line.substring(2).trim();
				groups.add(current);
				vertices.computeIfAbsent(current, ignored -> new ArrayList<>());
			} else if (current != null && line.startsWith("v ")) {
				String[] fields = line.substring(2).trim().split("\\s+");
				vertices.get(current).add(new double[] {
						Double.parseDouble(fields[0]),
						Double.parseDouble(fields[1]),
						Double.parseDouble(fields[2])
				});
			}
		}
		return new ObjSummary(Set.copyOf(groups), Map.copyOf(vertices));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record ObjSummary(Set<String> groups, Map<String, List<double[]>> vertices) {
	}
}
