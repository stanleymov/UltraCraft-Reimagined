"""Print the particle systems of a prefab in a readable form (the modules that decide how an effect looks).

usage: python tools/extract/dump_particles.py "<prefab key>" [more keys]
For each ParticleSystem: node path and transform, lifetime/speed/size/colour, emission (rate, bursts), shape,
velocity/colour/size over lifetime, trails, and the renderer (mode, stretch, material -> texture).
"""
import json
import sys

import ukenv


def curve(c):
    if not isinstance(c, dict):
        return c
    st = c.get('minMaxState')
    mode = {0: 'const', 1: 'curve', 2: 'rand2curves', 3: 'rand2consts'}.get(st, st)
    out = {'mode': mode, 'scalar': round(c.get('scalar', 0), 4)}
    if st == 3:
        out['min'] = round(c.get('minScalar', 0), 4)
    if st in (1, 2):
        keys = c.get('maxCurve', {}).get('m_Curve', [])
        out['keys'] = [(round(k['time'], 2), round(k['value'], 3)) for k in keys]
    return out


def grad(g):
    if not isinstance(g, dict):
        return g
    st = g.get('minMaxState')
    if st == 0:
        return {'color': {k: round(v, 3) for k, v in g.get('maxColor', {}).items()}}
    mg = g.get('maxGradient', {})
    n = mg.get('m_NumColorKeys', 0)
    na = mg.get('m_NumAlphaKeys', 0)
    cols = [({k: round(mg[f'key{i}'][k], 2) for k in 'rgb'}, round(mg.get(f'ctime{i}', 0) / 65535, 2)) for i in range(n)]
    alphas = [(round(mg[f'key{i}']['a'], 2), round(mg.get(f'atime{i}', 0) / 65535, 2)) for i in range(na)]
    return {'mode': st, 'colors': cols, 'alphas': alphas}


