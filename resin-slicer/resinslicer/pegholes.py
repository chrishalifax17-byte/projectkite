"""Peg-hole / blind-cavity detection and drain-hole drilling.

Blind holes (peg holes, magnet sockets, pin holes) and sealed internal voids
are the two geometries that trap liquid resin and create suction cups on
an MSLA printer. We find them with voxel morphology:

  1. Voxelize the solid (voids preserved).
  2. Morphologically *close* it with a ball of radius `max_radius`
     (done with two Euclidean distance transforms, so it is O(N)).
     Anything the closing fills that was air is a concavity.
  3. Each connected concavity is scored. A blind hole has a small opening
     relative to its wall area, a measurable depth along its axis and a
     limited radius. Fillet-like corners and wide gaps fail those tests.
  4. Air pockets that are not connected to the outside at all are sealed
     voids (e.g. hollowed models) and always need draining.

For each cavity a vent is drilled from the cavity floor straight through
the remaining material (along the hole axis by default), falling back to
the shortest path out if the axis route is too long.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict

import numpy as np
import trimesh
from scipy import ndimage

from .raster import voxelize
from .meshutil import is_solid


@dataclass
class Cavity:
    kind: str                 # "blind_hole" or "sealed_void"
    centroid: np.ndarray
    opening_center: np.ndarray | None
    axis: np.ndarray | None   # unit vector from the opening into the cavity
    bottom_point: np.ndarray  # a point in the air of the cavity near its floor
    radius: float
    depth: float
    volume: float
    open_fraction: float
    drains: list = field(default_factory=list)  # list of (start, end) points
    cup: bool = False

    def to_dict(self):
        d = asdict(self)
        for k, v in d.items():
            if isinstance(v, np.ndarray):
                d[k] = np.round(v, 3).tolist()
        d["drains"] = [[np.round(a, 3).tolist(), np.round(b, 3).tolist()] for a, b in self.drains]
        return d


def _auto_pitch(mesh, max_radius, budget=8e6):
    ext = mesh.extents + 2 * max_radius + 1.0
    pitch = (np.prod(ext) / budget) ** (1 / 3)
    return float(np.clip(pitch, 0.1, 1.0))


def find_cavities(mesh, pitch=None, max_radius=4.0, min_radius=0.35, min_depth=0.8,
                  max_open_fraction=0.36, min_aspect=0.6):
    """Detect blind holes and sealed voids. Returns a list of Cavity."""
    pitch = pitch or _auto_pitch(mesh, max_radius)
    pad = int(np.ceil(max_radius / pitch)) + 2
    solid, origin = voxelize(mesh, pitch, pad=pad)
    air = ~solid

    # Morphological closing with a ball of radius max_radius.
    dilated = ndimage.distance_transform_edt(air, sampling=pitch) <= max_radius
    closed = ndimage.distance_transform_edt(dilated, sampling=pitch) > max_radius
    pockets = closed & air

    # Air not connected to the outside = sealed voids.
    six = ndimage.generate_binary_structure(3, 1)
    air_lab, _ = ndimage.label(air, structure=six)
    outside_label = air_lab[0, 0, 0]
    voids = air & (air_lab != outside_label)
    pockets |= voids

    outside = air & ~pockets
    near_out = ndimage.binary_dilation(outside, structure=six)
    near_solid = ndimage.binary_dilation(solid, structure=six)

    lab, n = ndimage.label(pockets, structure=six)
    objs = ndimage.find_objects(lab)
    voxel_vol = pitch ** 3
    min_vox = max(4, int((np.pi * min_radius ** 2 * min_depth) / voxel_vol * 0.5))

    def to_world(kji):
        return origin + (kji[:, ::-1] + 0.5) * pitch

    cavities = []
    for label_id, sl in enumerate(objs, start=1):
        if sl is None:
            continue
        comp = lab[sl] == label_id
        count = int(comp.sum())
        if count < min_vox:
            continue
        offset = np.array([s.start for s in sl])
        pts = to_world(np.argwhere(comp) + offset)
        centroid = pts.mean(axis=0)
        is_void = bool(voids[sl][comp].any())
        open_mask = comp & near_out[sl]
        wall_mask = comp & near_solid[sl]
        n_open = int(open_mask.sum())
        n_wall = int(wall_mask.sum())

        if is_void and n_open == 0:
            # Sealed void: drain from its lowest region (any floor point works;
            # the drill planner looks for the thinnest wall).
            low = pts[pts[:, 2] <= pts[:, 2].min() + 1.5 * pitch].mean(axis=0)
            cavities.append(Cavity("sealed_void", centroid, None, None, low,
                                   radius=float(np.ptp(pts, axis=0).max() / 2), depth=0.0,
                                   volume=count * voxel_vol, open_fraction=0.0))
            continue

        open_fraction = n_open / max(1, n_open + n_wall)
        if open_fraction > max_open_fraction or n_open == 0:
            continue
        open_pts = to_world(np.argwhere(open_mask) + offset)
        opening_center = open_pts.mean(axis=0)
        axis = centroid - opening_center
        norm = np.linalg.norm(axis)
        if norm < 1e-6:
            continue
        axis /= norm
        k = int(np.argmax(np.abs(axis)))
        if abs(axis[k]) > 0.995:
            axis = np.sign(axis[k]) * np.eye(3)[k]
        rel = pts - opening_center
        proj = rel @ axis
        depth = float(proj.max() + pitch / 2)
        radial = np.linalg.norm(rel - proj[:, None] * axis, axis=1)
        radius = float(np.percentile(radial, 90) + pitch / 2)
        if depth < min_depth or radius < min_radius or radius > max_radius * 1.25:
            continue
        if depth < min_aspect * radius:
            continue
        floor = pts[proj >= proj.max() - 1.5 * pitch]
        bottom = floor.mean(axis=0)
        # keep the start point on the hole axis
        bottom = opening_center + axis * ((bottom - opening_center) @ axis)
        cavities.append(Cavity("blind_hole", centroid, opening_center, axis, bottom,
                               radius=radius, depth=depth, volume=count * voxel_vol,
                               open_fraction=open_fraction))
    return cavities


def is_cup(cavity, threshold=0.17):
    """True if a blind hole's opening faces the build plate (slicer -Z).

    On a bottom-up printer the part hangs upside down, so such a hole is
    physically open *upwards*: it fills with resin like a cup, and the layer
    that closes its floor creates suction against the FEP on every peel.
    Holes whose opening faces the vat drain by themselves.
    """
    return cavity.axis is not None and float(cavity.axis[2]) > threshold


def _fibonacci_dirs(n=96):
    i = np.arange(n) + 0.5
    phi = np.arccos(1 - 2 * i / n)
    theta = np.pi * (1 + 5 ** 0.5) * i
    return np.stack([np.cos(theta) * np.sin(phi), np.sin(theta) * np.sin(phi), np.cos(phi)], axis=1)


def _exit_distances(mesh, start, dirs):
    """Distance from `start` (in air) through the first solid wall to air again.

    Returns array of distances (inf when the ray never re-emerges).
    """
    origins = np.repeat(start[None], len(dirs), axis=0)
    locs, ray_idx, _ = mesh.ray.intersects_location(origins, dirs, multiple_hits=True)
    out = np.full(len(dirs), np.inf)
    if len(locs) == 0:
        return out
    dist = np.einsum("ij,ij->i", locs - origins[ray_idx], dirs[ray_idx])
    for r in range(len(dirs)):
        d = np.sort(dist[ray_idx == r])
        d = d[d > 1e-4]
        # Remove near-duplicate hits (shared edges report twice).
        if len(d) > 1:
            d = d[np.concatenate([[True], np.diff(d) > 1e-4])]
        if len(d) >= 2:
            out[r] = d[1]
    return out


def plan_drains(mesh, cavity: Cavity, prefer="axis", max_length=25.0, second_vent_for_voids=True):
    """Choose drill segments for a cavity. Fills cavity.drains and returns them."""
    start = np.asarray(cavity.bottom_point, float)
    dirs = _fibonacci_dirs()
    if cavity.axis is not None:
        dirs = np.vstack([cavity.axis[None], dirs])
        # Never drill back out through the opening.
        dirs = dirs[dirs @ cavity.axis > 0.05]
    dist = _exit_distances(mesh, start, dirs)
    if cavity.axis is not None:
        alignment = dirs @ cavity.axis
        cost = dist * (1.0 + 1.5 * (1.0 - alignment))
        if prefer == "axis" and dist[0] <= max_length:
            best = [0]
        else:
            best = [int(np.argmin(cost))]
    else:
        best = [int(np.argmin(dist))]
        if second_vent_for_voids:
            # A second vent pointing away from the first lets air in while resin drains.
            away = dirs @ dirs[best[0]] < -0.3
            if away.any():
                cand = np.where(away)[0]
                best.append(int(cand[np.argmin(dist[cand])]))
    drains = []
    for b in best:
        if not np.isfinite(dist[b]) or dist[b] > max_length:
            continue
        d = dirs[b]
        drains.append((start, start + d * dist[b]))
    cavity.drains = drains
    return drains


def drill(mesh, cavities, diameter=1.2, void_diameter=2.5, overshoot=1.0, sections=24):
    """Boolean-subtract vent cylinders for every planned drain."""
    cutters = []
    for c in cavities:
        dia = void_diameter if c.kind == "sealed_void" else diameter
        for a, b in c.drains:
            d = (b - a) / np.linalg.norm(b - a)
            seg = np.array([a - d * min(0.5, max(c.depth * 0.3, 0.2)), b + d * overshoot])
            cutters.append(trimesh.creation.cylinder(radius=dia / 2, segment=seg, sections=sections))
    if not cutters:
        return mesh
    if not is_solid(mesh):
        raise ValueError("Model is not a closed solid; cannot drill drain holes safely.")
    return trimesh.boolean.difference([mesh] + cutters, engine="manifold")


def find_and_drill(mesh, diameter=1.2, void_diameter=2.5, prefer="axis", **find_kw):
    cavities = find_cavities(mesh, **find_kw)
    for c in cavities:
        plan_drains(mesh, c, prefer=prefer)
    drilled = drill(mesh, cavities, diameter=diameter, void_diameter=void_diameter)
    return drilled, cavities
