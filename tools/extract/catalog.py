"""Read ULTRAKILL's Addressables catalog (StreamingAssets/aa/catalog.json).

Maps every addressable key (e.g. "Assets/Prefabs/Player/Player.prefab") to the
bundle file that holds it and the internal id used inside that bundle.

Format notes (Unity Addressables 1.2x, ContentCatalogData):
  m_KeyDataString   : int32 count, then entries: byte kind + payload
                      kind 0 ascii str, 1 utf16 str, 4 int32, 7 Hash128 (16 bytes)
  m_BucketDataString: int32 count, then per bucket: int32 keyOffset (into key data),
                      int32 n, int32 entry[n]
  m_EntryDataString : int32 count, then per entry 7 x int32:
                      internalId, provider, dependencyKey (bucket index or -1),
                      depHash, dataIndex (into extra data, -1 none), primaryKey (bucket index),
                      resourceType
"""
from __future__ import annotations

import base64
import json
import os
import struct
import sys
from dataclasses import dataclass, field

# the setup points every exporter at the player's own install through UK_GAME
DEFAULT_GAME = os.environ.get("UK_GAME") or r"C:\Program Files (x86)\Steam\steamapps\common\ULTRAKILL"


@dataclass
class Entry:
    internal_id: str
    provider: str
    primary_key: object
    dependency_key: object  # bundle key (string or hash) or None
    resource_type: str
    keys: list = field(default_factory=list)


@dataclass
class Catalog:
    keys: list
    entries: list
    bundles: dict  # bundle key -> Entry (AssetBundleProvider entries)
    buckets: dict  # key -> [Entry]; an asset's dependency key names a bucket of bundle entries

    def find(self, substring: str):
        s = substring.lower()
        return [e for e in self.entries if any(isinstance(k, str) and s in k.lower() for k in e.keys)]

    def bundle_entries(self, entry: Entry) -> list:
        """The bundle entries an asset depends on; the first one holds the asset itself."""
        if entry.provider == "AssetBundleProvider":
            return [entry]
        return [e for e in self.buckets.get(entry.dependency_key, []) if e.provider == "AssetBundleProvider"]

    def bundle_path(self, entry: Entry, game: str = DEFAULT_GAME) -> str | None:
        deps = self.bundle_entries(entry)
        if not deps:
            return None
        iid = deps[0].internal_id.replace("{UnityEngine.AddressableAssets.Addressables.RuntimePath}",
                                          os.path.join(game, "ULTRAKILL_Data", "StreamingAssets", "aa"))
        return os.path.normpath(iid)


def _read_keys(blob: bytes):
    n = struct.unpack_from("<i", blob, 0)[0]
    off = 4
    keys = []
    offsets = {}
    for _ in range(n):
        offsets[off] = len(keys)
        kind = blob[off]
        off += 1
        if kind == 0:
            ln = struct.unpack_from("<i", blob, off)[0]; off += 4
            keys.append(blob[off:off + ln].decode("ascii", "replace")); off += ln
        elif kind == 1:
            ln = struct.unpack_from("<i", blob, off)[0]; off += 4
            keys.append(blob[off:off + ln].decode("utf-16-le", "replace")); off += ln
        elif kind == 4:
            keys.append(struct.unpack_from("<i", blob, off)[0]); off += 4
        elif kind == 7:
            keys.append("hash:" + blob[off:off + 16].hex()); off += 16
        else:
            raise ValueError(f"unknown key kind {kind} at {off}")
    return keys, offsets


def load(game: str = DEFAULT_GAME) -> Catalog:
    path = os.path.join(game, "ULTRAKILL_Data", "StreamingAssets", "aa", "catalog.json")
    with open(path, encoding="utf-8") as f:
        c = json.load(f)
    keys, key_offsets = _read_keys(base64.b64decode(c["m_KeyDataString"]))
    internal_ids = c["m_InternalIds"]
    providers = c["m_ProviderIds"]
    rtypes = c["m_resourceTypes"]

    bd = base64.b64decode(c["m_BucketDataString"])
    nb = struct.unpack_from("<i", bd, 0)[0]
    off = 4
    buckets = []  # (key index, [entry indices])
    for _ in range(nb):
        koff, cnt = struct.unpack_from("<ii", bd, off); off += 8
        ents = list(struct.unpack_from(f"<{cnt}i", bd, off)); off += 4 * cnt
        buckets.append((key_offsets[koff], ents))

    ed = base64.b64decode(c["m_EntryDataString"])
    ne = struct.unpack_from("<i", ed, 0)[0]
    entries = []
    for i in range(ne):
        iid, prov, depkey, dephash, dataidx, pkey, rtype = struct.unpack_from("<7i", ed, 4 + 28 * i)
        rt = rtypes[rtype]
        rtname = rt["m_ClassName"] if isinstance(rt, dict) else str(rt)
        entries.append(Entry(
            internal_id=internal_ids[iid],
            provider=providers[prov].rsplit(".", 1)[-1],
            primary_key=keys[buckets[pkey][0]],
            dependency_key=keys[buckets[depkey][0]] if depkey >= 0 else None,
            resource_type=rtname,
        ))
    bucket_map = {}
    for kidx, ents in buckets:
        for e in ents:
            entries[e].keys.append(keys[kidx])
        bucket_map[keys[kidx]] = [entries[e] for e in ents]
    bundles = {e.primary_key: e for e in entries if e.provider == "AssetBundleProvider"}
    return Catalog(keys, entries, bundles, bucket_map)


if __name__ == "__main__":
    cat = load()
    print(f"keys {len(cat.keys)} entries {len(cat.entries)} bundles {len(cat.bundles)}")
    if len(sys.argv) > 1:
        for e in cat.find(sys.argv[1]):
            print(f"{e.primary_key}\n    type={e.resource_type} id={e.internal_id}\n    bundle={cat.bundle_path(e)}")
    else:
        # overview: count asset entries per bundle
        from collections import Counter
        per = Counter(os.path.basename(cat.bundle_path(e) or "?") for e in cat.entries if e.provider == "BundledAssetProvider")
        for b, n in sorted(per.items(), key=lambda x: -x[1]):
            print(f"{n:6d}  {b}")
