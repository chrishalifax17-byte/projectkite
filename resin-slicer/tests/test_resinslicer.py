import math
import sys
from pathlib import Path

import numpy as np
import pytest
import trimesh

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "examples"))

from resinslicer import goo, pegholes, settings as st, supports as sup  # noqa: E402
from resinslicer.raster import LayerSlicer, rasterize_segments  # noqa: E402
from resinslicer.pipeline import Options, run  # noqa: E402
from make_test_models import miniature, hollow_bust  # noqa: E402


def test_raster_area_of_ring():
    outer = trimesh.creation.cylinder(radius=10, height=2, sections=128)
    ring = trimesh.boolean.difference([outer, trimesh.creation.cylinder(radius=5, height=4, sections=128)],
                                      engine="manifold")
    mask = rasterize_segments(LayerSlicer(ring).segments(0.0), -11, -11, 0.05, 0.05, 440, 440)
    assert mask.sum() * 0.05 ** 2 == pytest.approx(math.pi * (100 - 25), rel=0.01)


def test_finds_peg_holes_and_cup_logic():
    m = miniature()
    cav = pegholes.find_cavities(m)
    holes = [c for c in cav if c.kind == "blind_hole"]
    assert len(holes) == 3
    cups = [c for c in holes if pegholes.is_cup(c)]
    assert len(cups) == 2  # the two underside peg holes; the side socket is not a cup
    for c in cups:
        assert c.radius == pytest.approx(1.0, abs=0.25)


def test_drilling_vents_the_hole_floor():
    m = miniature()
    cups = [c for c in pegholes.find_cavities(m) if pegholes.is_cup(c)]
    for c in cups:
        pegholes.plan_drains(m, c)
    drilled = pegholes.drill(m, cups, diameter=1.2)
    assert drilled.is_watertight and drilled.volume < m.volume
    for c in cups:
        probe = c.bottom_point + c.axis * 0.4  # just beyond the old floor
        assert m.contains([probe])[0] and not drilled.contains([probe])[0]


def test_sealed_void_gets_two_drains():
    m = hollow_bust()
    cav = pegholes.find_cavities(m)
    voids = [c for c in cav if c.kind == "sealed_void"]
    assert len(voids) == 1
    pegholes.plan_drains(m, voids[0])
    assert len(voids[0].drains) == 2


def test_supports_leave_no_islands():
    stem = trimesh.creation.cylinder(radius=3, height=10)
    stem.apply_translation((0, 0, 5))
    cap = trimesh.creation.icosphere(subdivisions=3, radius=10)
    cap.apply_translation((0, 0, 14))
    m = trimesh.boolean.union([stem, cap], engine="manifold")
    cfg = sup.SupportConfig.from_preset(st.SUPPORT_PRESETS["medium"])
    m = sup.place_on_plate(m, 5, cfg.raft_thickness)
    res = sup.generate_supports(m, cfg)
    assert len(res.supports) > 10
    assert res.islands_remaining == []
    assert res.mesh.is_watertight


def test_exposure_follows_working_curve():
    r = st.RESINS["standard"]
    e30 = st.exposure_for_layer(2.0, 0.03, r.penetration_depth_mm)
    e100 = st.exposure_for_layer(2.0, 0.10, r.penetration_depth_mm)
    assert e30 < 2.0 < e100
    assert e100 / 2.0 == pytest.approx(math.exp(0.05 / r.penetration_depth_mm))


def test_saturn4_profiles():
    p = st.get_printer("saturn-4")
    assert (p.res_x, p.res_y, p.file_format) == (11520, 5120, "goo")
    assert p.width_mm == pytest.approx(218.88) and p.depth_mm == pytest.approx(122.88)


def test_goo_rle_roundtrip_random():
    rng = np.random.default_rng(0)
    for _ in range(500):
        W, H = int(rng.integers(1, 40)), int(rng.integers(1, 40))
        w, h = int(rng.integers(0, W + 1)), int(rng.integers(0, H + 1))
        c0, r0 = int(rng.integers(0, W - w + 1)), int(rng.integers(0, H - h + 1))
        win = ((rng.random((h, w)) < rng.random()) * rng.choice([255, 0, 7, 128], (h, w))).astype(np.uint8)
        frame = np.zeros((H, W), np.uint8)
        frame[r0:r0 + h, c0:c0 + w] = win
        lengths, values = goo.runs_from_window(win, W, H, c0, r0)
        assert lengths.sum() == W * H and (lengths > 0).all()
        assert (goo.decode_rle(goo.encode_runs(lengths, values), W * H).reshape(H, W) == frame).all()


def test_goo_spec_examples():
    # Examples from the official spec (Goo Format Spec V1.2, image data section)
    assert goo.encode_runs([5], [0]) == bytes([0b00000101])
    assert goo.encode_runs([0x555657F], [0]) == bytes([0b00111111, 0x55, 0x56, 0x57])
    assert goo.encode_runs([0xBBCC155], [0xAA]) == bytes([0b01110101, 0xAA, 0xBB, 0xCC, 0x15])
    assert goo.decode_rle(bytes([0b01110101, 0xAA, 0xBB, 0xCC, 0x15]), 0xBBCC155)[-1] == 0xAA


def test_end_to_end_goo(tmp_path):
    src = tmp_path / "mini.stl"
    miniature().export(src)
    custom = tmp_path / "small_printer.json"
    # A small Saturn-like screen keeps the test fast; same format and pixel shape.
    custom.write_text('{"name": "test", "res_x": 2304, "res_y": 1280, "pixel_x_um": 19.0, '
                      '"pixel_y_um": 24.0, "max_z_mm": 100, "file_format": "goo", "model_name": "test"}')
    r = run(src, tmp_path / "out", Options(printer=str(custom), layer_height=0.1, auto_orient=False,
                                           preview=False, workers=2), log=lambda *_: None)
    f = goo.read_goo(r.files["goo"], decode={0, 60})
    assert f["version"] == "V3.0" and f["res_x"] == 2304
    assert f["layers"] == r.report["estimates"]["layers"]
    decoded = [l for l in f["layer_data"] if "image" in l]
    assert all(l["image"].any() for l in decoded)
    assert f["layer_data"][0]["exposure"] == pytest.approx(r.settings.bottom_exposure_s, rel=1e-4)
    assert sum(len(c.drains) for c in r.cavities) == 2
