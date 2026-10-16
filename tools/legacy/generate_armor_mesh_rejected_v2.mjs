import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/*
 * Flight Armor OBJ generator — v2「流线科幻能量甲」
 *
 * 坐标契约（Minecraft 模型像素，渲染时统一除以 16）：
 *   - 每个顶点都相对它所属 HumanoidModel 部件的枢轴，部件偏移由渲染器套用。
 *   - +X 为模型左侧、+Y 向下、-Z 为正前方。
 *   - head 局部 y [-8.6, 0.6]，body 局部 y [-0.9, 12.6]，
 *     arm 局部 y [-3.0, 10.6]，leg/boot 局部 y [-0.4, 12.8]。
 *
 * 分组命名契约（渲染器 / 动画 / 装配按子串路由，不要随意改名）：
 *   head_ visor_ body_ core_ vane_left_ vane_right_
 *   arm_left_ arm_right_ leg_left_ leg_right_ boot_left_ boot_right_
 *   材质 token：_titanium 主板甲、_gunmetal 结构与框、_shadow 内衬、
 *   _visor 镜片，发光件一律以 _led 结尾。
 *
 * 造型语言（v2）：
 *   1. 大块面 —— 每个壳体横截面只 12 点（4 个宽平面 + 4 条锐利倒角），纵向 4~6 环，
 *      面大且平，取代 v1 的超椭圆碎片低模。
 *   2. 明确分层 —— 内衬(shadow) → 主壳(titanium) → 面罩/结构(gunmetal) → 灯槽(led)，
 *      每层在法线方向上外移 0.15~0.35，侧视能看到真实台阶。
 *   3. 收束轮廓 —— 胸宽、腰窄、肩甲外张，腿从大腿到靴尖有明显曲线。
 *   4. 贯穿灯槽 —— 胸口 V → 腹中脊 → 腰侧 → 大腿外侧 → 靴底，一条连续的视觉轴。
 */

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(scriptDir, "..");
const outputDirectory = path.join(
  projectDir,
  "src",
  "main",
  "resources",
  "assets",
  "flight_armor",
  "models",
  "armor",
);

const round = (value) => {
  const cleaned = Math.abs(value) < 1e-7 ? 0 : value;
  return Number(cleaned.toFixed(6));
};

/** 统一把闭合网格的面翻成朝外。 */
const orientClosedMesh = (mesh) => {
  const center = mesh.vertices
    .reduce(
      (sum, vertex) => [sum[0] + vertex[0], sum[1] + vertex[1], sum[2] + vertex[2]],
      [0, 0, 0],
    )
    .map((value) => value / mesh.vertices.length);

  mesh.faces = mesh.faces.map((face) => {
    const a = mesh.vertices[face[0]];
    const b = mesh.vertices[face[1]];
    const c = mesh.vertices[face[2]];
    const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    const normal = [
      ab[1] * ac[2] - ab[2] * ac[1],
      ab[2] * ac[0] - ab[0] * ac[2],
      ab[0] * ac[1] - ab[1] * ac[0],
    ];
    const faceCenter = face
      .map((index) => mesh.vertices[index])
      .reduce(
        (sum, vertex) => [sum[0] + vertex[0], sum[1] + vertex[1], sum[2] + vertex[2]],
        [0, 0, 0],
      )
      .map((value) => value / face.length);
    const outward = [
      faceCenter[0] - center[0],
      faceCenter[1] - center[1],
      faceCenter[2] - center[2],
    ];
    const alignment =
      normal[0] * outward[0] + normal[1] * outward[1] + normal[2] * outward[2];
    return alignment < 0 ? [...face].reverse() : face;
  });
  return mesh;
};

const mergeMeshes = (...meshes) => {
  const result = { vertices: [], faces: [] };
  for (const mesh of meshes) {
    const offset = result.vertices.length;
    result.vertices.push(...mesh.vertices.map((vertex) => [...vertex]));
    result.faces.push(...mesh.faces.map((face) => face.map((index) => index + offset)));
  }
  return result;
};

const mirrorX = (points) => points.map(([x, y]) => [-x, y]).reverse();

/** 右半身用 X 镜像生成，保证左右严格对称。 */
function mirrorMeshX(mesh) {
  return {
    vertices: mesh.vertices.map(([x, y, z]) => [round(-x), y, z]),
    faces: mesh.faces.map((face) => [...face].reverse()),
  };
}

class ObjBuilder {
  constructor() {
    this.groups = new Map();
    /** 打开后本次 add 的网格整体做 X 镜像；左右肢体共用一套左坐标定义。 */
    this.mirror = false;
  }

  add(groupName, rawMesh) {
    const mesh = this.mirror ? mirrorMeshX(rawMesh) : rawMesh;
    if (!/^[a-z][a-z0-9_]*$/.test(groupName)) {
      throw new Error(`Invalid OBJ group name: ${groupName}`);
    }
    if (!mesh || mesh.vertices.length === 0 || mesh.faces.length === 0) {
      throw new Error(`Empty mesh for group: ${groupName}`);
    }

    let group = this.groups.get(groupName);
    if (!group) {
      group = { vertices: [], faces: [] };
      this.groups.set(groupName, group);
    }

    const offset = group.vertices.length;
    for (const vertex of mesh.vertices) {
      if (vertex.length !== 3 || vertex.some((value) => !Number.isFinite(value))) {
        throw new Error(`Invalid vertex in ${groupName}: ${JSON.stringify(vertex)}`);
      }
      group.vertices.push(vertex.map(round));
    }
    for (const face of mesh.faces) {
      if (face.length !== 3 && face.length !== 4) {
        throw new Error(`Only triangle/quad faces are allowed (${groupName})`);
      }
      group.faces.push(face.map((index) => index + offset));
    }
  }

  stats() {
    let vertices = 0;
    let faces = 0;
    let triangles = 0;
    let quads = 0;
    for (const group of this.groups.values()) {
      vertices += group.vertices.length;
      faces += group.faces.length;
      triangles += group.faces.filter((face) => face.length === 3).length;
      quads += group.faces.filter((face) => face.length === 4).length;
    }
    return { groups: this.groups.size, vertices, faces, triangles, quads };
  }

  validate() {
    const requiredPrefixes = [
      "head_",
      "visor_",
      "body_",
      "core_",
      "vane_left_",
      "vane_right_",
      "arm_left_",
      "arm_right_",
      "leg_left_",
      "leg_right_",
      "boot_left_",
      "boot_right_",
    ];

    for (const prefix of requiredPrefixes) {
      if (![...this.groups.keys()].some((name) => name.startsWith(prefix))) {
        throw new Error(`Missing required group prefix: ${prefix}`);
      }
    }

    for (const name of this.groups.keys()) {
      const isLed = name.endsWith("_led");
      const hasMaterial = ["_titanium", "_gunmetal", "_shadow", "_visor"].some((token) =>
        name.includes(token),
      );
      if (!isLed && !hasMaterial) {
        throw new Error(`Non-LED group has no material token: ${name}`);
      }
      if (name.includes("led") && !isLed) {
        throw new Error(`LED group must end in _led: ${name}`);
      }
    }

    for (const [name, group] of this.groups) {
      for (const face of group.faces) {
        if (face.some((index) => index < 0 || index >= group.vertices.length)) {
          throw new Error(`Out-of-range face index in ${name}`);
        }
        const a = group.vertices[face[0]];
        const b = group.vertices[face[1]];
        const c = group.vertices[face[2]];
        const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
        const ac = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
        const cross = [
          ab[1] * ac[2] - ab[2] * ac[1],
          ab[2] * ac[0] - ab[0] * ac[2],
          ab[0] * ac[1] - ab[1] * ac[0],
        ];
        if (cross[0] ** 2 + cross[1] ** 2 + cross[2] ** 2 < 1e-12) {
          throw new Error(`Degenerate face in ${name}: ${face.join(",")}`);
        }
      }
    }
  }

  toObj() {
    this.validate();
    const stats = this.stats();
    const lines = [
      "# Flight Armor - original faceted anti-gravity suit",
      "# Generated by tools/generate_armor_mesh.mjs; do not hand-edit.",
      "# Units: Minecraft model pixels. Front is -Z. Faces are triangles/quads only.",
      "# Part-local groups: head/visor, body/core/vanes, arms, legs, boots.",
      "# Dynamic pivots: core 0 3.65 -3.10 | visor 0 -4.85 -4.20",
      "# Dynamic pivots: vane_left 3.62 2.55 2.52 | vane_right -3.62 2.55 2.52",
      `# Statistics: ${stats.groups} groups, ${stats.vertices} vertices, ${stats.faces} faces`,
      "",
    ];

    let globalOffset = 0;
    for (const [name, group] of this.groups) {
      lines.push(`g ${name}`);
      for (const [x, y, z] of group.vertices) {
        lines.push(`v ${x} ${y} ${z}`);
      }
      for (const face of group.faces) {
        lines.push(`f ${face.map((index) => index + globalOffset + 1).join(" ")}`);
      }
      lines.push("");
      globalOffset += group.vertices.length;
    }
    return `${lines.join("\n")}\n`;
  }
}

// -----------------------------------------------------------------------------
// 几何工具
// -----------------------------------------------------------------------------

/**
 * 12 点装甲横截面。
 *
 * 结构是「四个各由三点描出的宽平面」+「四个切角」：
 *   0 前中、1 前右、2 右前角、3 右中、4 右后角、5 后右、
 *   6 后中、7 后左、8 左后角、9 左中、10 左前角、11 前左。
 * front/side/rear 只让面内中点轻微外凸 —— 面仍然是接近平的大块，
 * 但整条边会形成很浅的曲面过渡，避免 v1 那种密集碎面。
 */
const profile = ({
  hx,
  hz,
  cx = 0,
  cz = 0,
  bevel = 0.45,
  front = 0,
  side = 0,
  rear = 0,
}) => {
  const bx = Math.max(0.08, Math.min(bevel, hx * 0.55));
  const bz = Math.max(0.08, Math.min(bevel, hz * 0.55));
  return [
    [cx, cz - hz - front],
    [cx + hx - bx, cz - hz],
    [cx + hx, cz - hz + bz],
    [cx + hx + side, cz],
    [cx + hx, cz + hz - bz],
    [cx + hx - bx, cz + hz],
    [cx, cz + hz + rear],
    [cx - hx + bx, cz + hz],
    [cx - hx, cz + hz - bz],
    [cx - hx - side, cz],
    [cx - hx, cz - hz + bz],
    [cx - hx + bx, cz - hz],
  ];
};

/** 沿 Y 轴放样闭合装甲壳。rings 用 profile 的字段。 */
const hull = ({ rings, capTop = true, capBottom = true }) => {
  const segments = 12;
  const vertices = [];
  const faces = [];

  for (const ring of rings) {
    for (const [x, z] of profile(ring)) {
      vertices.push([x, ring.y, z]);
    }
  }

  for (let ring = 0; ring < rings.length - 1; ring += 1) {
    for (let segment = 0; segment < segments; segment += 1) {
      const next = (segment + 1) % segments;
      faces.push([
        ring * segments + segment,
        (ring + 1) * segments + segment,
        (ring + 1) * segments + next,
        ring * segments + next,
      ]);
    }
  }

  if (capTop) {
    const index = vertices.length;
    vertices.push([rings[0].cx ?? 0, rings[0].y, rings[0].cz ?? 0]);
    for (let segment = 0; segment < segments; segment += 1) {
      faces.push([index, (segment + 1) % segments, segment]);
    }
  }
  if (capBottom) {
    const last = rings.length - 1;
    const index = vertices.length;
    vertices.push([rings[last].cx ?? 0, rings[last].y, rings[last].cz ?? 0]);
    for (let segment = 0; segment < segments; segment += 1) {
      faces.push([index, last * segments + segment, last * segments + ((segment + 1) % segments)]);
    }
  }

  return orientClosedMesh({ vertices, faces });
};

