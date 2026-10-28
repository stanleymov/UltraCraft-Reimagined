"""Shared UnityPy environment for ULTRAKILL's addressable bundles.

Loads the bundles an asset needs (resolved through the catalog), plus the monoscript
bundle so MonoBehaviour class names resolve, with typetrees generated from the game's
own Managed/*.dll so every MonoBehaviour field can be read.
"""
from __future__ import annotations

import os
from functools import lru_cache

import UnityPy
from UnityPy.helpers.TypeTreeGenerator import TypeTreeGenerator

from catalog import DEFAULT_GAME, load as load_catalog

UNITY_VERSION = "2022.3.29f1"


def aa_dir(game: str = DEFAULT_GAME) -> str:
    return os.path.join(game, "ULTRAKILL_Data", "StreamingAssets", "aa", "StandaloneWindows64")


@lru_cache(maxsize=1)
def generator(game: str = DEFAULT_GAME) -> TypeTreeGenerator:
    gen = TypeTreeGenerator(UNITY_VERSION)
    gen.load_local_game(game)
    return gen


@lru_cache(maxsize=1)
def catalog(game: str = DEFAULT_GAME):
    return load_catalog(game)


def load_bundles(paths, game: str = DEFAULT_GAME):
    """UnityPy environment over the given bundle files + the monoscript bundle."""
    files = list(dict.fromkeys(list(paths) + [os.path.join(aa_dir(game), "monoscript_monoscripts.bundle")]))
    env = UnityPy.load(*files)
    env.typetree_generator = generator(game)
    return env


def env_for_key(key: str, game: str = DEFAULT_GAME):
    """Environment holding the bundle of an addressable key and all its dependency bundles.

    Returns (env, entries) where entries are the catalog entries matching the key exactly.
    """
    cat = catalog(game)
    entries = [e for e in cat.entries if key in e.keys]
    if not entries:
        raise KeyError(key)
    deps = []
    for e in entries:
        for b in cat.bundle_entries(e):
            p = cat.bundle_path(b, game)
            if p and p not in deps:
                deps.append(p)
    return load_bundles(deps, game), entries


def script_name(mb) -> str:
    try:
        return mb.m_Script.read().m_ClassName
    except Exception:
        return "?"
