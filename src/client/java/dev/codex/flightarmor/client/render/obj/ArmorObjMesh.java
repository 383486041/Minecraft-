package dev.codex.flightarmor.client.render.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.codex.flightarmor.client.assembly.ArmorAssemblyPart;
import dev.codex.flightarmor.configuration.SentinelVisualZone;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个极小且专用于盔甲的 OBJ 网格。
 *
 * <p>OBJ 顶点使用 Minecraft 模型部件的局部像素坐标：渲染时先应用
 * {@code ModelPart.translateAndRotate}，再统一缩放为 1/16 方块。解析器支持
 * {@code o/g/v/vn/f}、负索引、三角面、四边形和多边形；没有 {@code vn}
 * 时会根据顶点顺序计算平面法线。</p>
 *
 * <p>解析阶段会一次性预计算每个分组的平坦化顶点缓冲（{@code float[]}）与分类特征位，
 * 避免在每帧渲染循环中重复执行字符串拆解与多层集合迭代器开销。</p>
 */
final class ArmorObjMesh {
	static final int TONE_PRIMARY = 0;
	static final int TONE_HIGHLIGHT = 1;
	static final int TONE_ACCENT = 2;
	static final int TONE_GUNMETAL = 3;
	static final int TONE_SHADOW = 4;
	static final int TONE_VISOR = 5;

	private static final ArmorObjMesh EMPTY = new ArmorObjMesh(List.of());

	private final List<Group> groups;
	private final int totalFaceCount;

	private ArmorObjMesh(List<Group> groups) {
		this.groups = groups;
		this.totalFaceCount = groups.stream().mapToInt( group -> group.faces().size()).sum();
	}

	static ArmorObjMesh empty() {
		return EMPTY;
	}

	static ArmorObjMesh parse(Reader source) throws IOException {
		List<Vec3f> positions = new ArrayList<>();
		List<Vec3f> normals = new ArrayList<>();
		Map<String, GroupBuilder> builders = new LinkedHashMap<>();
		String currentGroup = "body_default";

		try (BufferedReader reader = new BufferedReader(source)) {
			String rawLine;
			int lineNumber = 0;
			while ((rawLine = reader.readLine()) != null) {
				lineNumber++;
				String line = stripComment(rawLine).trim();
				if (line.isEmpty()) {
					continue;
				}

				String[] tokens = line.split("\\s+");
				try {
					switch (tokens[0]) {
						case "o", "g" -> {
							if (tokens.length > 1 && !tokens[1].isBlank()) {
								currentGroup = normalizeGroupName(tokens[1]);
							}
						}
						case "v" -> {
							requireLength(tokens, 4);
							positions.add(new Vec3f(parseFloat(tokens[1]), parseFloat(tokens[2]), parseFloat(tokens[3])));
						}
						case "vn" -> {
							requireLength(tokens, 4);
							normals.add(new Vec3f(parseFloat(tokens[1]), parseFloat(tokens[2]), parseFloat(tokens[3])).normalized());
						}
						case "f" -> {
							requireLength(tokens, 4);
							GroupBuilder builder = builders.computeIfAbsent(currentGroup, GroupBuilder::new);
							List<ObjIndex> polygon = new ArrayList<>(tokens.length - 1);
							for (int i = 1; i < tokens.length; i++) {
								polygon.add(parseIndex(tokens[i], positions.size(), normals.size()));
							}
							builder.addPolygon(polygon, positions, normals);
						}
						default -> {
							// vt、mtllib、usemtl、s 等信息不影响按分组着色的盔甲网格。
						}
					}
				} catch (IllegalArgumentException ex) {
					throw new IOException("OBJ 第 " + lineNumber + " 行无效：" + rawLine, ex);
				}
			}
		}

		List<Group> groups = builders.values().stream()
				.map(GroupBuilder::build)
				.filter(group -> !group.faces().isEmpty())
				.toList();
		return groups.isEmpty() ? EMPTY : new ArmorObjMesh(groups);
	}

	List<Group> groups() {
		return groups;
	}

	int faceCount() {
		return totalFaceCount;
	}

	void render(Group group, PoseStack.Pose pose, VertexConsumer consumer, int packedLight, Color color) {
		float[] packed = group.packedVertices();
		int red = color.red();
		int green = color.green();
		int blue = color.blue();
		int alpha = color.alpha();
		for (int offset = 0, limit = packed.length; offset < limit; offset += 6) {
			consumer.addVertex(pose, packed[offset], packed[offset + 1], packed[offset + 2])
					.setColor(red, green, blue, alpha)
					.setUv(0.5F, 0.5F)
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(packedLight)
					.setNormal(pose, packed[offset + 3], packed[offset + 4], packed[offset + 5]);
		}
	}

