"""Layer image slicing and output files (SL1-style archive, STL, preview)."""
from __future__ import annotations

import base64
import io
import json
import math
import time
import zipfile

import numpy as np
from PIL import Image

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


def slice_to_sl1(path, bodies, printer, settings, job_name, resin_ml, print_time_s,
                 resin_name, progress=None):
    """Write an SL1-style zip: one grayscale PNG per layer plus config files.

    The plate is centred on the screen. Use UVtools to convert the archive to
    a printer-specific format (.ctb, .goo, .pwmx, ...).
    """
    px = printer.pixel_x_um / 1000
    py = printer.pixel_y_um / 1000
    W, H = printer.res_x, printer.res_y
    x0, y0 = -W * px / 2, -H * py / 2
    lo = np.min([b.bounds[0] for b in bodies], axis=0)
    hi = np.max([b.bounds[1] for b in bodies], axis=0)
    # Only rasterize the occupied window, then paste into the full frame.
    c0 = max(0, int(math.floor((lo[0] - x0) / px)) - 1)
    c1 = min(W, int(math.ceil((hi[0] - x0) / px)) + 1)
    r0 = max(0, int(math.floor((lo[1] - y0) / py)) - 1)
    r1 = min(H, int(math.ceil((hi[1] - y0) / py)) + 1)
    slicers = [LayerSlicer(b) for b in bodies]
    lh = settings.layer_height_mm
    n = layer_count(hi[2], lh)
    aa = max(1, int(settings.anti_aliasing))
    areas = np.zeros(n)
    with zipfile.ZipFile(path, "w") as zf:
        for i in range(n):
            z = (i + 0.5) * lh
            win = rasterize_bodies(slicers, z, x0 + c0 * px, y0 + r0 * py, px, py,
                                   c1 - c0, r1 - r0, supersample=aa)
            frame = np.zeros((H, W), np.uint8)
            frame[r0:r1, c0:c1] = win if win.dtype == np.uint8 else win.astype(np.uint8) * 255
            areas[i] = frame.sum() / 255.0 * px * py
            buf = io.BytesIO()
            Image.fromarray(frame[::-1]).save(buf, format="PNG", compress_level=3)
            zf.writestr(f"{job_name}{i:05d}.png", buf.getvalue(), compress_type=zipfile.ZIP_STORED)
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
