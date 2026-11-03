"""Mesh loading, repair and primitive construction helpers."""
from __future__ import annotations

import numpy as np
import trimesh


def load_mesh(path) -> trimesh.Trimesh:
    loaded = trimesh.load(path, force="mesh")
    if not isinstance(loaded, trimesh.Trimesh) or len(loaded.faces) == 0:
        raise ValueError(f"No triangle mesh found in {path}")
    return repair(loaded)


def repair(mesh: trimesh.Trimesh) -> trimesh.Trimesh:
    mesh = mesh.copy()
    mesh.merge_vertices()
    mesh.update_faces(mesh.nondegenerate_faces())
    mesh.update_faces(mesh.unique_faces())
    mesh.remove_unreferenced_vertices()
    trimesh.repair.fix_normals(mesh)
    if not mesh.is_watertight:
        trimesh.repair.fill_holes(mesh)
    return mesh


def frustum(p0, r0, p1, r1, sections=12) -> trimesh.Trimesh:
    """Closed truncated cone from p0 (radius r0) to p1 (radius r1)."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    axis = p1 - p0
    length = np.linalg.norm(axis)
    if length < 1e-9:
        raise ValueError("degenerate frustum")
    w = axis / length
    helper = np.array([1.0, 0, 0]) if abs(w[0]) < 0.9 else np.array([0, 1.0, 0])
    u = np.cross(w, helper)
    u /= np.linalg.norm(u)
    v = np.cross(w, u)
    ang = np.linspace(0, 2 * np.pi, sections, endpoint=False)
    ring = np.cos(ang)[:, None] * u + np.sin(ang)[:, None] * v
    verts = np.vstack([p0 + r0 * ring, p1 + r1 * ring, p0, p1])
    n = sections
    faces = []
    for i in range(n):
        j = (i + 1) % n
        faces += [[i, n + i, j], [j, n + i, n + j]]
        faces.append([2 * n, i, j])          # cap at p0 (normal -w)
        faces.append([2 * n + 1, n + j, n + i])  # cap at p1 (normal +w)
    m = trimesh.Trimesh(verts, np.array(faces), process=False)
    if m.volume < 0:
        m.invert()
    return m


def union(meshes):
    meshes = [m for m in meshes if m is not None and len(m.faces)]
    if not meshes:
        return trimesh.Trimesh()
    if len(meshes) == 1:
        return meshes[0]
    return trimesh.boolean.union(meshes, engine="manifold")


def is_solid(mesh) -> bool:
    return bool(mesh.is_watertight and mesh.is_winding_consistent and mesh.volume > 0)
