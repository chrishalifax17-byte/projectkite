"""Layer image slicing and output files (SL1-style archive, STL, preview)."""
from __future__ import annotations

import base64
import io
import json
import math
import multiprocessing as mp
import os
import shutil
import time
import zipfile

import numpy as np
from PIL import Image

from . import goo
from .raster import LayerSlicer, rasterize_bodies


def layer_count(height_mm, layer_h):
    return int(math.ceil(height_mm / layer_h - 1e-9))


def layer_areas(bodies, layer_h, pitch):
    """Cured area (mm^2) of each layer at a coarse resolution."""
    lo = np.min([b.bounds[0] for b in bodies], axis=0)
    hi = np.max([b.bounds[1] for b in bodies], axis=0)
    w = int(math.ceil((hi[0] - lo[0]) / pitch)) + 2
    h = int(math.ceil((hi[1] - lo[1]) / pitch)) + 2
    slicers = [LayerSlicer(b) for b in bodies]
    n = layer_count(hi[2], layer_h)
    areas = np.zeros(n)
    for i in range(n):
        z = (i + 0.5) * layer_h
        if z < lo[2]:
            continue
        m = rasterize_bodies(slicers, z, lo[0] - pitch, lo[1] - pitch, pitch, pitch, w, h)
        areas[i] = m.sum() * pitch * pitch
    return areas


# ------------------------------------------------------------------ layer rendering
# Layers are independent, so they are rendered in worker processes. State is
# put in a module global *before* the pool forks so children inherit it
# without pickling the meshes.
_G = {}


def _frame_geometry(bodies, printer):
    px, py = printer.pixel_x_um / 1000, printer.pixel_y_um / 1000
    W, H = printer.res_x, printer.res_y
    x0, y0 = -W * px / 2, -H * py / 2
    lo = np.min([b.bounds[0] for b in bodies], axis=0)
    hi = np.max([b.bounds[1] for b in bodies], axis=0)
    c0 = max(0, int(math.floor((lo[0] - x0) / px)) - 1)
    c1 = min(W, int(math.ceil((hi[0] - x0) / px)) + 1)
    r0 = max(0, int(math.floor((lo[1] - y0) / py)) - 1)
    r1 = min(H, int(math.ceil((hi[1] - y0) / py)) + 1)
    block = max(1, int(math.ceil(max(c1 - c0, r1 - r0) / 290)))
    return dict(px=px, py=py, W=W, H=H, x0=x0, y0=y0, c0=c0, c1=c1, r0=r0, r1=r1,
                top=float(hi[2]), block=block)


