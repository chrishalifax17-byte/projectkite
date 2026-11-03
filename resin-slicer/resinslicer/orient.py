"""Automatic orientation search.

Each candidate orientation tilts the model about a horizontal axis. We score:
  * the largest cross-section area (drives peel / suction force per layer),
  * the overhang area that will need supports,
  * the print height (drives time),
  * blind holes whose opening faces the build plate (resin cups).
Lower is better. Tilting by 20-50 degrees is what experienced resin printers
do by hand; this just measures it.
"""
from __future__ import annotations

import math

import numpy as np
import trimesh

from .raster import LayerSlicer, rasterize_segments


def candidate_transforms(tilts=(0, 15, 30, 45, 60), azimuths=8):
    out = [np.eye(4)]
    for t in tilts:
        if t == 0:
            continue
        for k in range(azimuths):
            a = 2 * math.pi * k / azimuths
            axis = [math.cos(a), math.sin(a), 0.0]
            out.append(trimesh.transformations.rotation_matrix(math.radians(t), axis))
    return out


def max_section_area(mesh, samples=24):
    lo, hi = mesh.bounds
    pitch = max(mesh.extents[:2]) / 120.0
    w = int(math.ceil((hi[0] - lo[0]) / pitch)) + 1
    h = int(math.ceil((hi[1] - lo[1]) / pitch)) + 1
    sl = LayerSlicer(mesh)
    best = 0.0
    for z in np.linspace(lo[2], hi[2], samples + 2)[1:-1]:
        mask = rasterize_segments(sl.segments(z), lo[0], lo[1], pitch, pitch, w, h)
        best = max(best, mask.sum() * pitch * pitch)
    return best


def evaluate(mesh, T, overhang_angle=55.0, cavities=()):
    m = mesh.copy()
    m.apply_transform(T)
    thresh = -math.cos(math.radians(overhang_angle))
    overhang = float(m.area_faces[m.face_normals[:, 2] < thresh].sum())
    cups = 0
    R = T[:3, :3]
    for c in cavities:
        if c.axis is not None and (R @ c.axis)[2] > 0.17:
            cups += 1
    return dict(section=max_section_area(m), overhang=overhang, height=float(m.extents[2]), cups=cups)


def best_orientation(mesh, cavities=(), overhang_angle=55.0, tilts=(0, 15, 30, 45, 60), azimuths=8):
    cands = candidate_transforms(tilts, azimuths)
    stats = [evaluate(mesh, T, overhang_angle, cavities) for T in cands]
    sec = np.array([s["section"] for s in stats])
    ovh = np.array([s["overhang"] for s in stats])
    hgt = np.array([s["height"] for s in stats])
    cups = np.array([s["cups"] for s in stats])
    score = 0.5 * sec / sec.max() + 0.3 * ovh / max(ovh.max(), 1e-9) + 0.2 * hgt / hgt.max() \
        + 0.08 * np.minimum(cups, 4)
    i = int(np.argmin(score))
    return cands[i], dict(stats[i], score=float(score[i]),
                          baseline=dict(stats[0], score=float(score[0])),
                          tilt_deg=float(math.degrees(math.acos(np.clip(cands[i][2, 2], -1, 1)))))
