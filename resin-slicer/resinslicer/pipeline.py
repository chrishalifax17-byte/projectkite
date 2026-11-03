"""End-to-end pipeline: analyse -> drill -> orient -> support -> settings -> slice."""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import trimesh

from . import pegholes, orient, supports as sup, settings as st, export
from .meshutil import load_mesh, is_solid


@dataclass
class Options:
    printer: str = "generic-8k-10in"
    resin: str = "abs-like"
    layer_height: float | None = None
    detail: str = "auto"
    support_preset: str | None = None
    auto_orient: bool = True
    rotate_deg: tuple | None = None          # manual (x, y, z) rotation in degrees
    drill: str = "cups"                     # "cups", "all" or "none"
    drain_diameter: float = 1.2
    void_drain_diameter: float = 2.5
    drain_direction: str = "axis"            # "axis" or "shortest"
    max_hole_radius: float = 4.0
    calibrated_exposure: float | None = None
    calibrated_layer: float = 0.05
    slice: bool = True
    preview: bool = True
    scale: float = 1.0


@dataclass
class Result:
    model: trimesh.Trimesh
    supports: sup.SupportResult
    cavities: list
    settings: st.PrintSettings
    report: dict = field(default_factory=dict)
    files: dict = field(default_factory=dict)


def _transform_cavities(cavities, T):
    R = T[:3, :3]
    for c in cavities:
        pts = lambda p: None if p is None else trimesh.transform_points([p], T)[0]
        c.centroid = pts(c.centroid)
        c.opening_center = pts(c.opening_center)
        c.bottom_point = pts(c.bottom_point)
        if c.axis is not None:
            c.axis = R @ c.axis
        c.drains = [(pts(a), pts(b)) for a, b in c.drains]


def _drain_markers(cavities, opts):
    out = []
    for c in cavities:
        d = opts.void_drain_diameter if c.kind == "sealed_void" else opts.drain_diameter
        for a, b in c.drains:
            u = (b - a) / np.linalg.norm(b - a)
            out.append(trimesh.creation.cylinder(radius=d / 2 + 0.1, segment=[a, b + u * 0.8], sections=16))
    return out


