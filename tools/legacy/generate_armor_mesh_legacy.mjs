import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/*
 * Flight Armor OBJ generator
 *
 * Coordinate contract (Minecraft model pixels; the renderer divides by 16):
 *   - Every vertex is local to the HumanoidModel part that owns its group.
 *   - +X is the model's left side, +Y points down, -Z is forward.
 *   - head: x [-4.5, 4.5], y [-8.5, 0.5], z [-4.5, 4.5]
 *   - body: x [-4.4, 4.4], y [-0.4, 12.4], z [-2.5, 2.5]
 *     Decorative plates may project beyond those base-shell bounds.
 *   - right arm: x [-3.4, 1.4], left arm: x [-1.4, 3.4], y [-2.4, 10.4]
 *   - legs/boots: x [-2.3, 2.3], y [-0.3, 12.3]
 *
 * Group naming is also a render contract:
 *   titanium = main pale alloy, gunmetal = dark frame, shadow = flexible inner suit,
 *   visor = translucent cyan-grey glass, and every flight-only emissive group ends in _led.
 *
 * Dynamic part pivot hints (body/head-local pixels):
 *   core_*       pivot ( 0.00, 3.65, -3.10)
 *   visor_*      pivot ( 0.00,-4.85, -4.20)
 *   vane_left_*  pivot ( 3.62, 2.55,  2.52)
 *   vane_right_* pivot (-3.62, 2.55,  2.52)
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
  const cleaned = Math.abs(value) < 1e-8 ? 0 : value;
  return Number(cleaned.toFixed(6));
};

const signPow = (value, exponent) =>
  Math.sign(value) * Math.pow(Math.abs(value), exponent);

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
    const alignment = normal[0] * outward[0] + normal[1] * outward[1] + normal[2] * outward[2];
    return alignment < 0 ? [...face].reverse() : face;
  });
  return mesh;
};

class ObjBuilder {
  constructor() {
    this.groups = new Map();
  }

  add(groupName, mesh) {
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

  /**
   * 将旧版 Y=4 的装饰分界重新拟合到原版玩家真实肘轴 Y=6。
   * 上臂延伸到肘前，前臂从肘后开始，避免甲片跨轴旋转造成穿模。
   */
  fitArmSegmentsToVanillaElbow() {
    for (const [name, group] of this.groups) {
      if (!name.startsWith("arm_")) continue;
      // Variant-exclusive geometry is authored directly against the final vanilla Y=6 elbow.
      if (name.includes("_swift_") || name.includes("_sentinel_")) continue;
      if (name.includes("_upper_")) {
        group.vertices = group.vertices.map(([x, y, z]) => [
          x,
          round(y > 1.2 ? 1.2 + (y - 1.2) * 1.65 : y),
          z,
        ]);
      } else if (name.includes("_forearm_")) {
        group.vertices = group.vertices.map(([x, y, z]) => [
          x,
          round(6.08 + (y - 3.94) * 0.975),
          z,
        ]);
      }
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
      const hasMaterial = ["_titanium", "_gunmetal", "_shadow", "_visor"].some(
        (token) => name.includes(token),
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
        const areaSquared = cross[0] ** 2 + cross[1] ** 2 + cross[2] ** 2;
        if (areaSquared < 1e-12) {
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

const mergeMeshes = (...meshes) => {
  const result = { vertices: [], faces: [] };
  for (const mesh of meshes) {
    const offset = result.vertices.length;
    result.vertices.push(...mesh.vertices);
    result.faces.push(...mesh.faces.map((face) => face.map((index) => index + offset)));
  }
  return result;
};

/**
 * Closed loft of rounded superellipse rings. More than four radial segments means the
 * silhouette is genuinely faceted/curved rather than a disguised Minecraft cuboid.
 */
const loftSuperellipse = ({ cx = 0, rings, segments = 12, power = 3.6 }) => {
  const vertices = [];
  const faces = [];
  const exponent = 2 / power;

  for (const ring of rings) {
    for (let segment = 0; segment < segments; segment += 1) {
      const angle = (segment / segments) * Math.PI * 2;
      const cosine = Math.cos(angle);
      const sine = Math.sin(angle);
      vertices.push([
        cx + ring.rx * signPow(cosine, exponent),
        ring.y,
        (ring.cz ?? 0) + ring.rz * signPow(sine, exponent),
      ]);
    }
  }

  for (let ring = 0; ring < rings.length - 1; ring += 1) {
    for (let segment = 0; segment < segments; segment += 1) {
      const next = (segment + 1) % segments;
      const currentTop = ring * segments + segment;
      const nextTop = ring * segments + next;
      const currentBottom = (ring + 1) * segments + segment;
      const nextBottom = (ring + 1) * segments + next;
      faces.push([currentTop, currentBottom, nextBottom, nextTop]);
    }
  }

  const topCenter = vertices.length;
  vertices.push([cx, rings[0].y, rings[0].cz ?? 0]);
  const bottomCenter = vertices.length;
  const last = rings.length - 1;
  vertices.push([cx, rings[last].y, rings[last].cz ?? 0]);
  for (let segment = 0; segment < segments; segment += 1) {
    const next = (segment + 1) % segments;
    faces.push([topCenter, next, segment]);
    faces.push([
      bottomCenter,
      last * segments + segment,
      last * segments + next,
    ]);
  }
  return orientClosedMesh({ vertices, faces });
};

/**
 * 封闭的八面倒角软甲壳。
 *
 * 玩家皮肤的 jacket/sleeve/pants 是在基础四方骨骼外再膨胀 0.25 像素的方壳；
 * 普通 superellipse 即使正面半径略大，四角仍会退进皮肤层，正背面看起来就像皮肤穿过装甲。
 * 这里保留小倒角带来的立体折面，但用接近方形的支撑轮廓完整包住皮肤第二层。
 */
const loftChamferedShell = ({ cx = 0, rings, bevel = 0.12 }) => {
  const vertices = [];
  const faces = [];
  const ringVertices = ({ y, hx, hz, cz = 0 }) => [
    [cx - hx + bevel, y, cz - hz],
    [cx + hx - bevel, y, cz - hz],
    [cx + hx, y, cz - hz + bevel],
    [cx + hx, y, cz + hz - bevel],
    [cx + hx - bevel, y, cz + hz],
    [cx - hx + bevel, y, cz + hz],
    [cx - hx, y, cz + hz - bevel],
    [cx - hx, y, cz - hz + bevel],
  ];

  for (const ring of rings) vertices.push(...ringVertices(ring));
  const segments = 8;
  for (let ring = 0; ring < rings.length - 1; ring += 1) {
    for (let segment = 0; segment < segments; segment += 1) {
      const next = (segment + 1) % segments;
      const upper = ring * segments + segment;
      const upperNext = ring * segments + next;
      const lower = (ring + 1) * segments + segment;
      const lowerNext = (ring + 1) * segments + next;
      faces.push([upper, lower, lowerNext, upperNext]);
    }
  }

  const topCenter = vertices.length;
  vertices.push([cx, rings[0].y, rings[0].cz ?? 0]);
  const bottomCenter = vertices.length;
  const last = rings.length - 1;
  vertices.push([cx, rings[last].y, rings[last].cz ?? 0]);
  for (let segment = 0; segment < segments; segment += 1) {
    const next = (segment + 1) % segments;
    faces.push([topCenter, next, segment]);
    faces.push([bottomCenter, last * segments + segment, last * segments + next]);
  }
  return orientClosedMesh({ vertices, faces });
};

const extrudePolygonZ = (points, outerZ, innerZ) => {
  const vertices = [];
  const faces = [];
  const count = points.length;
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

const extrudeCurvedPolygonZ = (points, outerZAtX, thickness) => {
  const vertices = [];
  const faces = [];
  const count = points.length;
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

const extrudePolygonX = (points, outerX, innerX) => {
  const vertices = [];
  const faces = [];
  const count = points.length;
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

const extrudePolygonY = (points, outerY, innerY) => {
  const vertices = [];
  const faces = [];
  const count = points.length;
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

const curvedRibbon = ({ xs, topY, bottomY, zAtX, thickness = 0.22 }) => {
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

const torusY = ({ cx = 0, cy, cz = 0, rx, rz, tube, majorSegments = 14, minorSegments = 5 }) => {
  const vertices = [];
  const faces = [];
  for (let major = 0; major < majorSegments; major += 1) {
    const theta = (major / majorSegments) * Math.PI * 2;
    for (let minor = 0; minor < minorSegments; minor += 1) {
      const phi = (minor / minorSegments) * Math.PI * 2;
      const radial = Math.cos(phi) * tube;
      vertices.push([
        cx + (rx + radial) * Math.cos(theta),
        cy + Math.sin(phi) * tube,
        cz + (rz + radial) * Math.sin(theta),
      ]);
    }
  }
  for (let major = 0; major < majorSegments; major += 1) {
    const nextMajor = (major + 1) % majorSegments;
    for (let minor = 0; minor < minorSegments; minor += 1) {
      const nextMinor = (minor + 1) % minorSegments;
      faces.push([
        major * minorSegments + nextMinor,
        nextMajor * minorSegments + nextMinor,
        nextMajor * minorSegments + minor,
        major * minorSegments + minor,
      ]);
    }
  }
  return { vertices, faces };
};

const mirrorX = (points) => points.map(([x, y]) => [-x, y]).reverse();
const visorFrontZ = (x, offset = 0) => {
  // 正面只有轻微外凸曲率，中心比两侧更突出，但不再形成圆鼓面罩。
  const normalized = Math.min(1, Math.abs(x) / 4.32);
  return -4.38 - 0.34 * (1 - normalized * normalized) - offset;
};

const armor = new ObjBuilder();

// -----------------------------------------------------------------------------
// Helmet: narrow faceted anti-gravity shell with blade-like facial plates.
// -----------------------------------------------------------------------------
armor.add(
  "head_shell_shadow",
  loftSuperellipse({
    segments: 12,
    power: 3.2,
    rings: [
      { y: -8.60, rx: 2.55, rz: 3.30, cz: 0.02 },
      { y: -8.30, rx: 3.55, rz: 4.02, cz: 0.03 },
      { y: -7.55, rx: 4.25, rz: 4.32, cz: 0.04 },
      { y: -4.60, rx: 4.32, rz: 4.38, cz: 0.05 },
      { y: -1.30, rx: 4.08, rz: 4.12, cz: 0.04 },
      { y: -0.32, rx: 3.38, rz: 3.35, cz: 0.02 },
    ],
  }),
);

armor.add(
  "head_crown_crest_titanium",
  extrudeCurvedPolygonZ(
    [
      [-0.68, -8.34],
      [0.68, -8.34],
      [1.02, -7.48],
      [0.78, -6.42],
      [0, -6.02],
      [-0.78, -6.42],
      [-1.02, -7.48],
    ],
    (x) => visorFrontZ(x, 0.02),
    0.22,
  ),
);

const leftBrow = [
  [0.22, -6.48],
  [1.10, -6.78],
  [3.62, -6.18],
  [3.36, -5.72],
  [1.08, -6.08],
];
armor.add(
  "head_brow_left_gunmetal",
  extrudeCurvedPolygonZ(leftBrow, (x) => visorFrontZ(x, 0.12), 0.24),
);
armor.add(
  "head_brow_right_gunmetal",
  extrudeCurvedPolygonZ(mirrorX(leftBrow), (x) => visorFrontZ(x, 0.12), 0.24),
);

// 脸颊以暗色结构为主，只留下窄钛合金下颌刀片。
const leftCheek = [
  [1.70, -4.72],
  [3.42, -4.92],
  [4.02, -4.18],
  [3.86, -2.46],
  [2.80, -1.62],
  [1.98, -2.72],
];
armor.add(
  "head_cheek_left_gunmetal",
  extrudeCurvedPolygonZ(leftCheek, (x) => visorFrontZ(x, 0.18), 0.32),
);
armor.add(
  "head_cheek_right_gunmetal",
  extrudeCurvedPolygonZ(mirrorX(leftCheek), (x) => visorFrontZ(x, 0.18), 0.32),
);

// 脸颊灯带沿装甲分瓣斜切，不铺满大面积，展开时会随左右脸颊独立移动。
const leftCheekSlash = [
  [2.18, -4.04],
  [2.44, -4.18],
  [3.42, -3.16],
  [3.22, -2.86],
];
armor.add(
  "head_cheek_left_slash_led",
  extrudeCurvedPolygonZ(leftCheekSlash, (x) => visorFrontZ(x, 0.48), 0.08),
);
armor.add(
  "head_cheek_right_slash_led",
  extrudeCurvedPolygonZ(mirrorX(leftCheekSlash), (x) => visorFrontZ(x, 0.48), 0.08),
);

armor.add(
  "head_faceplate_gunmetal",
  extrudeCurvedPolygonZ(
    [
      [-2.42, -4.70],
      [2.42, -4.70],
      [2.78, -3.54],
      [1.66, -1.16],
      [0, -0.28],
      [-1.66, -1.16],
      [-2.78, -3.54],
    ],
    (x) => visorFrontZ(x, 0.25),
    0.28,
  ),
);

armor.add(
  "head_faceplate_spine_titanium",
  extrudeCurvedPolygonZ(
    [
      [-0.56, -4.78],
      [0.56, -4.78],
      [0.88, -2.26],
      [0, -0.48],
      [-0.88, -2.26],
    ],
    (x) => visorFrontZ(x, 0.36),
    0.20,
  ),
);

armor.add(
  "head_faceplate_core_led",
  extrudeCurvedPolygonZ(
    [
      [-0.12, -3.92],
      [0.12, -3.92],
      [0.20, -1.72],
      [0, -1.18],
      [-0.20, -1.72],
    ],
    (x) => visorFrontZ(x, 0.48),
    0.10,
  ),
);

// 下颌只保留一道小型倒 V 灯，和中央灯形成连续的视觉轴线。
armor.add(
  "head_faceplate_chin_chevron_led",
  extrudeCurvedPolygonZ(
    [
      [-0.62, -1.46],
      [0, -0.82],
      [0.62, -1.46],
      [0.40, -1.64],
      [0, -1.20],
      [-0.40, -1.64],
    ],
    (x) => visorFrontZ(x, 0.49),
    0.08,
  ),
);

const leftJaw = [
  [1.20, -2.78],
  [2.14, -3.34],
  [3.26, -2.82],
  [2.78, -1.48],
  [1.30, -0.58],
  [0.78, -0.82],
];
armor.add(
  "head_cheek_left_jaw_titanium",
  extrudeCurvedPolygonZ(leftJaw, (x) => visorFrontZ(x, 0.31), 0.18),
);
armor.add(
  "head_cheek_right_jaw_titanium",
  extrudeCurvedPolygonZ(mirrorX(leftJaw), (x) => visorFrontZ(x, 0.31), 0.18),
);

// 短耳舱代替贯穿整侧的方形大板。
const templePod = [
  [-5.54, -1.52],
  [-5.06, -2.22],
  [-3.10, -2.36],
  [-2.48, -1.06],
  [-2.82, 0.72],
  [-4.68, 1.02],
];
armor.add("head_temple_left_gunmetal", extrudePolygonX(templePod, 4.48, 4.18));
armor.add("head_temple_right_gunmetal", extrudePolygonX(templePod, -4.48, -4.18));

const templeTrace = [
  [-4.92, -2.30],
  [-4.64, -2.52],
  [-3.20, -2.54],
  [-2.96, -2.30],
  [-4.54, -2.24],
];
armor.add("head_temple_left_trace_led", extrudePolygonX(templeTrace, 4.55, 4.47));
armor.add("head_temple_right_trace_led", extrudePolygonX(templeTrace, -4.55, -4.47));

armor.add(
  "head_rear_spine_gunmetal",
  extrudePolygonZ(
    [
      [-0.42, -7.72],
      [0.42, -7.72],
      [0.64, -6.58],
      [0.42, -1.30],
      [0, -0.72],
      [-0.42, -1.30],
      [-0.64, -6.58],
    ],
    4.50,
    4.26,
  ),
);

// 后脑使用暗色分瓣、窄亮边和可展开鳍片，避免两块大面积浅灰护板。
const leftRearPlate = [
  [0.46, -7.48],
  [2.88, -7.30],
  [3.46, -6.38],
  [3.18, -2.14],
  [2.38, -1.32],
  [0.78, -0.76],
  [0.46, -1.02],
];
armor.add("head_rear_left_gunmetal", extrudePolygonZ(leftRearPlate, 4.66, 4.42));
armor.add("head_rear_right_gunmetal", extrudePolygonZ(mirrorX(leftRearPlate), 4.66, 4.42));

const leftRearTrim = [
  [2.84, -7.12],
  [3.18, -6.34],
  [2.92, -2.34],
  [2.46, -1.72],
  [2.28, -2.02],
  [2.58, -6.20],
];
armor.add("head_rear_left_trim_titanium", extrudePolygonZ(leftRearTrim, 4.79, 4.66));
armor.add("head_rear_right_trim_titanium", extrudePolygonZ(mirrorX(leftRearTrim), 4.79, 4.66));

const leftRearFin = [
  [2.72, -6.72],
  [3.72, -6.02],
  [3.86, -3.18],
  [3.14, -2.28],
  [2.82, -2.74],
  [3.10, -5.72],
];
armor.add("head_rear_left_fin_gunmetal", extrudePolygonZ(leftRearFin, 4.88, 4.66));
armor.add("head_rear_right_fin_gunmetal", extrudePolygonZ(mirrorX(leftRearFin), 4.88, 4.66));

const leftRearChevron = [
  [0.82, -5.90],
  [1.08, -6.12],
  [2.42, -5.10],
  [2.18, -4.82],
  [1.10, -5.58],
];
armor.add("head_rear_left_chevron_led", extrudePolygonZ(leftRearChevron, 4.91, 4.82));
armor.add("head_rear_right_chevron_led", extrudePolygonZ(mirrorX(leftRearChevron), 4.91, 4.82));

armor.add(
  "head_crown_rear_cap_gunmetal",
  extrudePolygonZ(
    [
      [-1.84, -8.34],
      [1.84, -8.34],
      [2.46, -7.72],
      [1.92, -7.18],
      [0, -7.52],
      [-1.92, -7.18],
      [-2.46, -7.72],
    ],
    4.34,
    4.12,
  ),
);

// 头骨绑定的柔性颈部密封圈；尺寸小于胸甲领圈，转头时保留活动缝。
armor.add(
  "head_neck_seal_gunmetal",
  loftSuperellipse({
    segments: 12,
    power: 4.0,
    rings: [
      { y: -0.28, rx: 3.18, rz: 2.92 },
      { y: 0.06, rx: 3.34, rz: 3.02 },
      { y: 0.40, rx: 3.06, rz: 2.78 },
    ],
  }),
);

const visorXs = [-3.35, -2.45, -1.25, 0, 1.25, 2.45, 3.35];
const visorTop = (x) => -6.42 + 0.40 * Math.abs(x / 3.35);
const visorBottom = (x) => -4.58 - 0.30 * Math.abs(x / 3.35);
const visorSurface = (x) => visorFrontZ(x, 0.30);
armor.add(
  "visor_lens_visor",
  curvedRibbon({ xs: visorXs, topY: visorTop, bottomY: visorBottom, zAtX: visorSurface, thickness: 0.18 }),
);
armor.add(
  "visor_upper_frame_titanium",
  curvedRibbon({
    xs: visorXs,
    topY: (x) => visorTop(x) - 0.18,
    bottomY: (x) => visorTop(x) + 0.02,
    zAtX: (x) => visorSurface(x) - 0.05,
    thickness: 0.22,
  }),
);
armor.add(
  "visor_lower_frame_gunmetal",
  curvedRibbon({
    xs: visorXs,
    topY: (x) => visorBottom(x) - 0.02,
    bottomY: (x) => visorBottom(x) + 0.12,
    zAtX: (x) => visorSurface(x) - 0.04,
    thickness: 0.20,
  }),
);
// 目镜下缘拆成左右两段锐利 V 形灯带；使用 faceplate 命名使其跟随面罩展开。
const leftVisorVEdge = [
  [0.08, -4.66],
  [0.12, -4.54],
  [3.30, -4.84],
  [3.30, -4.96],
];
armor.add(
  "head_faceplate_v_edge_left_led",
  extrudeCurvedPolygonZ(leftVisorVEdge, (x) => visorFrontZ(x, 0.50), 0.08),
);
armor.add(
  "head_faceplate_v_edge_right_led",
  extrudeCurvedPolygonZ(mirrorX(leftVisorVEdge), (x) => visorFrontZ(x, 0.50), 0.08),
);

// -----------------------------------------------------------------------------
// Torso: continuous rounded inner shell plus angular floating alloy plates.
// -----------------------------------------------------------------------------
armor.add(
  "body_underlayer_shadow",
  loftChamferedShell({
    bevel: 0.14,
    rings: [
      { y: -0.38, hx: 4.38, hz: 2.38 },
      { y: 1.35, hx: 4.44, hz: 2.42 },
      { y: 5.70, hx: 4.40, hz: 2.40 },
      { y: 10.85, hx: 4.36, hz: 2.38 },
      { y: 12.34, hx: 4.34, hz: 2.36 },
    ],
  }),
);

const leftChest = [
  [0.52, 0.12],
  [3.48, 0.34],
  [4.20, 1.48],
  [3.72, 4.75],
  [2.42, 5.72],
  [0.72, 4.92],
  [0.18, 2.18],
];
armor.add(
  "body_chest_titanium",
  mergeMeshes(
    extrudePolygonZ(leftChest, -3.02, -2.24),
    extrudePolygonZ(mirrorX(leftChest), -3.02, -2.24),
  ),
);

const leftAb = [
  [0.42, 5.42],
  [2.56, 6.08],
  [3.62, 7.22],
  [3.30, 9.55],
  [2.30, 10.82],
  [0.48, 10.42],
  [0.14, 7.10],
];
armor.add(
  "body_abdomen_titanium",
  mergeMeshes(
    extrudePolygonZ(leftAb, -2.83, -2.19),
    extrudePolygonZ(mirrorX(leftAb), -2.83, -2.19),
  ),
);

armor.add(
  "body_collar_gunmetal",
  mergeMeshes(
    extrudePolygonY(
      [
        [0.20, -2.15],
        [3.92, -2.10],
        [4.38, -0.72],
        [2.38, 0.18],
        [0.34, -0.72],
      ],
      -0.58,
      0.16,
    ),
    extrudePolygonY(
      [
        [-0.20, -2.15],
        [-3.92, -2.10],
        [-4.38, -0.72],
        [-2.38, 0.18],
        [-0.34, -0.72],
      ],
      -0.58,
      0.16,
    ),
  ),
);

armor.add(
  "body_backplate_titanium",
  mergeMeshes(
    extrudePolygonZ(
      [
        [0.38, 0.15],
        [3.42, 0.48],
        [4.04, 2.10],
        [3.30, 7.12],
        [1.16, 9.28],
        [0.40, 8.52],
      ],
      2.92,
      2.18,
    ),
    extrudePolygonZ(
      [
        [-0.38, 0.15],
        [-3.42, 0.48],
        [-4.04, 2.10],
        [-3.30, 7.12],
        [-1.16, 9.28],
        [-0.40, 8.52],
      ],
      2.92,
      2.18,
    ),
  ),
);

armor.add(
  "body_spine_gunmetal",
  extrudePolygonZ(
    [
      [-0.48, 0.55],
      [0.48, 0.55],
      [0.72, 2.04],
      [0.48, 10.92],
      [0, 11.72],
      [-0.48, 10.92],
      [-0.72, 2.04],
    ],
    3.24,
    2.62,
  ),
);

armor.add(
  "body_waist_titanium",
  loftSuperellipse({
    segments: 12,
    power: 3.8,
    rings: [
      { y: 10.72, rx: 3.94, rz: 2.32 },
      { y: 11.36, rx: 4.02, rz: 2.38 },
      { y: 12.18, rx: 3.66, rz: 2.20 },
    ],
  }),
);

const leftTrace = [
  [0.62, 0.72],
  [0.92, 0.62],
  [2.86, 2.02],
  [3.12, 3.60],
  [2.84, 3.76],
  [2.54, 2.28],
];
armor.add(
  "body_chest_trace_led",
  mergeMeshes(
    extrudePolygonZ(leftTrace, -3.16, -3.02),
    extrudePolygonZ(mirrorX(leftTrace), -3.16, -3.02),
    extrudePolygonZ(
      [
        [-2.78, 7.12],
        [-2.48, 6.92],
        [-0.72, 8.28],
        [-0.54, 10.12],
        [-0.84, 10.26],
        [-1.06, 8.52],
      ],
      -2.98,
      -2.84,
    ),
    extrudePolygonZ(
      [
        [2.78, 7.12],
        [2.48, 6.92],
        [0.72, 8.28],
        [0.54, 10.12],
        [0.84, 10.26],
        [1.06, 8.52],
      ],
      -2.98,
      -2.84,
    ),
  ),
);

armor.add(
  "core_cradle_gunmetal",
  extrudePolygonZ(
    [
      [0, 2.38],
      [1.34, 3.70],
      [0, 5.02],
      [-1.34, 3.70],
    ],
    -3.14,
    -2.86,
  ),
);
armor.add(
  "core_housing_titanium",
  extrudePolygonZ(
    [
      [0, 2.72],
      [0.99, 3.70],
      [0, 4.68],
      [-0.99, 3.70],
    ],
    -3.20,
    -3.10,
  ),
);
armor.add(
  "core_reactor_led",
  extrudePolygonZ(
    [
      [0, 3.04],
      [0.66, 3.70],
      [0, 4.36],
      [-0.66, 3.70],
    ],
    -3.26,
    -3.18,
  ),
);

// Compact articulated gravitic vanes. They are stabilizer pods, not wings or exhausts.
const leftVane = [
  [3.28, 1.02],
  [4.18, 1.62],
  [4.52, 3.18],
  [4.12, 5.92],
  [3.46, 6.64],
  [3.06, 4.92],
  [3.10, 2.12],
];
const rightVane = mirrorX(leftVane);
armor.add("vane_left_shell_titanium", extrudePolygonZ(leftVane, 3.30, 2.46));
armor.add("vane_right_shell_titanium", extrudePolygonZ(rightVane, 3.30, 2.46));
armor.add(
  "vane_left_spine_gunmetal",
  extrudePolygonZ(
    [
      [3.42, 1.68],
      [3.78, 1.88],
      [3.94, 4.88],
      [3.58, 5.76],
      [3.34, 4.72],
    ],
    3.56,
    3.28,
  ),
);
armor.add(
  "vane_right_spine_gunmetal",
  extrudePolygonZ(
    [
      [-3.42, 1.68],
      [-3.78, 1.88],
      [-3.94, 4.88],
      [-3.58, 5.76],
      [-3.34, 4.72],
    ],
    3.56,
    3.28,
  ),
);
armor.add(
  "vane_left_field_led",
  extrudePolygonZ(
    [
      [3.86, 2.16],
      [4.08, 2.34],
      [4.12, 4.82],
      [3.82, 5.34],
      [3.70, 4.64],
    ],
    3.72,
    3.54,
  ),
);
armor.add(
  "vane_right_field_led",
  extrudePolygonZ(
    [
      [-3.86, 2.16],
      [-4.08, 2.34],
      [-4.12, 4.82],
      [-3.82, 5.34],
      [-3.70, 4.64],
    ],
    3.72,
    3.54,
  ),
);

// -----------------------------------------------------------------------------
// Variant-exclusive hard geometry. These groups share the articulated humanoid anchors,
// but the renderer makes _swift_ and _sentinel_ groups mutually exclusive. The variants
// therefore have genuinely different silhouettes rather than relying on tint and scaling.
// -----------------------------------------------------------------------------

// Swift: a long dorsal keel, split chest blade and swept stabilizer rails.
armor.add(
  "head_swift_dorsal_rear_keel_titanium",
  extrudePolygonX(
    [
      [-9.35, 0.70],
      [-8.72, 3.20],
      [-7.35, 5.25],
      [-5.72, 4.72],
      [-6.62, 2.35],
      [-8.10, 0.62],
    ],
    0.30,
    -0.30,
  ),
);
armor.add(
  "body_swift_center_keel_titanium",
  mergeMeshes(
    extrudePolygonZ(
      [
        [0, -0.18],
        [0.58, 0.82],
        [0.42, 2.40],
        [0, 2.82],
        [-0.42, 2.40],
        [-0.58, 0.82],
      ],
      -3.46,
      -3.02,
    ),
    extrudePolygonZ(
      [
        [0, 4.58],
        [0.48, 5.18],
        [0.36, 9.62],
        [0, 11.64],
        [-0.36, 9.62],
        [-0.48, 5.18],
      ],
      -3.34,
      -2.96,
    ),
  ),
);
armor.add(
  "body_swift_keel_trace_led",
  mergeMeshes(
    extrudePolygonZ(
      [
        [-0.10, 0.36],
        [0.10, 0.36],
        [0.16, 2.18],
        [0, 2.42],
        [-0.16, 2.18],
      ],
      -3.54,
      -3.46,
    ),
    extrudePolygonZ(
      [
        [-0.10, 5.18],
        [0.10, 5.18],
        [0.13, 9.48],
        [0, 10.68],
        [-0.13, 9.48],
      ],
      -3.42,
      -3.34,
    ),
  ),
);

const swiftLeftLongFin = [
  [3.28, 0.84],
  [6.92, 1.42],
  [7.32, 2.12],
  [5.22, 3.12],
  [4.22, 6.98],
  [3.42, 6.04],
  [3.10, 2.04],
];
armor.add(
  "vane_left_swift_longfin_titanium",
  extrudePolygonZ(swiftLeftLongFin, 3.54, 3.10),
);
armor.add(
  "vane_right_swift_longfin_titanium",
  extrudePolygonZ(mirrorX(swiftLeftLongFin), 3.54, 3.10),
);
const swiftLeftFinTrace = [
  [3.70, 1.28],
  [6.44, 1.76],
  [6.60, 1.98],
  [4.28, 2.62],
  [3.84, 5.86],
  [3.62, 5.46],
];
armor.add(
  "vane_left_swift_longfin_trace_led",
  extrudePolygonZ(swiftLeftFinTrace, 3.66, 3.54),
);
armor.add(
  "vane_right_swift_longfin_trace_led",
  extrudePolygonZ(mirrorX(swiftLeftFinTrace), 3.66, 3.54),
);

const addSwiftForearmBlade = (side) => {
  const isLeft = side === "left";
  const sign = isLeft ? 1 : -1;
  armor.add(
    `arm_${side}_swift_forearm_rear_blade_titanium`,
    extrudePolygonX(
      [
        [6.22, 1.42],
        [7.02, 3.10],
        [10.84, 4.08],
        [11.54, 3.42],
        [9.48, 1.64],
        [6.72, 0.92],
      ],
      sign * 3.50,
      sign * 3.08,
    ),
  );
};
addSwiftForearmBlade("left");
addSwiftForearmBlade("right");

// Sentinel: a self-contained fortress suit rather than a widened Vanguard recolor.
// Only the clearance under-suit is shared. Every visible shell below is authored for
// Sentinel and is used by both configurations: folded plates become the BREACH_MELEE
// bulwark, while the same dorsal segments fan into the OVERDRIVE_FLIGHT wing system.
// The wing groups deliberately keep the vane_* and wing_* tokens because the renderer
// treats those names as a public articulation contract.
const sentinelBrow = [
  [-4.22, -6.96],
  [4.22, -6.96],
  [4.08, -6.08],
  [2.86, -5.30],
  [-2.86, -5.30],
  [-4.08, -6.08],
];
armor.add(
  "head_faceplate_sentinel_brow_shield_titanium",
  extrudeCurvedPolygonZ(sentinelBrow, (x) => visorFrontZ(x, 0.64), 0.34),
);
armor.add(
  "head_faceplate_sentinel_brow_trace_led",
  extrudeCurvedPolygonZ(
    [
      [-2.84, -5.38],
      [2.84, -5.38],
      [2.56, -5.22],
      [-2.56, -5.22],
    ],
    (x) => visorFrontZ(x, 0.70),
    0.08,
  ),
);

// Broad crown, recessed face lock and a deep rear helmet cage give Sentinel a
// recognisable low, armoured brow without exposing the player skin at the temples.
armor.add(
  "head_faceplate_sentinel_crown_bastion_titanium",
  extrudeCurvedPolygonZ(
    [
      [-3.72, -8.36],
      [3.72, -8.36],
      [4.26, -7.50],
      [3.76, -6.78],
      [1.72, -6.36],
      [0, -6.62],
      [-1.72, -6.36],
      [-3.76, -6.78],
      [-4.26, -7.50],
    ],
    (x) => visorFrontZ(x, 0.50),
    0.42,
  ),
);
armor.add(
  "head_faceplate_sentinel_lock_gunmetal",
  extrudeCurvedPolygonZ(
    [
      [-3.70, -5.22],
      [3.70, -5.22],
      [4.12, -4.28],
      [3.52, -1.72],
      [2.04, -0.58],
      [0, -0.20],
      [-2.04, -0.58],
      [-3.52, -1.72],
      [-4.12, -4.28],
    ],
    (x) => visorFrontZ(x, 0.43),
    0.46,
  ),
);
const sentinelJawGuard = [
  [1.34, -3.62],
  [3.82, -3.18],
  [4.18, -2.32],
  [3.32, -0.56],
  [1.24, 0.18],
  [0.68, -0.34],
];
armor.add(
  "head_faceplate_sentinel_left_jaw_guard_titanium",
  extrudeCurvedPolygonZ(sentinelJawGuard, (x) => visorFrontZ(x, 0.70), 0.22),
);
armor.add(
  "head_faceplate_sentinel_right_jaw_guard_titanium",
  extrudeCurvedPolygonZ(mirrorX(sentinelJawGuard), (x) => visorFrontZ(x, 0.70), 0.22),
);
armor.add(
  "head_faceplate_sentinel_command_node_led",
  extrudeCurvedPolygonZ(
    [
      [-0.38, -4.52],
      [0.38, -4.52],
      [0.54, -2.20],
      [0, -1.48],
      [-0.54, -2.20],
    ],
    (x) => visorFrontZ(x, 0.91),
    0.09,
  ),
);
armor.add(
  "visor_sentinel_upper_lock_titanium",
  curvedRibbon({
    xs: visorXs,
    topY: (x) => visorTop(x) - 0.34,
    bottomY: (x) => visorTop(x) - 0.04,
    zAtX: (x) => visorSurface(x) - 0.26,
    thickness: 0.28,
  }),
);
armor.add(
  "visor_sentinel_lower_lock_gunmetal",
  curvedRibbon({
    xs: visorXs,
    topY: (x) => visorBottom(x) - 0.04,
    bottomY: (x) => visorBottom(x) + 0.22,
    zAtX: (x) => visorSurface(x) - 0.20,
    thickness: 0.26,
  }),
);
const sentinelRearShell = [
  [0.54, -8.20],
  [3.46, -7.92],
  [4.16, -6.94],
  [4.04, -2.02],
  [3.12, -0.52],
  [1.02, 0.12],
  [0.48, -0.62],
];
armor.add(
  "head_sentinel_rear_left_bastion_titanium",
  extrudePolygonZ(sentinelRearShell, 5.08, 4.48),
);
armor.add(
  "head_sentinel_rear_right_bastion_titanium",
  extrudePolygonZ(mirrorX(sentinelRearShell), 5.08, 4.48),
);
armor.add(
  "head_sentinel_rear_spine_led",
  extrudePolygonZ(
    [
      [-0.20, -7.46],
      [0.20, -7.46],
      [0.28, -5.88],
      [0.18, -1.36],
      [0, -0.88],
      [-0.18, -1.36],
      [-0.28, -5.88],
    ],
    5.18,
    5.08,
  ),
);

// A front armour belt made from interlocking plates. The shallow diamond reactor is
// embedded between the plates instead of floating outside the chest.
const sentinelChestPlate = [
  [0.48, -0.08],
  [3.76, 0.18],
  [4.54, 1.42],
  [4.10, 4.92],
  [2.78, 6.12],
  [0.76, 5.22],
  [0.18, 2.08],
];
armor.add(
  "body_sentinel_chest_bastion_titanium",
  mergeMeshes(
    extrudePolygonZ(sentinelChestPlate, -3.34, -2.30),
    extrudePolygonZ(mirrorX(sentinelChestPlate), -3.34, -2.30),
  ),
);
const sentinelChestFrame = [
  [0.72, 0.58],
  [3.32, 0.78],
  [3.74, 1.62],
  [3.32, 4.34],
  [2.44, 5.08],
  [1.08, 4.52],
  [0.54, 2.02],
];
armor.add(
  "body_sentinel_chest_frame_gunmetal",
  mergeMeshes(
    extrudePolygonZ(sentinelChestFrame, -3.53, -3.32),
    extrudePolygonZ(mirrorX(sentinelChestFrame), -3.53, -3.32),
  ),
);
armor.add(
  "body_sentinel_collar_yoke_gunmetal",
  mergeMeshes(
    extrudePolygonY(
      [
        [0.20, -2.28],
        [4.34, -2.26],
        [4.72, -0.44],
        [2.68, 0.42],
        [0.38, -0.62],
      ],
      -0.62,
      0.20,
    ),
    extrudePolygonY(
      mirrorX([
        [0.20, -2.28],
        [4.34, -2.26],
        [4.72, -0.44],
        [2.68, 0.42],
        [0.38, -0.62],
      ]),
      -0.62,
      0.20,
    ),
  ),
);
const sentinelAbPlate = [
  [0.42, 5.46],
  [2.78, 5.96],
  [3.86, 7.12],
  [3.56, 10.28],
  [2.26, 11.50],
  [0.54, 10.98],
  [0.12, 7.12],
];
armor.add(
  "body_sentinel_abdominal_bulwark_titanium",
  mergeMeshes(
    extrudePolygonZ(sentinelAbPlate, -3.14, -2.30),
    extrudePolygonZ(mirrorX(sentinelAbPlate), -3.14, -2.30),
  ),
);
armor.add(
  "body_sentinel_segment_trace_led",
  mergeMeshes(
    extrudePolygonZ(
      [[0.72, 6.18], [1.04, 6.24], [0.94, 8.10], [0.52, 9.42], [0.34, 9.24], [0.60, 7.90]],
      -3.30,
      -3.16,
    ),
    extrudePolygonZ(
      mirrorX([[0.72, 6.18], [1.04, 6.24], [0.94, 8.10], [0.52, 9.42], [0.34, 9.24], [0.60, 7.90]]),
      -3.30,
      -3.16,
    ),
  ),
);
armor.add(
  "core_cradle_sentinel_gunmetal",
  extrudePolygonZ(
    [
      [0, 1.96],
      [1.84, 3.62],
      [0, 5.30],
      [-1.84, 3.62],
    ],
    -3.58,
    -3.22,
  ),
);
armor.add(
  "core_housing_sentinel_titanium",
  extrudePolygonZ(
    [
      [0, 2.36],
      [1.38, 3.62],
      [0, 4.90],
      [-1.38, 3.62],
    ],
    -3.70,
    -3.55,
  ),
);
armor.add(
  "core_reactor_sentinel_led",
  extrudePolygonZ(
    [
      [0, 2.70],
      [1.02, 3.62],
      [0, 4.56],
      [-1.02, 3.62],
    ],
    -3.78,
    -3.69,
  ),
);

armor.add(
  "body_backplate_sentinel_load_frame_gunmetal",
  extrudePolygonZ(
    [
      [-1.72, 0.18],
      [1.72, 0.18],
      [2.54, 1.44],
      [2.18, 8.82],
      [1.18, 10.92],
      [0, 11.58],
      [-1.18, 10.92],
      [-2.18, 8.82],
      [-2.54, 1.44],
    ],
    3.78,
    2.82,
  ),
);
armor.add(
  "body_backplate_sentinel_lock_titanium",
  mergeMeshes(
    extrudePolygonZ(
      [
        [1.18, 0.62],
        [3.72, 0.84],
        [4.28, 1.76],
        [3.82, 4.02],
        [2.48, 4.76],
        [1.72, 3.82],
      ],
      3.94,
      3.02,
    ),
    extrudePolygonZ(
      mirrorX([
        [1.18, 0.62],
        [3.72, 0.84],
        [4.28, 1.76],
        [3.82, 4.02],
        [2.48, 4.76],
        [1.72, 3.82],
      ]),
      3.94,
      3.02,
    ),
  ),
);

const sentinelSpineNode = (centerY, halfWidth, halfHeight) =>
  extrudePolygonZ(
    [
      [0, centerY - halfHeight],
      [halfWidth, centerY],
      [0, centerY + halfHeight],
      [-halfWidth, centerY],
    ],
    4.02,
    3.90,
  );
armor.add(
  "body_spine_sentinel_segment_node_led",
  mergeMeshes(
    sentinelSpineNode(1.36, 0.38, 0.48),
    sentinelSpineNode(4.18, 0.30, 0.38),
    sentinelSpineNode(7.02, 0.28, 0.36),
    sentinelSpineNode(9.62, 0.34, 0.44),
  ),
);

const addSentinelWingBay = (side) => {
  const isLeft = side === "left";
  const sign = isLeft ? 1 : -1;
  const mirror = (points) => isLeft ? points : mirrorX(points);
  const prefix = `vane_${side}_sentinel_wing`;

  /*
   * Sentinel's flight silhouette is a four-stage mechanical wing, not a pair of
   * decorative fins. The long leaves are authored vertically so BREACH_MELEE can
   * compress them into a thick dorsal shield. OVERDRIVE_FLIGHT rotates each closed
   * mesh around the shared shoulder hinge, producing a broad stepped span without
   * stretching a texture or exposing paper-thin one-sided planes.
   */
  const rootShell = [
    [2.34, -2.20],
    [4.52, -2.82],
    [6.34, -1.52],
    [6.78, 4.82],
    [5.36, 10.38],
    [3.36, 9.26],
    [2.22, 3.16],
  ];
  const rootFrame = [
    [2.78, -1.48],
    [4.30, -2.02],
    [5.68, -1.02],
    [5.98, 4.54],
    [4.92, 9.12],
    [3.70, 8.34],
    [2.82, 3.10],
  ];
  armor.add(
    `${prefix}_root_shell_titanium`,
    extrudePolygonZ(mirror(rootShell), 4.12, 3.12),
  );
  armor.add(
    `${prefix}_root_frame_gunmetal`,
    extrudePolygonZ(mirror(rootFrame), 4.34, 4.10),
  );

  const primaryShell = [
    [2.54, -0.62],
    [4.44, -1.16],
    [6.42, 0.42],
    [6.66, 8.84],
    [5.12, 17.82],
    [3.30, 16.02],
    [2.32, 5.46],
  ];
  const primaryFrame = [
    [2.96, 0.12],
    [4.30, -0.30],
    [5.72, 0.88],
    [5.88, 8.50],
    [4.76, 16.28],
    [3.68, 14.96],
    [2.90, 5.52],
  ];
  armor.add(
    `${prefix}_primary_shell_titanium`,
    extrudePolygonZ(mirror(primaryShell), 4.42, 3.44),
  );
  armor.add(
    `${prefix}_primary_frame_gunmetal`,
    extrudePolygonZ(mirror(primaryFrame), 4.64, 4.40),
  );

  const secondaryShell = [
    [2.66, 0.90],
    [4.36, 0.42],
    [6.08, 2.02],
    [6.14, 11.14],
    [4.72, 21.12],
    [3.18, 19.10],
    [2.42, 7.02],
  ];
  const secondaryFrame = [
    [3.04, 1.72],
    [4.24, 1.36],
    [5.42, 2.52],
    [5.46, 10.78],
    [4.36, 19.52],
    [3.54, 18.18],
    [2.98, 7.06],
  ];
  armor.add(
    `${prefix}_secondary_shell_titanium`,
    extrudePolygonZ(mirror(secondaryShell), 4.74, 3.78),
  );
  armor.add(
    `${prefix}_secondary_frame_gunmetal`,
    extrudePolygonZ(mirror(secondaryFrame), 4.96, 4.72),
  );

  const tipShell = [
    [2.82, 2.54],
    [4.20, 2.18],
    [5.64, 3.82],
    [5.48, 13.68],
    [4.12, 24.62],
    [3.06, 22.30],
    [2.62, 8.92],
  ];
  const tipFrame = [
    [3.12, 3.36],
    [4.10, 3.10],
    [5.02, 4.30],
    [4.88, 13.28],
    [3.90, 22.76],
    [3.38, 21.16],
    [3.08, 8.96],
  ];
  armor.add(
    `${prefix}_tip_shell_titanium`,
    extrudePolygonZ(mirror(tipShell), 5.04, 4.12),
  );
  armor.add(
    `${prefix}_tip_frame_gunmetal`,
    extrudePolygonZ(mirror(tipFrame), 5.26, 5.02),
  );

  // A compact cartridge remains in the folded socket. In stable overdrive the
  // body renderer hides it and the real SentinelWingmanEntity takes ownership.
  const droneShell = [
    [2.82, 1.86],
    [4.74, 2.08],
    [5.78, 3.48],
    [5.54, 8.62],
    [4.18, 10.76],
    [2.94, 9.66],
    [2.50, 4.22],
  ];
  const droneFrame = [
    [3.20, 2.50],
    [4.42, 2.66],
    [5.08, 3.72],
    [4.90, 8.16],
    [4.02, 9.80],
    [3.38, 9.10],
    [3.08, 4.30],
  ];
  armor.add(
    `vane_${side}_sentinel_drone_pod_shell_titanium`,
    extrudePolygonZ(mirror(droneShell), 5.38, 4.34),
  );
  armor.add(
    `vane_${side}_sentinel_drone_pod_frame_gunmetal`,
    extrudePolygonZ(mirror(droneFrame), 5.62, 5.36),
  );

  // The cannon is a true closed barrel with a recessed full-bright aperture.
  // Its long local Y axis follows the wing hinge and points forward after deployment.
  armor.add(
    `${prefix}_cannon_housing_titanium`,
    loftSuperellipse({
      cx: sign * 4.42,
      segments: 10,
      power: 3.2,
      rings: [
        { y: 0.22, rx: 1.08, rz: 1.02, cz: 4.70 },
        { y: 1.06, rx: 1.28, rz: 1.20, cz: 4.70 },
        { y: 4.62, rx: 1.06, rz: 1.00, cz: 4.70 },
        { y: 5.36, rx: 0.86, rz: 0.82, cz: 4.70 },
      ],
    }),
  );
  armor.add(
    `${prefix}_cannon_barrel_gunmetal`,
    loftSuperellipse({
      cx: sign * 4.42,
      segments: 10,
      power: 3.0,
      rings: [
        { y: 4.78, rx: 0.78, rz: 0.74, cz: 4.70 },
        { y: 8.82, rx: 0.68, rz: 0.64, cz: 4.70 },
        { y: 9.52, rx: 0.82, rz: 0.78, cz: 4.70 },
      ],
    }),
  );
  armor.add(
    `${prefix}_cannon_muzzle_led`,
    torusY({
      cx: sign * 4.42,
      cy: 9.62,
      cz: 4.70,
      rx: 0.72,
      rz: 0.68,
      tube: 0.16,
      majorSegments: 14,
      minorSegments: 5,
    }),
  );

  // Interrupted nodes preserve a mechanical rhythm without returning to one long neon stripe.
  const node = (points) => extrudePolygonZ(mirror(points), 4.38, 4.26);
  armor.add(
    `${prefix}_root_node_led`,
    node([
      [3.58, -0.72],
      [4.20, -0.36],
      [3.84, 0.18],
      [3.24, -0.18],
    ]),
  );
  armor.add(
    `${prefix}_primary_node_led`,
    node([
      [3.62, 3.18],
      [4.28, 3.48],
      [4.06, 4.02],
      [3.42, 3.70],
    ]),
  );
  armor.add(
    `${prefix}_secondary_node_led`,
    extrudePolygonZ(
      mirror([
        [3.48, 10.42],
        [4.12, 10.68],
        [3.98, 12.42],
        [3.42, 13.56],
        [3.18, 13.36],
        [3.54, 12.18],
      ]),
      5.08,
      4.96,
    ),
  );
  armor.add(
    `${prefix}_tip_node_led`,
    extrudePolygonZ(
      mirror([
        [3.42, 17.12],
        [3.96, 17.34],
        [3.82, 19.22],
        [3.34, 20.42],
        [3.12, 20.20],
        [3.46, 18.96],
      ]),
      5.38,
      5.26,
    ),
  );
  armor.add(
    `vane_${side}_sentinel_drone_pod_node_led`,
    extrudePolygonZ(mirror([
      [3.62, 5.44],
      [4.34, 5.76],
      [4.16, 6.34],
      [3.46, 6.00],
    ]), 5.74, 5.62),
  );
  armor.add(
    `vane_${side}_sentinel_drone_pod_status_led`,
    extrudePolygonZ(mirror([
      [3.50, 8.14],
      [4.18, 8.42],
      [4.00, 8.98],
      [3.38, 8.68],
    ]), 5.74, 5.62),
  );
};
addSentinelWingBay("left");
addSentinelWingBay("right");

armor.add(
  "body_sentinel_waist_apron_titanium",
  extrudePolygonZ(
    [
      [-3.82, 8.42],
      [3.82, 8.42],
      [3.54, 11.62],
      [2.16, 12.28],
      [0, 11.78],
      [-2.16, 12.28],
      [-3.54, 11.62],
    ],
    -3.18,
    -2.72,
  ),
);

const addSentinelBulwarks = (side) => {
  const isLeft = side === "left";
  const sign = isLeft ? 1 : -1;
  const armCenter = sign;
  const frontUpper = isLeft
    ? [[-1.22, 0.74], [3.12, 0.58], [3.54, 1.42], [3.18, 5.18], [1.42, 5.72], [-1.08, 4.98]]
    : [[-3.12, 0.58], [1.22, 0.74], [1.08, 4.98], [-1.42, 5.72], [-3.18, 5.18], [-3.54, 1.42]];
  const frontForearm = isLeft
    ? [[-1.10, 6.12], [3.06, 6.00], [3.46, 7.06], [3.16, 10.18], [1.84, 10.76], [-0.96, 10.30]]
    : [[-3.06, 6.00], [1.10, 6.12], [0.96, 10.30], [-1.84, 10.76], [-3.16, 10.18], [-3.46, 7.06]];

  armor.add(
    `arm_${side}_sentinel_upper_bastion_titanium`,
    loftSuperellipse({
      cx: armCenter,
      segments: 12,
      power: 3.0,
      rings: [
        { y: -2.82, rx: 1.84, rz: 1.92 },
        { y: -2.42, rx: 2.76, rz: 2.72 },
        { y: -1.10, rx: 2.94, rz: 2.80 },
        { y: 1.24, rx: 2.62, rz: 2.64 },
        { y: 2.04, rx: 2.08, rz: 2.28 },
      ],
    }),
  );
  armor.add(
    `arm_${side}_sentinel_upper_tower_titanium`,
    extrudePolygonX(
      [
        [-2.30, -1.72],
        [-1.72, -2.84],
        [3.66, -2.70],
        [5.22, -0.52],
        [3.72, 2.54],
        [-1.34, 2.68],
      ],
      sign * 3.88,
      sign * 3.06,
    ),
  );
  armor.add(
    `arm_${side}_sentinel_upper_frontplate_titanium`,
    extrudePolygonZ(frontUpper, -3.14, -2.18),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_bulwark_titanium`,
    extrudePolygonZ(frontForearm, -3.18, -2.18),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_outer_brace_gunmetal`,
    extrudePolygonX(
      [
        [6.10, -1.76],
        [6.52, -2.42],
        [9.58, -2.54],
        [10.62, -1.54],
        [9.84, 1.38],
        [6.38, 1.46],
      ],
      sign * 3.78,
      sign * 3.08,
    ),
  );
  armor.add(
    `arm_${side}_sentinel_upper_node_led`,
    extrudePolygonZ(
      [
        [armCenter + sign * 1.42, 1.16],
        [armCenter + sign * 1.72, 1.06],
        [armCenter + sign * 1.54, 3.78],
        [armCenter + sign * 1.06, 4.52],
        [armCenter + sign * 0.88, 4.34],
        [armCenter + sign * 1.20, 3.54],
      ],
      -3.34,
      -3.18,
    ),
  );
  if (!isLeft) {
  // Sentinel carries one heavy positive-Z breaker cassette on the right forearm.
  // The hand shell is split at its center seam so deployment can open two real
  // armor leaves instead of moving one rigid box.
  const weaponX = (value) => (isLeft ? -value : value);
  const weaponPolygon = (points) => points.map(([x, y]) => [weaponX(x), y]);
  armor.add(
    `arm_${side}_sentinel_forearm_cover_outer_titanium`,
    extrudePolygonZ(
      weaponPolygon([
        [-3.18, 6.04], [-1.26, 6.06], [-1.02, 6.86],
        [-1.12, 9.94], [-1.70, 10.66], [-2.86, 10.42], [-3.26, 9.44],
      ]),
      3.55,
      3.24,
    ),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_cover_inner_titanium`,
    extrudePolygonZ(
      weaponPolygon([
        [-1.10, 6.06], [0.72, 6.12], [1.04, 7.02],
        [0.86, 10.10], [-0.18, 10.62], [-1.02, 9.96],
      ]),
      3.44,
      3.08,
    ),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_hand_shell_outer_titanium`,
    loftChamferedShell({
      cx: weaponX(-2.02),
      bevel: 0.14,
      rings: [
        { y: 9.86, hx: 0.96, hz: 0.43, cz: 3.02 },
        { y: 10.76, hx: 1.02, hz: 0.46, cz: 3.05 },
        { y: 12.20, hx: 0.86, hz: 0.36, cz: 3.00 },
      ],
    }),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_hand_shell_inner_titanium`,
    loftChamferedShell({
      cx: weaponX(0.02),
      bevel: 0.14,
      rings: [
        { y: 9.86, hx: 0.96, hz: 0.43, cz: 3.02 },
        { y: 10.76, hx: 1.02, hz: 0.46, cz: 3.05 },
        { y: 12.20, hx: 0.86, hz: 0.36, cz: 3.00 },
      ],
    }),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_wrist_lock_gunmetal`,
    loftChamferedShell({
      cx: weaponX(-1.0),
      bevel: 0.12,
      rings: [
        { y: 9.58, hx: 1.84, hz: 0.34, cz: 3.03 },
        { y: 9.90, hx: 2.02, hz: 0.40, cz: 3.04 },
        { y: 10.22, hx: 1.92, hz: 0.36, cz: 3.03 },
      ],
    }),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_emitter_shutter_gunmetal`,
    extrudePolygonZ(
      weaponPolygon([
        [-1.52, 9.72], [-0.48, 9.72], [-0.42, 11.62],
        [-0.72, 12.02], [-1.28, 12.02], [-1.58, 11.62],
      ]),
      3.68,
      3.50,
    ),
  );
  armor.add(
    `arm_${side}_sentinel_forearm_emitter_track_led`,
    extrudePolygonZ(
      weaponPolygon([
        [-1.28, 6.52], [-0.72, 6.52], [-0.68, 11.70],
        [-0.84, 12.18], [-1.16, 12.18], [-1.32, 11.70],
      ]),
      3.76,
      3.60,
    ),
  );
  }
  armor.add(
    `leg_${side}_sentinel_hip_shield_titanium`,
    extrudePolygonX(
      [
        [-0.18, -2.18],
        [0.62, -2.76],
        [4.92, -2.62],
        [6.92, -0.82],
        [6.20, 2.24],
        [1.02, 2.42],
      ],
      sign * 2.88,
      sign * 2.18,
    ),
  );
  armor.add(
    `leg_${side}_sentinel_thigh_bastion_titanium`,
    extrudePolygonZ(
      [
        [-1.92, 0.02],
        [1.92, 0.02],
        [2.42, 1.12],
        [1.94, 4.94],
        [0.54, 5.86],
        [-1.72, 5.08],
        [-2.42, 1.40],
      ],
      -3.10,
      -2.14,
    ),
  );
  armor.add(
    `leg_${side}_sentinel_knee_lock_titanium`,
    extrudePolygonZ(
      [
        [0, 4.76],
        [1.84, 5.14],
        [2.22, 6.06],
        [1.46, 7.22],
        [0, 7.54],
        [-1.46, 7.22],
        [-2.22, 6.06],
        [-1.84, 5.14],
      ],
      -3.34,
      -2.28,
    ),
  );
  armor.add(
    `leg_${side}_sentinel_thigh_rearlock_gunmetal`,
    extrudePolygonZ(
      [[-1.86, 0.34], [1.86, 0.34], [2.32, 1.40], [1.68, 5.22], [0, 5.92], [-1.68, 5.22], [-2.32, 1.40]],
      3.04,
      2.12,
    ),
  );
  armor.add(
    `leg_${side}_sentinel_thigh_node_led`,
    extrudePolygonZ(
      [
        [sign * 1.30, 0.70],
        [sign * 1.62, 0.64],
        [sign * 1.46, 3.24],
        [sign * 0.68, 4.82],
        [sign * 0.44, 5.72],
        [sign * 0.16, 5.64],
        [sign * 0.36, 4.58],
        [sign * 1.16, 3.04],
      ],
      -3.30,
      -3.12,
    ),
  );

  armor.add(
    `boot_${side}_sentinel_calf_bastion_titanium`,
    extrudePolygonZ(
      [
        [-1.84, 6.12],
        [1.84, 6.12],
        [2.34, 7.08],
        [2.02, 10.60],
        [0.72, 11.52],
        [-1.72, 10.68],
        [-2.34, 7.30],
      ],
      -3.18,
      -2.16,
    ),
  );
  armor.add(
    `boot_${side}_sentinel_outer_stabilizer_titanium`,
    extrudePolygonX(
      [
        [6.30, -1.82],
        [6.72, -2.40],
        [11.18, -2.58],
        [12.48, -1.24],
        [11.88, 1.74],
        [7.10, 1.58],
      ],
      sign * 2.88,
      sign * 2.08,
    ),
  );
  armor.add(
    `boot_${side}_sentinel_heel_lock_gunmetal`,
    extrudePolygonZ(
      [[-1.78, 8.14], [1.78, 8.14], [2.28, 9.30], [1.82, 12.18], [0, 12.70], [-1.82, 12.18], [-2.28, 9.30]],
      3.18,
      2.18,
    ),
  );
  armor.add(
    `boot_${side}_sentinel_toe_ram_titanium`,
    extrudePolygonY(
      [
        [-2.20, -1.54],
        [-1.80, -3.62],
        [-0.70, -4.02],
        [1.62, -3.70],
        [2.34, -2.30],
        [2.02, 2.10],
        [0, 2.64],
        [-2.02, 2.10],
      ],
      12.82,
      11.06,
    ),
  );
  armor.add(
    `boot_${side}_sentinel_sole_field_led`,
    mergeMeshes(
      extrudePolygonY(
        [[-1.82, -3.48], [0, -3.88], [1.82, -3.48], [1.54, -1.56], [0, -1.04], [-1.54, -1.56]],
        13.04,
        12.86,
      ),
      extrudePolygonY(
        [[-1.54, -0.32], [0, -0.86], [1.54, -0.32], [1.72, 1.40], [0, 2.48], [-1.72, 1.40]],
        13.04,
        12.86,
      ),
    ),
  );
  armor.add(
    `boot_${side}_sentinel_gravity_node_led`,
    extrudePolygonZ(
      [
        [sign * 1.32, 8.34],
        [sign * 1.66, 8.26],
        [sign * 1.48, 10.58],
        [sign * 0.52, 11.82],
        [sign * 0.20, 11.70],
        [sign * 1.10, 10.30],
      ],
      -3.38,
      -3.20,
    ),
  );
};
addSentinelBulwarks("left");
addSentinelBulwarks("right");

// -----------------------------------------------------------------------------
// Arms: side-aware local X offsets match vanilla wide-player arm pivots exactly.
// -----------------------------------------------------------------------------
const addArm = (side) => {
  const isLeft = side === "left";
  const cx = isLeft ? 1 : -1;
  const outerX = isLeft ? 3.62 : -3.62;
  const innerX = isLeft ? 3.12 : -3.12;
  const sign = isLeft ? 1 : -1;
  const prefix = `arm_${side}`;

  armor.add(
    `${prefix}_upper_underlayer_shadow`,
    loftChamferedShell({
      cx,
      bevel: 0.12,
      rings: [
        { y: -2.36, hx: 2.36, hz: 2.36 },
        { y: -1.18, hx: 2.42, hz: 2.42 },
        { y: 3.72, hx: 2.38, hz: 2.38 },
        { y: 4.109091, hx: 2.36, hz: 2.36 },
      ],
    }),
  );

  armor.add(
    `${prefix}_forearm_underlayer_shadow`,
    loftChamferedShell({
      cx,
      bevel: 0.12,
      rings: [
        { y: 3.858974, hx: 2.36, hz: 2.36 },
        { y: 4.30, hx: 2.38, hz: 2.38 },
        { y: 8.62, hx: 2.36, hz: 2.38 },
        { y: 10.32, hx: 2.38, hz: 2.40 },
      ],
    }),
  );

  // 肘轴上的独立软连接套采用一半弯曲角，覆盖上下两段旋转后形成的楔形缝隙。
  armor.add(
    `${prefix}_elbow_seal_shadow`,
    loftChamferedShell({
      cx,
      bevel: 0.12,
      rings: [
        { y: 5.72, hx: 2.40, hz: 2.40 },
        { y: 6.00, hx: 2.43, hz: 2.43 },
        { y: 6.28, hx: 2.40, hz: 2.40 },
      ],
    }),
  );

  armor.add(
    `${prefix}_upper_pauldron_titanium`,
    loftSuperellipse({
      cx,
      segments: 12,
      power: 3.0,
      rings: [
        { y: -2.64, rx: 1.72, rz: 1.78 },
        { y: -2.34, rx: 2.48, rz: 2.48 },
        { y: -1.08, rx: 2.62, rz: 2.54 },
        { y: 0.86, rx: 2.32, rz: 2.40 },
        { y: 1.42, rx: 1.82, rz: 2.08 },
      ],
    }),
  );

  const frontUpper = isLeft
    ? [
        [-1.18, 1.10],
        [2.88, 1.28],
        [3.26, 2.08],
        [2.78, 3.62],
        [1.28, 3.96],
        [-0.94, 3.72],
      ]
    : [
        [-2.88, 1.28],
        [1.18, 1.10],
        [0.94, 3.72],
        [-1.28, 3.96],
        [-2.78, 3.62],
        [-3.26, 2.08],
      ];
  armor.add(`${prefix}_upper_plate_titanium`, extrudePolygonZ(frontUpper, -2.78, -2.16));

  const frontForearm = isLeft
    ? [
        [-1.04, 4.18],
        [1.22, 4.56],
        [2.92, 4.12],
        [3.20, 8.18],
        [2.56, 10.18],
        [-0.86, 10.10],
      ]
    : [
        [-2.92, 4.12],
        [-1.22, 4.56],
        [1.04, 4.18],
        [0.86, 10.10],
        [-2.56, 10.18],
        [-3.20, 8.18],
      ];
  armor.add(`${prefix}_forearm_plate_titanium`, extrudePolygonZ(frontForearm, -2.84, -2.14));

  armor.add(
    `${prefix}_upper_outer_rail_gunmetal`,
    extrudePolygonX(
      [
        [0.18, -1.62],
        [1.10, -2.28],
        [3.72, -2.22],
        [4.02, -1.32],
        [3.70, 1.06],
        [2.12, 1.18],
      ],
      outerX,
      innerX,
    ),
  );

  armor.add(
    `${prefix}_forearm_outer_rail_gunmetal`,
    extrudePolygonX(
      [
        [4.12, -1.46],
        [4.38, -2.18],
        [8.98, -2.18],
        [10.08, -1.20],
        [8.86, 0.94],
        [4.42, 1.02],
      ],
      outerX,
      innerX,
    ),
  );

  armor.add(
    `${prefix}_upper_rear_plate_titanium`,
    extrudePolygonZ(
      isLeft
        ? [
            [-0.82, 1.28],
            [2.92, 1.18],
            [3.22, 3.50],
            [2.24, 3.96],
            [-0.74, 3.72],
          ]
        : [
            [-2.92, 1.18],
            [0.82, 1.28],
            [0.74, 3.72],
            [-2.24, 3.96],
            [-3.22, 3.50],
          ],
      2.66,
      2.08,
    ),
  );

  armor.add(
    `${prefix}_forearm_rear_plate_titanium`,
    extrudePolygonZ(
      isLeft
        ? [[-0.78, 4.16], [2.58, 4.12], [3.12, 5.02], [2.66, 9.76], [0.82, 10.18], [-0.72, 9.42]]
        : [[-2.58, 4.12], [0.78, 4.16], [0.72, 9.42], [-0.82, 10.18], [-2.66, 9.76], [-3.12, 5.02]],
      2.64,
      2.08,
    ),
  );

  const traceX0 = cx + sign * 1.35;
  const traceX1 = cx + sign * 1.62;
  armor.add(
    `${prefix}_upper_energy_trace_led`,
    extrudePolygonZ(
      [
        [traceX0, 1.82],
        [traceX1, 1.68],
        [traceX1 - sign * 0.08, 3.42],
        [traceX0 - sign * 0.22, 3.88],
      ],
      -3.00,
      -2.84,
    ),
  );

  armor.add(
    `${prefix}_forearm_energy_trace_led`,
    extrudePolygonZ(
      [
        [cx + sign * 0.66, 4.18],
        [cx + sign * 0.92, 4.12],
        [cx + sign * 0.70, 6.08],
        [cx + sign * 0.84, 9.34],
        [cx + sign * 0.56, 9.42],
        [cx + sign * 0.40, 6.18],
      ],
      -3.00,
      -2.84,
    ),
  );
};

addArm("left");
addArm("right");

// -----------------------------------------------------------------------------
// Legs and boots: separate slot groups meet at y=6.35 with overlapping inner seals.
// -----------------------------------------------------------------------------
const addLegAndBoot = (side) => {
  const isLeft = side === "left";
  const sign = isLeft ? 1 : -1;
  const legPrefix = `leg_${side}`;
  const bootPrefix = `boot_${side}`;

  armor.add(
    `${legPrefix}_thigh_underlayer_shadow`,
    loftChamferedShell({
      bevel: 0.12,
      rings: [
        { y: -0.34, hx: 2.36, hz: 2.36 },
        { y: 1.10, hx: 2.40, hz: 2.40 },
        { y: 4.82, hx: 2.38, hz: 2.38 },
        { y: 6.30, hx: 2.36, hz: 2.36 },
      ],
    }),
  );

  // 膝部软连接套跟随半角关节，防止提膝时大腿内衬和小腿内衬分开露出皮肤。
  armor.add(
    `${legPrefix}_knee_seal_shadow`,
    loftChamferedShell({
      bevel: 0.12,
      rings: [
        { y: 5.70, hx: 2.40, hz: 2.40 },
        { y: 6.00, hx: 2.43, hz: 2.43 },
        { y: 6.32, hx: 2.40, hz: 2.40 },
      ],
    }),
  );

  armor.add(
    `${legPrefix}_thigh_plate_titanium`,
    extrudePolygonZ(
      [
        [-1.78, 0.18],
        [1.72, 0.10],
        [2.22, 1.18],
        [1.66, 4.68],
        [0.42, 5.48],
        [-1.58, 4.72],
        [-2.20, 1.52],
      ],
      -2.78,
      -2.12,
    ),
  );

  armor.add(
    `${legPrefix}_thigh_outer_flank_gunmetal`,
    extrudePolygonX(
      [
        [0.42, -1.46],
        [0.92, -2.04],
        [5.18, -2.16],
        [6.28, -0.82],
        [5.20, 1.24],
        [1.20, 1.38],
      ],
      sign * 2.46,
      sign * 2.04,
    ),
  );

  armor.add(
    `${legPrefix}_thigh_rear_plate_titanium`,
    extrudePolygonZ(
      [
        [-1.72, 0.28],
        [1.72, 0.28],
        [2.12, 1.52],
        [1.54, 5.14],
        [0, 5.78],
        [-1.54, 5.14],
        [-2.12, 1.52],
      ],
      2.66,
      2.08,
    ),
  );

  armor.add(
    `${legPrefix}_thigh_knee_titanium`,
    extrudePolygonZ(
      [
        [0, 4.72],
        [1.62, 5.16],
        [2.04, 6.06],
        [1.28, 6.88],
        [0, 7.14],
        [-1.28, 6.88],
        [-2.04, 6.06],
        [-1.62, 5.16],
      ],
      -3.06,
      -2.24,
    ),
  );

  armor.add(
    `${legPrefix}_thigh_energy_trace_led`,
    extrudePolygonZ(
      [
        [sign * 1.34, 0.72],
        [sign * 1.62, 0.62],
        [sign * 1.42, 3.28],
        [sign * 0.58, 4.78],
        [sign * 0.40, 6.12],
        [sign * 0.12, 6.08],
        [sign * 0.26, 4.64],
        [sign * 1.10, 3.10],
      ],
      -2.96,
      -2.78,
    ),
  );

  armor.add(
    `${bootPrefix}_underlayer_shadow`,
    loftChamferedShell({
      bevel: 0.12,
      rings: [
        { y: 5.98, hx: 2.36, hz: 2.36 },
        { y: 7.48, hx: 2.40, hz: 2.40 },
        { y: 10.72, hx: 2.38, hz: 2.46, cz: -0.06 },
        { y: 12.34, hx: 2.40, hz: 2.58, cz: -0.28 },
      ],
    }),
  );

  armor.add(
    `${bootPrefix}_calf_plate_titanium`,
    extrudePolygonZ(
      [
        [-1.68, 6.28],
        [1.68, 6.28],
        [2.12, 7.14],
        [1.78, 10.32],
        [0.58, 11.02],
        [-1.52, 10.42],
        [-2.12, 7.38],
      ],
      -2.90,
      -2.18,
    ),
  );

  armor.add(
    `${bootPrefix}_outer_guard_titanium`,
    extrudePolygonX(
      [
        [6.42, -1.52],
        [6.82, -2.12],
        [11.42, -2.34],
        [12.28, -1.12],
        [11.72, 1.54],
        [7.20, 1.46],
      ],
      sign * 2.52,
      sign * 2.06,
    ),
  );

  armor.add(
    `${bootPrefix}_heel_gunmetal`,
    extrudePolygonZ(
      [
        [-1.64, 8.26],
        [1.64, 8.26],
        [2.10, 9.34],
        [1.66, 12.12],
        [0, 12.54],
        [-1.66, 12.12],
        [-2.10, 9.34],
      ],
      2.84,
      2.18,
    ),
  );

  armor.add(
    `${bootPrefix}_toe_titanium`,
    extrudePolygonY(
      [
        [-2.06, -1.44],
        [-1.68, -3.22],
        [-0.64, -3.62],
        [1.52, -3.34],
        [2.20, -2.18],
        [1.86, 1.98],
        [0, 2.46],
        [-1.86, 1.98],
      ],
      12.62,
      11.18,
    ),
  );

  // 真正位于鞋底下方的双区反重力光板；与踝部光环分开，低角度和俯冲视角都能看见。
  armor.add(
    `${bootPrefix}_sole_field_led`,
    mergeMeshes(
      extrudePolygonY(
        [
          [-1.70, -3.02],
          [0, -3.42],
          [1.70, -3.02],
          [1.42, -1.52],
          [0, -1.04],
          [-1.42, -1.52],
        ],
        12.84,
        12.68,
      ),
      extrudePolygonY(
        [
          [-1.42, -0.30],
          [0, -0.72],
          [1.42, -0.30],
          [1.62, 1.28],
          [0, 2.30],
          [-1.62, 1.28],
        ],
        12.84,
        12.68,
      ),
    ),
  );

  armor.add(
    `${bootPrefix}_ankle_frame_gunmetal`,
    torusY({ cy: 7.72, rx: 2.36, rz: 2.40, tube: 0.20, majorSegments: 14, minorSegments: 5 }),
  );
  armor.add(
    `${bootPrefix}_gravity_ring_led`,
    torusY({ cy: 8.18, rx: 2.55, rz: 2.58, tube: 0.13, majorSegments: 16, minorSegments: 5 }),
  );

  armor.add(
    `${bootPrefix}_energy_trace_led`,
    extrudePolygonZ(
      [
        [sign * 1.28, 8.62],
        [sign * 1.54, 8.48],
        [sign * 1.30, 10.56],
        [sign * 0.38, 11.74],
        [sign * 0.12, 11.64],
        [sign * 1.02, 10.34],
      ],
      -3.06,
      -2.88,
    ),
  );
};

addLegAndBoot("left");
addLegAndBoot("right");

armor.fitArmSegmentsToVanillaElbow();

// -----------------------------------------------------------------------------
// Per-variant authored output. Shared groups are copied into three independent
// meshes and reshaped here in vertex space, never by runtime PoseStack scaling.
// Coverage shells and joint seals stay bit-identical so every silhouette keeps
// the same anti-clipping contract around skin, elbows and knees.
// -----------------------------------------------------------------------------

const copyMesh = (mesh) => ({
  vertices: mesh.vertices.map((vertex) => [...vertex]),
  faces: mesh.faces.map((face) => [...face]),
});

const isCoverageOrClearanceGroup = (name) =>
  name.includes("underlayer") ||
  name.includes("_seal_") ||
  name === "head_shell_shadow" ||
  name === "head_faceplate_gunmetal" ||
  name === "visor_lens_visor" ||
  name === "head_rear_left_gunmetal" ||
  name === "head_rear_right_gunmetal";

const includesVariantGroup = (variant, name) => {
  const swiftExclusive = name.includes("_swift_");
  const sentinelExclusive = name.includes("_sentinel_");
  if (variant === "vanguard") return !swiftExclusive && !sentinelExclusive;
  if (variant === "swift") {
    return !sentinelExclusive && ![
      "forearm_rear_plate",
      "thigh_rear_plate",
      "outer_guard",
      "head_cheek_left_jaw",
      "head_cheek_right_jaw",
      "head_crown_rear_cap",
    ].some((token) => name.includes(token));
  }
  if (sentinelExclusive) return true;
  // Sentinel shares only invisible/clearance shells and the inset visor lens. Every
  // visible alloy plate, frame, trace and reactor belongs to its own authored set.
  const sentinelCoverage = new Set([
    "head_shell_shadow",
    "head_faceplate_gunmetal",
    "head_rear_left_gunmetal",
    "head_rear_right_gunmetal",
    "head_neck_seal_gunmetal",
    "visor_lens_visor",
    "body_underlayer_shadow",
    "arm_left_upper_underlayer_shadow",
    "arm_left_forearm_underlayer_shadow",
    "arm_left_elbow_seal_shadow",
    "arm_right_upper_underlayer_shadow",
    "arm_right_forearm_underlayer_shadow",
    "arm_right_elbow_seal_shadow",
    "leg_left_thigh_underlayer_shadow",
    "leg_left_knee_seal_shadow",
    "leg_right_thigh_underlayer_shadow",
    "leg_right_knee_seal_shadow",
    "boot_left_underlayer_shadow",
    "boot_right_underlayer_shadow",
  ]);
  return sentinelCoverage.has(name);
};

const silhouetteTransform = (variant, name, mesh) => {
  const result = copyMesh(mesh);
  if (isCoverageOrClearanceGroup(name)) return result;

  const isHead = name.startsWith("head_") || name.startsWith("visor_");
  const isBody = name.startsWith("body_") || name.startsWith("core_");
  const isVane = name.startsWith("vane_");
  const isArm = name.startsWith("arm_");
  const isLeg = name.startsWith("leg_");
  const isBoot = name.startsWith("boot_");
  let scaleX = 1;
  let scaleZ = 1;
  let translateZ = 0;

  if (variant === "vanguard") {
    if (isHead) {
      scaleX = 1.035;
      scaleZ = 1.025;
    } else if (isBody) {
      scaleX = name.startsWith("core_") ? 1.06 : 1.10;
      scaleZ = name.startsWith("core_") ? 0.98 : 1.08;
    } else if (isVane) {
      scaleX = 1.08;
      scaleZ = 1.04;
    } else if (isArm) {
      scaleX = name.includes("forearm") ? 1.13 : 1.10;
      scaleZ = name.includes("forearm") ? 1.14 : 1.10;
    } else if (isLeg || isBoot) {
      scaleX = isBoot ? 1.11 : 1.08;
      scaleZ = isBoot ? 1.12 : 1.08;
    }
  } else if (variant === "swift") {
    if (isHead) {
      scaleX = name.includes("_swift_") ? 1.0 : 0.94;
      scaleZ = name.includes("_swift_") ? 1.08 : 0.96;
      if (name.includes("_swift_")) translateZ = 0.24;
    } else if (isBody) {
      scaleX = name.startsWith("core_") ? 0.84 : 0.88;
      scaleZ = name.startsWith("core_") ? 0.96 : 0.91;
    } else if (isVane) {
      scaleX = 0.82;
      scaleZ = 1.28;
      translateZ = 0.42;
    } else if (isArm) {
      scaleX = 0.88;
      scaleZ = name.includes("_swift_") ? 1.06 : 0.88;
    } else if (isLeg) {
      scaleX = 0.89;
      scaleZ = 0.90;
    } else if (isBoot) {
      scaleX = 0.90;
      scaleZ = name.includes("toe") ? 1.16 : 0.94;
      if (name.includes("toe")) translateZ = -0.16;
    }
  } else {
    const sentinelAuthored = name.includes("_sentinel_");
    if (isHead) {
      scaleX = sentinelAuthored ? 1.0 : 1.075;
      scaleZ = sentinelAuthored ? 1.0 : 1.065;
    } else if (isBody) {
      scaleX = sentinelAuthored ? 1.0 : name.startsWith("core_") ? 1.18 : 1.14;
      scaleZ = sentinelAuthored ? 1.0 : name.startsWith("core_") ? 0.96 : 1.13;
    } else if (isVane) {
      // Sentinel wing bays are authored at final thickness.  Flattening the old
      // generic stabilizer made the rear silhouette read as decals instead of
      // self-contained mechanical pods.
      scaleX = 1.0;
      scaleZ = 1.02;
      translateZ = 0.06;
    } else if (isArm) {
      scaleX = sentinelAuthored ? 1.0 : name.includes("upper") || name.includes("forearm") ? 1.17 : 1.12;
      scaleZ = sentinelAuthored ? 1.0 : name.includes("upper") || name.includes("forearm") ? 1.18 : 1.13;
    } else if (isLeg) {
      scaleX = sentinelAuthored ? 1.0 : 1.13;
      scaleZ = sentinelAuthored ? 1.0 : 1.14;
    } else if (isBoot) {
      scaleX = sentinelAuthored ? 1.0 : 1.17;
      scaleZ = sentinelAuthored ? 1.0 : 1.20;
    }
  }

  result.vertices = result.vertices.map(([x, y, z]) => [
    round(x * scaleX),
    y,
    round(z * scaleZ + translateZ),
  ]);
  return result;
};

const buildVariantMesh = (variant) => {
  const result = new ObjBuilder();
  for (const [name, mesh] of armor.groups) {
    if (!includesVariantGroup(variant, name)) continue;
    result.add(name, silhouetteTransform(variant, name, mesh));
  }
  return result;
};
const variantMeshes = new Map([
  ["vanguard", buildVariantMesh("vanguard")],
  ["swift", buildVariantMesh("swift")],
  ["sentinel", buildVariantMesh("sentinel")],
]);

fs.mkdirSync(outputDirectory, { recursive: true });
for (const [variant, mesh] of variantMeshes) {
  const outputPath = path.join(outputDirectory, `flight_armor_${variant}.obj`);
  fs.writeFileSync(outputPath, mesh.toObj(), "utf8");
  const stats = mesh.stats();
  console.log(`Generated ${path.relative(projectDir, outputPath)}`);
  console.log(
    `${stats.groups} groups, ${stats.vertices} vertices, ${stats.faces} faces ` +
      `(${stats.triangles} triangles, ${stats.quads} quads)`,
  );
}
