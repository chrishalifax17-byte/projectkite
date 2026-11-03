"""Elegoo .goo file format (V3.0) - native format of the Saturn 4 family.

Written from Elegoo's public specification:
https://github.com/elegooofficial/GOO (Goo Format Spec V1.2)

File layout (all multi-byte values big-endian):
    header (195477 bytes) | layer 1 | ... | layer N | ending string

Each layer is a 66-byte definition + delimiter, a u32 data size, then
0x55, the RLE image, an 8-bit checksum (bitwise NOT of the byte sum of the
RLE data) and a delimiter. The RLE chunks used here are:
    0b00LLxxxx  run of 0x00     0b11LLxxxx  run of 0xFF
    0b01LLxxxx  run of a grey value given in the next byte
where LL says how many extra length bytes follow (0-3); the run length is
those bytes (big-endian) followed by the low nibble of byte 0.
"""
from __future__ import annotations

import struct
import time
from dataclasses import dataclass

import numpy as np

MAGIC_TAG = bytes([0x07, 0x00, 0x00, 0x00, 0x44, 0x4C, 0x50, 0x00])
DELIMITER = b"\r\n"
ENDING = bytes([0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x44, 0x4C, 0x50, 0x00])
HEADER_SIZE = 0x2FB95
SMALL_PREVIEW = 116
BIG_PREVIEW = 290
MAX_RUN = 0xFFFFFFF


def _s(text, size):
    b = text.encode("utf-8")[: size - 1]
    return b + b"\0" * (size - len(b))


# --------------------------------------------------------------------------- RLE

def runs_from_window(win, frame_w, frame_h, c0, r0):
    """Run-lengths of a full frame that is zero outside `win`.

    `win` is a (h, w) uint8 array placed at column c0 / row r0 of a
    frame_w x frame_h image (row 0 = first row in the file). Pixels are
    ordered row-major. Returns (lengths int64, values uint8).
    """
    h, w = win.shape
    if h == 0 or w == 0 or not win.any():
        return np.array([frame_w * frame_h], np.int64), np.array([0], np.uint8)
    full_width = w == frame_w
    # One padding zero column per row stands in for the (frame_w - w) zeros
    # between the end of one window row and the start of the next.
    ext = win if full_width else np.zeros((h, w + 1), np.uint8)
    if not full_width:
        ext[:, :w] = win
    flat = ext.ravel()
    change = np.flatnonzero(flat[1:] != flat[:-1]) + 1
    starts = np.concatenate([[0], change])
    lengths = np.diff(np.concatenate([starts, [flat.size]])).astype(np.int64)
    values = flat[starts]
    lead = r0 * frame_w + c0
    if not full_width:
        pad_idx = np.arange(h) * (w + 1) + w
        run_of_pad = np.searchsorted(starts, pad_idx, side="right") - 1
        np.add.at(lengths, run_of_pad, frame_w - w - 1)
        # The last pad would also swallow the next row's c0 leading zeros;
        # trim it so it ends exactly at the end of the window's last row.
        lengths[-1] -= c0
    tail = frame_w * frame_h - (lead + int(lengths.sum()))
    L = [lengths]
    V = [values]
    if lead:
        L.insert(0, np.array([lead], np.int64))
        V.insert(0, np.array([0], np.uint8))
    if tail:
        L.append(np.array([tail], np.int64))
        V.append(np.array([0], np.uint8))
    lengths, values = np.concatenate(L), np.concatenate(V)
    nz = lengths > 0  # the trimmed last pad is empty when the window touches the right edge
    lengths, values = lengths[nz], values[nz]
    # merge neighbouring runs with the same value (zeros around the window)
    keep = np.concatenate([[True], values[1:] != values[:-1]])
    group = np.cumsum(keep) - 1
    merged = np.zeros(int(keep.sum()), np.int64)
    np.add.at(merged, group, lengths)
    return merged, values[keep]


def encode_runs(lengths, values):
    """Encode runs into GOO RLE bytes (vectorised)."""
    lengths = np.asarray(lengths, np.int64)
    values = np.asarray(values, np.uint8)
    if (lengths > MAX_RUN).any():
        reps = (lengths + MAX_RUN - 1) // MAX_RUN
        values = np.repeat(values, reps)
        new = np.full(int(reps.sum()), MAX_RUN, np.int64)
        ends = np.cumsum(reps) - 1
        new[ends] = lengths - (reps - 1) * MAX_RUN
        lengths = new
    ctype = np.where(values == 0, 0, np.where(values == 255, 3, 1)).astype(np.int64)
    lsize = np.select([lengths <= 0xF, lengths <= 0xFFF, lengths <= 0xFFFFF], [0, 1, 2], 3)
    nbytes = 1 + (ctype == 1) + lsize
    offs = np.concatenate([[0], np.cumsum(nbytes)[:-1]])
    out = np.zeros(int(nbytes.sum()), np.uint8)
    out[offs] = ((ctype << 6) | (lsize << 4) | (lengths & 0xF)).astype(np.uint8)
    pos = offs + 1
    g = ctype == 1
    out[pos[g]] = values[g]
    pos = pos + g
    for k in range(3, 0, -1):  # extra length bytes, most significant first
        m = lsize >= k
        shift = 4 + 8 * (k - 1)
        out[pos[m] + (lsize[m] - k)] = ((lengths[m] >> shift) & 0xFF).astype(np.uint8)
    return out.tobytes()