def run(input_path, out_dir, opts: Options = Options(), log=print):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    name = Path(input_path).stem
    printer = st.get_printer(opts.printer)
    resin = st.get_resin(opts.resin)
    report = {"input": str(input_path), "printer": printer.name, "resin": resin.name, "warnings": []}

    model = load_mesh(input_path)
    if opts.scale != 1.0:
        model.apply_scale(opts.scale)
    if not is_solid(model):
        report["warnings"].append("Mesh is not watertight; results may be unreliable. Repair it first "
                                  "(e.g. Meshmixer / PrusaSlicer 'fix model').")
    log(f"Loaded {name}: {len(model.faces)} triangles, {model.extents.round(1)} mm, "
        f"{model.volume / 1000:.1f} ml")

    # 1. Peg holes and sealed voids (detected in the original orientation).
    log("Scanning for peg holes and sealed voids...")
    cavities = pegholes.find_cavities(model, max_radius=opts.max_hole_radius)
    log(f"  found {sum(c.kind == 'blind_hole' for c in cavities)} blind holes, "
        f"{sum(c.kind == 'sealed_void' for c in cavities)} sealed voids")

    # 2. Orientation (cups facing the plate are penalised).
    if opts.rotate_deg is not None:
        T = trimesh.transformations.euler_matrix(*np.radians(opts.rotate_deg), "sxyz")
        report["orientation"] = {"mode": "manual", "rotate_deg": list(opts.rotate_deg)}
    elif opts.auto_orient:
        log("Searching for the best orientation...")
        T, info = orient.best_orientation(model, cavities)
        report["orientation"] = {"mode": "auto", **info}
        log(f"  tilt {info['tilt_deg']:.0f} deg, peak layer area {info['section']:.0f} mm^2 "
            f"(was {info['baseline']['section']:.0f})")
    else:
        T = np.eye(4)
        report["orientation"] = {"mode": "as-is"}
    model.apply_transform(T)
    _transform_cavities(cavities, T)

    # 3. Drain holes: vent every sealed void, and every blind hole that acts as a
    #    resin cup in this orientation (or all blind holes with drill="all").
    to_drill = []
    for c in cavities:
        c.cup = c.kind == "sealed_void" or pegholes.is_cup(c)
        if opts.drill == "all" or (opts.drill == "cups" and c.cup):
            pegholes.plan_drains(model, c, prefer=opts.drain_direction)
            to_drill.append(c)
            if not c.drains:
                report["warnings"].append(f"Could not find a short drill path for {c.kind} at "
                                          f"{np.round(c.centroid, 1).tolist()}; vent it manually.")
        elif c.cup:
            report["warnings"].append(f"{c.kind} at {np.round(c.centroid, 1).tolist()} traps resin in this "
                                      "orientation but drilling is disabled.")
    n_cups = sum(c.cup for c in cavities)
    log(f"  {n_cups} of {len(cavities)} cavities would trap resin / form suction cups in this orientation")
    if to_drill:
        try:
            model = pegholes.drill(model, to_drill, opts.drain_diameter, opts.void_drain_diameter)
            log(f"  drilled {sum(len(c.drains) for c in to_drill)} drain holes")
        except ValueError as e:
            report["warnings"].append(str(e))
            log(f"  ! {e}")
            for c in to_drill:
                c.drains = []

    # 4. Settings.
    s = st.recommend(printer, resin, model.extents, model.volume / 1000, opts.layer_height,
                     opts.detail, opts.support_preset, opts.calibrated_exposure, opts.calibrated_layer)
    cfg = sup.SupportConfig.from_preset(st.SUPPORT_PRESETS[s.support_preset])

    # 5. Placement and supports.
    lo_before = model.bounds[0].copy()
    model = sup.place_on_plate(model, s.lift_height_mm, cfg.raft_thickness)
    shift = model.bounds[0] - lo_before
    _transform_cavities(cavities, trimesh.transformations.translation_matrix(shift))
    exclusions = [(c.opening_center, c.bottom_point + c.axis * 0.5, c.radius + 0.3)
                  for c in cavities if c.kind == "blind_hole" and c.radius < 2.5]
    log(f"Generating '{s.support_preset}' supports...")
    sres = sup.generate_supports(model, cfg, exclusions=exclusions)
    log(f"  {len(sres.supports)} supports ({sum(x.on_model for x in sres.supports)} land on the model), "
        f"{sres.islands_fixed} added for islands, {len(sres.islands_remaining)} islands remaining")
    if sres.islands_remaining:
        report["warnings"].append(f"{len(sres.islands_remaining)} layer islands could not be supported "
                                  "automatically; check them in the preview.")
    if sres.skipped:
        report["warnings"].append(f"{sres.skipped} candidate support points were skipped "
                                  "(blocked by the model or too short).")

    bodies = [model] + ([sres.mesh] if sres.mesh is not None and len(sres.mesh.faces) else [])
    hi = np.max([b.bounds[1] for b in bodies], axis=0)
    lo = np.min([b.bounds[0] for b in bodies], axis=0)
    if hi[0] - lo[0] > printer.width_mm or hi[1] - lo[1] > printer.depth_mm or hi[2] > printer.max_z_mm:
        report["warnings"].append("Model plus supports exceeds the printer's build volume.")

    # 6. Peel-aware tuning from real layer areas.
    areas = export.layer_areas(bodies, s.layer_height_mm, pitch=float(np.clip(max(hi - lo) / 300, 0.1, 0.5)))
    st.tune_for_peel(s, float(areas.max()), resin)
    n_layers = export.layer_count(hi[2], s.layer_height_mm)
    support_ml = (sres.mesh.volume / 1000) if sres.mesh is not None and len(sres.mesh.faces) else 0.0
    resin_ml = model.volume / 1000 + support_ml
    t = st.estimate_time_s(s, n_layers)
    report.update({
        "settings": s.to_dict(),
        "estimates": {
            "layers": n_layers,
            "print_time": f"{int(t // 3600)}h {int(t % 3600 // 60)}m",
            "print_time_s": round(t),
            "resin_ml": round(resin_ml, 1),
            "resin_g": round(resin_ml * resin.density_g_ml, 1),
            "resin_cost": round(resin_ml * resin.density_g_ml / 1000 * resin.price_per_kg, 2),
            "model_ml": round(model.volume / 1000, 2),
            "support_ml": round(support_ml, 2),
            "peak_layer_area_mm2": round(float(areas.max()), 1),
        },
        "cavities": [c.to_dict() for c in cavities],
        "supports": {
            "count": len(sres.supports),
            "landing_on_model": sum(x.on_model for x in sres.supports),
            "island_fixes": sres.islands_fixed,
            "islands_remaining": [np.round(p, 2).tolist() for p in sres.islands_remaining],
            "config": cfg.__dict__,
        },
    })
    if model.volume / 1000 > 40:
        report["warnings"].append("Solid model over 40 ml: hollowing it (2-3 mm walls) would save resin "
                                  "and reduce peel force; re-run afterwards to drill the drain holes.")

    # 7. Outputs.
    files = {}
    combined = trimesh.util.concatenate(bodies)
    files["stl"] = out / f"{name}_supported.stl"
    combined.export(files["stl"])
    if opts.preview:
        files["preview"] = out / f"{name}_preview.html"
        summary = (f"{sum(len(c.drains) for c in cavities)} drain holes &middot; {len(sres.supports)} supports &middot; "
                   f"{s.layer_height_mm} mm layers &middot; {s.exposure_s}s exposure &middot; "
                   f"{report['estimates']['print_time']}")
        export.write_preview(files["preview"], model, sres.mesh, _drain_markers(cavities, opts), name, summary)
    if opts.slice:
        files["sl1"] = out / f"{name}.sl1"
        last = [0]

        def progress(i, n):
            pct = int(100 * i / n)
            if pct >= last[0] + 10 or i == n:
                last[0] = pct
                log(f"  slicing {pct}%")
        log(f"Slicing {n_layers} layers at {printer.res_x}x{printer.res_y}, AA x{s.anti_aliasing}...")
        export.slice_to_sl1(files["sl1"], bodies, printer, s, name, resin_ml, t, resin.name, progress)
    files["report"] = out / f"{name}_settings.json"
    report["files"] = {k: str(v) for k, v in files.items()}
    export.write_json(files["report"], report)
    return Result(model, sres, cavities, s, report, files)