/** 平面多边形沿 Z 挤出（points 为 [x, y]）。用于面板、翼片、背板。 */
const plateZ = (points, outerZ, innerZ) => {
  const count = points.length;
  const vertices = [];
  const faces = [];
  for (const [x, y] of points) vertices.push([x, y, outerZ]);
  for (const [x, y] of points) vertices.push([x, y, innerZ]);
  const outerCenter = vertices.length;
  vertices.push([
    points.reduce((sum, point) => sum + point[0], 0) / count,
    points.reduce((sum, point) => sum + point[1], 0) / count,
    outerZ,
  ]);
  const innerCenter = vertices.length;
  vertices.push([vertices[outerCenter][0], vertices[outerCenter][1], innerZ]);
  for (let index = 0; index < count; index += 1) {
    const next = (index + 1) % count;
    faces.push([outerCenter, index, next]);
    faces.push([innerCenter, count + next, count + index]);
    faces.push([index, count + index, count + next, next]);
  }
  return orientClosedMesh({ vertices, faces });
};

/** 带前后曲率的面罩：points 为 [x, y]，前沿 z 由函数给出。 */
const plateCurvedZ = (points, outerZAtX, thickness) => {
  const count = points.length;
  const vertices = [];
  const faces = [];
  for (const [x, y] of points) vertices.push([x, y, outerZAtX(x)]);
  for (const [x, y] of points) vertices.push([x, y, outerZAtX(x) + thickness]);

  const average = (axis, inner) =>
    points.reduce((sum, point) => {
      if (axis < 2) return sum + point[axis];
      return sum + outerZAtX(point[0]) + (inner ? thickness : 0);
    }, 0) / count;
  const outerCenter = vertices.length;
  vertices.push([average(0, false), average(1, false), average(2, false)]);
  const innerCenter = vertices.length;
  vertices.push([average(0, true), average(1, true), average(2, true)]);

  for (let index = 0; index < count; index += 1) {
    const next = (index + 1) % count;
    faces.push([outerCenter, index, next]);
    faces.push([innerCenter, count + next, count + index]);
    faces.push([index, count + index, count + next, next]);
  }
  return orientClosedMesh({ vertices, faces });
};

/**
 * 前倾装甲板：前沿 z 由 y 决定，points 为 [x, y]。
 * 让胸板、腹板、大腿板能做成「上收下张」的斜面，而不是竖直方柱。
 */
const plateSlopedZ = (points, zAtY, thickness) => {
  const count = points.length;
  const vertices = [];
  const faces = [];
  for (const [x, y] of points) vertices.push([x, y, zAtY(y)]);
  for (const [x, y] of points) vertices.push([x, y, zAtY(y) + thickness]);
  const meanX = points.reduce((sum, point) => sum + point[0], 0) / count;
  const meanY = points.reduce((sum, point) => sum + point[1], 0) / count;
  const outerCenter = vertices.length;
  vertices.push([meanX, meanY, zAtY(meanY)]);
  const innerCenter = vertices.length;
  vertices.push([meanX, meanY, zAtY(meanY) + thickness]);
  for (let index = 0; index < count; index += 1) {
    const next = (index + 1) % count;
    faces.push([outerCenter, index, next]);
    faces.push([innerCenter, count + next, count + index]);
    faces.push([index, count + index, count + next, next]);
  }
  return orientClosedMesh({ vertices, faces });
};

/** 平面多边形沿 X 挤出（points 为 [y, z]）。用于侧面舱体、外侧护板。 */const plateX = (points, outerX, innerX) => {
  const count = points.length;
  const vertices = [];
  const faces = [];
  for (const [y, z] of points) vertices.push([outerX, y, z]);
  for (const [y, z] of points) vertices.push([innerX, y, z]);
  const outerCenter = vertices.length;
  vertices.push([
    outerX,
    points.reduce((sum, point) => sum + point[0], 0) / count,
    points.reduce((sum, point) => sum + point[1], 0) / count,
  ]);
  const innerCenter = vertices.length;
  vertices.push([innerX, vertices[outerCenter][1], vertices[outerCenter][2]]);
  for (let index = 0; index < count; index += 1) {
    const next = (index + 1) % count;
    faces.push([outerCenter, next, index]);
    faces.push([innerCenter, count + index, count + next]);
    faces.push([index, next, count + next, count + index]);
  }
  return orientClosedMesh({ vertices, faces });
};

/** 平面多边形沿 Y 挤出（points 为 [x, z]）。用于顶面脊、水平翼面。 */
const plateY = (points, outerY, innerY) => {
  const count = points.length;
  const vertices = [];
  const faces = [];
  for (const [x, z] of points) vertices.push([x, outerY, z]);
  for (const [x, z] of points) vertices.push([x, innerY, z]);
  const outerCenter = vertices.length;
  vertices.push([
    points.reduce((sum, point) => sum + point[0], 0) / count,
    outerY,
    points.reduce((sum, point) => sum + point[1], 0) / count,
  ]);
  const innerCenter = vertices.length;
  vertices.push([vertices[outerCenter][0], innerY, vertices[outerCenter][2]]);
  for (let index = 0; index < count; index += 1) {
    const next = (index + 1) % count;
    faces.push([outerCenter, index, next]);
    faces.push([innerCenter, count + next, count + index]);
    faces.push([index, count + index, count + next, next]);
  }
  return orientClosedMesh({ vertices, faces });
};

/** 沿 X 采样、上下缘为函数的曲面带。用于面罩、镜片、镜框、灯槽。 */
const ribbon = ({ xs, topY, bottomY, zAtX, thickness = 0.16 }) => {
  const vertices = [];
  const faces = [];
  for (const x of xs) {
    const z = zAtX(x);
    vertices.push([x, topY(x), z]);
    vertices.push([x, bottomY(x), z]);
    vertices.push([x, topY(x), z + thickness]);
    vertices.push([x, bottomY(x), z + thickness]);
  }
  for (let index = 0; index < xs.length - 1; index += 1) {
    const a = index * 4;
    const b = (index + 1) * 4;
    faces.push([a, b, b + 1, a + 1]);
    faces.push([a + 2, a + 3, b + 3, b + 2]);
    faces.push([a, a + 2, b + 2, b]);
    faces.push([a + 1, b + 1, b + 3, a + 3]);
  }
  faces.push([0, 1, 3, 2]);
  const last = (xs.length - 1) * 4;
  faces.push([last, last + 2, last + 3, last + 1]);
  return orientClosedMesh({ vertices, faces });
};

/** 水平环带（圆环）。用于重力环、核心座、关节环。 */
const ringY = ({ cx = 0, cy, cz = 0, rx, rz, tube, majorSegments = 16 }) => {
  const vertices = [];
  const faces = [];
  for (let major = 0; major < majorSegments; major += 1) {
    const theta = (major / majorSegments) * Math.PI * 2;
    vertices.push([cx + rx * Math.cos(theta), cy, cz + rz * Math.sin(theta)]);
    vertices.push([cx + (rx + tube) * Math.cos(theta), cy - tube * 0.55, cz + (rz + tube) * Math.sin(theta)]);
    vertices.push([cx + (rx + tube) * Math.cos(theta), cy + tube * 0.55, cz + (rz + tube) * Math.sin(theta)]);
    vertices.push([cx + rx * Math.cos(theta), cy + tube * 1.1, cz + rz * Math.sin(theta)]);
  }
  for (let major = 0; major < majorSegments; major += 1) {
    const next = (major + 1) % majorSegments;
    for (let lane = 0; lane < 4; lane += 1) {
      const laneNext = (lane + 1) % 4;
      faces.push([major * 4 + lane, next * 4 + lane, next * 4 + laneNext, major * 4 + laneNext]);
    }
  }
  return orientClosedMesh({ vertices, faces });
};

/** 沿一条折线扫掠的薄刃。用于翼脊、导流刀、颊刃。 */
const blade = ({ spine, halfWidth, thickness, axis = "z" }) => {
  const points = [];
  for (const [index, node] of spine.entries()) {
    const width = typeof halfWidth === "function" ? halfWidth(index) : halfWidth;
    if (axis === "z") {
      points.push([node[0], node[1], node[2] - width]);
      points.push([node[0], node[1], node[2] + width]);
    } else {
      points.push([node[0] - width, node[1], node[2]]);
      points.push([node[0] + width, node[1], node[2]]);
    }
  }
  const vertices = [];
  const faces = [];
  for (const [x, y, z] of points) {
    vertices.push([x, y, z]);
    vertices.push([x, y + thickness, z]);
  }
  for (let index = 0; index < points.length - 1; index += 1) {
    const a = index * 2;
    const b = (index + 1) * 2;
    faces.push([a, b, b + 1, a + 1]);
  }
  faces.push([0, 1, points.length * 2 - 1, points.length * 2 - 2]);
  return orientClosedMesh({ vertices, faces });
};

// -----------------------------------------------------------------------------
// 型号参数：三套轮廓来自同一套结构，但体量、收束与外张各自独立写成设计值
// -----------------------------------------------------------------------------

const SPECS = {
  vanguard: {
    label: "vanguard",
    headWidth: 1.0,
    headDepth: 1.0,
    collarWidth: 1.0,
    chestWidth: 1.0,
    chestDepth: 1.0,
    waistWidth: 0.88,
    hipWidth: 1.03,
    pauldronSpan: 1.12,
    pauldronDepth: 1.0,
    armBulk: 1.0,
    thighBulk: 1.0,
    bootBulk: 1.0,
    finSpan: 1.0,
    finLength: 1.0,
    wingKind: "stabilizer",
    visorTilt: 0.55,
    crownHeight: 1.0,
  },
  swift: {
    label: "swift",
    headWidth: 0.94,
    headDepth: 1.0,
    collarWidth: 0.94,
    chestWidth: 0.90,
    chestDepth: 0.90,
    waistWidth: 0.78,
    hipWidth: 0.94,
    pauldronSpan: 0.86,
    pauldronDepth: 0.92,
    armBulk: 0.90,
    thighBulk: 0.90,
    bootBulk: 0.92,
    finSpan: 0.78,
    finLength: 1.55,
    wingKind: "longfin",
    visorTilt: 0.85,
    crownHeight: 1.35,
  },
  sentinel: {
    label: "sentinel",
    headWidth: 1.08,
    headDepth: 1.06,
    collarWidth: 1.10,
    chestWidth: 1.14,
    chestDepth: 1.08,
    waistWidth: 1.0,
    hipWidth: 1.16,
    pauldronSpan: 1.22,
    pauldronDepth: 1.18,
    armBulk: 1.12,
    thighBulk: 1.14,
    bootBulk: 1.18,
    finSpan: 0.9,
    finLength: 0.8,
    wingKind: "wingbay",
    visorTilt: 0.35,
    crownHeight: 0.72,
  },
};

