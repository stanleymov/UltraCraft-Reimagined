"""Rig access shared by the exporters: find a prefab's Animator GameObject, its bone tree, its skinned
meshes and its animator controller's clips. Everything is resolved through UnityPy attribute access
(PPtr.read()), which follows cross-bundle references when the dependency bundles are loaded (ukenv)."""
from __future__ import annotations

import zlib
from dataclasses import dataclass, field

from ukenv import env_for_key


@dataclass
class Bone:
    name: str
    path: str
    parent: int
    position: tuple
    rotation: tuple  # x y z w
    scale: tuple
    path_id: int


@dataclass
class Rig:
    key: str
    root_name: str
    bones: list = field(default_factory=list)
    skinned: list = field(default_factory=list)  # (go_name, SkinnedMeshRenderer object)
    static: list = field(default_factory=list)  # (go_name, path, bone index, MeshFilter, MeshRenderer) rigid meshes under the rig
    animator: object = None
    controller: object = None
    avatar: object = None
    env: object = None
    mount: list = field(default_factory=list)  # prefab root .. animator parent, outermost first

    def bone_index_by_path_id(self):
        return {b.path_id: i for i, b in enumerate(self.bones)}

    def path_hashes(self):
        """Unity animation binding path hash (CRC32) -> bone path.

        Clips bind by path relative to the Animator that plays them, but when the Animator uses a model's
        Avatar the paths are the model's (relative to the GameObject named like the avatar root, e.g.
        "Armature/Upper Arm" under "Arm Blue/Feedbacker"). So every suffix of a bone's path is a candidate;
        full paths win on collisions.
        """
        out = {}
        for depth in range(0, 8):
            for b in self.bones:
                parts = b.path.split("/") if b.path else []
                if depth >= len(parts):
                    continue
                cand = "/".join(parts[depth:])
                h = zlib.crc32(cand.encode("utf-8")) & 0xFFFFFFFF
                out.setdefault(h, b.path)
        return out



def ui_local_position(tr):
    """A Transform's local position. A RectTransform under a plain Transform (a world-space canvas on a gun: the
    shotgun's Canvas (2), the revolver's Canvas) is placed by m_AnchoredPosition in x/y: its serialized m_LocalPosition
    x/y are stale (Unity rebuilds them from the anchors at runtime); only z is kept from m_LocalPosition."""
    p = tr.m_LocalPosition
    try:
        if tr.object_reader.type.name == "RectTransform":
            father = tr.m_Father.read() if tr.m_Father.path_id else None
            if father is None or father.object_reader.type.name != "RectTransform":
                a = tr.m_AnchoredPosition
                return (a.x, a.y, p.z)
    except Exception:
        pass
    return (p.x, p.y, p.z)

def _root_gameobject(env, entries):
    iid = entries[0].internal_id
    for p, o in env.container.items():
        if (p == iid or p.endswith(iid)) and o.type.name == "GameObject":
            return o.read()
    raise KeyError("root GameObject not found")


def _find_animator(go, depth=0):
    for cp in go.m_Components:
        c = cp.read()
        if c.object_reader.type.name == "Animator":
            return go, c
    for ch in go.m_Transform.read().m_Children:
        r = _find_animator(ch.read().m_GameObject.read(), depth + 1)
        if r:
            return r
    return None


def load_rig(key: str, animator_name: str | None = None) -> Rig:
    env, entries = env_for_key(key)
    root = _root_gameobject(env, entries)
    found = None
    if animator_name:
        # pick the Animator on the GameObject with this name
        def search(go):
            nonlocal found
            if found:
                return
            if go.m_Name == animator_name:
                for cp in go.m_Components:
                    c = cp.read()
                    if c.object_reader.type.name == "Animator":
                        found = (go, c)
                        return
            if go.m_Transform is None or not go.m_Transform.path_id:
                return
            for ch in go.m_Transform.read().m_Children:
                search(ch.read().m_GameObject.read())
        search(root)
    if not found:
        found = _find_animator(root)
    if not found:
        raise KeyError("no Animator in " + key)
    ago, animator = found
    rig = Rig(key=key, root_name=ago.m_Name, animator=animator, env=env)
    # mount chain: transforms from the prefab root down to (excluding) the animator GameObject
    chain = []
    tr = ago.m_Transform.read()
    try:
        parent = tr.m_Father.read() if tr.m_Father.path_id else None
    except Exception:
        parent = None
    while parent is not None:
        pgo = parent.m_GameObject.read()
        # the camera-space weapon holders (Player prefab: Main Camera/Punch and /Guns) are placed by the mod itself
        if pgo.m_Name in ("Punch", "Guns"):
            break
        p, r, s = parent.m_LocalPosition, parent.m_LocalRotation, parent.m_LocalScale
        chain.append({"name": pgo.m_Name, "pos": [p.x, p.y, p.z], "rot": [r.x, r.y, r.z, r.w], "scale": [s.x, s.y, s.z]})
        try:
            parent = parent.m_Father.read() if parent.m_Father.path_id else None
        except Exception:
            parent = None
    rig.mount = list(reversed(chain))
    try:
        rig.controller = animator.m_Controller.read()
    except Exception:
        rig.controller = None
    try:
        rig.avatar = animator.m_Avatar.read()
    except Exception:
        rig.avatar = None

    # bone tree: every Transform under the animator root (paths relative to it, root itself = "")
    def walk(tr, parent, prefix):
        go = tr.m_GameObject.read()
        path = go.m_Name if prefix is None else (prefix + "/" + go.m_Name if prefix else go.m_Name)
        if parent == -1:
            path = ""
        p, r, s = tr.m_LocalPosition, tr.m_LocalRotation, tr.m_LocalScale
        pos = ui_local_position(tr)
        rig.bones.append(Bone(go.m_Name, path, parent, pos, (r.x, r.y, r.z, r.w), (s.x, s.y, s.z), tr.object_reader.path_id))
        idx = len(rig.bones) - 1
        mf = mr = None
        effect = False
        for cp in go.m_Components:
            try:
                c = cp.read()
            except Exception:
                continue
            tn = c.object_reader.type.name
            if tn == "SkinnedMeshRenderer":
                rig.skinned.append((go.m_Name, path, c))
            elif tn == "MeshFilter":
                mf = c
            elif tn == "MeshRenderer":
                mr = c
            elif tn in ("ParticleSystemRenderer", "ParticleSystem", "Canvas", "CanvasRenderer"):
                effect = True
        # static meshes inside the rig (the nailgun's spinning barrels, the revolver's charge monitor, ...): rigid to their
        # node; particle / UI objects and inactive ones are effects the game turns on itself
        if mf is not None and mr is not None and not effect and go.m_IsActive and getattr(mr, "m_Enabled", 1):
            rig.static.append((go.m_Name, path, idx, mf, mr))
        for ch in tr.m_Children:
            walk(ch.read(), idx, path if parent != -1 else "")
    walk(ago.m_Transform.read(), -1, None)
    return rig


def controller_clips(controller):
    """(name, AnimationClip object) for every clip the controller references."""
    out = []
    if controller is None:
        return out
    for pp in controller.m_AnimationClips:
        try:
            c = pp.read()
            out.append((c.m_Name, c))
        except Exception as e:  # noqa: BLE001
            print("clip unreadable:", e)
    return out
