#!/usr/bin/env python3
"""Reproducible, original procedural assets. Python standard library only; not needed to build/run."""
import json
import math
import random
from pathlib import Path
import struct
import wave

RUNTIME = Path(__file__).resolve().parents[1] / "runtime"


def make_glb():
    # A 0.7 m cube, Y-up, face normals and counter-clockwise triangles.
    faces = [
        ((0, 0, 1), [(-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]),
        ((0, 0, -1), [(1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)]),
        ((1, 0, 0), [(1, -1, 1), (1, -1, -1), (1, 1, -1), (1, 1, 1)]),
        ((-1, 0, 0), [(-1, -1, -1), (-1, -1, 1), (-1, 1, 1), (-1, 1, -1)]),
        ((0, 1, 0), [(-1, 1, 1), (1, 1, 1), (1, 1, -1), (-1, 1, -1)]),
        ((0, -1, 0), [(-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1)]),
    ]
    positions, normals, indices = [], [], []
    for normal, corners in faces:
        base = len(positions) // 3
        for corner in corners:
            positions.extend(value * .35 for value in corner)
            normals.extend(normal)
        indices.extend(base + value for value in [0, 1, 2, 0, 2, 3])
    binary = bytearray()
    views, accessors = [], []

    def accessor(values, fmt, component_type, shape, components, **bounds):
        while len(binary) % 4:
            binary.append(0)
        payload = struct.pack("<" + fmt * len(values), *values)
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(payload)})
        binary.extend(payload)
        accessors.append({"bufferView": len(views) - 1, "componentType": component_type,
                          "count": len(values) // components, "type": shape, **bounds})
        return len(accessors) - 1

    pos = accessor(positions, "f", 5126, "VEC3", 3, min=[-.35] * 3, max=[.35] * 3)
    norm = accessor(normals, "f", 5126, "VEC3", 3)
    idx = accessor(indices, "H", 5123, "SCALAR", 1)
    times = accessor([0, .5, 1, 1.5, 2], "f", 5126, "SCALAR", 1, min=[0], max=[2])
    translations = accessor([v for y in [.55, 1.05, .55, 1.05, .55] for v in [0, y, 0]],
                            "f", 5126, "VEC3", 3)
    rotations = accessor([v for angle in [0, math.pi / 2, math.pi, 3 * math.pi / 2, 2 * math.pi]
                          for v in [0, math.sin(angle / 2), 0, math.cos(angle / 2)]],
                         "f", 5126, "VEC4", 4)
    document = {
        "asset": {"version": "2.0", "generator": "Atomic Plumberman M0 generate_probes.py"},
        "scene": 0, "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "ProbeCube", "mesh": 0, "translation": [0, .55, 0]}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": pos, "NORMAL": norm},
                                     "indices": idx, "material": 0}]}],
        "materials": [{"name": "ProbeTeal", "pbrMetallicRoughness": {
            "baseColorFactor": [.08, .9, .65, 1], "metallicFactor": 0, "roughnessFactor": .55}}],
        "animations": [{"name": "ProbeBounce", "samplers": [
            {"input": times, "output": translations, "interpolation": "LINEAR"},
            {"input": times, "output": rotations, "interpolation": "LINEAR"}],
            "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}},
                         {"sampler": 1, "target": {"node": 0, "path": "rotation"}}]}],
        "buffers": [{"byteLength": len(binary)}], "bufferViews": views, "accessors": accessors,
    }
    metadata = json.dumps(document, separators=(",", ":")).encode("utf-8")
    metadata += b" " * (-len(metadata) % 4)
    binary += b"\0" * (-len(binary) % 4)
    payload = (struct.pack("<III", 0x46546C67, 2, 28 + len(metadata) + len(binary))
               + struct.pack("<II", len(metadata), 0x4E4F534A) + metadata
               + struct.pack("<II", len(binary), 0x004E4942) + binary)
    target = RUNTIME / "Models/Diagnostics/animated-probe.glb"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(payload)


RATE = 22050


def write_wav(path, samples):
    target = RUNTIME / path
    target.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(target), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(struct.pack("<" + "h" * len(samples), *(max(-32767, min(32767, int(v))) for v in samples)))


def synth(duration, sample):
    """sample(t, progress) -> -1..1, with a 5 ms attack to avoid clicks."""
    count = int(RATE * duration)
    return [12000 * min(1, i / (RATE * .005)) * sample(i / RATE, i / count) for i in range(count)]


def make_wav():
    duration = .24
    write_wav("Audio/Diagnostics/probe.wav", [
        10000 * min(1, t / .01) * max(0, 1 - t / duration) * math.sin(2 * math.pi * 660 * t)
        for t in (i / RATE for i in range(int(RATE * duration)))])


