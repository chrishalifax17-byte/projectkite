# resinslicer

Resin (MSLA) slicer built for the **ELEGOO Saturn 4** family. It writes
native `.goo` files you can copy to a USB stick and print, and it:

- **finds peg holes, magnet sockets and sealed voids** and drills drain/vent
  holes into the ones that would trap resin or act as suction cups in the
  chosen orientation (`--drill all` vents every peg hole),
- **picks an orientation** that minimises peel area, overhangs and resin cups,
- **generates supports** (tips, shafts, braces, raft), then slices the result
  and adds supports for any island that would still print in mid-air,
- **recommends settings**: layer height, exposure (scaled with the resin's
  working curve), bottom/transition layers, two-stage lift and retract, and
  **per-layer lift speeds** from each layer's real area (stored in the .goo).

## Usage

```
pip install -r requirements.txt
python examples/make_test_models.py examples
python -m resinslicer profiles
python -m resinslicer slice my_model.stl -o out                       # Saturn 4 (default)
python -m resinslicer slice my_model.stl -o out --printer saturn-4-ultra --resin standard
python -m resinslicer analyze my_model.stl -o out                     # everything except layer images
```

Outputs: `<name>.goo` (print file), `<name>_supported.stl` (open in ChituBox /
Lychee if you want to tweak supports), `<name>_preview.html` (3D view) and
`<name>_settings.json` (settings with the reasoning behind each value).

Useful options: `--calibrated-exposure 2.4 --calibrated-layer 0.05` (from your
own exposure test - strongly recommended), `--supports light|medium|heavy`,
`--layer-height`, `--rotate X Y Z` / `--no-auto-orient`, `--drain-diameter`,
`--drain-direction axis|shortest`, `--format sl1` for other printers (convert
with UVtools).

## Printers

| profile | screen | pixel | build volume |
|---|---|---|---|
| `saturn-4` | 11520 x 5120 | 19 x 24 um | 218.88 x 122.88 x 220 mm |
| `saturn-4-ultra` | 11520 x 5120 | 19 x 24 um | 218.88 x 122.88 x 220 mm (tilt vat) |
| `saturn-4-ultra-16k` | 15120 x 6230 | 14 x 19 um | 211.68 x 118.37 x 220 mm |

Other printers: pass a JSON file with the `Printer` fields to `--printer`.

## .goo format

`resinslicer/goo.py` is written from Elegoo's public spec
(https://github.com/elegooofficial/GOO, V1.2) and cross-checked against the
open-source mslicer reader. It is verified by round-trip tests and the spec's
own encoding examples, **but has not yet been printed on real hardware**:
before a long print, slice a small model, open it in UVtools or mslicer to
confirm it reads, and do a short test print. Images are not mirrored
(mirror flags 0); if text comes out reversed, report it.

## Tests

```
python -m pytest -q tests
```
