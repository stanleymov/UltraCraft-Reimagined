"""Export the animation clips of a prefab's animator controller to JSON, decoding Unity's muscle
clips (StreamedClip / DenseClip / ConstantClip) ourselves so the setup needs nothing but UnityPy.

usage: python export_clips.py "<prefab key>" out_dir [animator GameObject name]

Per clip: { "name", "sampleRate", "length", "loop", "events": [{"time","name","float","int","string"}],
            "tracks": { "<bone path>": {"pos": [[x,y,z]...], "rot": [[x,y,z,w]...], "scale": [[x,y,z]...]} } }
sampled at the clip's sample rate from start to stop time (Unity units, left-handed).

Decoder notes (the same layout AssetStudio/AssetRipper read):
  StreamedClip.data (uint32s) = frames: float time, int32 keyCount, keys {int32 curveIndex, float coeff[4]}
      value(t) = ((c0*dx + c1)*dx + c2)*dx + c3 with dx = t - keyTime, valid until the next key of that curve.
  DenseClip: frameCount, curveCount, sampleRate, beginTime, sampleArray[frame*curveCount + curve].
  ConstantClip: data[curve].
  Curve index space: [streamed curves][dense curves][constant curves].
  m_ClipBindingConstant.genericBindings in curve order; Transform (typeID 4) attribute 1 = position (3),
  2 = rotation quaternion (4), 3 = scale (3), 4 = euler (3). path = CRC32 of the bone path.
"""
from __future__ import annotations

import json
import math
import os
import struct
import sys

from rigs import controller_clips, load_rig


class Streamed:
    def __init__(self, words, curve_count):
        self.curve_count = curve_count
        buf = struct.pack("<%dI" % len(words), *(int(w) & 0xFFFFFFFF for w in words))
        self.frames = []  # (time, {curve: coeff})
        off = 0
        n = len(buf)
        while off + 8 <= n:
            t, nk = struct.unpack_from("<fi", buf, off)
            off += 8
            keys = {}
            for _ in range(nk):
                if off + 20 > n:
                    break
                idx, c0, c1, c2, c3 = struct.unpack_from("<iffff", buf, off)
                off += 20
                keys[idx] = (t, c0, c1, c2, c3)
            self.frames.append((t, keys))
        # per curve key list
        self.curves = {}
        for t, keys in self.frames:
            for idx, k in keys.items():
                self.curves.setdefault(idx, []).append(k)

    def value(self, curve, t):
        keys = self.curves.get(curve)
        if not keys:
            return None
        # last key with time <= t
        lo, hi = 0, len(keys) - 1
        if t <= keys[0][0]:
            k = keys[0]
            return k[4]
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if keys[mid][0] <= t:
                lo = mid
            else:
                hi = mid - 1
        k = keys[lo]
        dx = t - k[0]
        if lo == len(keys) - 1:
            dx = 0.0  # hold the last value
        return ((k[1] * dx + k[2]) * dx + k[3]) * dx + k[4]


class Dense:
    def __init__(self, d):
        self.frame_count = int(d.get("m_FrameCount", 0))
        self.curve_count = int(d.get("m_CurveCount", 0))
        self.sample_rate = float(d.get("m_SampleRate", 0) or 0)
        self.begin = float(d.get("m_BeginTime", 0) or 0)
        self.samples = list(d.get("m_SampleArray", []) or [])

    def value(self, curve, t):
        if self.frame_count == 0 or self.sample_rate == 0:
            return None
        f = (t - self.begin) * self.sample_rate
        f0 = max(0, min(self.frame_count - 1, int(math.floor(f))))
        f1 = min(self.frame_count - 1, f0 + 1)
        a = self.samples[f0 * self.curve_count + curve]
        b = self.samples[f1 * self.curve_count + curve]
        u = max(0.0, min(1.0, f - f0))
        return a + (b - a) * u


def decode(clip, hashes, name_fallback):
    tt = clip.object_reader.read_typetree()
    mc = tt["m_MuscleClip"]
    c = mc["m_Clip"]
    if "data" in c:
        c = c["data"]
    sc = c.get("m_StreamedClip", {})
    streamed = Streamed(sc.get("data", []), int(sc.get("curveCount", 0)))
    dense = Dense(c.get("m_DenseClip", {}))
    const = list(c.get("m_ConstantClip", {}).get("data", []) or [])
    n_s, n_d = streamed.curve_count, dense.curve_count

    def curve_value(i, t):
        if i < n_s:
            return streamed.value(i, t)
        if i < n_s + n_d:
            return dense.value(i - n_s, t)
        j = i - n_s - n_d
        return const[j] if j < len(const) else None

    bindings = tt["m_ClipBindingConstant"]["genericBindings"]
    rate = float(tt.get("m_SampleRate", 60) or 60)
    start, stop = float(mc.get("m_StartTime", 0)), float(mc.get("m_StopTime", 0))
    frames = max(1, int(round((stop - start) * rate)) + 1)
    times = [start + i / rate for i in range(frames)]
    tracks = {}
    unknown = set()
    ci = 0
    for b in bindings:
        attr, type_id = int(b["attribute"]), int(b["typeID"])
        if type_id == 4 and attr in (1, 2, 3, 4):
            count = 4 if attr == 2 else 3
            path = hashes.get(int(b["path"]) & 0xFFFFFFFF)
            if path is None:
                unknown.add(int(b["path"]) & 0xFFFFFFFF)
                ci += count
                continue
            vals = []
            for t in times:
                v = [curve_value(ci + k, t) for k in range(count)]
                vals.append([0.0 if x is None else float(x) for x in v])
            tr = tracks.setdefault(path, {})
            if attr == 1:
                tr["pos"] = vals
            elif attr == 2:
                tr["rot"] = vals
            elif attr == 3:
                tr["scale"] = vals
            else:
                tr["euler"] = vals
            ci += count
        else:
            # float / pptr / blendshape curves: one curve each, skipped
            ci += 1
    events = []
    for e in tt.get("m_Events", []) or []:
        events.append({"time": float(e.get("time", 0)), "name": e.get("functionName", ""), "float": float(e.get("floatParameter", 0) or 0),
                       "int": int(e.get("intParameter", 0) or 0), "string": e.get("data", "")})
    out = {"name": tt.get("m_Name", name_fallback), "sampleRate": rate, "length": stop - start, "loop": bool(mc.get("m_LoopTime", False)),
           "events": events, "tracks": tracks}
    return out, unknown, (n_s, n_d, len(const), len(bindings))


def export(key, out_dir, animator_name=None):
    rig = load_rig(key, animator_name)
    hashes = rig.path_hashes()
    clips = controller_clips(rig.controller)
    print(f"{rig.root_name}: {len(rig.bones)} bones, controller {'none' if rig.controller is None else rig.controller.m_Name}, {len(clips)} clips")
    os.makedirs(out_dir, exist_ok=True)
    seen = set()
    for name, clip in clips:
        if name in seen:
            continue
        seen.add(name)
        data, unknown, stats = decode(clip, hashes, name)
        fn = os.path.join(out_dir, name.replace("/", "_").replace(" ", "_") + ".json")
        with open(fn, "w", encoding="utf-8") as f:
            json.dump(data, f, separators=(",", ":"))
        print(f"  {name}: {data['length']:.3f}s {len(data['tracks'])} tracks, events {[e['name'] for e in data['events']]}, curves s/d/c={stats[0]}/{stats[1]}/{stats[2]} bindings={stats[3]}" + (f" unknown paths {len(unknown)}" if unknown else ""))


if __name__ == "__main__":
    export(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
