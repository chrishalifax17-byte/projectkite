"""Printer / resin profiles and the print-settings recommender.

Exposure scaling uses the Jacobs working curve that governs photopolymer
cure depth:

    Cd = Dp * ln(E / Ec)      ->      E(h) = E_ref * exp((h - h_ref) / Dp)

so if a resin is known to cure well at `h_ref` with exposure `E_ref`, the
time needed for another layer height follows from its penetration depth Dp.
The built-in base exposures are *starting points* for typical mono-LCD
printers; for the best results print an exposure calibration test once and
pass the result with `calibrated_exposure` / `calibrated_layer`.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, asdict, field
from pathlib import Path


@dataclass
class Printer:
    name: str
    res_x: int
    res_y: int
    pixel_x_um: float
    pixel_y_um: float
    max_z_mm: float
    light_factor: float = 1.0   # relative exposure multiplier vs. a typical 4K mono printer
    supports_grayscale_aa: bool = True

    @property
    def width_mm(self):
        return self.res_x * self.pixel_x_um / 1000

    @property
    def depth_mm(self):
        return self.res_y * self.pixel_y_um / 1000

    @property
    def xy_res_um(self):
        return max(self.pixel_x_um, self.pixel_y_um)


@dataclass
class Resin:
    name: str
    base_exposure_s: float      # normal-layer exposure at 0.05 mm on a light_factor=1 printer
    bottom_exposure_s: float
    penetration_depth_mm: float  # Dp of the working curve
    density_g_ml: float = 1.10
    price_per_kg: float = 30.0
    min_lift_speed: float = 40.0  # mm/min; tough / flexible resins peel harder
    notes: str = ""


PRINTERS = {
    "generic-4k-6in": Printer("Generic 4K mono, 6.6\"", 3840, 2400, 35.0, 35.0, 165, 1.0),
    "generic-8k-10in": Printer("Generic 8K mono, 10\"", 7680, 4320, 28.5, 28.5, 250, 1.1),
    "generic-12k-10in": Printer("Generic 12K mono, 10\"", 11520, 5120, 19.0, 24.0, 260, 1.2),
    "generic-16k-10in": Printer("Generic 16K mono, 10\"", 15120, 6230, 14.0, 19.0, 220, 1.3),
}

RESINS = {
    "standard": Resin("Standard", 2.2, 25, 0.16, notes="General purpose; brittle when over-cured."),
    "abs-like": Resin("ABS-like", 2.6, 28, 0.15, notes="Tougher, good default for miniatures and parts."),
    "water-washable": Resin("Water washable", 2.3, 25, 0.15, 1.12,
                            notes="Rinse in water; cures slightly soft, avoid thin unsupported spans."),
    "plant-based": Resin("Plant-based", 2.8, 30, 0.14, notes="Lower odour; slower cure."),
    "high-detail-8k": Resin("High-detail (8K/12K)", 2.0, 22, 0.13, notes="Low-pigment-bleed detail resin."),
    "tough": Resin("Tough / engineering", 3.5, 35, 0.18, 1.12, 45.0, 30.0,
                   notes="Peels hard: slow lifts and heavier supports."),
    "flexible-blend": Resin("Flexible blend", 4.0, 40, 0.20, 1.10, 50.0, 30.0,
                            notes="Mix with standard resin; expect soft supports."),
}

SUPPORT_PRESETS = {
    "light":  dict(tip_diameter=0.30, shaft_diameter=0.70, spacing=2.6, tip_length=2.0, base_diameter=2.0),
    "medium": dict(tip_diameter=0.40, shaft_diameter=1.00, spacing=2.2, tip_length=2.5, base_diameter=2.6),
    "heavy":  dict(tip_diameter=0.55, shaft_diameter=1.50, spacing=1.8, tip_length=3.0, base_diameter=3.4),
}


def load_custom(path, cls):
    data = json.loads(Path(path).read_text())
    return cls(**data)


def get_printer(name_or_path):
    if name_or_path in PRINTERS:
        return PRINTERS[name_or_path]
    return load_custom(name_or_path, Printer)


def get_resin(name_or_path):
    if name_or_path in RESINS:
        return RESINS[name_or_path]
    return load_custom(name_or_path, Resin)


@dataclass
class PrintSettings:
    layer_height_mm: float
    exposure_s: float
    bottom_exposure_s: float
    bottom_layers: int
    transition_layers: int
    lift_distance_mm: float
    lift_speed_mm_min: float
    retract_speed_mm_min: float
    rest_before_exposure_s: float
    anti_aliasing: int
    support_preset: str
    lift_height_mm: float           # gap between raft and the model's lowest point
    tilt_note: str = ""
    reasons: list = field(default_factory=list)

    def to_dict(self):
        return asdict(self)


def exposure_for_layer(base_at_50um, layer_h, dp):
    return base_at_50um * math.exp((layer_h - 0.05) / dp)


def recommend_layer_height(printer: Printer, model_extents, detail="auto"):
    if detail == "draft":
        return 0.10, "draft quality requested"
    if detail == "high":
        return (0.03 if printer.xy_res_um <= 30 else 0.035), "high detail requested"
    if detail == "normal":
        return 0.05, "normal detail requested"
    size = max(model_extents)
    if size <= 60:
        if printer.xy_res_um <= 30:
            return 0.03, f"small model ({size:.0f} mm) on a fine-pixel screen: thin layers hide layer lines"
        return 0.035, f"small model ({size:.0f} mm): thin layers for detail"
    if size <= 150:
        return 0.05, f"medium model ({size:.0f} mm): 0.05 mm balances detail and print time"
    return 0.05, f"large model ({size:.0f} mm): 0.05 mm keeps time reasonable without stepping"


def recommend_support_preset(model_volume_ml, resin: Resin):
    if model_volume_ml > 60 or resin.min_lift_speed > 40:
        return "heavy", f"model is {model_volume_ml:.0f} ml / {resin.name} peels hard"
    if model_volume_ml > 12:
        return "medium", f"model volume {model_volume_ml:.0f} ml"
    return "light", f"small model ({model_volume_ml:.1f} ml): light tips leave the smallest marks"


def recommend(printer: Printer, resin: Resin, model_extents, model_volume_ml,
              layer_height=None, detail="auto", support_preset=None,
              calibrated_exposure=None, calibrated_layer=0.05):
    reasons = []
    if layer_height is None:
        layer_height, why = recommend_layer_height(printer, model_extents, detail)
        reasons.append(f"Layer height {layer_height} mm: {why}.")
    else:
        reasons.append(f"Layer height {layer_height} mm: user specified.")

    if calibrated_exposure:
        base = calibrated_exposure * math.exp((0.05 - calibrated_layer) / resin.penetration_depth_mm)
        reasons.append(f"Exposure derived from your calibration ({calibrated_exposure}s @ {calibrated_layer} mm).")
    else:
        base = resin.base_exposure_s * printer.light_factor
        reasons.append("Exposure is a starting point from the resin profile - run one exposure "
                       "calibration print and pass --calibrated-exposure for best accuracy.")
    exposure = exposure_for_layer(base, layer_height, resin.penetration_depth_mm)
    reasons.append(f"Exposure scaled with the resin working curve (Dp={resin.penetration_depth_mm} mm).")
    bottom = resin.bottom_exposure_s * printer.light_factor * (exposure / base) ** 0.5
    bottom = max(bottom, exposure * 8)

    if support_preset is None:
        support_preset, why = recommend_support_preset(model_volume_ml, resin)
        reasons.append(f"Support preset '{support_preset}': {why}.")

    # Bottom layers: ~0.2 mm of burn-in is plenty; more just makes the raft hard to remove.
    bottom_layers = int(min(8, max(3, round(0.2 / layer_height))))
    transition = int(min(10, max(4, round(0.3 / layer_height))))
    aa = 4 if (printer.supports_grayscale_aa and max(model_extents) <= 80) else 2
    if not printer.supports_grayscale_aa:
        aa = 1

    s = PrintSettings(
        layer_height_mm=layer_height,
        exposure_s=round(exposure, 2),
        bottom_exposure_s=round(bottom, 1),
        bottom_layers=bottom_layers,
        transition_layers=transition,
        lift_distance_mm=6.0,
        lift_speed_mm_min=120.0,
        retract_speed_mm_min=180.0,
        rest_before_exposure_s=0.5,
        anti_aliasing=aa,
        support_preset=support_preset,
        lift_height_mm=5.0 if max(model_extents) < 100 else 7.0,
        reasons=reasons,
    )
    s._resin = resin
    return s


def tune_for_peel(settings: PrintSettings, max_area_mm2, resin: Resin):
    """Adjust lift settings to the largest cross-section actually being printed.

    Peel (suction) force grows with the cured area per layer, so large
    layers need a slower, longer lift and a longer rest for resin to flow back.
    """
    a = max_area_mm2
    speed = max(resin.min_lift_speed, min(180.0, 180.0 - 0.045 * a))
    dist = min(10.0, 5.0 + a / 1500.0)
    rest = min(3.0, 0.5 + a / 2500.0)
    retract = min(210.0, max(120.0, 210.0 - 0.03 * a))
    settings.lift_speed_mm_min = round(speed)
    settings.lift_distance_mm = round(dist, 1)
    settings.rest_before_exposure_s = round(rest, 1)
    settings.retract_speed_mm_min = round(retract)
    settings.reasons.append(
        f"Largest layer is {a:.0f} mm^2: lift {settings.lift_distance_mm} mm at "
        f"{settings.lift_speed_mm_min} mm/min, rest {settings.rest_before_exposure_s}s to limit peel force.")
    if a > 3000:
        settings.reasons.append("Very large layer area - consider hollowing or tilting the model further.")
    return settings


def estimate_time_s(settings: PrintSettings, n_layers):
    move = settings.lift_distance_mm * 60 / settings.lift_speed_mm_min + \
        settings.lift_distance_mm * 60 / settings.retract_speed_mm_min
    per = move + settings.rest_before_exposure_s
    nb = min(n_layers, settings.bottom_layers)
    return nb * (settings.bottom_exposure_s + per) + (n_layers - nb) * (settings.exposure_s + per)