def format_summary(r: Result) -> str:
    s, e = r.settings, r.report["estimates"]
    lines = [
        "",
        "Recommended settings",
        f"  Layer height         {s.layer_height_mm} mm",
        f"  Exposure             {s.exposure_s} s",
        f"  Bottom exposure      {s.bottom_exposure_s} s x {s.bottom_layers} layers "
        f"(+{s.transition_layers} transition)",
        f"  Lift                 {s.lift_distance_mm} mm @ {s.lift_speed_mm_min} mm/min, "
        f"retract {s.retract_speed_mm_min} mm/min",
        f"  Rest before exposure {s.rest_before_exposure_s} s",
        f"  Anti-aliasing        x{s.anti_aliasing}",
        f"  Supports             {s.support_preset}, model lifted {s.lift_height_mm} mm",
        "",
        f"Estimate: {e['layers']} layers, {e['print_time']}, {e['resin_ml']} ml resin "
        f"(~{e['resin_g']} g, ~${e['resin_cost']})",
        "",
        "Why:",
    ] + [f"  - {x}" for x in s.reasons]
    if r.report["warnings"]:
        lines += ["", "Warnings:"] + [f"  ! {w}" for w in r.report["warnings"]]
    lines += ["", "Files:"] + [f"  {k:8} {v}" for k, v in r.report["files"].items()]
    return "\n".join(lines)