	private static String stripComment(String line) {
		int comment = line.indexOf('#');
		return comment < 0 ? line : line.substring(0, comment);
	}

	private static String normalizeGroupName(String name) {
		return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-.]", "_");
	}

	private static void requireLength(String[] tokens, int minimum) {
		if (tokens.length < minimum) {
			throw new IllegalArgumentException("字段数量不足");
		}
	}

	private static float parseFloat(String value) {
		float parsed = Float.parseFloat(value);
		if (!Float.isFinite(parsed)) {
			throw new IllegalArgumentException("坐标不是有限数值");
		}
		return parsed;
	}

	private static ObjIndex parseIndex(String value, int positionCount, int normalCount) {
		String[] indices = value.split("/", -1);
		if (indices.length == 0 || indices[0].isBlank()) {
			throw new IllegalArgumentException("面缺少顶点索引");
		}

		int position = resolveIndex(Integer.parseInt(indices[0]), positionCount);
		int normal = -1;
		if (indices.length >= 3 && !indices[2].isBlank()) {
			normal = resolveIndex(Integer.parseInt(indices[2]), normalCount);
		}
		return new ObjIndex(position, normal);
	}

	private static int resolveIndex(int objIndex, int size) {
		int resolved = objIndex > 0 ? objIndex - 1 : size + objIndex;
		if (objIndex == 0 || resolved < 0 || resolved >= size) {
			throw new IllegalArgumentException("OBJ 索引越界：" + objIndex + "/" + size);
		}
		return resolved;
	}

	private static boolean computeIsLed(String name) {
		return name.endsWith("_led") || name.contains("_led_") || name.startsWith("led_")
				|| name.contains("emissive") || name.contains("_glow") || name.startsWith("glow_");
	}

	private static int computeToneCategory(String name) {
		if (name.contains("visor") || name.contains("glass")) {
			return TONE_VISOR;
		}
		if (name.contains("shadow") || name.contains("under") || name.contains("black")) {
			return TONE_SHADOW;
		}
		if (name.contains("dark") || name.contains("gunmetal") || name.contains("frame")
				|| name.contains("joint") || name.contains("barrel") || name.contains("shutter")
				|| name.contains("wrist_lock") || name.contains("rail")) {
			return TONE_GUNMETAL;
		}
		if (name.contains("accent") || name.contains("trim") || name.contains("flank")
				|| name.contains("apron") || name.contains("fin_") || name.contains("_fin")
				|| name.contains("cowl") || name.contains("pelvis") || name.contains("cuff")
				|| name.contains("collar") || name.contains("housing")) {
			return TONE_ACCENT;
		}
		if (name.contains("light") || name.contains("silver") || name.contains("crest")
				|| name.contains("brow") || name.contains("spine") || name.contains("sternum")
				|| name.contains("knee") || name.contains("toe") || name.contains("cover_outer")
				|| name.contains("hand_shell_outer") || name.contains("wing_tip")) {
			return TONE_HIGHLIGHT;
		}
		return TONE_PRIMARY;
	}

	private static boolean computeIsCoaxialShell(String name) {
		return name.contains("underlayer") || name.contains("_seal_")
				|| name.contains("head_shell_shadow") || name.contains("head_dome_cowl")
				|| name.contains("_shell_titanium") || name.contains("waist")
				|| name.contains("pelvis") || name.contains("cuff")
				|| name.contains("ankle_frame") || name.contains("gravity_ring")
				|| name.contains("boot_ring") || name.contains("ankle_ring")
				|| name.contains("sole_pad") || name.contains("sole_field");
	}

	private static boolean computeIsRearGroup(String name, float centerZ) {
		if (name.contains("rear") || name.contains("backplate") || name.contains("spine")
				|| name.contains("heel") || name.contains("vane_") || name.contains("flight_fin")
				|| name.contains("cover_outer") || name.contains("cover_inner")
				|| name.contains("hand_shell") || name.contains("wrist_lock")
				|| name.contains("emitter_shutter") || name.contains("emitter_track")) {
			return true;
		}
		return centerZ > 1.25F;
	}

	record Group(
			String name,
			List<Face> faces,
			float centerX,
			float centerY,
			float centerZ,
			float[] packedVertices,
			boolean led,
			int toneCategory,
			boolean coaxialShell,
			boolean rearGroup,
			SentinelVisualZone sentinelZone,
			ArmorAssemblyPart classifiedAssemblyPart) {
		Group {
			faces = List.copyOf(faces);
		}
	}

	record Color(int red, int green, int blue, int alpha) {
		Color {
			red = clampChannel(red);
			green = clampChannel(green);
			blue = clampChannel(blue);
			alpha = clampChannel(alpha);
		}

		private static int clampChannel(int value) {
			return Math.max(0, Math.min(255, value));
		}
	}

	private record Vec3f(float x, float y, float z) {
		Vec3f subtract(Vec3f other) {
			return new Vec3f(x - other.x, y - other.y, z - other.z);
		}

		Vec3f cross(Vec3f other) {
			return new Vec3f(y * other.z - z * other.y,
					z * other.x - x * other.z,
					x * other.y - y * other.x);
		}

		Vec3f normalized() {
			float lengthSquared = x * x + y * y + z * z;
			if (lengthSquared < 1.0E-12F) {
				return new Vec3f(0.0F, 1.0F, 0.0F);
			}
			float inverseLength = (float) (1.0D / Math.sqrt(lengthSquared));
			return new Vec3f(x * inverseLength, y * inverseLength, z * inverseLength);
		}
	}

	private record ObjIndex(int position, int normal) {
	}

	/** 四边形输出；三角面会重复最后一个顶点，以匹配实体渲染管线的 QUADS 模式。 */
	private record Face(List<Vertex> vertices) {
		private Face {
			if (vertices.size() != 4) {
				throw new IllegalArgumentException("盔甲面必须输出四个顶点");
			}
			vertices = List.copyOf(vertices);
		}
	}

	private record Vertex(float x, float y, float z, float nx, float ny, float nz) {
	}

	private static final class GroupBuilder {
		private final String name;
		private final List<Face> faces = new ArrayList<>();
		private float minX = Float.POSITIVE_INFINITY;
		private float minY = Float.POSITIVE_INFINITY;
		private float minZ = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;
		private float maxY = Float.NEGATIVE_INFINITY;
		private float maxZ = Float.NEGATIVE_INFINITY;

		private GroupBuilder(String name) {
			this.name = name;
		}

		private void addPolygon(List<ObjIndex> polygon, List<Vec3f> positions, List<Vec3f> normals) {
			for (int i = 1; i < polygon.size() - 1; i++) {
				addTriangle(polygon.getFirst(), polygon.get(i), polygon.get(i + 1), positions, normals);
			}
		}

		private void addTriangle(ObjIndex first, ObjIndex second, ObjIndex third,
				List<Vec3f> positions, List<Vec3f> normals) {
			Vec3f p0 = positions.get(first.position());
			Vec3f p1 = positions.get(second.position());
			Vec3f p2 = positions.get(third.position());
			Vec3f faceNormal = p1.subtract(p0).cross(p2.subtract(p0)).normalized();

			List<Vertex> vertices = new ArrayList<>(4);
			appendVertex(vertices, first, p0, faceNormal, normals);
			appendVertex(vertices, second, p1, faceNormal, normals);
			appendVertex(vertices, third, p2, faceNormal, normals);
			vertices.add(vertices.getLast());
			faces.add(new Face(vertices));
		}

		private void appendVertex(List<Vertex> output, ObjIndex index, Vec3f position,
				Vec3f faceNormal, List<Vec3f> normals) {
			Vec3f normal = index.normal() >= 0 ? normals.get(index.normal()) : faceNormal;
			output.add(new Vertex(position.x(), position.y(), position.z(), normal.x(), normal.y(), normal.z()));
			minX = Math.min(minX, position.x());
			minY = Math.min(minY, position.y());
			minZ = Math.min(minZ, position.z());
			maxX = Math.max(maxX, position.x());
			maxY = Math.max(maxY, position.y());
			maxZ = Math.max(maxZ, position.z());
		}

		private Group build() {
			if (faces.isEmpty()) {
				return new Group(name, Collections.emptyList(), 0.0F, 0.0F, 0.0F,
						new float[0], false, TONE_PRIMARY, false, false,
						SentinelVisualZone.NONE, null);
			}
			float centerX = (minX + maxX) * 0.5F;
			float centerY = (minY + maxY) * 0.5F;
			float centerZ = (minZ + maxZ) * 0.5F;
			float[] packed = new float[faces.size() * 24];
			int cursor = 0;
			for (Face face : faces) {
				for (Vertex vertex : face.vertices()) {
					packed[cursor++] = vertex.x();
					packed[cursor++] = vertex.y();
					packed[cursor++] = vertex.z();
					packed[cursor++] = vertex.nx();
					packed[cursor++] = vertex.ny();
					packed[cursor++] = vertex.nz();
				}
			}
			return new Group(
					name,
					faces,
					centerX,
					centerY,
					centerZ,
					packed,
					computeIsLed(name),
					computeToneCategory(name),
					computeIsCoaxialShell(name),
					computeIsRearGroup(name, centerZ),
					SentinelVisualZone.fromGroup(name),
					ArmorAssemblyPart.fromGroupName(name));
		}
	}
}