def make_sfx():
    noise = random.Random(1997)
    phase = [0.0]

    def sweep(start, end):
        def sample(t, p):
            phase[0] += 2 * math.pi * (start + (end - start) * p) / RATE
            return math.sin(phase[0])
        return sample

    place = sweep(180, 90)
    write_wav("Audio/Sfx/place.wav", synth(.09, lambda t, p: (1 - p) ** 2 * place(t, p)))
    low = [0.0]

    def boom(t, p):
        low[0] += .08 * (noise.uniform(-1, 1) - low[0])  # one-pole low-pass rumble
        return (1 - p) ** 2.5 * (4 * low[0] + .35 * noise.uniform(-1, 1) * (1 - p) ** 6)
    write_wav("Audio/Sfx/explosion.wav", synth(.7, boom))
    phase[0] = 0
    chirp = sweep(520, 1560)
    write_wav("Audio/Sfx/pickup.wav", synth(.16, lambda t, p: (1 - p) * chirp(t, p)))
    phase[0] = 0
    fall = sweep(440, 70)
    write_wav("Audio/Sfx/death.wav", synth(.55, lambda t, p: (1 - p) * (fall(t, p) > 0) * .8))
    write_wav("Audio/Sfx/go.wav", synth(.3, lambda t, p: (1 - p) * math.sin(2 * math.pi * 990 * t)))
    notes = [523.25, 659.25, 783.99, 1046.5]
    write_wav("Audio/Sfx/fanfare.wav", synth(.8, lambda t, p: (1 - (p * 4) % 1) * .8
                                              * math.sin(2 * math.pi * notes[min(3, int(p * 4))] * t)))


def make_reactions():
    """Short effects for M4/M5 events and the UI; each is a few lines of synthesis."""
    noise = random.Random(2026)

    def tone(freq_start, freq_end, duration, shape=lambda p: 1 - p, wave_fn=math.sin):
        phase = [0.0]

        def sample(t, p):
            phase[0] += 2 * math.pi * (freq_start + (freq_end - freq_start) * p) / RATE
            return shape(p) * wave_fn(phase[0])
        return synth(duration, sample)

    def whoosh(duration, brightness):
        low = [0.0]

        def sample(t, p):
            low[0] += brightness * (noise.uniform(-1, 1) - low[0])
            return 3 * low[0] * math.sin(math.pi * p)
        return synth(duration, sample)

    square = lambda phase: 1 if math.sin(phase) >= 0 else -1
    write_wav("Audio/Sfx/kick.wav", tone(240, 120, .08, lambda p: (1 - p) ** 3))
    write_wav("Audio/Sfx/punch.wav", whoosh(.18, .35))
    write_wav("Audio/Sfx/throw.wav", whoosh(.28, .15))
    write_wav("Audio/Sfx/land.wav", tone(110, 60, .12, lambda p: (1 - p) ** 2))
    write_wav("Audio/Sfx/stun.wav", tone(300, 900, .35, lambda p: (1 - p) * (.6 + .4 * math.sin(40 * p))))
    write_wav("Audio/Sfx/infect.wav", tone(330, 220, .45, lambda p: (1 - p) * (.5 + .5 * math.sin(60 * p)), square))
    write_wav("Audio/Sfx/warp.wav", tone(1400, 300, .25, lambda p: (1 - p) ** 1.5, square))
    write_wav("Audio/Sfx/warning.wav", synth(.5, lambda t, p: (.8 if (p * 4) % 1 < .5 else 0) * math.sin(2 * math.pi * 880 * t)))
    low = [0.0]

    def chain(t, p):
        low[0] += .05 * (noise.uniform(-1, 1) - low[0])
        return (1 - p) ** 1.8 * 5 * low[0]
    write_wav("Audio/Sfx/chain.wav", synth(1.1, chain))
    write_wav("Audio/Sfx/ui-move.wav", tone(1200, 1200, .03, lambda p: 1 - p))
    write_wav("Audio/Sfx/ui-confirm.wav", tone(700, 1400, .09, lambda p: 1 - p))


def make_music():
    """One eight-bar loop per theme: bass, arpeggio and a soft pulse; seamless because it ends on the bar."""
    themes = {
        "workshop": (132, [0, 4, 7, 12], [48, 48, 53, 55]),  # bouncy major, C F G
        "garden": (108, [0, 2, 4, 7, 9], [50, 50, 55, 57]),  # calm pentatonic, D G A
        "reactor": (148, [0, 3, 7, 10], [45, 45, 41, 43]),  # driving minor, A F G
    }
    for name, (bpm, scale, roots) in themes.items():
        beat = 60 / bpm
        samples = []
        total = int(RATE * beat * 32)
        for i in range(total):
            t = i / RATE
            step = int(t / (beat / 2))
            bar = int(t / (beat * 4)) % len(roots)
            root = roots[bar]
            within = (t % (beat / 2)) / (beat / 2)
            arp = 440 * 2 ** ((root + 12 + scale[step % len(scale)] - 69) / 12)
            bass = 440 * 2 ** ((root - 12 - 69) / 12)
            value = .35 * (1 - within) ** 2 * math.sin(2 * math.pi * arp * t)
            value += .45 * (1 if math.sin(2 * math.pi * bass * t) > 0 else -1) * .5 * (1 - (t % beat) / beat)
            value += .12 * math.sin(2 * math.pi * bass * 2 * t)
            samples.append(5200 * value)
        write_wav(f"Audio/Music/{name}.wav", samples)


if __name__ == "__main__":
    make_glb()
    make_wav()
    make_sfx()
    make_reactions()
    make_music()
