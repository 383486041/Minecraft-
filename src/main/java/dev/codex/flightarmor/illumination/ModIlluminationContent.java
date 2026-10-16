package dev.codex.flightarmor.illumination;

import dev.codex.flightarmor.FlightArmorMod;
import dev.codex.flightarmor.variant.ArmorVariant;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;

/**
 * 装甲照明用的隐形光锚点方块，每个型号注册一个。
 *
 * <p>为什么不是一个方块就够了：客户端彩色光渲染器（Polytone + Veil 那套）是**按方块 id**
 * 决定颜色的，颜色挂在 id 上、不挂在方块实例上。要让「先锋发青、疾影发紫、守望发金」，
 * 就必须有三个各自独立的 id，否则只能三型共用一个颜色。</p>
 *
 * <p>方块本体直接复用原版 {@link LightBlock}：它的形状是空的、碰撞是空的、渲染形状是
 * {@code INVISIBLE}，所以看不见也碰不到，和 {@code minecraft:light} 行为一致，但 id 是我们的，
 * 因此不会跟世界上任何别的光源方块共用颜色。</p>
 *
 * <p>属性刻意照抄原版 {@code minecraft:light}（{@code replaceable} + 不可破坏 + 无掉落物）：
 * 别人的方块可以正常替换它，但它本身不产生掉落、不被爆炸破坏，也不会成为「照明吃掉方块」的
 * 元凶 —— 放置前 {@link ArmorIlluminationController} 已经确认目标格是空气。</p>
 */
public final class ModIlluminationContent {
	private static final Map<ArmorVariant, Block> AURA_LIGHTS = new EnumMap<>(ArmorVariant.class);

	private static boolean initialized;

	private ModIlluminationContent() {
	}

	public static synchronized void initialize() {
		if (initialized) return;
		for (ArmorVariant variant : ArmorVariant.values()) {
			Block block = new LightBlock(BlockBehaviour.Properties.of()
					.replaceable()
					.strength(-1.0F, 3600000.0F)
					.lightLevel(state -> state.getValue(LightBlock.LEVEL))
					.noLootTable());
			Registry.register(BuiltInRegistries.BLOCK, FlightArmorMod.id(blockId(variant)), block);
			AURA_LIGHTS.put(variant, block);
		}
		initialized = true;
	}

	/** 取该型号的光锚点方块；未知型号回退到 {@link ArmorVariant#DEFAULT}。 */
	public static Block auraLight(ArmorVariant variant) {
		Block block = AURA_LIGHTS.get(variant);
		return block != null ? block : AURA_LIGHTS.get(ArmorVariant.DEFAULT);
	}

	/** 撤除光源前的归属判定：只撤自己放的那种光，绝不误删别人的方块。 */
	public static boolean isAuraLight(BlockState state) {
		return isAuraLightBlock(state.getBlock());
	}

	/** 护栏用：这个方块是不是我们的光锚点（与 {@code minecraft:light} 同等，永不参与地形破坏）。 */
	public static boolean isAuraLightBlock(Block block) {
		return AURA_LIGHTS.containsValue(block);
	}

	public static String blockId(ArmorVariant variant) {
		return "aura_light_" + variant.serializedName();
	}
}