def main():
    for key in sys.argv[1:]:
        env, _ = ukenv.env_for_key(key)
        by_id = {o.path_id: o for o in env.objects}
        print('=' * 20, key)
        # only this prefab: find its root GameObject by name and walk the transform tree
        root_name = key.rsplit('/', 1)[-1].rsplit('.', 1)[0]
        roots = [o for o in env.objects if o.type.name == 'GameObject' and o.read().m_Name == root_name]
        systems = []

        def walk(go_obj, path):
            go = go_obj.read()
            for comp in go.m_Components:
                try:
                    co = comp.read()
                except Exception:
                    continue
                tn = co.object_reader.type.name
                if tn == 'ParticleSystem':
                    systems.append((co.object_reader, path))
                if tn == 'Transform':
                    for ch in co.m_Children:
                        try:
                            cgo = ch.read().m_GameObject.read()
                        except Exception:
                            continue
                        walk(cgo.object_reader, path + '/' + cgo.m_Name)

        for r in roots[:1]:
            walk(r, root_name)
        for o, ppath in systems:
            ps = o.read_typetree()
            go = o.read().m_GameObject.read()
            path = ppath
            print('--', path)
            for comp in go.m_Components:
                try:
                    co = comp.read()
                except Exception:
                    continue
                if co.object_reader.type.name == 'Transform':
                    tt = co.object_reader.read_typetree()
                    print('   xf pos', {k: round(v, 3) for k, v in tt['m_LocalPosition'].items()}, 'rot', {k: round(v, 3) for k, v in tt['m_LocalRotation'].items()}, 'scale', {k: round(v, 3) for k, v in tt['m_LocalScale'].items()})
            try:
                tr = [c for c in go.m_Components if c.read().object_reader.type.name == 'Transform']
            except Exception:
                tr = []
            im = ps.get('InitialModule', {})
            print('   life', curve(im.get('startLifetime')), 'speed', curve(im.get('startSpeed')), 'size', curve(im.get('startSize')),
                  'size3D', im.get('size3D'), 'gravity', curve(im.get('gravityModifier')), 'max', im.get('maxNumParticles'))
            print('   startColor', grad(im.get('startColor')), 'rotation', curve(im.get('startRotation')))
            print('   duration', ps.get('lengthInSec'), 'loop', ps.get('looping'), 'simSpace', ps.get('moveWithTransform'), 'scaling', ps.get('scalingMode'))
            em = ps.get('EmissionModule', {})
            print('   emission rate', curve(em.get('rateOverTime')), 'perDist', curve(em.get('rateOverDistance')), 'bursts',
                  [(b.get('time'), curve(b.get('countCurve'))) for b in em.get('m_Bursts', [])])
            sh = ps.get('ShapeModule', {})
            if sh.get('enabled'):
                print('   shape type', sh.get('type'), 'angle', sh.get('angle'), 'radius', (sh.get('radius') or {}).get('value'), 'arc',
                      (sh.get('arc') or {}).get('value'), 'pos', sh.get('m_Position'), 'rot', sh.get('m_Rotation'), 'scale', sh.get('m_Scale'),
                      'thickness', sh.get('radiusThickness'), 'length', sh.get('length'))
            for mod in ('VelocityModule', 'ColorModule', 'SizeModule', 'RotationModule', 'NoiseModule', 'LimitVelocityModule' if False else 'ClampVelocityModule', 'TrailModule'):
                m = ps.get(mod, {})
                if not m.get('enabled'):
                    continue
                if mod == 'VelocityModule':
                    print('   velocity x', curve(m.get('x')), 'y', curve(m.get('y')), 'z', curve(m.get('z')), 'space', m.get('inWorldSpace'),
                          'orbital', curve(m.get('orbitalY')), 'radial', curve(m.get('radial')), 'speedMod', curve(m.get('speedModifier')))
                elif mod == 'ColorModule':
                    print('   colorOverLife', grad(m.get('gradient')))
                elif mod == 'SizeModule':
                    print('   sizeOverLife', curve(m.get('curve')))
                elif mod == 'TrailModule':
                    print('   trails ratio', m.get('ratio'), 'life', curve(m.get('lifetime')), 'width', curve(m.get('widthOverTrail')),
                          'colorOverLife', grad(m.get('colorOverLifetime')), 'colorOverTrail', grad(m.get('colorOverTrail')))
                else:
                    print('  ', mod, {k: v for k, v in m.items() if k != 'enabled'})
            # renderer on the same GameObject
            for comp in go.m_Components:
                try:
                    co = comp.read()
                except Exception:
                    continue
                if co.object_reader.type.name != 'ParticleSystemRenderer':
                    continue
                r = co.object_reader.read_typetree()
                mats = []
                for mp in r.get('m_Materials', []):
                    mo = by_id.get(mp.get('m_PathID'))
                    if mo is None:
                        try:
                            mo = co.m_Materials[r['m_Materials'].index(mp)].read().object_reader
                        except Exception:
                            mo = None
                    if mo is None:
                        mats.append(None)
                        continue
                    md = mo.read_typetree()
                    tex = None
                    for name, tv in md.get('m_SavedProperties', {}).get('m_TexEnvs', []):
                        if name == '_MainTex' and tv.get('m_Texture', {}).get('m_PathID'):
                            to = by_id.get(tv['m_Texture']['m_PathID'])
                            tex = to.read_typetree().get('m_Name') if to else '?'
                    cols = {n: {k: round(v, 2) for k, v in c.items()} for n, c in md.get('m_SavedProperties', {}).get('m_Colors', []) if n in ('_Color', '_TintColor', '_EmissionColor')}
                    mats.append((md.get('m_Name'), tex, cols))
                print('   renderer mode', r.get('m_RenderMode'), 'lengthScale', r.get('m_LengthScale'), 'velocityScale', r.get('m_VelocityScale'),
                      'cameraVelScale', r.get('m_CameraVelocityScale'), 'sortMode', r.get('m_SortMode'), 'mats', mats)


if __name__ == '__main__':
    main()