def checksum(data: bytes) -> int:
    return (~(int(np.frombuffer(data, np.uint8).sum(dtype=np.uint64)) & 0xFF)) & 0xFF


def decode_rle(data: bytes, n_pixels: int) -> np.ndarray:
    """Decode GOO RLE (all chunk types, including diff chunks)."""
    out = np.zeros(n_pixels, np.uint8)
    i, p, last = 0, 0, 0
    d = data
    while i < len(d):
        b0 = d[i]
        t = b0 >> 6
        if t == 2:
            diff = b0 & 0xF
            neg = (b0 >> 5) & 1
            has_len = (b0 >> 4) & 1
            val = last - diff if neg else last + diff
            i += 1
            n = 1
            if has_len:
                n = d[i]
                i += 1
            out[p:p + n] = val
            p += n
            last = val
            continue
        ls = (b0 >> 4) & 3
        i += 1
        if t == 0:
            val = 0
        elif t == 3:
            val = 255
        else:
            val = d[i]
            i += 1
        n = 0
        for _ in range(ls):
            n = (n << 8) | d[i]
            i += 1
        n = (n << 4) | (b0 & 0xF)
        out[p:p + n] = val
        p += n
        last = val
    if p != n_pixels:
        raise ValueError(f"RLE decoded {p} pixels, expected {n_pixels}")
    return out


# ----------------------------------------------------------------------- previews

def rgb565(img_rgb: np.ndarray) -> bytes:
    r = (img_rgb[..., 0].astype(np.uint16) >> 3) << 11
    g = (img_rgb[..., 1].astype(np.uint16) >> 2) << 5
    b = img_rgb[..., 2].astype(np.uint16) >> 3
    return (r | g | b).astype(">u2").tobytes()


