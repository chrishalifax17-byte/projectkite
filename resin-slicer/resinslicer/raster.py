"""Fast planar slicing and even-odd rasterization of triangle meshes.

The same rasterizer is used for three jobs:
  * voxelizing a model (cavity / peg-hole detection),
  * checking the supported model for unsupported islands,
  * producing the final layer images for the printer.

Even-odd filling means nested shells (holes, voids, islands inside holes)
are handled without having to assemble polygons, as long as each body passed
in is closed (watertight). Overlapping bodies (e.g. a support tip pushed
into the model) must be rasterized separately and OR-ed together.
"""
from __future__ import annotations

import numpy as np


class LayerSlicer:
    """Cuts a mesh with horizontal planes, returning 2D line segments."""

    def __init__(self, mesh):
        self.tris = np.asarray(mesh.triangles, dtype=np.float64)
        z = self.tris[:, :, 2]
        self.zmin = z.min(axis=1)
        self.zmax = z.max(axis=1)
        self.order = np.argsort(self.zmin, kind="stable")
        self.zmin_sorted = self.zmin[self.order]

    def segments(self, z: float) -> np.ndarray:
        """Return (n, 2, 2) XY segments where plane Z=z cuts the mesh."""
        n = np.searchsorted(self.zmin_sorted, z, side="right")
        idx = self.order[:n]
        idx = idx[self.zmax[idx] >= z]
        if idx.size == 0:
            return np.zeros((0, 2, 2))
        t = self.tris[idx]
        d = t[:, :, 2] - z
        # Vertices exactly on the plane are nudged "above" consistently.
        d = np.where(d == 0.0, 1e-12, d)
        above = d > 0
        pts, cross = [], []
        for a, b in ((0, 1), (1, 2), (2, 0)):
            m = above[:, a] != above[:, b]
            denom = np.where(m, d[:, a] - d[:, b], 1.0)
            s = d[:, a] / denom
            p = t[:, a, :2] + s[:, None] * (t[:, b, :2] - t[:, a, :2])
            pts.append(p)
            cross.append(m)
        cross = np.stack(cross, axis=1)
        pts = np.stack(pts, axis=1)
        keep = cross.sum(axis=1) == 2
        if not keep.any():
            return np.zeros((0, 2, 2))
        return pts[keep][cross[keep]].reshape(-1, 2, 2)


def rasterize_segments(segs, x0, y0, px, py, width, height):
    """Even-odd fill of closed loops given as segments.

    Pixel (row r, col c) has its centre at (x0 + (c+.5)*px, y0 + (r+.5)*py).
    Returns a bool array of shape (height, width); row 0 is the lowest Y.
    """
    out = np.zeros((height, width), dtype=bool)
    if len(segs) == 0:
        return out
    ya, yb = segs[:, 0, 1], segs[:, 1, 1]
    xa, xb = segs[:, 0, 0], segs[:, 1, 0]
    lo, hi = np.minimum(ya, yb), np.maximum(ya, yb)
    r0 = np.clip(np.ceil((lo - y0) / py - 0.5), 0, height).astype(np.int64)
    r1 = np.clip(np.ceil((hi - y0) / py - 0.5), 0, height).astype(np.int64)
    counts = r1 - r0
    valid = counts > 0
    if not valid.any():
        return out
    xa, xb, ya, yb, r0, counts = xa[valid], xb[valid], ya[valid], yb[valid], r0[valid], counts[valid]
    total = int(counts.sum())
    seg_i = np.repeat(np.arange(len(counts)), counts)
    offs = np.arange(total) - np.repeat(np.cumsum(counts) - counts, counts)
    rows = r0[seg_i] + offs
    yc = y0 + (rows + 0.5) * py
    t = (yc - ya[seg_i]) / (yb[seg_i] - ya[seg_i])
    x = xa[seg_i] + t * (xb[seg_i] - xa[seg_i])
    cols = np.clip(np.ceil((x - x0) / px - 0.5), 0, width).astype(np.int64)
    toggles = np.zeros((height, width + 1), dtype=np.uint8)
    np.add.at(toggles, (rows, cols), 1)
    return (np.cumsum(toggles, axis=1, dtype=np.uint8) & 1)[:, :width].astype(bool)


def rasterize_bodies(slicers, z, x0, y0, px, py, width, height, supersample=1):
    """OR together the even-odd rasters of several bodies at height z.

    With supersample > 1 an anti-aliased uint8 image (0..255) is returned,
    otherwise a bool mask.
    """
    s = int(supersample)
    acc = np.zeros((height * s, width * s), dtype=bool)
    for sl in slicers:
        segs = sl.segments(z)
        if len(segs):
            acc |= rasterize_segments(segs, x0, y0, px / s, py / s, width * s, height * s)
    if s == 1:
        return acc
    blocks = acc.reshape(height, s, width, s).sum(axis=(1, 3))
    return (blocks * 255 // (s * s)).astype(np.uint8)


def voxelize(mesh, pitch, pad=0):
    """Solid voxelization honouring internal voids.

    Returns (grid, origin) where grid has shape (nz, ny, nx) and voxel
    (k, j, i) has its centre at origin + (i+.5, j+.5, k+.5) * pitch.
    `pad` adds that many empty voxels on every side.
    """
    lo, hi = mesh.bounds
    origin = lo - pad * pitch
    n = np.ceil((hi - lo) / pitch).astype(int) + 2 * pad
    nx, ny, nz = (int(v) for v in n)
    grid = np.zeros((nz, ny, nx), dtype=bool)
    sl = LayerSlicer(mesh)
    for k in range(nz):
        z = origin[2] + (k + 0.5) * pitch
        if z < lo[2] or z > hi[2]:
            continue
        segs = sl.segments(z)
        if len(segs):
            grid[k] = rasterize_segments(segs, origin[0], origin[1], pitch, pitch, nx, ny)
    return grid, origin
