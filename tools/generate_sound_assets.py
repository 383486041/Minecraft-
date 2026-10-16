#!/usr/bin/env python3
"""生成凌空战甲的第三代原创程序合成音效。

设计目标：机械反重力动力总成、可辨认的伺服/齿轮/泵阀结构，以及只在启动提示中
出现的克制科技纹理。持续工作声不再依靠相邻正弦拍频伪装“未来感”。
所有输出均为 48 kHz 单声道 OGG Vorbis，不采样任何第三方音频。
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
import soundfile as sf


SAMPLE_RATE = 48_000
OUTPUT_ROOT = (
    Path(__file__).resolve().parents[1]
    / "src/main/resources/assets/flight_armor/sounds/armor"
)


def timeline(duration: float) -> np.ndarray:
    return np.arange(round(duration * SAMPLE_RATE), dtype=np.float64) / SAMPLE_RATE


def smoothstep(value: np.ndarray) -> np.ndarray:
    value = np.clip(value, 0.0, 1.0)
    return value * value * (3.0 - 2.0 * value)


def fade_envelope(t: np.ndarray, duration: float, attack: float, release: float) -> np.ndarray:
    return smoothstep(t / max(attack, 0.001)) * smoothstep((duration - t) / max(release, 0.001))


def gaussian(t: np.ndarray, center: float, width: float) -> np.ndarray:
    return np.exp(-0.5 * np.square((t - center) / max(width, 0.001)))


def periodic_sine(t: np.ndarray, frequency: float, duration: float, phase: float = 0.0) -> np.ndarray:
    cycles = max(1, round(frequency * duration))
    return np.sin(2.0 * np.pi * cycles * t / duration + phase)

def periodic_phase(t: np.ndarray, frequency: float, duration: float, phase: float = 0.0) -> np.ndarray:
    """返回首尾相位闭合的 0..1 周期，用于合成齿轮与换向器节拍。"""
    cycles = max(1, round(frequency * duration))
    return np.mod(cycles * t / duration + phase, 1.0)


def triangle_wave(t: np.ndarray, frequency: float, duration: float, phase: float = 0.0) -> np.ndarray:
    cycle = periodic_phase(t, frequency, duration, phase)
    return 1.0 - 4.0 * np.abs(cycle - 0.5)


def pulse_train(
    t: np.ndarray,
    frequency: float,
    duration: float,
    sharpness: float = 10.0,
    phase: float = 0.0,
) -> np.ndarray:
    """平滑窄脉冲；比方波更适合表现换向片、泵阀和齿轮齿通过。"""
    cycle = periodic_phase(t, frequency, duration, phase)
    pulse = np.power(0.5 + 0.5 * np.cos(2.0 * np.pi * cycle), sharpness)
    return pulse - float(np.mean(pulse))


def mechanical_rotor(
    t: np.ndarray,
    duration: float,
    shaft_hz: float,
    load: float,
    seed: int,
    external: bool = False,
) -> np.ndarray:
    """轴频、换向器、齿轮啮合和轴承摩擦组成的稳定电机总成。"""
    shaft = (
        0.24 * periodic_sine(t, shaft_hz, duration)
        + 0.11 * triangle_wave(t, shaft_hz * 2.0, duration, 0.17)
        + 0.045 * periodic_sine(t, shaft_hz * 3.0, duration, 0.63)
    )
    commutator = pulse_train(t, shaft_hz * 6.0, duration, 7.0, 0.08)
    mesh_frequency = shaft_hz * (11.0 if external else 9.0)
    mesh = (
        periodic_sine(t, mesh_frequency, duration, 0.28)
        + 0.32 * periodic_sine(t, mesh_frequency * 2.0, duration, 0.74)
    )
    bearing = band_noise(
        duration,
        115.0,
        2200.0 if external else 1500.0,
        -0.72,
        seed,
    )
    torque_ripple = 0.78 + 0.12 * triangle_wave(t, shaft_hz / 4.0, duration, 0.31)
    return torque_ripple * (
        shaft * (0.62 + 0.38 * load)
        + commutator * (0.060 + 0.055 * load)
        + mesh * (0.026 + 0.036 * load)
        + bearing * (0.030 + (0.025 if external else 0.014) + 0.018 * load)
    )


def motor_sweep(
    t: np.ndarray,
    start: float,
    end: float,
    duration: float,
    seed: int,
    weight: float = 1.0,
) -> np.ndarray:
    """带有齿通过和电刷颗粒的有载电机升/降速，不是单一电子扫频。"""
    fundamental = chirp(t, start, end, duration)
    second = chirp(t, start * 2.01, end * 2.01, duration, 0.43)
    mesh = chirp(t, start * 7.2, end * 7.2, duration, 0.91)
    brush_gate = np.power(np.abs(chirp(t, start * 3.0, end * 3.0, duration, 0.2)), 9.0)
    brush = transient_noise(t, 180.0, 3100.0, -0.82, seed) * brush_gate
    return weight * (0.36 * fundamental + 0.16 * second + 0.050 * mesh + 0.070 * brush)


def chirp(t: np.ndarray, start: float, end: float, duration: float, phase: float = 0.0) -> np.ndarray:
    """指数扫频比线性扫频更自然，并避免把过多时间留在尖锐高频。"""
    ratio = max(end, 1.0) / max(start, 1.0)
    if abs(ratio - 1.0) < 1.0e-6:
        return np.sin(2.0 * np.pi * start * t + phase)
    k = math.log(ratio) / max(duration, 0.001)
    phase_curve = 2.0 * np.pi * start * (np.exp(k * t) - 1.0) / k
    return np.sin(phase_curve + phase)


def band_noise(
    duration: float,
    low: float,
    high: float,
    slope: float,
    seed: int,
) -> np.ndarray:
    """频域生成首尾天然连续的带限噪声，slope 为每倍频的幅度倾斜。"""
    sample_count = round(duration * SAMPLE_RATE)
    frequencies = np.fft.rfftfreq(sample_count, 1.0 / SAMPLE_RATE)
    amplitudes = np.zeros_like(frequencies)
    active = (frequencies >= low) & (frequencies <= high)
    reference = np.maximum(frequencies[active], max(low, 1.0)) / max(low, 1.0)
    amplitudes[active] = np.power(reference, slope)

    transition = max(12.0, min((high - low) * 0.16, 500.0))
    lower = (frequencies >= low) & (frequencies < low + transition)
    upper = (frequencies > high - transition) & (frequencies <= high)
    amplitudes[lower] *= np.sin(
        0.5 * np.pi * (frequencies[lower] - low) / transition
    ) ** 2
    amplitudes[upper] *= np.sin(
        0.5 * np.pi * (high - frequencies[upper]) / transition
    ) ** 2

    rng = np.random.default_rng(seed)
    phases = rng.uniform(0.0, 2.0 * np.pi, len(frequencies))
    spectrum = amplitudes * np.exp(1j * phases)
    spectrum[0] = 0.0
    result = np.fft.irfft(spectrum, n=sample_count)
    deviation = float(np.std(result))
    return result / deviation if deviation > 1.0e-9 else result


def transient_noise(t: np.ndarray, low: float, high: float, slope: float, seed: int) -> np.ndarray:
    return band_noise(len(t) / SAMPLE_RATE, low, high, slope, seed)[: len(t)]


def impact(t: np.ndarray, frequency: float, decay: float, drop: float = 0.28) -> np.ndarray:
    phase = 2.0 * np.pi * (
        frequency * t + frequency * drop * (1.0 - np.exp(-t * 24.0)) / 24.0
    )
    return np.sin(phase) * np.exp(-t * decay)


def servo_cluster(t: np.ndarray, center: float, variant: int, weight: float = 1.0) -> np.ndarray:
    """多个有先后关系的小型执行器，避免单个电子哔声造成廉价感。"""
    result = np.zeros_like(t)
    offsets = (-0.024, 0.0, 0.031)
    base_frequencies = (310.0, 470.0, 690.0)
    for index, (offset, base) in enumerate(zip(offsets, base_frequencies)):
        local = np.maximum(t - center - offset, 0.0)
        gate = (t >= center + offset).astype(np.float64)
        frequency = base * (1.0 + (variant - 1) * 0.021 + index * 0.008)
        result += gate * np.sin(2.0 * np.pi * frequency * local + index * 0.4) * np.exp(-local * (22.0 + index * 7.0))
    return result * weight


def master(signal: np.ndarray, target_rms: float, peak_limit: float = 0.64) -> np.ndarray:
    """以响度目标而不是统一归一化峰值，保留各层应有的动态差异。"""
    signal = np.asarray(signal, dtype=np.float64)
    signal -= float(np.mean(signal))
    rms = float(np.sqrt(np.mean(np.square(signal))))
    if rms > 1.0e-9:
        signal *= target_rms / rms
    peak = float(np.max(np.abs(signal)))
    if peak > peak_limit:
        # 保留机械冲击的峰均比，只回调增益，不把短音效压成同样厚的砖墙。
        signal *= peak_limit / peak
    return signal


def rotate_to_quiet_seam(signal: np.ndarray) -> np.ndarray:
    """在周期波形中寻找幅度和斜率都平顺的 OGG 循环切点。"""
    signal = np.asarray(signal, dtype=np.float64)
    step = np.roll(signal, -1) - signal
    scale = max(float(np.sqrt(np.mean(np.square(step)))), 1.0e-9)
    score = np.abs(np.roll(step, 1)) / scale
    score += 0.35 * np.abs(step - np.roll(step, 1)) / scale
    # 只在均匀采样候选和最佳波形候选中试验，避免生成过程过慢。
    uniform = np.linspace(0, len(signal) - 1, 80, dtype=np.int64)
    best = np.argpartition(score, min(20, len(score) - 1))[:20]
    candidates = np.unique(np.concatenate((uniform, best)))
    best_index = 0
    best_score = math.inf
    for candidate in candidates:
        rolled = np.roll(signal, -int(candidate)).astype(np.float32)
        decoded_path = OUTPUT_ROOT / ".seam_probe.ogg"
        decoded_path.parent.mkdir(parents=True, exist_ok=True)
        sf.write(decoded_path, rolled, SAMPLE_RATE, format="OGG", subtype="VORBIS")
        decoded, _ = sf.read(decoded_path, dtype="float64")
        decoded_step = np.diff(decoded)
        decoded_scale = max(float(np.sqrt(np.mean(np.square(decoded_step)))), 1.0e-9)
        seam = abs(float(decoded[0] - decoded[-1])) / decoded_scale
        slope = abs(float(
            (decoded[1] - decoded[0]) - (decoded[-1] - decoded[-2])
        )) / decoded_scale
        candidate_score = seam + 0.28 * slope
        if candidate_score < best_score:
            best_score = candidate_score
            best_index = int(candidate)
        if seam <= 0.12 and slope <= 0.25:
            break
    probe = OUTPUT_ROOT / ".seam_probe.ogg"
    if probe.exists():
        probe.unlink()
    return np.roll(signal, -best_index)


def write_sound(path: str, signal: np.ndarray, target_rms: float, loop: bool = False) -> None:
    signal = master(signal, target_rms)
    if loop:
        signal = rotate_to_quiet_seam(signal)
    else:
        edge = min(round(0.006 * SAMPLE_RATE), len(signal) // 4)
        tail = min(round(0.035 * SAMPLE_RATE), len(signal) // 4)
        signal[:edge] *= np.linspace(0.0, 1.0, edge, endpoint=False)
        signal[-tail:] *= np.linspace(1.0, 0.0, tail, endpoint=False)
    output = OUTPUT_ROOT / f"{path}.ogg"
    output.parent.mkdir(parents=True, exist_ok=True)
    encoded = signal.astype(np.float32)
    sf.write(output, encoded, SAMPLE_RATE, format="OGG", subtype="VORBIS")
    # Vorbis 可能产生轻微过冲；以实际解码结果检查并回调增益。
    for _ in range(3):
        decoded, _ = sf.read(output, dtype="float64")
        peak = float(np.max(np.abs(decoded)))
        if peak <= 0.86:
            break
        encoded *= 0.84 / peak
        sf.write(output, encoded, SAMPLE_RATE, format="OGG", subtype="VORBIS")


def core_loop(external: bool) -> np.ndarray:
    """常态核心是冷却泵和轴承，不使用启动提示里的科技扫频。"""
    duration = 8.0
    t = timeline(duration)
    pump = mechanical_rotor(t, duration, 24.0 if external else 27.0, 0.38, 110 if external else 111, external)
    coolant = band_noise(duration, 42.0, 560.0 if external else 420.0, -0.70, 112 if external else 113)
    valve = pulse_train(t, 4.5, duration, 13.0, 0.23)
    casing = periodic_sine(t, 72.0, duration, 0.35) * valve
    governor = 0.88 + 0.07 * triangle_wave(t, 0.5, duration, 0.15)
    return governor * (pump * (0.70 if external else 0.58)
                       + coolant * (0.030 if external else 0.020)
                       + casing * (0.095 if external else 0.072))


def hover_loop(external: bool) -> np.ndarray:
    """悬空主身份：有载转子、换向器、电机轴承与反重力泵阀节拍。"""
    duration = 8.0
    t = timeline(duration)
    shaft_hz = 34.0 if external else 38.0
    rotor = mechanical_rotor(t, duration, shaft_hz, 0.64, 210 if external else 211, external)
    pump_gate = pulse_train(t, 5.0, duration, 14.0, 0.11)
    pump_body = (
        periodic_sine(t, 68.0, duration, 0.18)
        + 0.42 * periodic_sine(t, 102.0, duration, 0.73)
    ) * pump_gate
    valve_texture = band_noise(duration, 95.0, 1250.0 if external else 840.0, -0.74,
                               212 if external else 213)
    valve_texture *= 0.48 + 0.52 * np.abs(pulse_train(t, 10.0, duration, 5.0, 0.37))
    suspension = 0.88 + 0.055 * triangle_wave(t, 1.0, duration, 0.28)
    return suspension * (
        rotor * (0.92 if external else 0.78)
        + pump_body * (0.090 if external else 0.072)
        + valve_texture * (0.040 if external else 0.026)
    )


def field_loop() -> np.ndarray:
    """姿态稳定层：小型执行电机、齿条修正和短促阀门动作。"""
    duration = 8.0
    t = timeline(duration)
    actuator = mechanical_rotor(t, duration, 47.0, 0.42, 310, False)
    correction = pulse_train(t, 6.0, duration, 11.0, 0.19)
    rack = (
        0.12 * triangle_wave(t, 282.0, duration, 0.33)
        + band_noise(duration, 190.0, 1900.0, -0.82, 311) * 0.050
    ) * (0.58 + 0.42 * np.abs(correction))
    hydraulic = band_noise(duration, 80.0, 620.0, -0.65, 312) * 0.026
    return actuator * 0.58 + rack + hydraulic


def air_loop(external: bool) -> np.ndarray:
    """巡航气流中保留动力总成叶频，避免只剩原版式风声。"""
    duration = 8.0
    t = timeline(duration)
    pressure = band_noise(duration, 95.0, 4100.0 if external else 2850.0, -0.76,
                          410 if external else 411)
    body = band_noise(duration, 58.0, 720.0, -0.60, 412 if external else 413)
    fan = mechanical_rotor(t, duration, 52.0, 0.48, 414 if external else 415, external)
    blade = periodic_sine(t, 416.0, duration, 0.42)
    turbulence = 0.78 + 0.13 * triangle_wave(t, 0.5, duration, 0.21)
    return turbulence * (
        pressure * (0.18 if external else 0.13)
        + body * 0.075
        + fan * (0.28 if external else 0.22)
        + blade * (0.025 if external else 0.018)
    )


def boost_loop() -> np.ndarray:
    """Ctrl 推进：高负载电机、齿轮啮合与增压气流，随控制器音高平滑升速。"""
    duration = 8.0
    t = timeline(duration)
    drive = mechanical_rotor(t, duration, 68.0, 0.94, 510, True)
    compressor = band_noise(duration, 110.0, 3900.0, -0.78, 511)
    torque = pulse_train(t, 8.0, duration, 8.0, 0.15)
    gearbox = (
        periodic_sine(t, 612.0, duration, 0.17)
        + 0.36 * periodic_sine(t, 1224.0, duration, 0.63)
    ) * (0.70 + 0.30 * np.abs(torque))
    return drive * 0.74 + compressor * 0.145 + gearbox * 0.052


def dive_loop() -> np.ndarray:
    """保留旧事件名，但声音语义是高速飞行传动负载，并非鞘翅俯冲。"""
    duration = 8.0
    t = timeline(duration)
    drive = mechanical_rotor(t, duration, 84.0, 1.0, 610, True)
    wind = band_noise(duration, 72.0, 5200.0, -0.71, 611)
    pressure = band_noise(duration, 38.0, 520.0, -0.52, 612)
    brace_gate = 0.78 + 0.16 * np.abs(pulse_train(t, 7.0, duration, 7.0, 0.29))
    chassis = (
        periodic_sine(t, 63.0, duration, 0.34)
        + 0.35 * triangle_wave(t, 126.0, duration, 0.61)
    )
    return drive * 0.64 + wind * 0.205 + pressure * 0.105 + chassis * brace_gate * 0.095


def underwater_loop(kind: str) -> np.ndarray:
    """水下仍是封闭式电机与泵工作，液体只改变传导和阻尼。"""
    duration = 8.0
    t = timeline(duration)
    shaft = {"hover": 29.0, "field": 43.0, "dive": 66.0}[kind]
    load = {"hover": 0.68, "field": 0.50, "dive": 1.0}[kind]
    seed = {"hover": 710, "field": 711, "dive": 712}[kind]
    high = {"hover": 850.0, "field": 1450.0, "dive": 2700.0}[kind]
    drive = mechanical_rotor(t, duration, shaft, load, seed, False)
    fluid = band_noise(duration, 24.0, high, -0.66, seed + 10)
    pump = pulse_train(t, 4.0 if kind == "hover" else 7.0, duration, 12.0, 0.27)
    housing = periodic_sine(t, 58.0, duration, 0.14) * pump
    return drive * (0.70 if kind == "dive" else 0.58) + fluid * (
        0.19 if kind == "dive" else 0.12
    ) + housing * 0.11


def inbound(kind: str, variant: int) -> np.ndarray:
    """分件以自带小型电机飞入；主体是升速转子和风压，不是能量扫频。"""
    settings = {
        "light": (0.58, 44.0, 92.0, 0.72),
        "medium": (0.72, 34.0, 78.0, 0.82),
        "heavy": (0.92, 22.0, 58.0, 0.96),
        "helmet": (0.76, 38.0, 82.0, 0.78),
        "faceplate": (0.60, 48.0, 98.0, 0.65),
    }
    duration, start, end, drive_weight = settings[kind]
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.026, 0.095)
    approach = smoothstep(t / duration)
    variant_scale = 1.0 + (variant - 1) * 0.021
    drive = motor_sweep(
        t, start * variant_scale, end * variant_scale, duration,
        800 + variant + len(kind) * 17, drive_weight,
    )
    whoosh = transient_noise(t, 90.0, 3100.0, -0.82, 830 + variant + len(kind) * 23)
    panel_rattle = transient_noise(t, 260.0, 1850.0, -0.68,
                                   860 + variant + len(kind) * 29)
    commutator_gate = 0.50 + 0.50 * np.power(
        np.abs(chirp(t, start * 4.0, end * 4.0, duration, 0.17)), 7.0
    )
    # 末端飞行声快速让位给独立的锁定瞬态。
    brake = 1.0 - 0.72 * smoothstep(np.maximum(t - duration * 0.82, 0.0) / (duration * 0.18))
    return envelope * brake * (
        drive
        + whoosh * (0.045 + 0.13 * approach)
        + panel_rattle * commutator_gate * 0.032
    )


def armor_lock(kind: str, variant: int) -> np.ndarray:
    """伺服收紧、装甲接触、双级金属卡扣和密封压合。"""
    settings = {
        "boot": (0.48, 82.0, 0.36),
        "limb": (0.43, 108.0, 0.29),
        "torso": (0.68, 58.0, 0.49),
    }
    duration, base, body_weight = settings[kind]
    t = timeline(duration)
    scale = 1.0 + (variant - 1) * 0.022
    shell = impact(t, base * scale, 10.5, 0.34) * body_weight
    servo = motor_sweep(t, base * 3.8 * scale, base * 1.25 * scale,
                        duration, 900 + variant + len(kind) * 13, 0.34)
    servo *= np.exp(-t * 5.8)
    clamp = servo_cluster(t, 0.070, variant, 0.16)
    second_center = 0.205 if kind == "torso" else 0.145
    second_clamp = servo_cluster(t, second_center, 3 - variant, 0.105)
    second_local = np.maximum(t - second_center, 0.0)
    second_gate = (t >= second_center).astype(np.float64)
    second_metal = impact(second_local, base * 1.22, 19.0, 0.24) * second_gate * 0.22
    contact = transient_noise(t, 85.0, 2500.0, -0.78, 940 + variant + len(kind) * 19)
    contact *= (gaussian(t, 0.025, 0.022) + gaussian(t, second_center, 0.032)) * 0.12
    return fade_envelope(t, duration, 0.002, 0.10) * (
        shell + servo + clamp + second_clamp + second_metal + contact
    )


def armor_unlock(kind: str, variant: int) -> np.ndarray:
    """释压、松锁、电机滑轨回退三段式脱离声。"""
    settings = {
        "boot": (0.46, 92.0, 0.25),
        "limb": (0.42, 126.0, 0.21),
        "torso": (0.62, 64.0, 0.36),
    }
    duration, body_frequency, body_weight = settings[kind]
    t = timeline(duration)
    scale = 1.0 + (variant - 1) * 0.019
    release = impact(t, body_frequency * scale, 12.0, 0.18) * body_weight
    latch = servo_cluster(t, 0.046, 3 - variant, 0.12)
    actuator = motor_sweep(t, 62.0 * scale, 27.0 * scale, duration,
                           1500 + variant + len(kind) * 11, 0.52)
    actuator *= np.exp(-t * 3.8)
    pressure = transient_noise(t, 70.0, 1800.0, -0.78,
                               1530 + variant + len(kind) * 17)
    pressure *= gaussian(t, 0.12, 0.070) * 0.11
    return fade_envelope(t, duration, 0.003, 0.11) * (
        release + latch + actuator + pressure
    )


def retract_part(kind: str, variant: int) -> np.ndarray:
    """分件离体返回时，小型驱动电机重新升速并带走气流。"""
    settings = {
        "light": (0.56, 46.0, 94.0, 0.66),
        "medium": (0.70, 35.0, 80.0, 0.78),
        "heavy": (0.88, 23.0, 60.0, 0.92),
    }
    duration, start, end, drive_weight = settings[kind]
    t = timeline(duration)
    scale = 1.0 + (variant - 1) * 0.018
    envelope = fade_envelope(t, duration, 0.016, 0.13)
    departure = 0.52 + 0.48 * smoothstep(t / duration)
    drive = motor_sweep(t, start * scale, end * scale, duration,
                        1600 + variant + len(kind) * 17, drive_weight)
    wake = transient_noise(t, 82.0, 2800.0, -0.80,
                           1640 + variant + len(kind) * 23)
    rail_release = servo_cluster(t, 0.035, variant, 0.075)
    return envelope * (drive + wake * (0.045 + 0.095 * departure) + rail_release)


def mechanical_drive_cue(kind: str) -> np.ndarray:
    """非启动状态切换统一采用离合器、电机负载和泄压阀语言。"""
    settings = {
        "core_lock": (0.72, 58.0, 24.0, 0.72, 0.22),
        "shutdown": (0.88, 52.0, 18.0, 0.72, 0.20),
        "disengage": (0.66, 48.0, 20.0, 0.56, 0.15),
        "boost_enter": (0.76, 32.0, 82.0, 0.88, 0.26),
        "boost_release": (0.64, 78.0, 31.0, 0.62, 0.16),
        "dive_enter": (0.78, 51.0, 88.0, 0.94, 0.30),
        "dive_recover": (0.86, 86.0, 43.0, 0.70, 0.20),
    }
    duration, start, end, drive_weight, body_weight = settings[kind]
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.010, 0.13)
    drive = motor_sweep(t, start, end, duration, 1700 + len(kind) * 19, drive_weight)
    engage = start < end
    clutch_center = 0.14 if engage else 0.10
    local = np.maximum(t - clutch_center, 0.0)
    gate = (t >= clutch_center).astype(np.float64)
    clutch = impact(local, 78.0 if engage else 62.0, 17.0, 0.26) * gate * body_weight
    latch = servo_cluster(t, clutch_center + 0.035, 1 if engage else 2, 0.095)
    vent = transient_noise(t, 82.0, 2200.0, -0.76, 1720 + len(kind) * 23)
    vent *= gaussian(t, duration * (0.72 if engage else 0.34), duration * 0.11) * 0.085
    return envelope * (drive + clutch + latch + vent)


def core_cue(kind: str) -> np.ndarray:
    """科技层只服务核心唤醒、上线和飞行系统启动；其余状态交给机械提示。"""
    if kind in {
        "core_lock", "shutdown", "disengage",
        "boost_enter", "boost_release", "dive_enter", "dive_recover",
    }:
        return mechanical_drive_cue(kind)

    settings = {
        "recall": (1.10, 58.0, 290.0, 0.22),
        "wake": (0.96, 55.0, 230.0, 0.24),
        "charge": (1.02, 68.0, 480.0, 0.22),
        "online": (0.86, 86.0, 610.0, 0.17),
        "engage": (0.72, 62.0, 300.0, 0.23),
    }
    duration, start, end, low_weight = settings[kind]
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.018, 0.16)
    primary = chirp(t, start, end, duration)
    restrained_harmonic = chirp(t, start * 1.5, end * 1.5, duration, 0.55)
    startup_body = impact(t, 55.0, 4.2, 0.22) * low_weight
    contactor_center = duration * 0.62
    local = np.maximum(t - contactor_center, 0.0)
    gate = (t >= contactor_center).astype(np.float64)
    contactor = impact(local, 92.0, 20.0, 0.18) * gate * 0.14
    texture = transient_noise(t, 110.0, 2200.0, -1.0, 1800 + len(kind) * 19)
    texture *= (0.022 + 0.030 * smoothstep(t / duration))
    return envelope * (
        primary * 0.14 + restrained_harmonic * 0.045
        + startup_body + contactor + texture
    )

def helmet_seal() -> np.ndarray:
    duration = 0.82
    t = timeline(duration)
    slide = motor_sweep(t, 46.0, 19.0, duration, 1910, 0.48)
    slide *= fade_envelope(t, duration, 0.015, 0.16)
    seal = transient_noise(t, 70.0, 1350.0, -0.76, 1911)
    seal *= gaussian(t, 0.44, 0.14) * 0.14
    latch = servo_cluster(t, 0.53, 1, 0.17)
    local = np.maximum(t - 0.54, 0.0)
    body = impact(local, 78.0, 16.0) * (t >= 0.54) * 0.22
    return fade_envelope(t, duration, 0.010, 0.10) * (slide + seal + latch + body)


def landing(hard: bool) -> np.ndarray:
    """落地是承力结构、阻尼器和电机卸载，不再播放电子式场塌缩。"""
    duration = 0.76 if hard else 0.50
    t = timeline(duration)
    body = impact(t, 46.0 if hard else 72.0, 7.5 if hard else 13.0, 0.40)
    body *= 0.50 if hard else 0.30
    motor = motor_sweep(t, 58.0 if hard else 44.0, 19.0, duration,
                        1930 if hard else 1931, 0.38 if hard else 0.26)
    motor *= np.exp(-t * 4.6)
    damper = transient_noise(t, 48.0, 1850.0 if hard else 1250.0, -0.72,
                             1940 if hard else 1941)
    damper *= np.exp(-t * (11.0 if hard else 18.0)) * (0.18 if hard else 0.09)
    latch = servo_cluster(t, 0.10 if hard else 0.07, 2, 0.08 if hard else 0.05)
    return fade_envelope(t, duration, 0.002, 0.14) * (body + motor + damper + latch)


def flyby() -> np.ndarray:
    duration = 1.55
    t = timeline(duration)
    center = gaussian(t, 0.76, 0.28)
    motor = motor_sweep(t, 92.0, 38.0, duration, 1960, 0.68)
    pressure = transient_noise(t, 65.0, 4200.0, -0.72, 1961) * 0.27
    low = chirp(t, 88.0, 54.0, duration, 0.7) * 0.13
    return fade_envelope(t, duration, 0.09, 0.15) * center * (motor + pressure + low)


def underwater_boost() -> np.ndarray:
    duration = 0.92
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.022, 0.18)
    drive = motor_sweep(t, 24.0, 68.0, duration, 1980, 0.84)
    pressure = transient_noise(t, 24.0, 1550.0, -0.68, 1981) * 0.18
    clutch_local = np.maximum(t - 0.16, 0.0)
    clutch = impact(clutch_local, 56.0, 16.0, 0.22) * (t >= 0.16) * 0.22
    valve = servo_cluster(t, 0.21, 1, 0.075)
    return envelope * (drive + pressure + clutch + valve)


def sonic_internal() -> np.ndarray:
    """Short pressure impulse conducted through the helmet at a real Mach crossing."""
    duration = 0.82
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.0015, 0.22)
    body = impact(t, 34.0, 4.6, 0.52) * 0.58
    crack = transient_noise(t, 80.0, 5200.0, -0.58, 1990)
    crack *= np.exp(-t * 19.0) * 0.30
    shell = impact(t, 118.0, 8.5, 0.20) * 0.22
    pressure_tail = chirp(t, 96.0, 38.0, duration, 0.35)
    pressure_tail *= np.exp(-t * 4.4) * 0.19
    latch_rattle = pulse_train(t, 13.0, duration, 9.0, 0.21)
    latch_rattle *= np.exp(-t * 7.5) * 0.075
    return envelope * (body + crack + shell + pressure_tail + latch_rattle)


def weapon_blade_motion(external: bool, extending: bool) -> np.ndarray:
    """Forearm plates unlock, rail the emitter outward, then hard-lock it in place."""
    duration = 0.72 if extending else 0.60
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.003, 0.11)
    if extending:
        motor = motor_sweep(t, 42.0, 128.0, duration,
                            2000 if external else 2001, 0.62)
        lock_center = duration * 0.76
    else:
        motor = motor_sweep(t, 118.0, 34.0, duration,
                            2002 if external else 2003, 0.56)
        lock_center = duration * 0.18
    rail = transient_noise(t, 260.0, 4100.0 if external else 2700.0,
                           -0.72, 2010 if external else 2011)
    rail *= (0.32 + 0.68 * smoothstep(t / duration)) * 0.075
    lock_local = np.maximum(t - lock_center, 0.0)
    lock_gate = (t >= lock_center).astype(np.float64)
    hard_lock = impact(lock_local, 126.0, 22.0, 0.22) * lock_gate * 0.30
    clamp = servo_cluster(t, lock_center + 0.025, 1 if extending else 2, 0.14)
    field = chirp(t, 170.0 if extending else 360.0,
                  420.0 if extending else 92.0, duration, 0.42)
    field *= (smoothstep(t / duration) if extending
              else 1.0 - smoothstep(t / duration)) * 0.075
    return envelope * (motor + rail + hard_lock + clamp + field)


def weapon_blade_hum(external: bool) -> np.ndarray:
    """Closed-loop emitter field with a quiet mechanical regulator underneath."""
    duration = 4.0
    t = timeline(duration)
    regulator = mechanical_rotor(
        t, duration, 31.0 if external else 35.0, 0.52,
        2020 if external else 2021, external,
    )
    field = (
        0.32 * periodic_sine(t, 72.0, duration, 0.18)
        + 0.18 * periodic_sine(t, 144.0, duration, 0.51)
        + 0.07 * periodic_sine(t, 288.0, duration, 0.83)
    )
    plasma = band_noise(duration, 180.0, 3900.0 if external else 2500.0,
                        -0.88, 2022 if external else 2023)
    breathing = 0.89 + 0.07 * periodic_sine(t, 0.75, duration, 0.26)
    return breathing * (
        regulator * (0.24 if external else 0.17)
        + field
        + plasma * (0.055 if external else 0.036)
    )


def weapon_blade_slash(external: bool) -> np.ndarray:
    """Broad pressure cut followed by the blade field recovering its phase."""
    duration = 0.76 if external else 0.54
    t = timeline(duration)
    center = 0.24 if external else 0.17
    sweep = transient_noise(t, 95.0, 5600.0 if external else 3600.0,
                            -0.62, 2030 if external else 2031)
    sweep *= gaussian(t, center, 0.115 if external else 0.085) * (
        0.36 if external else 0.25
    )
    edge = chirp(t, 620.0, 118.0, duration, 0.36)
    edge *= gaussian(t, center + 0.035, 0.16) * 0.17
    recovery = chirp(t, 84.0, 210.0, duration, 0.72)
    recovery *= np.exp(-np.maximum(t - center, 0.0) * 5.8)
    recovery *= (t >= center).astype(np.float64) * 0.11
    return fade_envelope(t, duration, 0.006, 0.13) * (sweep + edge + recovery)


def weapon_core_charge(external: bool) -> np.ndarray:
    """可无缝循环的核心蓄力场；依靠层次变厚而不是尖锐升调表达能量。"""
    duration = 4.0
    t = timeline(duration)
    breathing = 0.82 + 0.13 * periodic_sine(t, 0.5, duration, -0.4)
    field = (
        0.34 * periodic_sine(t, 54.0, duration)
        + 0.22 * periodic_sine(t, 81.0, duration, 0.45)
        + 0.11 * periodic_sine(t, 162.0, duration, 0.9)
    )
    lattice = band_noise(duration, 95.0, 1650.0 if external else 1050.0,
                         -0.92, 1810 if external else 1811)
    circulation = 0.055 * periodic_sine(t, 324.0, duration, 1.1)
    return breathing * (field + lattice * (0.105 if external else 0.075) + circulation)


def weapon_core_release(external: bool) -> np.ndarray:
    duration = 0.96
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.004, 0.20)
    pressure = impact(t, 48.0, 4.8, 0.34) * (0.52 if external else 0.40)
    field_drop = chirp(t, 430.0, 66.0, duration, 0.25) * np.exp(-t * 4.2) * 0.20
    body = transient_noise(t, 70.0, 3300.0 if external else 2300.0,
                           -0.86, 1820 if external else 1821)
    body *= np.exp(-t * 6.0) * (0.24 if external else 0.16)
    tail = chirp(t, 116.0, 58.0, duration, 0.7) * 0.13
    return envelope * (pressure + field_drop + body + tail)


def weapon_core_cancel() -> np.ndarray:
    duration = 0.54
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.008, 0.12)
    collapse = chirp(t, 260.0, 52.0, duration, 0.4) * np.exp(-t * 6.2) * 0.18
    field = impact(t, 58.0, 9.0, 0.18) * 0.19
    texture = transient_noise(t, 75.0, 1350.0, -1.0, 1830) * np.exp(-t * 11.0) * 0.08
    return envelope * (collapse + field + texture)


def weapon_core_impact() -> np.ndarray:
    duration = 0.82
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.002, 0.18)
    body = impact(t, 44.0, 6.2, 0.42) * 0.48
    compression = transient_noise(t, 55.0, 2800.0, -0.78, 1840) * np.exp(-t * 8.0) * 0.22
    ring = chirp(t, 210.0, 68.0, duration, 0.6) * np.exp(-t * 4.8) * 0.14
    return envelope * (body + compression + ring)


def weapon_gravity_impact(internal: bool) -> np.ndarray:
    duration = 0.78 if internal else 1.28
    t = timeline(duration)
    envelope = fade_envelope(t, duration, 0.002, 0.24)
    body = impact(t, 38.0 if internal else 34.0, 5.8 if internal else 4.1, 0.48)
    body *= 0.46 if internal else 0.58
    pressure = transient_noise(t, 34.0, 1700.0 if internal else 2600.0,
                               -0.76, 1850 if internal else 1851)
    pressure *= np.exp(-t * (8.0 if internal else 4.8)) * (0.16 if internal else 0.25)
    damping = chirp(t, 170.0, 42.0, duration, 0.35) * np.exp(-t * 4.3) * 0.17
    return envelope * (body + pressure + damping)


def generate_all() -> None:
    # 动力层响度排序：悬空电机 > 稳定执行器；Ctrl/高速再叠加更重的机械负载。
    write_sound("core/idle_int_loop", core_loop(False), 0.060, True)
    write_sound("core/idle_ext_loop", core_loop(True), 0.067, True)
    write_sound("flight/hover_int_loop", hover_loop(False), 0.088, True)
    write_sound("flight/hover_ext_loop", hover_loop(True), 0.098, True)
    write_sound("flight/field_loop", field_loop(), 0.066, True)
    write_sound("flight/air_int_loop", air_loop(False), 0.078, True)
    write_sound("flight/air_ext_loop", air_loop(True), 0.089, True)
    write_sound("flight/boost_loop", boost_loop(), 0.106, True)
    write_sound("flight/dive_loop", dive_loop(), 0.116, True)
    write_sound("underwater/hover_loop", underwater_loop("hover"), 0.094, True)
    write_sound("underwater/field_loop", underwater_loop("field"), 0.082, True)
    write_sound("underwater/dive_loop", underwater_loop("dive"), 0.114, True)

    # 分件飞入和机械锁定拥有细微变体，由 sounds.json 随机选择。
    target_rms = {"light": 0.095, "medium": 0.108, "heavy": 0.122}
    for kind in ("light", "medium", "heavy"):
        for variant in (1, 2):
            write_sound(f"assembly/inbound_{kind}_{variant:02d}", inbound(kind, variant), target_rms[kind])
    write_sound("assembly/inbound_helmet_01", inbound("helmet", 1), 0.108)
    write_sound("assembly/inbound_faceplate_01", inbound("faceplate", 1), 0.092)
    lock_rms = {"boot": 0.135, "limb": 0.130, "torso": 0.150}
    for kind in ("boot", "limb", "torso"):
        for variant in (1, 2):
            write_sound(f"assembly/lock_{kind}_{variant:02d}", armor_lock(kind, variant), lock_rms[kind])

    unlock_rms = {"boot": 0.115, "limb": 0.110, "torso": 0.130}
    for kind in ("boot", "limb", "torso"):
        for variant in (1, 2):
            write_sound(f"assembly/unlock_{kind}_{variant:02d}", armor_unlock(kind, variant), unlock_rms[kind])
    retract_rms = {"light": 0.088, "medium": 0.098, "heavy": 0.112}
    for kind in ("light", "medium", "heavy"):
        for variant in (1, 2):
            write_sound(f"assembly/retract_{kind}_{variant:02d}", retract_part(kind, variant), retract_rms[kind])

    write_sound("assembly/recall_start_01", core_cue("recall"), 0.145)
    write_sound("assembly/helmet_seal_01", helmet_seal(), 0.140)
    write_sound("assembly/core_charge_01", core_cue("charge"), 0.140)
    write_sound("assembly/core_lock_01", core_cue("core_lock"), 0.165)

    write_sound("core/wake_01", core_cue("wake"), 0.140)
    write_sound("core/online_01", core_cue("online"), 0.135)
    write_sound("core/shutdown_01", core_cue("shutdown"), 0.125)

    write_sound("flight/engage_01", core_cue("engage"), 0.140)
    write_sound("flight/disengage_01", core_cue("disengage"), 0.120)
    write_sound("flight/boost_enter_01", core_cue("boost_enter"), 0.145)
    write_sound("flight/boost_release_01", core_cue("boost_release"), 0.120)
    write_sound("flight/dive_enter_01", core_cue("dive_enter"), 0.150)
    write_sound("flight/dive_recover_01", core_cue("dive_recover"), 0.145)
    write_sound("flight/land_soft_01", landing(False), 0.145)
    write_sound("flight/land_hard_01", landing(True), 0.185)
    write_sound("flight/flyby_01", flyby(), 0.130)
    write_sound("flight/sonic_internal_01", sonic_internal(), 0.205)
    write_sound("underwater/boost_enter_01", underwater_boost(), 0.150)

    # 战甲武器：核心蓄力保持低频磁场身份，俯冲冲击强调压力波而非爆炸声。
    write_sound("weapon/core_charge_int_loop", weapon_core_charge(False), 0.092, True)
    write_sound("weapon/core_charge_ext_loop", weapon_core_charge(True), 0.108, True)
    write_sound("weapon/core_release_int", weapon_core_release(False), 0.155)
    write_sound("weapon/core_release_ext", weapon_core_release(True), 0.178)
    write_sound("weapon/core_cancel", weapon_core_cancel(), 0.105)
    write_sound("weapon/core_impact", weapon_core_impact(), 0.175)
    write_sound("weapon/gravity_impact_int", weapon_gravity_impact(True), 0.162)
    write_sound("weapon/gravity_impact_ext", weapon_gravity_impact(False), 0.190)
    write_sound("weapon/blade_deploy_int", weapon_blade_motion(False, True), 0.132)
    write_sound("weapon/blade_deploy_ext", weapon_blade_motion(True, True), 0.154)
    write_sound("weapon/blade_retract_int", weapon_blade_motion(False, False), 0.118)
    write_sound("weapon/blade_retract_ext", weapon_blade_motion(True, False), 0.138)
    write_sound("weapon/blade_hum_int_loop", weapon_blade_hum(False), 0.072, True)
    write_sound("weapon/blade_hum_ext_loop", weapon_blade_hum(True), 0.088, True)
    write_sound("weapon/blade_slash_int", weapon_blade_slash(False), 0.142)
    write_sound("weapon/blade_slash_ext", weapon_blade_slash(True), 0.170)


def spectral_centroid(signal: np.ndarray) -> float:
    window = np.hanning(len(signal))
    spectrum = np.abs(np.fft.rfft(signal * window))
    frequencies = np.fft.rfftfreq(len(signal), 1.0 / SAMPLE_RATE)
    total = float(np.sum(spectrum))
    return float(np.sum(spectrum * frequencies) / total) if total > 1.0e-9 else 0.0


def validate_assets() -> None:
    failures: list[str] = []
    for file in sorted(OUTPUT_ROOT.rglob("*.ogg")):
        signal, rate = sf.read(file, dtype="float64")
        relative = file.relative_to(OUTPUT_ROOT).as_posix()
        if rate != SAMPLE_RATE or signal.ndim != 1:
            failures.append(f"{relative}: 不是 48 kHz 单声道")
            continue
        if not np.isfinite(signal).all():
            failures.append(f"{relative}: 存在非有限采样")
            continue
        peak = float(np.max(np.abs(signal)))
        rms = float(np.sqrt(np.mean(np.square(signal))))
        centroid = spectral_centroid(signal)
        if peak > 10.0 ** (-1.0 / 20.0):
            failures.append(f"{relative}: 峰值 {20.0 * math.log10(peak):.2f} dBFS 过高")
        if rms > 0.205:
            failures.append(f"{relative}: RMS {rms:.3f} 过高")
        if file.stem.endswith("loop"):
            step = np.diff(signal)
            scale = max(float(np.sqrt(np.mean(np.square(step)))), 1.0e-9)
            seam = abs(float(signal[0] - signal[-1])) / scale
            if seam > 1.5:
                failures.append(f"{relative}: 循环接缝 {seam:.2f} 过大")
        print(f"{relative:42s} rms={rms:.3f} peak={peak:.3f} centroid={centroid:6.0f} Hz")
    if failures:
        raise RuntimeError("音效资源验收失败：\n" + "\n".join(failures))


if __name__ == "__main__":
    generate_all()
    validate_assets()
    print(f"已生成 {len(list(OUTPUT_ROOT.rglob('*.ogg')))} 个第三代机械化原创 OGG 音效：{OUTPUT_ROOT}")
