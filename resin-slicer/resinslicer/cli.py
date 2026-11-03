"""Command line interface: `resinslicer slice|analyze|profiles`."""
from __future__ import annotations

import argparse
import sys

from . import settings as st
from .pipeline import Options, run, format_summary


def _add_common(p):
    p.add_argument("model", help="STL / OBJ / 3MF / PLY file")
    p.add_argument("-o", "--out", default="out", help="output directory (default: out)")
    p.add_argument("--printer", default="generic-8k-10in", help="printer profile name or JSON file")
    p.add_argument("--resin", default="abs-like", help="resin profile name or JSON file")
    p.add_argument("--layer-height", type=float, help="override recommended layer height (mm)")
    p.add_argument("--detail", choices=["auto", "high", "normal", "draft"], default="auto")
    p.add_argument("--supports", choices=list(st.SUPPORT_PRESETS), help="override support preset")
    p.add_argument("--no-auto-orient", action="store_true", help="keep the model's orientation")
    p.add_argument("--rotate", type=float, nargs=3, metavar=("X", "Y", "Z"),
                   help="manual rotation in degrees (disables auto-orient)")
    p.add_argument("--drill", choices=["cups", "all", "none"], default="cups",
                   help="cups: vent peg holes that trap resin in the final orientation (+ sealed voids); "
                        "all: vent every peg hole; none: report only")
    p.add_argument("--drain-diameter", type=float, default=1.2, help="peg-hole vent diameter (mm)")
    p.add_argument("--void-drain-diameter", type=float, default=2.5, help="sealed-void drain diameter (mm)")
    p.add_argument("--drain-direction", choices=["axis", "shortest"], default="axis",
                   help="drill straight through the hole floor, or along the thinnest wall")
    p.add_argument("--max-hole-radius", type=float, default=4.0,
                   help="largest peg-hole radius to treat as a blind hole (mm)")
    p.add_argument("--calibrated-exposure", type=float,
                   help="exposure (s) that printed perfectly in your calibration test")
    p.add_argument("--calibrated-layer", type=float, default=0.05,
                   help="layer height (mm) used for that calibration test")
    p.add_argument("--scale", type=float, default=1.0)
    p.add_argument("--no-preview", action="store_true")


def _opts(a, slice_layers):
    return Options(
        printer=a.printer, resin=a.resin, layer_height=a.layer_height, detail=a.detail,
        support_preset=a.supports, auto_orient=not a.no_auto_orient, rotate_deg=a.rotate,
        drill=a.drill, drain_diameter=a.drain_diameter, void_drain_diameter=a.void_drain_diameter,
        drain_direction=a.drain_direction, max_hole_radius=a.max_hole_radius,
        calibrated_exposure=a.calibrated_exposure, calibrated_layer=a.calibrated_layer,
        slice=slice_layers, preview=not a.no_preview, scale=a.scale)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="resinslicer", description="Resin (MSLA) slicer with automatic "
                                 "supports, peg-hole drain drilling and recommended print settings.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p_slice = sub.add_parser("slice", help="full pipeline including layer images (.sl1)")
    _add_common(p_slice)
    p_an = sub.add_parser("analyze", help="drill, orient, support and recommend settings; no layer images")
    _add_common(p_an)
    sub.add_parser("profiles", help="list printer and resin profiles")
    a = ap.parse_args(argv)

    if a.cmd == "profiles":
        print("Printers:")
        for k, p in st.PRINTERS.items():
            print(f"  {k:18} {p.name}: {p.res_x}x{p.res_y}, {p.pixel_x_um}x{p.pixel_y_um} um, "
                  f"{p.width_mm:.0f}x{p.depth_mm:.0f}x{p.max_z_mm:.0f} mm")
        print("Resins:")
        for k, r in st.RESINS.items():
            print(f"  {k:18} {r.name}: {r.base_exposure_s}s @ 0.05 mm, Dp {r.penetration_depth_mm} mm. {r.notes}")
        print("\nCustom profiles: pass a JSON file with the same fields to --printer / --resin.")
        return 0

    result = run(a.model, a.out, _opts(a, a.cmd == "slice"))
    print(format_summary(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
