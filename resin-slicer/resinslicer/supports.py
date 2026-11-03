"""Automatic support generation for bottom-up MSLA printing.

Coordinates: the build plate is at Z=0 and the model hangs "above" it in
slicer space (physically it is upside down). Anything whose surface faces
-Z must be held up from the plate side.

Support points come from three sources, in priority order:
  1. local minima of the mesh (points that start a new island),
  2. area-weighted samples on overhanging faces, thinned to `spacing`,
  3. islands found afterwards by actually slicing the supported model.

Every support is a contact cone (tip) -> shaft -> base cone on a raft. If the
straight drop to the raft is blocked by the model, the shaft lands on the
model with a second small tip instead.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
import trimesh
from scipy import ndimage
from scipy.spatial import cKDTree
from shapely.geometry import Point
from shapely.ops import unary_union

from .meshutil import frustum, union
from .raster import LayerSlicer, rasterize_bodies


@dataclass
class SupportConfig:
    tip_diameter: float = 0.4
    shaft_diameter: float = 1.0
    base_diameter: float = 2.6
    tip_length: float = 2.5
    spacing: float = 2.2
    overhang_angle: float = 55.0   # faces within this angle of straight down get supported
    raft_thickness: float = 1.0
    raft_margin: float = 1.5
    base_height: float = 1.0
    tip_penetration: float = 0.25
    brace_min_height: float = 12.0
    brace_max_dist: float = 7.0
    allow_model_contacts: bool = True
    seed: int = 0

    @classmethod
    def from_preset(cls, preset: dict, **kw):
        return cls(**{**preset, **kw})


@dataclass
class Support:
    contact: np.ndarray
    normal: np.ndarray
    kind: str
    top: np.ndarray        # end of the tip / start of the shaft
    bottom: np.ndarray     # end of the shaft
    on_model: bool
    land_normal: np.ndarray | None = None


@dataclass
class SupportResult:
    supports: list = field(default_factory=list)
    mesh: trimesh.Trimesh | None = None      # supports + raft as one solid
    raft: trimesh.Trimesh | None = None
    skipped: int = 0
    islands_fixed: int = 0
    islands_remaining: list = field(default_factory=list)


def place_on_plate(mesh, lift_height, raft_thickness):
    m = mesh.copy()
    lo, hi = m.bounds
    center = (lo + hi) / 2
    m.apply_translation([-center[0], -center[1], raft_thickness + lift_height - lo[2]])
    return m


def _local_minima(mesh):
    z = mesh.vertices[:, 2]
    nbrs = mesh.vertex_neighbors
    vn = mesh.vertex_normals
    out = []
    for i, nb in enumerate(nbrs):
        if nb and vn[i, 2] < -0.2 and z[i] <= z[nb].min():
            out.append(i)
    return np.array(out, dtype=int)


def _poisson_thin(points, spacing, order):
    tree = cKDTree(points)
    removed = np.zeros(len(points), bool)
    keep = []
    for i in order:
        if removed[i]:
            continue
        keep.append(i)
        removed[tree.query_ball_point(points[i], spacing)] = True
    return np.array(keep, dtype=int)


def _in_exclusions(points, exclusions):
    """exclusions: list of (a, b, radius) capsules (e.g. narrow peg holes)."""
    bad = np.zeros(len(points), bool)
    for a, b, r in exclusions:
        ab = b - a
        t = np.clip(((points - a) @ ab) / max(ab @ ab, 1e-12), 0, 1)
        d = np.linalg.norm(points - (a + t[:, None] * ab), axis=1)
        bad |= d < r
    return bad


def sample_support_points(mesh, cfg: SupportConfig, exclusions=()):
    rng = np.random.default_rng(cfg.seed)
    normals = mesh.face_normals
    thresh = -math.cos(math.radians(cfg.overhang_angle))
    faces = np.where(normals[:, 2] < thresh)[0]
    pts, nrm, kinds = [], [], []

    mins = _local_minima(mesh)
    if len(mins):
        pts.append(mesh.vertices[mins])
        nrm.append(mesh.vertex_normals[mins])
        kinds += ["minimum"] * len(mins)

    if len(faces):
        areas = mesh.area_faces[faces]
        n = int(min(200_000, max(1, areas.sum() * 4.0 / cfg.spacing ** 2)))
        pick = faces[rng.choice(len(faces), size=n, p=areas / areas.sum())]
        tri = mesh.triangles[pick]
        u, v = rng.random(n), rng.random(n)
        flip = u + v > 1
        u[flip], v[flip] = 1 - u[flip], 1 - v[flip]
        p = tri[:, 0] + u[:, None] * (tri[:, 1] - tri[:, 0]) + v[:, None] * (tri[:, 2] - tri[:, 0])
        pts.append(p)
        nrm.append(normals[pick])
        kinds += ["overhang"] * n

    if not pts:
        return np.zeros((0, 3)), np.zeros((0, 3)), []
    pts, nrm = np.vstack(pts), np.vstack(nrm)
    kinds = np.array(kinds)
    if exclusions:
        ok = ~_in_exclusions(pts, exclusions)
        pts, nrm, kinds = pts[ok], nrm[ok], kinds[ok]
    if len(pts) == 0:
        return pts, nrm, []
    priority = np.where(kinds == "minimum", 0, 1)
    order = np.lexsort((pts[:, 2], priority))
    keep = _poisson_thin(pts, cfg.spacing, order)
    return pts[keep], nrm[keep], list(kinds[keep])


def _tip_direction(normal):
    d = 0.45 * normal + 0.55 * np.array([0, 0, -1.0])
    d /= np.linalg.norm(d)
    if d[2] > -0.5:  # keep tips reasonably steep
        d = np.array([d[0], d[1], -0.9])
        d /= np.linalg.norm(d)
    return d


def plan_supports(mesh, points, normals, kinds, cfg: SupportConfig):
    raft_top = cfg.raft_thickness
    tops = np.array([p + _tip_direction(n) * cfg.tip_length for p, n in zip(points, normals)])
    if len(tops) == 0:
        return [], 0
    inside = mesh.contains(tops) if mesh.is_watertight else np.zeros(len(tops), bool)
    origins = tops + np.array([0, 0, -1e-3])
    dirs = np.tile([0, 0, -1.0], (len(tops), 1))
    locs, ray_idx, tri_idx = mesh.ray.intersects_location(origins, dirs, multiple_hits=False)
    hit = {int(r): (locs[k], mesh.face_normals[tri_idx[k]]) for k, r in enumerate(ray_idx)}
    supports, skipped = [], 0
    for i, (p, n, k, q) in enumerate(zip(points, normals, kinds, tops)):
        if inside[i] or q[2] < raft_top + cfg.base_height + 0.2:
            skipped += 1
            continue
        if i in hit and hit[i][0][2] > raft_top:
            if not cfg.allow_model_contacts:
                skipped += 1
                continue
            h, hn = hit[i]
            if q[2] - h[2] < 2 * cfg.tip_length:
                skipped += 1
                continue
            bottom = np.array([q[0], q[1], h[2] + cfg.tip_length])
            supports.append(Support(p, n, k, q, bottom, True, hn))
        else:
            bottom = np.array([q[0], q[1], raft_top + cfg.base_height])
            supports.append(Support(p, n, k, q, bottom, False))
    return supports, skipped


def _support_meshes(s: Support, cfg: SupportConfig):
    rt, rs, rb = cfg.tip_diameter / 2, cfg.shaft_diameter / 2, cfg.base_diameter / 2
    parts = []
    start = s.contact - s.normal * cfg.tip_penetration
    parts.append(frustum(start, rt, s.top, rs))
    parts.append(trimesh.creation.icosphere(subdivisions=1, radius=rs).apply_translation(s.top))
    if s.top[2] - s.bottom[2] > 1e-3:
        parts.append(frustum(s.bottom, rs, s.top, rs))
    parts.append(trimesh.creation.icosphere(subdivisions=1, radius=rs).apply_translation(s.bottom))
    if s.on_model:
        land = s.bottom - np.array([0, 0, cfg.tip_length + cfg.tip_penetration])
        parts.append(frustum(land, rt, s.bottom, rs))
    else:
        base0 = np.array([s.bottom[0], s.bottom[1], cfg.raft_thickness - 0.1])
        parts.append(frustum(base0, rb, s.bottom, rs))
    return parts


def _braces(mesh, supports, cfg: SupportConfig):
    ground = [s for s in supports if not s.on_model and s.top[2] - s.bottom[2] > cfg.brace_min_height]
    if len(ground) < 2:
        return []
    xy = np.array([s.bottom[:2] for s in ground])
    tree = cKDTree(xy)
    pairs = set()
    for i in range(len(ground)):
        d, j = tree.query(xy[i], k=2)
        j = int(j[1])
        if d[1] <= cfg.brace_max_dist:
            pairs.add((min(i, j), max(i, j)))
    r = cfg.shaft_diameter * 0.35
    segs = []
    for i, j in pairs:
        a, b = ground[i], ground[j]
        z0 = cfg.raft_thickness + cfg.base_height + 2.0
        z1 = min(a.top[2], b.top[2]) - 2.0
        z, flip = z0, False
        while z + 3.0 < z1:
            dz = min(6.0, z1 - z)
            p0 = np.array([*(b if flip else a).bottom[:2], z])
            p1 = np.array([*(a if flip else b).bottom[:2], z + dz])
            segs.append((p0, p1))
            z += dz
            flip = not flip
    if not segs:
        return []
    o = np.array([s[0] for s in segs])
    d = np.array([s[1] - s[0] for s in segs])
    L = np.linalg.norm(d, axis=1)
    d = d / L[:, None]
    locs, ridx, _ = mesh.ray.intersects_location(o, d, multiple_hits=False)
    blocked = set()
    for k, ri in enumerate(ridx):
        if np.linalg.norm(locs[k] - o[ri]) < L[ri] + r:
            blocked.add(int(ri))
    return [frustum(segs[k][0], r, segs[k][1], r, sections=8) for k in range(len(segs)) if k not in blocked]


def _raft(supports, cfg: SupportConfig):
    pts = [s.bottom[:2] for s in supports if not s.on_model]
    if not pts:
        return None
    shape = unary_union([Point(p).buffer(cfg.base_diameter / 2 + cfg.raft_margin, 12) for p in pts])
    shape = shape.buffer(2.5, 8).buffer(-2.5, 8)
    polys = [shape] if shape.geom_type == "Polygon" else list(shape.geoms)
    meshes = [trimesh.creation.extrude_polygon(p, cfg.raft_thickness) for p in polys]
    return trimesh.util.concatenate(meshes) if len(meshes) > 1 else meshes[0]


def build_support_mesh(mesh, supports, cfg: SupportConfig):
    parts = []
    for s in supports:
        parts.extend(_support_meshes(s, cfg))
    parts.extend(_braces(mesh, supports, cfg))
    raft = _raft(supports, cfg)
    if raft is not None:
        parts.append(raft)
    return union(parts), raft


def find_islands(bodies, layer_h, pitch, z_start=0.0):
    """Slice the bodies and return [(x, y, z)] for every unsupported island."""
    lo = np.min([b.bounds[0] for b in bodies], axis=0) - pitch
    hi = np.max([b.bounds[1] for b in bodies], axis=0) + pitch
    w = int(math.ceil((hi[0] - lo[0]) / pitch))
    h = int(math.ceil((hi[1] - lo[1]) / pitch))
    slicers = [LayerSlicer(b) for b in bodies]
    prev = None
    islands = []
    eight = np.ones((3, 3), bool)
    z = max(z_start, lo[2]) + layer_h / 2
    first = True
    while z < hi[2]:
        mask = rasterize_bodies(slicers, z, lo[0], lo[1], pitch, pitch, w, h)
        if prev is not None and not first:
            lab, n = ndimage.label(mask, structure=eight)
            if n:
                supported = np.unique(lab[mask & ndimage.binary_dilation(prev, structure=eight)])
                for comp in set(range(1, n + 1)) - set(supported.tolist()):
                    rr, cc = np.nonzero(lab == comp)
                    cy, cx = rr.mean(), cc.mean()
                    k = np.argmin((rr - cy) ** 2 + (cc - cx) ** 2)
                    islands.append(np.array([lo[0] + (cc[k] + 0.5) * pitch,
                                             lo[1] + (rr[k] + 0.5) * pitch, z]))
        if mask.any():
            first = False
        prev = mask
        z += layer_h
    return islands


def _island_points(model, islands):
    """Turn island locations into surface contact points on the model."""
    if not islands:
        return np.zeros((0, 3)), np.zeros((0, 3))
    o = np.array([[x, y, z - 3.0] for x, y, z in islands])
    d = np.tile([0, 0, 1.0], (len(o), 1))
    locs, ridx, tidx = model.ray.intersects_location(o, d, multiple_hits=False)
    pts, nrm = [], []
    for k, r in enumerate(ridx):
        if abs(locs[k][2] - islands[r][2]) < 1.0:
            n = model.face_normals[tidx[k]]
            pts.append(locs[k])
            nrm.append(n if n[2] < 0 else np.array([0, 0, -1.0]))
    return np.array(pts).reshape(-1, 3), np.array(nrm).reshape(-1, 3)


def generate_supports(model, cfg: SupportConfig, exclusions=(), check_layer=0.1, check_pitch=None,
                      max_island_passes=2):
    res = SupportResult()
    pts, nrm, kinds = sample_support_points(model, cfg, exclusions)
    supports, skipped = plan_supports(model, pts, nrm, kinds, cfg)
    res.skipped = skipped
    pitch = check_pitch or float(np.clip(max(model.extents[:2]) / 400, 0.1, 0.3))
    islands = []
    for _ in range(max_island_passes + 1):
        support_mesh, raft = build_support_mesh(model, supports, cfg)
        islands = find_islands([model, support_mesh], check_layer, pitch)
        if not islands or _ == max_island_passes:
            break
        ip, inrm = _island_points(model, islands)
        if not len(ip):
            break
        new, _sk = plan_supports(model, ip, inrm, ["island"] * len(ip), cfg)
        if not new:
            break
        supports += new
        res.islands_fixed += len(new)
    res.supports = supports
    res.mesh = support_mesh
    res.raft = raft
    res.islands_remaining = islands
    return res