def _render_layer(i):
    g = _G
    z = (i + 0.5) * g["lh"]
    win = rasterize_bodies(g["slicers"], z, g["x0"] + g["c0"] * g["px"], g["y0"] + g["r0"] * g["py"],
                           g["px"], g["py"], g["c1"] - g["c0"], g["r1"] - g["r0"], supersample=g["aa"])
    if win.dtype != np.uint8:
        win = win.astype(np.uint8) * 255
    win = win[::-1]  # image row 0 is the far (max Y) edge of the screen
    area = float(win.sum(dtype=np.int64)) / 255.0 * g["px"] * g["py"]
    bsz = g["block"]
    h, w = win.shape
    hp, wp = -(-h // bsz) * bsz, -(-w // bsz) * bsz
    occ = np.zeros((hp, wp), bool)
    occ[:h, :w] = win > 0
    occ = occ.reshape(hp // bsz, bsz, wp // bsz, bsz).any(axis=(1, 3))
    if g["fmt"] == "goo":
        lengths, values = goo.runs_from_window(win, g["W"], g["H"], g["c0"], g["H"] - g["r1"])
        payload = goo.encode_runs(lengths, values)
    else:
        frame = np.zeros((g["H"], g["W"]), np.uint8)
        frame[g["H"] - g["r1"]:g["H"] - g["r0"], g["c0"]:g["c1"]] = win
        buf = io.BytesIO()
        Image.fromarray(frame).save(buf, format="PNG", compress_level=1)
        payload = buf.getvalue()
    return i, payload, area, occ


def render_layers(bodies, printer, layer_h, aa, fmt, workers=None):
    """Yield (index, payload, area_mm2, preview_occupancy) for each layer in order."""
    geom = _frame_geometry(bodies, printer)
    n = layer_count(geom["top"], layer_h)
    _G.clear()
    _G.update(geom, slicers=[LayerSlicer(b) for b in bodies], lh=layer_h, aa=max(1, int(aa)), fmt=fmt)
    workers = workers or max(1, min(8, (os.cpu_count() or 2)))
    try:
        if workers > 1 and n > 8:
            ctx = mp.get_context("fork")
            with ctx.Pool(workers) as pool:
                yield from pool.imap(_render_layer, range(n), chunksize=2)
        else:
            for i in range(n):
                yield _render_layer(i)
    finally:
        _G.clear()


def _heightmap_add(hmap, occ, i):
    if hmap is None:
        hmap = np.zeros(occ.shape, np.int32)
    hmap[occ] = i + 1
    return hmap


def slice_to_goo(path, bodies, printer, settings, resin, resin_ml, progress=None, workers=None):
    """Write a native Elegoo .goo file. Returns (n_layers, areas, print_time_s)."""
    from .settings import peel_motion, global_motion, layer_exposure, layer_motion_time
    lh = settings.layer_height_mm
    geom = _frame_geometry(bodies, printer)
    n = layer_count(geom["top"], lh)
    g = global_motion(settings)

    def params(i, area):
        bottom = i < settings.bottom_layers
        m = g
        if settings.adaptive_lift and not bottom:
            m = peel_motion(area, resin, printer)
        lp = goo.LayerParams(z=round((i + 1) * lh, 5), exposure=layer_exposure(settings, i),
                             lift_distance=m["lift_distance"], lift_speed=m["lift_speed"],
                             second_lift_distance=m["second_lift_distance"],
                             second_lift_speed=m["second_lift_speed"],
                             retract_distance=m["retract_distance"], retract_speed=m["retract_speed"],
                             second_retract_distance=m["second_retract_distance"],
                             second_retract_speed=m["second_retract_speed"],
                             after_retract=m["rest"])
        if bottom:  # bottom layers peel off the plate-bonded raft: always slow
            lp.lift_speed = min(lp.lift_speed, 60.0)
            lp.second_lift_speed = min(lp.second_lift_speed, 150.0)
        return lp, m

    areas = np.zeros(n)
    hmap = None
    total_time = 0.0
    tmp = str(path) + ".layers"
    with open(tmp, "wb") as f:
        for i, rle, area, occ in render_layers(bodies, printer, lh, settings.anti_aliasing, "goo", workers):
            areas[i] = area
            hmap = _heightmap_add(hmap, occ, i)
            lp, m = params(i, area)
            total_time += layer_motion_time(m, lp.exposure)
            f.write(goo.layer_bytes(lp, rle, pause_z=printer.max_z_mm))
            if progress:
                progress(i + 1, n)
    hm = hmap if hmap is not None else np.zeros((4, 4), np.int32)
    b0, _ = params(0, 0.0)
    nrm, _ = params(settings.bottom_layers, 0.0)
    nrm.lift_distance, nrm.lift_speed = g["lift_distance"], g["lift_speed"]
    header = goo.header_bytes(
        printer_name=printer.model_name or printer.name, printer_type="MSLA",
        profile_name=f"{resin.name} {lh}mm", aa=max(1, settings.anti_aliasing),
        res_x=printer.res_x, res_y=printer.res_y, size_x=printer.width_mm, size_y=printer.depth_mm,
        size_z=printer.max_z_mm, layer_h=lh, n_layers=n, exposure=settings.exposure_s,
        bottom_exposure=settings.bottom_exposure_s, bottom_layers=settings.bottom_layers,
        transition=settings.transition_layers, bottom=b0, normal=nrm, print_time=total_time,
        volume_mm3=resin_ml * 1000, weight_g=resin_ml * resin.density_g_ml,
        price=resin_ml * resin.density_g_ml / 1000 * resin.price_per_kg,
        small_preview=goo.preview_from_heightmap(hm, goo.SMALL_PREVIEW, printer.pixel_x_um / printer.pixel_y_um),
        big_preview=goo.preview_from_heightmap(hm, goo.BIG_PREVIEW, printer.pixel_x_um / printer.pixel_y_um))
    with open(path, "wb") as out, open(tmp, "rb") as f:
        out.write(header)
        shutil.copyfileobj(f, out, 1 << 22)
        out.write(goo.ENDING)
    os.remove(tmp)
    return n, areas, total_time


def slice_to_sl1(path, bodies, printer, settings, job_name, resin_ml, print_time_s,
                 resin_name, progress=None, workers=None):
    """Write an SL1-style zip: one grayscale PNG per layer plus config files.

    Use UVtools to convert it to other printer formats (.ctb, .pwmx, ...).
    """
    lh = settings.layer_height_mm
    px, py = printer.pixel_x_um / 1000, printer.pixel_y_um / 1000
    W, H = printer.res_x, printer.res_y
    n = layer_count(_frame_geometry(bodies, printer)["top"], lh)
    areas = np.zeros(n)
    with zipfile.ZipFile(path, "w") as zf:
        for i, png, area, _occ in render_layers(bodies, printer, lh, settings.anti_aliasing, "png", workers):
            areas[i] = area
            zf.writestr(f"{job_name}{i:05d}.png", png, compress_type=zipfile.ZIP_STORED)
            if progress:
                progress(i + 1, n)
        nb = settings.bottom_layers
        config = {
            "action": "print",
            "jobDir": job_name,
            "expTime": settings.exposure_s,
            "expTimeFirst": settings.bottom_exposure_s,
            "fileCreationTimestamp": time.strftime("%Y-%m-%d at %H:%M:%S UTC", time.gmtime()),
            "hollow": 0,
            "layerHeight": lh,
            "materialName": resin_name,
            "numFade": settings.transition_layers,
            "numFast": n,
            "numSlow": 0,
            "printProfile": f"resinslicer {lh}mm",
            "printTime": int(print_time_s),
            "printerModel": "SL1",
            "printerProfile": printer.name,
            "printerVariant": "default",
            "prusaSlicerVersion": "resinslicer",
            "usedMaterial": round(resin_ml, 2),
        }
        ini = {
            "display_pixels_x": W, "display_pixels_y": H,
            "display_width": round(W * px, 3), "display_height": round(H * py, 3),
            "display_orientation": "landscape", "display_mirror_x": 0, "display_mirror_y": 0,
            "layer_height": lh, "exposure_time": settings.exposure_s,
            "initial_exposure_time": settings.bottom_exposure_s,
            "faded_layers": settings.transition_layers,
            "bottom_layer_count": nb,
            "bottom_lift_distance": settings.lift_distance_mm,
            "lift_distance": settings.lift_distance_mm,
            "lift_speed": settings.lift_speed_mm_min,
            "retract_speed": settings.retract_speed_mm_min,
            "wait_time_before_cure": settings.rest_before_exposure_s,
            "max_print_height": printer.max_z_mm,
            "printer_technology": "SLA",
        }
        zf.writestr("config.ini", "".join(f"{k} = {v}\n" for k, v in config.items()))
        zf.writestr("prusaslicer.ini", "".join(f"{k} = {v}\n" for k, v in ini.items()))
    return n, areas


PREVIEW_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Resin Slicer Preview</title>
<style>
:root{--bg:#f4f5f7;--fg:#1d2228;--muted:#5b6470}
@media (prefers-color-scheme:dark){:root{--bg:#15181c;--fg:#e8eaed;--muted:#9aa3ad}}
html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif}
#info{position:absolute;top:12px;left:16px;right:16px;pointer-events:none}
#info b{font-size:15px} #info span{color:var(--muted)}
.k{display:inline-block;width:10px;height:10px;border-radius:2px;margin:0 4px 0 10px}
canvas{display:block}
</style></head><body>
<div id="info"><b>__TITLE__</b><br><span>__SUMMARY__</span><br>
<span><i class="k" style="background:#b8bec6"></i>model<i class="k" style="background:#2fa4a9"></i>supports
<i class="k" style="background:#e5484d"></i>drain holes</span></div>
<script type="importmap">{"imports":{"three":"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js",
"three/addons/":"https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/"}}</script>
<script type="module">
import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
const dark = matchMedia('(prefers-color-scheme: dark)').matches;
const renderer = new THREE.WebGLRenderer({antialias:true});
renderer.setPixelRatio(devicePixelRatio); renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const scene = new THREE.Scene(); scene.background = new THREE.Color(dark?0x15181c:0xf4f5f7);
const cam = new THREE.PerspectiveCamera(40, innerWidth/innerHeight, 0.1, 5000);
scene.add(new THREE.HemisphereLight(0xffffff, 0x445566, 1.6));
const dl = new THREE.DirectionalLight(0xffffff, 1.4); dl.position.set(1,2,3); scene.add(dl);
const controls = new OrbitControls(cam, renderer.domElement);
const data = Uint8Array.from(atob("__GLB__"), c => c.charCodeAt(0)).buffer;
new GLTFLoader().parse(data, '', g => {
  const colors = {model:0xb8bec6, supports:0x2fa4a9, drains:0xe5484d};
  g.scene.traverse(o => { if (o.isMesh) { const key = Object.keys(colors).find(k => o.name.includes(k)) || 'model';
    o.material = new THREE.MeshStandardMaterial({color:colors[key], roughness:.6, metalness:0,
      side:THREE.DoubleSide}); }});
  // slicer Z-up -> three.js Y-up
  g.scene.rotation.x = -Math.PI/2; scene.add(g.scene);
  const box = new THREE.Box3().setFromObject(g.scene), c = box.getCenter(new THREE.Vector3());
  const r = box.getSize(new THREE.Vector3()).length();
  const grid = new THREE.GridHelper(r*1.5, 20, dark?0x445:0xaab, dark?0x2a2f35:0xd5d9de); grid.position.y = box.min.y; scene.add(grid);
  controls.target.copy(c); cam.position.set(c.x + r*0.8, c.y + r*0.5, c.z + r*0.9); controls.update();
});
addEventListener('resize', () => { cam.aspect = innerWidth/innerHeight; cam.updateProjectionMatrix(); renderer.setSize(innerWidth, innerHeight); });
renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, cam); });
</script></body></html>"""


def write_preview(path, model, supports, drain_meshes, title, summary):
    import trimesh
    scene = trimesh.Scene()
    scene.add_geometry(model, node_name="model", geom_name="model")
    if supports is not None and len(supports.faces):
        scene.add_geometry(supports, node_name="supports", geom_name="supports")
    if drain_meshes:
        scene.add_geometry(trimesh.util.concatenate(drain_meshes), node_name="drains", geom_name="drains")
    glb = scene.export(file_type="glb")
    html = (PREVIEW_HTML.replace("__GLB__", base64.b64encode(glb).decode())
            .replace("__TITLE__", title).replace("__SUMMARY__", summary))
    with open(path, "w") as f:
        f.write(html)


def write_json(path, data):
    def default(o):
        if isinstance(o, np.ndarray):
            return o.tolist()
        if isinstance(o, (np.floating, np.integer)):
            return o.item()
        raise TypeError(type(o))
    with open(path, "w") as f:
        json.dump(data, f, indent=2, default=default)
