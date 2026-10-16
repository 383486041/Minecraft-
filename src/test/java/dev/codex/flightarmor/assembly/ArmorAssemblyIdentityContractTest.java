package dev.codex.flightarmor.assembly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source contract for the atomic inventory-to-armor move.
 *
 * <p>The Minecraft inventory types make this transaction integration-heavy. This test protects
 * the identity decisions around it so a later UI or animation edit cannot silently mix four
 * different physical suits.</p>
 */
public final class ArmorAssemblyIdentityContractTest {
	private static final Path PROJECT = Path.of(
			System.getProperty("flightArmor.projectDir", ".")).toAbsolutePath().normalize();

	private ArmorAssemblyIdentityContractTest() {
	}

	public static void main(String[] args) throws Exception {
		String source = Files.readString(PROJECT.resolve(
				"src/main/java/dev/codex/flightarmor/assembly/ArmorAssemblyController.java"),
				StandardCharsets.UTF_8);
		String equip = section(source, "private static EquipResult equipSlotsAtomically(",
				"private static RetractionResult commitRetraction(");
		require(equip.contains("AssemblySetKey selectedSet"),
				"assembly no longer chooses one physical suit identity");
		require(equip.contains("selectedSet != null && !selectedSet.equals(observed)"),
				"already worn parts from different suits are no longer rejected");
		require(equip.contains("chooseInventoryAssemblySet(player, inventory, missingSlots)"),
				"empty armor slots no longer select one complete inventory set");
		require(equip.contains("findInventorySlot(")
						&& equip.contains("matchesAssemblySet("),
				"planned and committed item moves no longer revalidate suit identity");
		require(equip.contains("不会使用其他战甲的同类部件补位"),
				"missing parts can again be substituted from another suit");
		require(source.contains("tryHandleCompleteInventorySet(")
						&& source.contains("forbidden.contains(candidate.suitId())")
						&& source.contains("OwnershipLocation.CARRIED")
						&& source.contains("身份重复或所有权位置异常"),
				"local assembly before remote recall lost its carrier/ledger admission guard");

		String identity = section(source, "private static boolean matchesAssemblySet(",
				"private static List<Integer> findEmptyInventorySlots(");
		require(identity.contains("claimed.ownerId().equals(player.getUUID())")
						&& identity.contains("claimed.slot() != equipmentSlot")
						&& identity.contains("claimed.suitId()")
						&& identity.contains("ArmorVariantData.get(stack)"),
				"assembly identity must include owner, suitId, equipment slot and variant");
		require(identity.contains("new AssemblySetKey(null, ArmorVariantData.get(stack), false)"),
				"anonymous crafting parts should remain a separate, variant-locked set");
		System.out.println("ArmorAssemblyIdentityContractTest passed");
	}

	private static String section(String source, String startMarker, String endMarker)
			throws IOException {
		int start = source.indexOf(startMarker);
		int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());
		if (start < 0 || end <= start) {
			throw new IOException("Missing source markers: " + startMarker + " -> " + endMarker);
		}
		return source.substring(start, end);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