const HEAD_BASE = {
  halfWidth: 4.22,
  halfDepth: 4.28,
  top: -8.55,
  bottom: 0.55,
};

/**
 * 面罩前沿的横向曲率：中心最前，向两侧回收。
 * 所有面部件都基于它偏移，保证面板之间始终贴合。
 */
const faceFrontZ = (x, offset = 0, spread = 4.05, depth = 0.42) => {
  const normalized = Math.min(1, Math.abs(x) / spread);
  return -4.40 - depth * (1 - normalized * normalized) - offset;
};

// -----------------------------------------------------------------------------
// 头部：内衬 → 主壳 → 面罩 → 分件 → 灯
// -----------------------------------------------------------------------------

const buildHead = (armor, spec) => {
  const hw = HEAD_BASE.halfWidth * spec.headWidth;
  const hd = HEAD_BASE.halfDepth * spec.headDepth;

  // 内衬：贴身层，尺寸固定为玩家头部第二层皮肤口径，不随型号缩放。
  // 三型号共用同一份内衬，保证后脑安全距离恒定落在 [4.25, 4.50]。
  armor.add(
    "head_shell_shadow",
    hull({
      rings: [
        { y: -8.20, hx: 3.28, hz: 3.40, bevel: 0.36 },
        { y: -7.45, hx: 4.06, hz: 4.20, bevel: 0.38 },
        { y: -4.60, hx: 4.30, hz: 4.32, bevel: 0.40 },
        { y: -1.50, hx: 4.20, hz: 4.20, bevel: 0.38 },
        { y: 0.45, hx: 3.10, hz: 3.12, bevel: 0.36 },
      ],
    }),
  );

  // 主壳：包住头顶、两侧与后脑；前脸中央内收成凹槽给面罩，侧视形成真实台阶。
  const shellHx = hw + 0.26;
  const shellHz = hd - 0.42;
  armor.add(
    "head_shell_titanium",
    hull({
      rings: [
        { y: -8.46, hx: shellHx * 0.72, hz: shellHz * 0.74, bevel: 0.40 },
        { y: -7.72, hx: shellHx * 0.94, hz: shellHz * 0.94, bevel: 0.42, front: -0.06 },
        { y: -5.10, hx: shellHx * 1.0, hz: shellHz * 1.0, bevel: 0.46, front: -0.18 },
        { y: -2.30, hx: shellHx * 0.99, hz: shellHz * 0.99, bevel: 0.44, front: -0.14 },
        { y: -0.05, hx: shellHx * 0.80, hz: shellHz * 0.80, bevel: 0.42, front: -0.04 },
      ],
    }),
  );

  // 头顶中脊：从前额贯通到后脑，是整顶头盔的主视觉轴。
  const crestH = HEAD_BASE.top - (spec.crownHeight - 1.0) * 0.30;
  armor.add(
    "head_crown_crest_titanium",
    hull({
      rings: [
        { y: crestH, hx: 0.86, hz: 2.30, cz: -0.30, bevel: 0.30 },
        { y: crestH + 0.55, hx: 1.12, hz: 3.05, cz: -0.15, bevel: 0.34 },
        { y: crestH + 1.85, hx: 1.24, hz: 3.60, bevel: 0.36 },
        { y: crestH + 2.60, hx: 0.92, hz: 2.90, bevel: 0.32 },
      ],
    }),
  );

  // 面罩：一整块深色面板，从额头压到下颌，是整张脸的结构骨架。
  const faceXs = [-3.55, -2.60, -1.35, 0, 1.35, 2.60, 3.55];
  const faceSpan = 3.9;
  armor.add(
    "head_faceplate_gunmetal",
    ribbon({
      xs: faceXs,
      topY: (x) => -6.95 + 0.34 * Math.abs(x / faceSpan),
      bottomY: (x) => -1.15 - 0.46 * Math.abs(x / faceSpan),
      zAtX: (x) => faceFrontZ(x, 0.10, faceSpan, 0.42),
      thickness: 0.34,
    }),
  );

  // 面中脊：把面罩一分为二，形成中央承力线。
  armor.add(
    "head_faceplate_spine_titanium",
    plateCurvedZ(
      [
        [-0.52, -6.60],
        [0.52, -6.60],
        [0.72, -3.40],
        [0.42, -1.28],
        [0, -0.86],
        [-0.42, -1.28],
        [-0.72, -3.40],
      ],
      (x) => faceFrontZ(x, 0.50, faceSpan, 0.42),
      0.24,
    ),
  );

  // 目镜：横贯的 V 形镜片，两端上翘，是「流线」最直接的表达。
  const tilt = spec.visorTilt;
  armor.add(
    "visor_lens_visor",
    ribbon({
      xs: [-3.60, -2.75, -1.40, 0, 1.40, 2.75, 3.60],
      topY: (x) => -6.05 + tilt * Math.abs(x / 3.6),
      bottomY: (x) => -4.62 - 0.40 * Math.abs(x / 3.6),
      zAtX: (x) => faceFrontZ(x, 0.54, faceSpan, 0.42),
      thickness: 0.14,
    }),
  );
  armor.add(
    "visor_upper_frame_titanium",
    ribbon({
      xs: faceXs,
      topY: (x) => -6.42 + tilt * Math.abs(x / 3.6),
      bottomY: (x) => -6.02 + tilt * Math.abs(x / 3.6),
      zAtX: (x) => faceFrontZ(x, 0.52, faceSpan, 0.42),
      thickness: 0.22,
    }),
  );
  armor.add(
    "visor_lower_frame_gunmetal",
    ribbon({
      xs: faceXs,
      topY: (x) => -4.70 - 0.40 * Math.abs(x / 3.6),
      bottomY: (x) => -4.30 - 0.40 * Math.abs(x / 3.6),
      zAtX: (x) => faceFrontZ(x, 0.50, faceSpan, 0.42),
      thickness: 0.22,
    }),
  );

  // 眉框：镜片上方左右两块外张的板，给头盔加出「攻击性」。
  const brow = [
    [0.30, -6.40],
    [1.35, -6.86],
    [3.55, -6.28],
    [3.30, -5.62],
    [1.20, -6.02],
  ];
  armor.add("head_brow_left_gunmetal", plateCurvedZ(brow, (x) => faceFrontZ(x, 0.44, faceSpan, 0.42), 0.26));
  armor.add("head_brow_right_gunmetal", plateCurvedZ(mirrorX(brow), (x) => faceFrontZ(x, 0.44, faceSpan, 0.42), 0.26));

  // 目镜下缘灯：从中央向两侧上扬，和镜片同一条视觉轴。
  const vEdge = [
    [0.16, -4.42],
    [0.24, -4.16],
    [3.34, -4.62],
    [3.30, -4.84],
  ];
  armor.add("head_faceplate_v_edge_left_led", plateCurvedZ(vEdge, (x) => faceFrontZ(x, 0.70, faceSpan, 0.42), 0.08));
  armor.add("head_faceplate_v_edge_right_led", plateCurvedZ(mirrorX(vEdge), (x) => faceFrontZ(x, 0.70, faceSpan, 0.42), 0.08));

  // 面中灯：从额头点到鼻梁，是整张脸的中央轴线。
  armor.add(
    "head_faceplate_core_led",
    plateCurvedZ(
      [
        [-0.16, -6.28],
        [0.16, -6.28],
        [0.22, -4.10],
        [0, -3.62],
        [-0.22, -4.10],
      ],
      (x) => faceFrontZ(x, 0.74, faceSpan, 0.42),
      0.10,
    ),
  );

  // 颏灯：下颌中央的一道小 V，收住整条中线。
  armor.add(
    "head_faceplate_chin_chevron_led",
    plateCurvedZ(
      [
        [-0.66, -1.62],
        [0, -0.92],
        [0.66, -1.62],
        [0.40, -1.86],
        [0, -1.30],
        [-0.40, -1.86],
      ],
      (x) => faceFrontZ(x, 0.72, faceSpan, 0.42),
      0.08,
    ),
  );

  // 颊侧：从镜片外端压到颏部的暗色侧板，让脸收窄。
  const cheek = [
    [1.60, -4.62],
    [3.52, -4.74],
    [4.06, -3.84],
    [3.86, -1.90],
    [2.60, -0.86],
    [1.72, -2.00],
  ];
  armor.add("head_cheek_left_gunmetal", plateCurvedZ(cheek, (x) => faceFrontZ(x, 0.24, faceSpan, 0.42), 0.34));
  armor.add("head_cheek_right_gunmetal", plateCurvedZ(mirrorX(cheek), (x) => faceFrontZ(x, 0.24, faceSpan, 0.42), 0.34));

  // 下颌刃：颊板内缘的一道窄钛刃，从正面看形成尖下巴。
  const jaw = [
    [1.12, -2.86],
    [2.26, -3.44],
    [3.26, -2.74],
    [2.70, -1.34],
    [1.24, -0.56],
    [0.74, -0.96],
  ];
  armor.add("head_cheek_left_jaw_titanium", plateCurvedZ(jaw, (x) => faceFrontZ(x, 0.40, faceSpan, 0.42), 0.20));
  armor.add("head_cheek_right_jaw_titanium", plateCurvedZ(mirrorX(jaw), (x) => faceFrontZ(x, 0.40, faceSpan, 0.42), 0.20));

  // 颊灯：一条沿颊板斜下的短线。
  const cheekSlash = [
    [2.10, -3.94],
    [2.42, -4.10],
    [3.46, -2.96],
    [3.20, -2.62],
  ];
  armor.add("head_cheek_left_slash_led", plateCurvedZ(cheekSlash, (x) => faceFrontZ(x, 0.72, faceSpan, 0.42), 0.08));
  armor.add("head_cheek_right_slash_led", plateCurvedZ(mirrorX(cheekSlash), (x) => faceFrontZ(x, 0.72, faceSpan, 0.42), 0.08));

  // 耳舱：侧面一个收窄的机械舱，替代 v1 的整侧大方板。
  const temple = [
    [-1.90, -2.42],
    [-1.34, -3.10],
    [0.86, -3.20],
    [1.44, -2.06],
    [1.10, -0.36],
    [-0.78, -0.10],
  ];
  armor.add("head_temple_left_gunmetal", plateX(temple, shellHx + 0.18, shellHx - 0.30));
  armor.add("head_temple_right_gunmetal", plateX(temple, -(shellHx + 0.18), -(shellHx - 0.30)));
  const templeTrace = [
    [-1.52, -2.72],
    [-1.22, -2.94],
    [0.62, -3.00],
    [0.44, -2.78],
  ];
  armor.add("head_temple_left_trace_led", plateX(templeTrace, shellHx + 0.26, shellHx + 0.16));
  armor.add("head_temple_right_trace_led", plateX(templeTrace, -(shellHx + 0.26), -(shellHx + 0.16)));

  // 后脑：中央竖脊 + 左右分瓣 + 亮边 + 可动鳍。
  armor.add(
    "head_rear_spine_gunmetal",
    plateZ(
      [
        [-0.44, -7.78],
        [0.44, -7.78],
        [0.66, -6.30],
        [0.44, -1.20],
        [0, -0.60],
        [-0.44, -1.20],
        [-0.66, -6.30],
      ],
      4.58,
      4.34,
    ),
  );
  armor.add(
    "head_crown_rear_cap_gunmetal",
    plateZ(
      [
        [-1.90, -8.42],
        [1.90, -8.42],
        [2.56, -7.74],
        [1.96, -7.10],
        [0, -7.50],
        [-1.96, -7.10],
        [-2.56, -7.74],
      ],
      4.40,
      4.14,
    ),
  );

  const rearPlate = [
    [0.50, -7.44],
    [2.94, -7.22],
    [3.52, -6.20],
    [3.22, -2.02],
    [2.34, -1.22],
    [0.80, -0.72],
    [0.50, -1.10],
  ];
  armor.add("head_rear_left_gunmetal", plateZ(rearPlate, 4.66, 4.42));
  armor.add("head_rear_right_gunmetal", plateZ(mirrorX(rearPlate), 4.66, 4.42));

  const rearTrim = [
    [2.88, -7.02],
    [3.24, -6.18],
    [2.96, -2.22],
    [2.48, -1.62],
    [2.30, -1.94],
    [2.62, -6.10],
  ];
  armor.add("head_rear_left_trim_titanium", plateZ(rearTrim, 4.79, 4.66));
  armor.add("head_rear_right_trim_titanium", plateZ(mirrorX(rearTrim), 4.79, 4.66));

  // 后脑鳍：向后上方张开的窄鳍，飞行姿态的主要识别件。
  const rearFin = [
    [2.78, -6.62 - spec.finLength * 0.9],
    [3.90, -5.80 - spec.finLength * 1.5],
    [4.10, -3.40 - spec.finLength * 0.4],
    [3.30, -2.30],
    [2.94, -2.82],
    [3.22, -5.60 - spec.finLength * 0.5],
  ];
  armor.add("head_rear_left_fin_gunmetal", plateZ(rearFin, 4.88, 4.66));
  armor.add("head_rear_right_fin_gunmetal", plateZ(mirrorX(rearFin), 4.88, 4.66));

  const rearChevron = [
    [0.86, -5.86],
    [1.12, -6.10],
    [2.48, -5.02],
    [2.22, -4.72],
    [1.14, -5.52],
  ];
  armor.add("head_rear_left_chevron_led", plateZ(rearChevron, 4.90, 4.80));
  armor.add("head_rear_right_chevron_led", plateZ(mirrorX(rearChevron), 4.90, 4.80));

  // 颈圈：头骨绑定的密封环，口径小于胸领，转头时保留活动缝。
  armor.add(
    "head_neck_seal_gunmetal",
    hull({
      rings: [
        { y: -0.32, hx: hw * 0.72, hz: hd * 0.66, bevel: 0.30 },
        { y: 0.06, hx: hw * 0.78, hz: hd * 0.70, bevel: 0.32 },
        { y: 0.48, hx: hw * 0.68, hz: hd * 0.62, bevel: 0.30 },
      ],
    }),
  );

  // 型号专属头部件
  if (spec.wingKind === "longfin") {
    // 疾影：后脑延伸到颈背的一体式长脊。
    armor.add(
      "head_swift_dorsal_rear_keel_titanium",
      plateZ(
        [
          [-0.62, -7.90],
          [0.62, -7.90],
          [0.90, -5.00],
          [0.52, -0.90],
          [0, -0.20],
          [-0.52, -0.90],
          [-0.90, -5.00],
        ],
        shellHz + 0.48,
        shellHz + 0.26,
      ),
    );
  }

  if (spec.wingKind === "wingbay") {
    // 守望：左右后脑装甲堡 + 面颊护盾 + 颈锁，强化「要塞」轮廓。
    const bastion = [
      [0.60, -8.10],
      [3.30, -7.70],
      [4.20, -6.40],
      [3.90, -3.10],
      [2.60, -2.20],
      [0.70, -2.60],
    ];
    armor.add("head_sentinel_rear_left_bastion_titanium", plateZ(bastion, shellHz + 0.52, shellHz + 0.18));
    armor.add("head_sentinel_rear_right_bastion_titanium", plateZ(mirrorX(bastion), shellHz + 0.52, shellHz + 0.18));
    armor.add(
      "head_sentinel_rear_spine_led",
      plateZ(
        [
          [-0.20, -8.20],
          [0.20, -8.20],
          [0.30, -6.60],
          [0, -5.60],
          [-0.30, -6.60],
        ],
        shellHz + 0.60,
        shellHz + 0.48,
      ),
    );

    const jawGuard = [
      [2.20, -4.90],
      [4.10, -4.50],
      [4.30, -2.60],
      [3.30, -1.10],
      [2.10, -2.00],
    ];
    armor.add("head_faceplate_sentinel_left_jaw_guard_titanium", plateCurvedZ(jawGuard, (x) => faceFrontZ(x, 0.30, faceSpan, 0.42), 0.28));
    armor.add("head_faceplate_sentinel_right_jaw_guard_titanium", plateCurvedZ(mirrorX(jawGuard), (x) => faceFrontZ(x, 0.30, faceSpan, 0.42), 0.28));
    armor.add(
      "head_faceplate_sentinel_brow_shield_titanium",
      plateCurvedZ(
        [
          [-3.40, -7.30],
          [3.40, -7.30],
          [3.90, -6.20],
          [2.10, -5.80],
          [-2.10, -5.80],
          [-3.90, -6.20],
        ],
        (x) => faceFrontZ(x, 0.30, faceSpan, 0.42),
        0.30,
      ),
    );
    armor.add(
      "head_faceplate_sentinel_brow_trace_led",
      ribbon({
        xs: [-3.30, -1.60, 0, 1.60, 3.30],
        topY: () => -6.70,
        bottomY: () => -6.42,
        zAtX: (x) => faceFrontZ(x, 0.66, faceSpan, 0.42),
        thickness: 0.10,
      }),
    );
    armor.add(
      "head_faceplate_sentinel_crown_bastion_titanium",
      plateZ(
        [
          [-2.80, -8.70],
          [2.80, -8.70],
          [3.60, -8.00],
          [2.90, -6.90],
          [0, -7.30],
          [-2.90, -6.90],
          [-3.60, -8.00],
        ],
        shellHz + 0.44,
        shellHz + 0.12,
      ),
    );
    armor.add(
      "head_faceplate_sentinel_command_node_led",
      plateCurvedZ(
        [
          [-0.30, -7.10],
          [0.30, -7.10],
          [0.42, -6.30],
          [0, -5.90],
          [-0.42, -6.30],
        ],
        (x) => faceFrontZ(x, 0.68, faceSpan, 0.42),
        0.10,
      ),
    );
    armor.add(
      "head_faceplate_sentinel_lock_gunmetal",
      hull({
        rings: [
          { y: -0.34, hx: hw * 0.84, hz: hd * 0.78, bevel: 0.32 },
          { y: 0.16, hx: hw * 0.92, hz: hd * 0.84, bevel: 0.34 },
          { y: 0.66, hx: hw * 0.80, hz: hd * 0.74, bevel: 0.32 },
        ],
      }),
    );
    armor.add(
      "visor_sentinel_upper_lock_titanium",
      ribbon({
        xs: faceXs,
        topY: (x) => -6.62 + 0.35 * Math.abs(x / 3.6),
        bottomY: (x) => -6.28 + 0.35 * Math.abs(x / 3.6),
        zAtX: (x) => faceFrontZ(x, 0.62, faceSpan, 0.42),
        thickness: 0.20,
      }),
    );
    armor.add(
      "visor_sentinel_lower_lock_gunmetal",
      ribbon({
        xs: faceXs,
        topY: (x) => -4.46 - 0.40 * Math.abs(x / 3.6),
        bottomY: (x) => -4.10 - 0.40 * Math.abs(x / 3.6),
        zAtX: (x) => faceFrontZ(x, 0.60, faceSpan, 0.42),
        thickness: 0.22,
      }),
    );
  }
};

