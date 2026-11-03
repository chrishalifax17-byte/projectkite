"""Generate small test models: a miniature with peg holes and a hollow figure."""
import sys
from pathlib import Path

import trimesh


def miniature():
    """Round 25 mm base with two 2 mm peg holes underneath and a figure on top."""
    base = trimesh.creation.cylinder(radius=12.5, height=3, sections=96)
    base.apply_translation((0, 0, 1.5))
    body = trimesh.creation.cylinder(radius=3.5, height=18, sections=48)
    body.apply_translation((0, 0, 12))
    head = trimesh.creation.icosphere(subdivisions=3, radius=4.5)
    head.apply_translation((0, 0, 24))
    arm = trimesh.creation.box((16, 2.5, 2.5))
    arm.apply_translation((4, 0, 17))
    solid = trimesh.boolean.union([base, body, head, arm], engine="manifold")
    holes = []
    for x in (-6.0, 6.0):  # 2 mm diameter, 2.2 mm deep, opening on the underside
        h = trimesh.creation.cylinder(radius=1.0, height=4.4, sections=32)
        h.apply_translation((x, 0, 0))
        holes.append(h)
    # magnet socket in the side of the head (opening sideways)
    m = trimesh.creation.cylinder(radius=1.5, height=6, sections=32)
    m.apply_transform(trimesh.transformations.rotation_matrix(1.5708, [0, 1, 0]))
    m.apply_translation((-4.5, 0, 24))
    holes.append(m)
    return trimesh.boolean.difference([solid] + holes, engine="manifold")


def hollow_bust():
    outer = trimesh.creation.icosphere(subdivisions=4, radius=15)
    inner = trimesh.creation.icosphere(subdivisions=4, radius=12.5)
    stand = trimesh.creation.cylinder(radius=8, height=10, sections=64)
    stand.apply_translation((0, 0, -17))
    return trimesh.boolean.difference([trimesh.boolean.union([outer, stand], engine="manifold"), inner],
                                      engine="manifold")


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "examples")
    out.mkdir(exist_ok=True)
    miniature().export(out / "miniature_pegs.stl")
    hollow_bust().export(out / "hollow_bust.stl")
    print("wrote", out / "miniature_pegs.stl", out / "hollow_bust.stl")