def preview_from_heightmap(height, size, pixel_aspect=1.0):
    """Shade a top-down height map (0 = empty) into an RGB preview.

    pixel_aspect = pixel width / pixel height, to undo non-square LCD pixels.
    """
    from PIL import Image
    h = height.astype(np.float32)
    occupied = h > 0
    img = np.zeros(h.shape + (3,), np.uint8)
    if occupied.any():
        t = (h - h[occupied].min()) / max(1e-6, np.ptp(h[occupied]))
        shade = (90 + 165 * t)
        img[occupied] = np.stack([shade * 0.55, shade * 0.85, shade], axis=-1)[occupied].astype(np.uint8)
    pil = Image.fromarray(img)
    if pixel_aspect != 1.0:
        pil = pil.resize((max(1, round(pil.width * pixel_aspect)), pil.height), Image.LANCZOS)
    pil.thumbnail((size, size), Image.LANCZOS)
    canvas = Image.new("RGB", (size, size), (24, 26, 30))
    canvas.paste(pil, ((size - pil.width) // 2, (size - pil.height) // 2))
    return np.asarray(canvas)


# ------------------------------------------------------------------------- writer

@dataclass
class LayerParams:
    z: float
    exposure: float
    lift_distance: float
    lift_speed: float
    second_lift_distance: float
    second_lift_speed: float
    retract_distance: float
    retract_speed: float
    second_retract_distance: float
    second_retract_speed: float
    before_lift: float = 0.0
    after_lift: float = 0.0
    after_retract: float = 0.0
    pwm: int = 255


def layer_bytes(p: LayerParams, rle: bytes, pause_z: float) -> bytes:
    head = struct.pack(">Hff", 0, pause_z, p.z) + struct.pack(
        ">13f", p.exposure, 0.0, p.before_lift, p.after_lift, p.after_retract,
        p.lift_distance, p.lift_speed, p.second_lift_distance, p.second_lift_speed,
        p.retract_distance, p.retract_speed, p.second_retract_distance, p.second_retract_speed,
    ) + struct.pack(">H", p.pwm) + DELIMITER
    return head + struct.pack(">I", len(rle) + 2) + b"\x55" + rle + bytes([checksum(rle)]) + DELIMITER


def header_bytes(*, printer_name, printer_type, profile_name, aa, res_x, res_y, size_x, size_y,
                 size_z, layer_h, n_layers, exposure, bottom_exposure, bottom_layers, transition,
                 bottom: LayerParams, normal: LayerParams, print_time, volume_mm3, weight_g,
                 price, small_preview, big_preview, mirror_x=False, mirror_y=False,
                 software="resinslicer", version="0.2.0"):
    b = bytearray()
    b += b"V3.0"
    b += MAGIC_TAG
    b += _s(software, 32) + _s(version, 24)
    b += _s(time.strftime("%Y-%m-%d %H:%M:%S"), 24)
    b += _s(printer_name, 32) + _s(printer_type, 32) + _s(profile_name, 32)
    b += struct.pack(">HHH", aa, 0, 0)
    b += rgb565(small_preview) + DELIMITER + rgb565(big_preview) + DELIMITER
    b += struct.pack(">IHH??", n_layers, res_x, res_y, mirror_x, mirror_y)
    b += struct.pack(">fffff", size_x, size_y, size_z, layer_h, exposure)
    b += struct.pack(">?", True)  # exposure delay mode: static wait times
    b += struct.pack(">7f", 0.0, bottom.before_lift, bottom.after_lift, bottom.after_retract,
                     normal.before_lift, normal.after_lift, normal.after_retract)
    b += struct.pack(">fI", bottom_exposure, bottom_layers)
    b += struct.pack(">16f",
                     bottom.lift_distance, bottom.lift_speed, normal.lift_distance, normal.lift_speed,
                     bottom.retract_distance, bottom.retract_speed, normal.retract_distance, normal.retract_speed,
                     bottom.second_lift_distance, bottom.second_lift_speed,
                     normal.second_lift_distance, normal.second_lift_speed,
                     bottom.second_retract_distance, bottom.second_retract_speed,
                     normal.second_retract_distance, normal.second_retract_speed)
    b += struct.pack(">HH?", bottom.pwm, normal.pwm, True)  # advance mode: per-layer values
    b += struct.pack(">Ifff", int(print_time), volume_mm3, weight_g, price)
    b += _s("$", 8)
    b += struct.pack(">I?H", HEADER_SIZE, True, transition)
    assert len(b) == HEADER_SIZE, len(b)
    return bytes(b)


# ------------------------------------------------------------------------- reader

def read_goo(path, decode=()):
    """Parse a .goo file (used for verification and tests).

    `decode` is a collection of layer indices whose images should be decoded
    (full frames are large: 59 MP each on a Saturn 4), or True for all.
    """
    data = open(path, "rb").read()
    if data[4:12] != MAGIC_TAG:
        raise ValueError("not a GOO file")
    o = 4 + 8 + 32 + 24 + 24 + 32 * 3
    aa, grey, blur = struct.unpack_from(">HHH", data, o)
    o += 6 + SMALL_PREVIEW ** 2 * 2 + 2 + BIG_PREVIEW ** 2 * 2 + 2
    n, rx, ry, mx, my = struct.unpack_from(">IHH??", data, o)
    o += 10
    sx, sy, sz, lh, exp = struct.unpack_from(">fffff", data, o)
    o += 20 + 1 + 28
    bexp, blayers = struct.unpack_from(">fI", data, o)
    hdr = dict(version=data[:4].decode(), printer=data[92:124].split(b"\0")[0].decode(),
               layers=n, res_x=rx, res_y=ry, size=(sx, sy, sz), layer_height=lh, exposure=exp,
               bottom_exposure=bexp, bottom_layers=blayers, aa=aa)
    off = struct.unpack_from(">I", data, HEADER_SIZE - 7)[0]
    assert off == HEADER_SIZE
    o = HEADER_SIZE
    layers = []
    for idx in range(n):
        pause, pz, z = struct.unpack_from(">Hff", data, o)
        vals = struct.unpack_from(">13f", data, o + 10)
        pwm = struct.unpack_from(">H", data, o + 62)[0]
        assert data[o + 64:o + 66] == DELIMITER
        o += 66
        size = struct.unpack_from(">I", data, o)[0]
        assert data[o + 4] == 0x55
        rle = data[o + 5:o + 4 + size - 1]
        cs = data[o + 4 + size - 1]
        if cs != checksum(rle):
            raise ValueError(f"bad checksum in layer at z={z}")
        o += 4 + size
        assert data[o:o + 2] == DELIMITER
        o += 2
        layer = dict(z=z, exposure=vals[0], lift_distance=vals[5], lift_speed=vals[6], pwm=pwm)
        if decode is True or idx in decode:
            layer["image"] = decode_rle(rle, rx * ry).reshape(ry, rx)
        layers.append(layer)
    if data[o:] != ENDING:
        raise ValueError("bad ending string")
    hdr["layer_data"] = layers
    return hdr