// -----------------------------------------------------------------------------
// 躯干：领 → 胸 → 腹 → 腰，收束轮廓 + 贯穿灯槽
// -----------------------------------------------------------------------------

const buildTorso = (armor, spec) => {
  const skinHx = 4.36;
  const skinHz = 2.36;

  armor.add(
    "body_underlayer_shadow",
    hull({
      rings: [
        { y: -0.40, hx: skinHx * 0.98, hz: skinHz * 0.98, bevel: 0.30 },
        { y: 1.40, hx: skinHx * 1.0, hz: skinHz * 1.0, bevel: 0.30 },
        { y: 5.60, hx: skinHx * 1.0, hz: skinHz * 1.0, bevel: 0.30 },
        { y: 10.90, hx: skinHx * 0.98, hz: skinHz * 0.96, bevel: 0.30 },
        { y: 12.50, hx: skinHx * 0.94, hz: skinHz * 0.92, bevel: 0.30 },
      ],
    }),
  );

  // 领圈：胸甲上沿的内收台阶，把颈部与胸部切开。
  armor.add(
    "body_collar_gunmetal",
    hull({
      rings: [
        { y: -0.86, hx: 3.30 * spec.collarWidth, hz: 2.36, bevel: 0.30 },
        { y: -0.30, hx: 3.78 * spec.collarWidth, hz: 2.72, bevel: 0.34, front: 0.10 },
        { y: 0.70, hx: 3.58 * spec.collarWidth, hz: 2.60, bevel: 0.32, front: 0.06 },
      ],
    }),
  );

  // 胸甲：一层薄壳提供体积，外面再压两块前倾大板，中间让出核心槽。
  const chestHx = 4.86 * spec.chestWidth;
  const chestHz = 3.02 * spec.chestDepth;
  armor.add(
    "body_chest_titanium",
    hull({
      rings: [
        { y: -0.05, hx: chestHx * 0.84, hz: chestHz * 0.84, bevel: 0.36, front: 0.10 },
        { y: 1.60, hx: chestHx * 0.96, hz: chestHz * 0.96, bevel: 0.42, front: 0.18 },
        { y: 4.20, hx: chestHx, hz: chestHz, bevel: 0.46, front: 0.24 },
        { y: 6.30, hx: chestHx * 0.88, hz: chestHz * 0.86, bevel: 0.38, front: 0.14 },
      ],
    }),
  );

  // 左右胸板：肩侧厚、胸中薄，向前下方倾斜，是正面最重的一块结构。
  const chestPlate = [
    [0.70, 5.60],
    [3.70, 5.30],
    [chestHx, 1.90],
    [chestHx * 0.92, -0.30],
    [1.40, -0.60],
    [0.70, 1.00],
  ];
  const chestZ = (y) => -chestHz - 0.62 * (1 - Math.min(1, Math.abs(y - 3.0) / 4.6) ** 1.5);
  armor.add("body_chest_titanium", plateSlopedZ(chestPlate, chestZ, 0.96));
  armor.add("body_chest_titanium", plateSlopedZ(mirrorX(chestPlate), chestZ, 0.96));

  // 胸板分割缝：沿板外缘走一圈深色细条，把大面切开，避免糊成一整片。
  const chestSeam = [
    [chestHx - 0.36, 5.00],
    [chestHx - 0.12, 5.06],
    [chestHx + 0.08, 1.70],
    [chestHx - 0.18, 1.58],
  ];
  armor.add("body_chest_seam_gunmetal", plateSlopedZ(chestSeam, (y) => chestZ(y) - 0.06, 0.14));
  armor.add(
    "body_chest_seam_gunmetal",
    plateSlopedZ(mirrorX(chestSeam), (y) => chestZ(y) - 0.06, 0.14),
  );

  // 腹甲与腰甲：两段收束，腰最窄，再到下摆外张。
  const waistHx = 4.40 * spec.waistWidth;
  armor.add(
    "body_abdomen_titanium",
    hull({
      rings: [
        { y: 5.90, hx: 4.18, hz: 2.78, bevel: 0.40, front: 0.26 },
        { y: 7.60, hx: 3.88 * spec.waistWidth, hz: 2.62, bevel: 0.38, front: 0.14 },
        { y: 9.10, hx: 3.66 * spec.waistWidth, hz: 2.52, bevel: 0.36, front: 0.08 },
      ],
    }),
  );
  const hipHx = 4.58 * spec.hipWidth;
  armor.add(
    "body_waist_titanium",
    hull({
      rings: [
        { y: 8.70, hx: 3.58 * spec.waistWidth, hz: 2.54, bevel: 0.34, front: 0.06 },
        { y: 10.40, hx: hipHx * 0.96, hz: 2.70, bevel: 0.42, front: 0.14 },
        { y: 12.20, hx: hipHx, hz: 2.80, bevel: 0.44, front: 0.16 },
        { y: 12.62, hx: hipHx * 0.88, hz: 2.54, bevel: 0.38 },
      ],
    }),
  );

  // 背板与背脊：背部唯一的凸起结构，侧视给躯干做出纵深。
  armor.add(
    "body_backplate_titanium",
    plateZ(
      [
        [-3.90, 1.10],
        [3.90, 1.10],
        [4.30, 5.20],
        [3.40, 9.90],
        [-3.40, 9.90],
        [-4.30, 5.20],
      ],
      2.34,
      3.06,
    ),
  );
  armor.add(
    "body_spine_gunmetal",
    plateZ(
      [
        [-0.62, 0.70],
        [0.62, 0.70],
        [0.86, 6.20],
        [0.54, 11.60],
        [-0.54, 11.60],
        [-0.86, 6.20],
      ],
      3.06,
      2.72,
    ),
  );

  // 胸口 V 灯：左右各一条，从肩侧收到核心，是全身灯槽的起点。
  const trace = [
    [3.10, -0.10],
    [3.34, 0.90],
    [1.10, 3.30],
    [0.62, 2.60],
    [2.62, 0.42],
  ];
  armor.add(
    "body_chest_trace_led",
    plateCurvedZ(trace, (x) => -3.10 - 0.62 * (1 - Math.min(1, Math.abs(x) / 4.4) ** 2), 0.10),
  );
  armor.add(
    "body_chest_trace_led",
    plateCurvedZ(mirrorX(trace), (x) => -3.10 - 0.62 * (1 - Math.min(1, Math.abs(x) / 4.4) ** 2), 0.10),
  );

  // 胸前核心：外壳 + 深色座 + 发光堆，三件叠出凹陷感。
  const coreZ = -3.10 - 0.62;
  armor.add(
    "core_housing_titanium",
    plateCurvedZ(
      [
        [-1.34, 1.20],
        [1.34, 1.20],
        [1.72, 2.10],
        [1.72, 3.90],
        [1.24, 4.72],
        [-1.24, 4.72],
        [-1.72, 3.90],
        [-1.72, 2.10],
      ],
      (x) => coreZ + 0.28 * (1 - Math.min(1, Math.abs(x) / 1.9) ** 2),
      0.34,
    ),
  );
  armor.add(
    "core_cradle_gunmetal",
    plateCurvedZ(
      [
        [-1.06, 1.72],
        [1.06, 1.72],
        [1.38, 2.30],
        [1.38, 3.70],
        [1.00, 4.28],
        [-1.00, 4.28],
        [-1.38, 3.70],
        [-1.38, 2.30],
      ],
      (x) => coreZ - 0.06 + 0.24 * (1 - Math.min(1, Math.abs(x) / 1.5) ** 2),
      0.20,
    ),
  );
  armor.add(
    "core_reactor_led",
    plateCurvedZ(
      [
        [-0.66, 2.24],
        [0.66, 2.24],
        [0.82, 3.04],
        [0, 3.94],
        [-0.82, 3.04],
      ],
      (x) => coreZ - 0.22 + 0.18 * (1 - Math.min(1, Math.abs(x) / 0.9) ** 2),
      0.12,
    ),
  );

  if (spec.wingKind === "longfin") {
    // 疾影：腹部收成一条纵向龙骨，正面形成锐利的中线。
    armor.add(
      "body_swift_center_keel_titanium",
      plateZ(
        [
          [-0.76, 5.60],
          [0.76, 5.60],
          [0.94, 8.40],
          [0.52, 12.30],
          [-0.52, 12.30],
          [-0.94, 8.40],
        ],
        2.94,
        2.58,
      ),
    );
    armor.add(
      "body_swift_keel_trace_led",
      plateZ(
        [
          [-0.22, 5.90],
          [0.22, 5.90],
          [0.30, 8.60],
          [0, 12.10],
          [-0.30, 8.60],
        ],
        3.06,
        2.94,
      ),
    );
  }

  if (spec.wingKind === "wingbay") {
    // 守望：胸口装甲堡 + 腹侧护墙 + 腰带围裙，整体加厚一档。
    armor.add(
      "body_sentinel_collar_yoke_gunmetal",
      hull({
        rings: [
          { y: -1.20, hx: 3.24 * spec.collarWidth, hz: 2.62, bevel: 0.30 },
          { y: -0.45, hx: 4.06 * spec.collarWidth, hz: 3.02, bevel: 0.36, front: 0.16 },
          { y: 0.80, hx: 3.86 * spec.collarWidth, hz: 2.88, bevel: 0.34, front: 0.10 },
        ],
      }),
    );
    armor.add(
      "core_housing_sentinel_titanium",
      plateCurvedZ(
        [
          [-1.60, 0.90],
          [1.60, 0.90],
          [2.02, 2.00],
          [2.02, 4.10],
          [1.46, 5.00],
          [-1.46, 5.00],
          [-2.02, 4.10],
          [-2.02, 2.00],
        ],
        (x) => coreZ - 0.32 + 0.30 * (1 - Math.min(1, Math.abs(x) / 2.2) ** 2),
        0.36,
      ),
    );
    armor.add(
      "core_cradle_sentinel_gunmetal",
      plateCurvedZ(
        [
          [-1.16, 1.82],
          [1.16, 1.82],
          [1.50, 2.42],
          [1.50, 3.58],
          [1.08, 4.28],
          [-1.08, 4.28],
          [-1.50, 3.58],
          [-1.50, 2.42],
        ],
        (x) => coreZ - 0.48 + 0.26 * (1 - Math.min(1, Math.abs(x) / 1.6) ** 2),
        0.22,
      ),
    );
    armor.add(
      "core_reactor_sentinel_led",
      plateCurvedZ(
        [
          [-0.72, 2.32],
          [0.72, 2.32],
          [0.88, 3.12],
          [0, 4.06],
          [-0.88, 3.12],
        ],
        (x) => coreZ - 0.62 + 0.20 * (1 - Math.min(1, Math.abs(x) / 1.0) ** 2),
        0.12,
      ),
    );
    armor.add(
      "body_sentinel_chest_frame_gunmetal",
      hull({
        rings: [
          { y: -0.50, hx: 4.10, hz: 2.66, bevel: 0.36 },
          { y: 1.20, hx: 5.24, hz: 3.32, bevel: 0.44, front: 0.44 },
          { y: 4.40, hx: 5.34, hz: 3.40, bevel: 0.48, front: 0.54 },
          { y: 6.60, hx: 4.86, hz: 3.10, bevel: 0.42, front: 0.28 },
        ],
      }),
    );
    armor.add(
      "body_sentinel_chest_bastion_titanium",
      plateCurvedZ(
        [
          [-3.60, 0.10],
          [3.60, 0.10],
          [4.30, 1.60],
          [4.10, 4.60],
          [2.60, 6.10],
          [-2.60, 6.10],
          [-4.10, 4.60],
          [-4.30, 1.60],
        ],
        (x) => -3.20 - 0.78 * (1 - Math.min(1, Math.abs(x) / 5.0) ** 2),
        0.42,
      ),
    );
    armor.add(
      "body_sentinel_segment_trace_led",
      plateCurvedZ(
        [
          [-4.30, 0.70],
          [-4.10, 1.00],
          [-3.20, 5.60],
          [-3.44, 5.90],
        ],
        (x) => -3.20 - 0.86 * (1 - Math.min(1, Math.abs(x) / 5.0) ** 2),
        0.10,
      ),
    );
    armor.add(
      "body_sentinel_segment_trace_led",
      plateCurvedZ(
        [
          [4.30, 0.70],
          [4.10, 1.00],
          [3.20, 5.60],
          [3.44, 5.90],
        ],
        (x) => -3.20 - 0.86 * (1 - Math.min(1, Math.abs(x) / 5.0) ** 2),
        0.10,
      ),
    );
    armor.add(
      "body_sentinel_abdominal_bulwark_titanium",
      hull({
        rings: [
          { y: 6.30, hx: 4.86, hz: 3.06, bevel: 0.42, front: 0.32 },
          { y: 8.10, hx: 4.74, hz: 2.98, bevel: 0.40, front: 0.24 },
          { y: 9.60, hx: 4.50, hz: 2.86, bevel: 0.38, front: 0.16 },
        ],
      }),
    );
    armor.add(
      "body_sentinel_waist_apron_titanium",
      plateZ(
        [
          [-4.20, 9.10],
          [4.20, 9.10],
          [4.90, 10.90],
          [3.60, 12.80],
          [-3.60, 12.80],
          [-4.90, 10.90],
        ],
        3.16,
        2.72,
      ),
    );
    armor.add(
      "body_backplate_sentinel_load_frame_gunmetal",
      plateZ(
        [
          [-4.10, 1.40],
          [4.10, 1.40],
          [4.60, 5.40],
          [3.60, 10.60],
          [-3.60, 10.60],
          [-4.60, 5.40],
        ],
        3.34,
        3.02,
      ),
    );
    armor.add(
      "body_backplate_sentinel_lock_titanium",
      plateZ(
        [
          [-1.10, 2.20],
          [1.10, 2.20],
          [1.34, 5.40],
          [0.90, 9.80],
          [-0.90, 9.80],
          [-1.34, 5.40],
        ],
        3.52,
        3.30,
      ),
    );
    armor.add(
      "body_spine_sentinel_segment_node_led",
      plateZ(
        [
          [-0.34, 2.60],
          [0.34, 2.60],
          [0.42, 4.40],
          [-0.42, 4.40],
        ],
        3.64,
        3.52,
      ),
    );
    armor.add(
      "body_spine_sentinel_segment_node_led",
      plateZ(
        [
          [-0.34, 6.20],
          [0.34, 6.20],
          [0.42, 8.00],
          [-0.42, 8.00],
        ],
        3.64,
        3.52,
      ),
    );
  }
};

// -----------------------------------------------------------------------------
// 手臂：肩甲外张 → 上臂 → 肘封 → 前臂（带武器挂点）
// -----------------------------------------------------------------------------

const buildArm = (armor, spec, side) => {
  const isLeft = side === "left";
  const cx = 1.0;
  const prefix = `arm_${side}`;
  // 全部按左臂坐标编写，右臂由 builder 的镜像开关整体翻转。
  const outer = (value) => value;
  const addMirrored = (name, mesh) => armor.add(name, mesh);
  armor.mirror = !isLeft;

  const bulk = spec.armBulk;
  const span = spec.pauldronSpan;
  const depth = spec.pauldronDepth;

  armor.add(
    `${prefix}_upper_underlayer_shadow`,
    hull({
      rings: [
        { y: -2.40, hx: 2.24, hz: 2.24, cx, bevel: 0.28 },
        { y: -1.10, hx: 2.40, hz: 2.40, cx, bevel: 0.30 },
        { y: 3.40, hx: 2.42, hz: 2.42, cx, bevel: 0.30 },
        { y: 5.92, hx: 2.40, hz: 2.40, cx, bevel: 0.30 },
      ],
    }),
  );

  // 肩甲：整条手臂最厚的一块，越往下越外张，给上身做出倒三角。
  armor.add(
    `${prefix}_upper_pauldron_titanium`,
    hull({
      rings: [
        { y: -3.25, hx: 1.40 * bulk * span, hz: 1.90 * bulk * depth, cx: cx * 0.96, bevel: 0.30, front: 0.12 },
        { y: -2.60, hx: 2.05 * bulk * span, hz: 2.44 * bulk * depth, cx: cx * 1.05, bevel: 0.36, front: 0.34 },
        { y: -1.40, hx: 2.44 * bulk * span, hz: 2.78 * bulk * depth, cx: cx * 1.16, bevel: 0.42, front: 0.48 },
        { y: -0.10, hx: 2.52 * bulk * span, hz: 2.84 * bulk * depth, cx: cx * 1.20, bevel: 0.44, front: 0.46 },
        { y: 1.40, hx: 2.16 * bulk * span, hz: 2.40 * bulk * depth, cx: cx * 1.12, bevel: 0.36, front: 0.22 },
      ],
    }),
  );

  // 肩甲顶盖：一块水平板压住肩顶，做出「肩膀上再叠一层甲」的读感。
  armor.add(
    `${prefix}_upper_pauldron_titanium`,
    plateY(
      [
        [cx - 0.90, -2.55],
        [cx + 2.90 * span, -2.05],
        [cx + 3.10 * span, 0.40],
        [cx - 0.70, 0.30],
      ],
      -3.60,
      -3.06,
    ),
  );

  armor.add(
    `${prefix}_upper_plate_titanium`,
    hull({
      rings: [
        { y: 0.90, hx: 2.36 * bulk, hz: 2.36 * bulk, cx, bevel: 0.32, front: 0.18 },
        { y: 3.20, hx: 2.44 * bulk, hz: 2.42 * bulk, cx, bevel: 0.34, front: 0.22 },
        { y: 5.60, hx: 2.34 * bulk, hz: 2.34 * bulk, cx, bevel: 0.32, front: 0.16 },
      ],
    }),
  );

  // 外侧结构轨：沿上臂纵向的一条深色框，是把「机甲」读出来的关键。
  armor.add(
    `${prefix}_upper_outer_rail_gunmetal`,
    plateX(
      [
        [-2.20, -2.30],
        [-1.60, -2.86],
        [3.60, -2.80],
        [4.20, -2.10],
        [3.90, 1.10],
        [-1.80, 1.30],
      ],
      outer(cx + 2.46 * bulk + 0.24),
      outer(cx + 2.46 * bulk - 0.20),
    ),
  );

  // 上臂外灯：从肩甲下缘直贯到肘部。
  armor.add(
    `${prefix}_upper_energy_trace_led`,
    plateX(
      [
        [-1.30, -2.62],
        [-1.02, -2.80],
        [3.30, -2.74],
        [3.46, -2.52],
        [3.20, -2.40],
        [-1.02, -2.44],
      ],
      outer(cx + 2.46 * bulk + 0.34),
      outer(cx + 2.46 * bulk + 0.24),
    ),
  );

  armor.add(
    `${prefix}_upper_rear_plate_titanium`,
    plateZ(
      [
        [cx - 2.30 * bulk, 0.90],
        [cx + 2.30 * bulk, 0.90],
        [cx + 2.34 * bulk, 3.60],
        [cx + 1.60 * bulk, 4.16],
        [cx - 2.30 * bulk, 3.94],
      ],
      2.42 * bulk + 0.06,
      2.42 * bulk - 0.18,
    ),
  );

  // 肘封：上下两段之间的柔性连接，弯曲时露出，是「分层」的实物证据。
  armor.add(
    `${prefix}_elbow_seal_shadow`,
    hull({
      rings: [
        { y: 5.66, hx: 2.30, hz: 2.30, cx, bevel: 0.28 },
        { y: 6.00, hx: 2.36, hz: 2.36, cx, bevel: 0.30 },
        { y: 6.34, hx: 2.30, hz: 2.30, cx, bevel: 0.28 },
      ],
    }),
  );

  armor.add(
    `${prefix}_forearm_underlayer_shadow`,
    hull({
      rings: [
        { y: 6.00, hx: 2.40, hz: 2.40, cx, bevel: 0.28 },
        { y: 7.40, hx: 2.42, hz: 2.42, cx, bevel: 0.30 },
        { y: 10.44, hx: 2.40, hz: 2.40, cx, bevel: 0.30 },
      ],
    }),
  );

  // 前臂甲：向腕部收束，给武器挂点留出干净的外侧面。
  armor.add(
    `${prefix}_forearm_plate_titanium`,
    hull({
      rings: [
        { y: 6.20, hx: 2.42 * bulk, hz: 2.42 * bulk, cx, bevel: 0.32, front: 0.24 },
        { y: 8.10, hx: 2.50 * bulk, hz: 2.46 * bulk, cx, bevel: 0.34, front: 0.26 },
        { y: 10.28, hx: 2.30 * bulk, hz: 2.28 * bulk, cx, bevel: 0.30, front: 0.16 },
      ],
    }),
  );

  armor.add(
    `${prefix}_forearm_outer_rail_gunmetal`,
    plateX(
      [
        [6.30, -2.40],
        [6.90, -2.92],
        [9.90, -2.86],
        [10.42, -2.20],
        [10.10, 1.00],
        [6.40, 1.10],
      ],
      outer(cx + 2.52 * bulk + 0.22),
      outer(cx + 2.52 * bulk - 0.22),
    ),
  );

  armor.add(
    `${prefix}_forearm_energy_trace_led`,
    plateX(
      [
        [6.60, -2.70],
        [6.86, -2.86],
        [9.70, -2.80],
        [9.86, -2.58],
        [9.60, -2.46],
        [6.80, -2.52],
      ],
      outer(cx + 2.52 * bulk + 0.32),
      outer(cx + 2.52 * bulk + 0.22),
    ),
  );

  armor.add(
    `${prefix}_forearm_rear_plate_titanium`,
    plateZ(
      [
        [cx - 2.34 * bulk, 6.44],
        [cx + 2.34 * bulk, 6.44],
        [cx + 2.38 * bulk, 10.06],
        [cx + 1.64 * bulk, 10.30],
        [cx - 2.34 * bulk, 10.18],
      ],
      2.46 * bulk + 0.06,
      2.46 * bulk - 0.18,
    ),
  );

  if (spec.wingKind === "longfin") {
    // 疾影：前臂后侧展开的长导流刃，让轻甲也有攻击性轮廓。
    addMirrored(
      `${prefix}_swift_forearm_rear_blade_titanium`,
      plateZ(
        [
          [cx - 2.24, 6.34],
          [cx + 2.24, 6.34],
          [cx + 2.06, 11.40],
          [cx + 0.62, 13.40],
          [cx - 0.62, 13.40],
          [cx - 2.06, 11.40],
        ],
        2.76,
        2.44,
      ),
    );
  }

  if (spec.wingKind === "wingbay") {
    // 守望：上臂堡 + 前甲 + 顶部塔台 + 节点灯，前臂整段变成武器平台。
    addMirrored(
      `${prefix}_sentinel_upper_bastion_titanium`,
      plateX(
        [
          [-2.80, -2.90],
          [-1.90, -3.60],
          [1.20, -3.54],
          [2.00, -2.60],
          [1.70, 1.40],
          [-2.40, 1.60],
        ],
        outer(cx + 2.90),
        outer(cx + 2.30),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_upper_frontplate_titanium`,
      plateZ(
        [
          [-1.40, -2.70],
          [2.20, -2.60],
          [2.50, 0.90],
          [1.20, 1.40],
          [-1.50, 1.20],
        ],
        cx - 2.96,
        cx - 2.58,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_upper_tower_titanium`,
      plateY(
        [
          [cx - 0.60, -2.60],
          [cx + 2.40, -2.60],
          [cx + 2.70, 0.40],
          [cx - 0.50, 0.50],
        ],
        -3.40,
        -2.86,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_upper_node_led`,
      plateX(
        [
          [-2.20, -3.30],
          [-1.60, -3.52],
          [0.00, -3.48],
          [0.16, -3.26],
          [-1.60, -3.20],
        ],
        outer(cx + 3.02),
        outer(cx + 2.90),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_bulwark_titanium`,
      plateX(
        [
          [6.40, -2.90],
          [7.30, -3.60],
          [10.20, -3.50],
          [10.80, -2.40],
          [10.30, 1.30],
          [6.50, 1.50],
        ],
        outer(cx + 2.94),
        outer(cx + 2.30),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_outer_brace_gunmetal`,
      plateX(
        [
          [6.60, -3.20],
          [7.20, -3.86],
          [10.10, -3.78],
          [10.62, -3.10],
          [10.30, -2.60],
          [6.80, -2.70],
        ],
        outer(cx + 3.16),
        outer(cx + 2.96),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_wrist_lock_gunmetal`,
      plateX(
        [
          [9.30, -2.60],
          [9.70, -3.20],
          [10.60, -3.14],
          [10.80, -2.40],
          [10.50, 0.70],
          [9.50, 0.80],
        ],
        outer(cx + 3.10),
        outer(cx + 2.72),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_emitter_track_led`,
      plateX(
        [
          [7.00, -3.90],
          [7.24, -4.02],
          [10.00, -3.96],
          [10.10, -3.80],
          [7.20, -3.76],
        ],
        outer(cx + 3.30),
        outer(cx + 3.18),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_emitter_shutter_gunmetal`,
      plateX(
        [
          [7.10, -4.30],
          [7.60, -4.62],
          [9.80, -4.58],
          [10.10, -4.24],
          [9.70, -4.00],
          [7.30, -4.02],
        ],
        cx + 0.60,
        cx - 0.20,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_cover_outer_titanium`,
      plateZ(
        [
          [6.60, -2.40],
          [10.40, -2.30],
          [10.60, 0.60],
          [9.80, 1.40],
          [6.70, 1.30],
        ],
        cx + 3.20,
        cx + 2.80,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_cover_inner_titanium`,
      plateZ(
        [
          [6.60, -2.40],
          [10.40, -2.30],
          [10.60, 0.60],
          [9.80, 1.40],
          [6.70, 1.30],
        ],
        cx - 3.20,
        cx - 2.80,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_hand_shell_outer_titanium`,
      plateX(
        [
          [10.20, -2.40],
          [10.60, -2.90],
          [11.40, -2.60],
          [11.50, -0.80],
          [10.40, -0.70],
        ],
        outer(cx + 3.00),
        outer(cx + 2.40),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_forearm_hand_shell_inner_titanium`,
      plateX(
        [
          [10.20, -2.40],
          [10.60, -2.90],
          [11.40, -2.60],
          [11.50, -0.80],
          [10.40, -0.70],
        ],
        outer(cx - 3.00),
        outer(cx - 2.40),
      ),
    );
  }
  armor.mirror = false;
};

// -----------------------------------------------------------------------------
// 腿与靴：大腿 → 膝 → 小腿 → 重力环 → 靴尖
// -----------------------------------------------------------------------------

const buildLeg = (armor, spec, side) => {
  const isLeft = side === "left";
  const prefix = `leg_${side}`;
  const outer = (value) => value;
  const addMirrored = (name, mesh) => armor.add(name, mesh);
  const bulk = spec.thighBulk;
  armor.mirror = !isLeft;

  armor.add(
    `${prefix}_thigh_underlayer_shadow`,
    hull({
      rings: [
        { y: -0.35, hx: 2.36, hz: 2.36, bevel: 0.28 },
        { y: 2.60, hx: 2.42, hz: 2.42, bevel: 0.30 },
        { y: 6.15, hx: 2.38, hz: 2.38, bevel: 0.30 },
      ],
    }),
  );

  // 大腿甲：上粗下收，是整条腿体量的来源。
  armor.add(
    `${prefix}_thigh_plate_titanium`,
    hull({
      rings: [
        { y: -0.20, hx: 2.34 * bulk, hz: 2.30 * bulk, bevel: 0.32, front: 0.20 },
        { y: 2.40, hx: 2.46 * bulk, hz: 2.40 * bulk, bevel: 0.34, front: 0.26 },
        { y: 4.60, hx: 2.36 * bulk, hz: 2.32 * bulk, bevel: 0.32, front: 0.18 },
        { y: 5.50, hx: 2.24 * bulk, hz: 2.22 * bulk, bevel: 0.30, front: 0.12 },
      ],
    }),
  );

  // 外侧翼板：大腿外侧一块外张的板，正面轮廓的收束点。
  addMirrored(
    `${prefix}_thigh_outer_flank_gunmetal`,
    plateX(
      [
        [-0.10, -2.40],
        [0.60, -3.14],
        [4.40, -3.00],
        [5.30, -2.10],
        [4.90, 1.40],
        [0.20, 1.70],
      ],
      outer(2.62 * bulk),
      outer(2.30 * bulk),
    ),
  );

  armor.add(
    `${prefix}_thigh_rear_plate_titanium`,
    plateZ(
      [
        [-1.20, 0.20],
        [2.10, 0.30],
        [2.30, 4.40],
        [1.30, 5.40],
        [-1.30, 5.20],
      ],
      2.42 * bulk + 0.24,
      2.42 * bulk - 0.10,
    ),
  );

  addMirrored(
    `${prefix}_thigh_energy_trace_led`,
    plateX(
      [
        [0.40, -2.86],
        [0.66, -3.04],
        [4.20, -2.94],
        [4.36, -2.70],
        [4.10, -2.56],
        [0.60, -2.66],
      ],
      outer(2.62 * bulk + 0.14),
      outer(2.62 * bulk + 0.04),
    ),
  );

  // 膝甲：向前的突起，把大腿与小腿在视觉上分段。
  armor.add(
    `${prefix}_thigh_knee_titanium`,
    hull({
      rings: [
        { y: 4.60, hx: 2.24 * bulk, hz: 2.20 * bulk, bevel: 0.28, front: 0.24 },
        { y: 5.60, hx: 2.42 * bulk, hz: 2.34 * bulk, bevel: 0.30, front: 0.46 },
        { y: 6.60, hx: 2.28 * bulk, hz: 2.24 * bulk, bevel: 0.28, front: 0.26 },
      ],
    }),
  );

  armor.add(
    `${prefix}_knee_seal_shadow`,
    hull({
      rings: [
        { y: 5.70, hx: 2.30, hz: 2.30, bevel: 0.26 },
        { y: 6.10, hx: 2.36, hz: 2.36, bevel: 0.28 },
        { y: 6.50, hx: 2.30, hz: 2.30, bevel: 0.26 },
      ],
    }),
  );

  if (spec.wingKind === "wingbay") {
    // 守望：髋盾 + 大腿装甲堡 + 后锁 + 膝锁 + 节点灯。
    addMirrored(
      `${prefix}_sentinel_hip_shield_titanium`,
      plateX(
        [
          [-0.90, -2.60],
          [-0.10, -3.50],
          [3.40, -3.20],
          [3.60, 0.10],
          [0.60, 1.60],
          [-0.80, 1.10],
        ],
        outer(2.86 * bulk),
        outer(2.20 * bulk),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_thigh_bastion_titanium`,
      plateZ(
        [
          [-2.00, 0.40],
          [2.40, 0.50],
          [2.60, 3.40],
          [1.60, 4.60],
          [-2.00, 4.40],
        ],
        2.42 * bulk + 0.48,
        2.42 * bulk + 0.10,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_thigh_rearlock_gunmetal`,
      plateZ(
        [
          [-1.10, 1.00],
          [1.60, 1.10],
          [1.70, 3.20],
          [0.90, 3.80],
          [-1.10, 3.60],
        ],
        2.42 * bulk + 0.64,
        2.42 * bulk + 0.46,
      ),
    );
    addMirrored(
      `${prefix}_sentinel_thigh_node_led`,
      plateX(
        [
          [0.80, -3.30],
          [1.20, -3.52],
          [2.60, -3.44],
          [2.72, -3.20],
          [1.20, -3.16],
        ],
        outer(2.98 * bulk),
        outer(2.88 * bulk),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_knee_lock_titanium`,
      hull({
        rings: [
          { y: 4.90, hx: 2.34 * bulk, hz: 2.28 * bulk, bevel: 0.28, front: 0.40 },
          { y: 5.60, hx: 2.56 * bulk, hz: 2.44 * bulk, bevel: 0.32, front: 0.62 },
          { y: 6.40, hx: 2.40 * bulk, hz: 2.32 * bulk, bevel: 0.28, front: 0.40 },
        ],
      }),
    );
  }
  armor.mirror = false;
};

const buildBoot = (armor, spec, side) => {
  const isLeft = side === "left";
  const prefix = `boot_${side}`;
  const outer = (value) => value;
  const addMirrored = (name, mesh) => armor.add(name, mesh);
  const bulk = spec.bootBulk;
  armor.mirror = !isLeft;

  armor.add(
    `${prefix}_underlayer_shadow`,
    hull({
      rings: [
        { y: 7.90, hx: 2.38, hz: 2.38, bevel: 0.26 },
        { y: 10.20, hx: 2.42, hz: 2.42, bevel: 0.28 },
        { y: 12.50, hx: 2.40, hz: 2.46, bevel: 0.28 },
      ],
    }),
  );

  // 小腿甲：上收下展，和靴筒连成一体。
  armor.add(
    `${prefix}_calf_plate_titanium`,
    hull({
      rings: [
        { y: 6.30, hx: 2.30 * bulk, hz: 2.26 * bulk, bevel: 0.30, front: 0.16 },
        { y: 8.40, hx: 2.54 * bulk, hz: 2.48 * bulk, bevel: 0.34, front: 0.30 },
        { y: 10.60, hx: 2.50 * bulk, hz: 2.52 * bulk, bevel: 0.34, front: 0.28 },
      ],
    }),
  );

  addMirrored(
    `${prefix}_outer_guard_titanium`,
    plateX(
      [
        [6.60, -2.60],
        [7.40, -3.30],
        [10.40, -3.20],
        [10.90, -2.20],
        [10.60, 1.60],
        [6.80, 1.80],
      ],
      outer(2.62 * bulk),
      outer(2.24 * bulk),
    ),
  );

  armor.add(
    `${prefix}_ankle_frame_gunmetal`,
    hull({
      rings: [
        { y: 9.60, hx: 2.44 * bulk, hz: 2.46 * bulk, bevel: 0.30 },
        { y: 10.40, hx: 2.52 * bulk, hz: 2.56 * bulk, bevel: 0.32 },
        { y: 11.20, hx: 2.42 * bulk, hz: 2.50 * bulk, bevel: 0.30 },
      ],
    }),
  );

  armor.add(
    `${prefix}_heel_gunmetal`,
    plateZ(
      [
        [-2.20, 10.40],
        [2.20, 10.40],
        [2.30, 12.40],
        [1.20, 12.90],
        [-1.20, 12.90],
        [-2.30, 12.40],
      ],
      2.60 * bulk,
      2.20 * bulk,
    ),
  );

  armor.add(
    `${prefix}_toe_titanium`,
    plateZ(
      [
        [-2.20, 11.10],
        [2.20, 11.10],
        [2.00, 12.50],
        [0.90, 12.90],
        [-0.90, 12.90],
        [-2.00, 12.50],
      ],
      -2.70 * bulk,
      -2.10 * bulk,
    ),
  );

  // 重力环：环绕脚踝的一圈灯，落地悬停时最显眼的部件。
  armor.add(
    `${prefix}_gravity_ring_led`,
    ringY({
      cy: 11.10,
      rx: 2.50 * bulk,
      rz: 2.56 * bulk,
      tube: 0.24,
      majorSegments: 16,
    }),
  );

  armor.add(
    `${prefix}_sole_field_led`,
    plateY(
      [
        [-2.10, -2.30],
        [2.10, -2.30],
        [2.30, 0.40],
        [2.10, 2.40],
        [-2.10, 2.40],
        [-2.30, 0.40],
      ],
      12.98,
      12.62,
    ),
  );

  armor.add(
    `${prefix}_energy_trace_led`,
    plateX(
      [
        [6.80, -2.94],
        [7.06, -3.12],
        [10.20, -3.02],
        [10.34, -2.78],
        [10.10, -2.64],
        [7.00, -2.74],
      ],
      outer(2.62 * bulk + 0.14),
      outer(2.62 * bulk + 0.04),
    ),
  );

  if (spec.wingKind === "wingbay") {
    // 守望：小腿堡 + 外侧稳定器 + 靴尖撞角 + 后跟锁 + 重力节点。
    addMirrored(
      `${prefix}_sentinel_calf_bastion_titanium`,
      plateX(
        [
          [6.40, -3.10],
          [7.20, -3.90],
          [10.30, -3.76],
          [10.90, -2.60],
          [10.60, 2.00],
          [6.60, 2.20],
        ],
        outer(2.90 * bulk),
        outer(2.26 * bulk),
      ),
    );
    addMirrored(
      `${prefix}_sentinel_outer_stabilizer_titanium`,
      plateZ(
        [
          [-1.60, 7.00],
          [2.60, 7.10],
          [2.80, 10.60],
          [1.60, 11.40],
          [-1.60, 11.20],
        ],
        2.52 * bulk + 0.42,
        2.52 * bulk + 0.06,
      ),
    );
    armor.add(
      `${prefix}_sentinel_toe_ram_titanium`,
      plateZ(
        [
          [-2.40, 11.40],
          [2.40, 11.40],
          [2.10, 12.60],
          [0.80, 13.20],
          [-0.80, 13.20],
          [-2.10, 12.60],
        ],
        -3.20 * bulk,
        -2.62 * bulk,
      ),
    );
    armor.add(
      `${prefix}_sentinel_heel_lock_gunmetal`,
      plateZ(
        [
          [-2.00, 10.80],
          [2.00, 10.80],
          [2.10, 12.20],
          [1.00, 12.70],
          [-1.00, 12.70],
          [-2.10, 12.20],
        ],
        3.02 * bulk,
        2.62 * bulk,
      ),
    );
    armor.add(
      `${prefix}_sentinel_gravity_node_led`,
      plateZ(
        [
          [-1.10, 10.90],
          [1.10, 10.90],
          [1.40, 11.90],
          [0, 12.40],
          [-1.40, 11.90],
        ],
        3.14 * bulk,
        3.00 * bulk,
      ),
    );
    armor.add(
      `${prefix}_sentinel_sole_field_led`,
      plateY(
        [
          [-2.26, -2.60],
          [2.26, -2.60],
          [2.46, 0.40],
          [2.26, 2.60],
          [-2.26, 2.60],
          [-2.46, 0.40],
        ],
        13.14,
        12.98,
      ),
    );
  }
  armor.mirror = false;
};

// -----------------------------------------------------------------------------
// 背甲翼组：稳定翼 / 长鳍 / 四层翼舱（守望的僚机与翼炮载体）
// -----------------------------------------------------------------------------

const buildVane = (armor, spec, side) => {
  const isLeft = side === "left";
  const prefix = `vane_${side}_`;
  const addMirrored = (name, mesh) => armor.add(name, mesh);
  armor.mirror = !isLeft;

  if (spec.wingKind === "stabilizer") {
    // 先锋：短而厚的稳定翼，向后上方张开，正面与侧面都要能读出轮廓。
    addMirrored(
      `${prefix}shell_titanium`,
      plateZ(
        [
          [3.40, 5.10],
          [4.90, 1.30],
          [8.60, -0.70],
          [9.40, 1.40],
          [7.30, 5.70],
          [4.30, 6.95],
        ],
        2.45,
        3.85,
      ),
    );
    addMirrored(
      `${prefix}spine_gunmetal`,
      plateZ(
        [
          [3.30, 5.45],
          [5.05, 1.60],
          [7.10, 0.05],
          [7.50, 1.40],
          [5.60, 4.90],
          [4.10, 6.80],
        ],
        2.30,
        2.80,
      ),
    );
    addMirrored(
      `${prefix}field_led`,
      plateZ(
        [
          [6.40, 5.40],
          [9.20, 1.10],
          [8.85, 0.65],
          [6.10, 5.05],
        ],
        3.72,
        3.92,
      ),
    );
    return;
  }

  if (spec.wingKind === "longfin") {
    // 疾影：两片细长的后掠鳍，从背甲一直拖到腰后，速度感来源。
    const span = spec.finSpan;
    addMirrored(
      `${prefix}shell_titanium`,
      plateZ(
        [
          [3.55, 4.90],
          [4.05 + span * 0.4, 1.20],
          [5.10 + span * 0.9, -2.60],
          [5.70 + span * 1.1, -1.20],
          [5.05 + span * 0.6, 2.60],
          [4.25, 5.60],
        ],
        2.45,
        3.15,
      ),
    );
    addMirrored(
      `${prefix}spine_gunmetal`,
      plateZ(
        [
          [3.50, 5.20],
          [4.30, 1.40],
          [5.05, -1.60],
          [5.35, -0.90],
          [4.70, 2.60],
          [4.05, 5.80],
        ],
        2.30,
        2.70,
      ),
    );
    addMirrored(
      `${prefix}field_led`,
      plateZ(
        [
          [4.55, 5.20],
          [5.15 + span * 0.5, 0.60],
          [4.95 + span * 0.4, -0.40],
          [4.32, 4.90],
        ],
        3.05,
        3.22,
      ),
    );
    addMirrored(
      `${prefix}swift_longfin_titanium`,
      plateZ(
        [
          [4.20, 3.90],
          [4.90 + span * 0.6, 0.20],
          [5.80 + span * 1.0, -3.60],
          [6.40 + span * 1.2, -2.20],
          [5.60 + span * 0.6, 1.60],
          [4.85, 4.60],
        ],
        3.30,
        3.62,
      ),
    );
    addMirrored(
      `${prefix}swift_longfin_trace_led`,
      plateZ(
        [
          [5.10, 3.30],
          [5.60 + span * 0.6, -0.10],
          [5.42 + span * 0.5, -1.00],
          [4.92, 3.00],
        ],
        3.62,
        3.76,
      ),
    );
    return;
  }

  // 守望：四层递进的翼舱（根 / 主 / 次 / 尖），层间留缝，展开时逐层铰接。
  const layers = [
    { name: "root", z0: 2.30, z1: 3.24, x0: 3.35, x1: 6.10, yTop: 1.10, yBottom: 6.30, node: 2.90 },
    { name: "primary", z0: 3.06, z1: 4.06, x0: 4.60, x1: 7.90, yTop: 0.20, yBottom: 5.60, node: 3.62 },
    { name: "secondary", z0: 3.88, z1: 4.90, x0: 6.30, x1: 9.60, yTop: -0.70, yBottom: 4.60, node: 4.44 },
    { name: "tip", z0: 4.72, z1: 5.72, x0: 8.00, x1: 11.30, yTop: -1.60, yBottom: 3.60, node: 5.26 },
  ];

  for (const layer of layers) {
    const shelf = [
      [layer.x0, layer.yBottom],
      [layer.x0 + (layer.x1 - layer.x0) * 0.35, layer.yTop + 0.25],
      [layer.x1, layer.yTop],
      [layer.x1 + 0.32, layer.yTop + 0.95],
      [layer.x1 - 0.30, layer.yBottom + 1.30],
      [layer.x0 + 0.20, layer.yBottom + 0.35],
    ];
    addMirrored(
      `${prefix}sentinel_wing_${layer.name}_shell_titanium`,
      plateZ(shelf, layer.z0, layer.z1),
    );
    addMirrored(
      `${prefix}sentinel_wing_${layer.name}_frame_gunmetal`,
      plateZ(
        [
          [layer.x0 - 0.12, layer.yBottom + 0.60],
          [layer.x0 + 0.30, layer.yTop + 0.60],
          [layer.x1 - 0.30, layer.yTop + 0.40],
          [layer.x1 - 0.52, layer.yTop + 1.05],
          [layer.x0 + 0.10, layer.yBottom + 1.25],
        ],
        layer.z1 - 0.14,
        layer.z1 + 0.06,
      ),
    );
    addMirrored(
      `${prefix}sentinel_wing_${layer.name}_node_led`,
      plateZ(
        [
          [layer.x0 + 0.70, layer.yBottom - 0.30],
          [layer.x0 + 1.10, layer.yBottom - 0.52],
          [layer.x1 - 0.80, layer.yBottom - 0.20],
          [layer.x1 - 1.00, layer.yBottom + 0.10],
          [layer.x0 + 0.90, layer.yBottom + 0.02],
        ],
        layer.z0 - 0.06,
        layer.z0 + 0.08,
      ),
    );
  }

  // 翼炮：挂在主层上的炮体 + 炮管 + 炮口灯。
  addMirrored(
    `${prefix}sentinel_wing_cannon_housing_titanium`,
    plateZ(
      [
        [5.60, 1.60],
        [8.60, 1.20],
        [9.00, 3.20],
        [8.30, 4.30],
        [5.80, 4.20],
      ],
      4.14,
      5.04,
    ),
  );
  addMirrored(
    `${prefix}sentinel_wing_cannon_barrel_gunmetal`,
    plateZ(
      [
        [8.70, 2.00],
        [12.20, 2.40],
        [12.30, 3.40],
        [8.80, 3.60],
      ],
      4.28,
      4.90,
    ),
  );
  addMirrored(
    `${prefix}sentinel_wing_cannon_muzzle_led`,
    plateZ(
      [
        [12.10, 2.30],
        [12.60, 2.60],
        [12.60, 3.30],
        [12.10, 3.60],
      ],
      4.32,
      4.86,
    ),
  );

  // 僚机舱：回收后贴在背甲外侧，是 H 键分离出去的那个实体。
  addMirrored(
    `${prefix}sentinel_drone_pod_shell_titanium`,
    plateZ(
      [
        [3.30, 7.10],
        [6.10, 6.70],
        [6.80, 8.40],
        [5.60, 10.60],
        [3.40, 10.30],
      ],
      1.20,
      2.30,
    ),
  );
  addMirrored(
    `${prefix}sentinel_drone_pod_frame_gunmetal`,
    plateZ(
      [
        [3.45, 7.30],
        [6.00, 6.95],
        [6.58, 8.40],
        [5.50, 10.35],
        [3.55, 10.10],
      ],
      1.02,
      1.22,
    ),
  );
  addMirrored(
    `${prefix}sentinel_drone_pod_node_led`,
    plateZ(
      [
        [3.90, 8.10],
        [5.70, 7.90],
        [5.95, 8.90],
        [4.20, 9.20],
      ],
      1.02,
      1.14,
    ),
  );
  addMirrored(
    `${prefix}sentinel_drone_pod_status_led`,
    plateZ(
      [
        [4.10, 9.50],
        [5.30, 9.38],
        [5.40, 9.90],
        [4.20, 10.02],
      ],
      2.30,
      2.40,
    ),
  );
  armor.mirror = false;
};

// -----------------------------------------------------------------------------
// 输出
// -----------------------------------------------------------------------------

const buildSuit = (variant) => {
  const spec = SPECS[variant];
  const armor = new ObjBuilder();
  buildHead(armor, spec);
  buildTorso(armor, spec);
  buildArm(armor, spec, "left");
  buildArm(armor, spec, "right");
  buildLeg(armor, spec, "left");
  buildLeg(armor, spec, "right");
  buildBoot(armor, spec, "left");
  buildBoot(armor, spec, "right");
  buildVane(armor, spec, "left");
  buildVane(armor, spec, "right");
  return armor;
};

fs.mkdirSync(outputDirectory, { recursive: true });
for (const variant of Object.keys(SPECS)) {
  const armor = buildSuit(variant);
  const outputPath = path.join(outputDirectory, `flight_armor_${variant}.obj`);
  fs.writeFileSync(outputPath, armor.toObj(), "utf8");
  const stats = armor.stats();
  console.log(
    `${path.relative(projectDir, outputPath)}: ${stats.groups} groups, ` +
      `${stats.vertices} vertices, ${stats.faces} faces (${stats.triangles}T/${stats.quads}Q)`,
  );
}

