"""Export a pretrained HTDemucs model to ONNX for the Android app.

The STFT / iSTFT are kept out of the ONNX graph (they are done in Kotlin, see
core/src/main/kotlin/.../Spectrogram.kt), so the exported graph takes:

    mix  float32[1, 2, L]           stereo chunk (L = model training length)
    spec float32[1, 4, 2048, F]     complex-as-channels spectrogram of `mix`

and returns:

    spec_out float32[1, S, 4, 2048, F]   per-source spectrogram (denormalised)
    wave_out float32[1, S, 2, L]         per-source time branch (denormalised)

The final stem is istft(spec_out) + wave_out.

Usage:
    python export_onnx.py --model htdemucs_6s --out stems.onnx
    python export_onnx.py --random --out test.onnx   # random weights, no download
"""
import argparse
import math
import os
from fractions import Fraction

import numpy as np
import torch
from torch import nn
from einops import rearrange

from demucs.htdemucs import HTDemucs


class SpecInOut(nn.Module):
    """HTDemucs.forward with the spectrogram computed outside of the graph."""

    def __init__(self, model: HTDemucs):
        super().__init__()
        self.m = model

    def forward(self, mix, spec):
        m = self.m
        length = mix.shape[-1]
        x = spec
        B, C, Fq, T = x.shape

        mean = x.mean(dim=(1, 2, 3), keepdim=True)
        std = x.std(dim=(1, 2, 3), keepdim=True)
        x = (x - mean) / (1e-5 + std)

        xt = mix
        meant = xt.mean(dim=(1, 2), keepdim=True)
        stdt = xt.std(dim=(1, 2), keepdim=True)
        xt = (xt - meant) / (1e-5 + stdt)

        saved, saved_t, lengths, lengths_t = [], [], [], []
        for idx, encode in enumerate(m.encoder):
            lengths.append(x.shape[-1])
            inject = None
            if idx < len(m.tencoder):
                lengths_t.append(xt.shape[-1])
                tenc = m.tencoder[idx]
                xt = tenc(xt)
                if not tenc.empty:
                    saved_t.append(xt)
                else:
                    inject = xt
            x = encode(x, inject)
            if idx == 0 and m.freq_emb is not None:
                frs = torch.arange(x.shape[-2], device=x.device)
                emb = m.freq_emb(frs).t()[None, :, :, None].expand_as(x)
                x = x + m.freq_emb_scale * emb
            saved.append(x)

        if m.crosstransformer:
            if m.bottom_channels:
                b, c, f, t = x.shape
                x = rearrange(x, "b c f t-> b c (f t)")
                x = m.channel_upsampler(x)
                x = rearrange(x, "b c (f t)-> b c f t", f=f)
                xt = m.channel_upsampler_t(xt)
            x, xt = m.crosstransformer(x, xt)
            if m.bottom_channels:
                x = rearrange(x, "b c f t-> b c (f t)")
                x = m.channel_downsampler(x)
                x = rearrange(x, "b c (f t)-> b c f t", f=f)
                xt = m.channel_downsampler_t(xt)

        for idx, decode in enumerate(m.decoder):
            skip = saved.pop(-1)
            x, pre = decode(x, skip, lengths.pop(-1))
            offset = m.depth - len(m.tdecoder)
            if idx >= offset:
                tdec = m.tdecoder[idx - offset]
                length_t = lengths_t.pop(-1)
                if tdec.empty:
                    pre = pre[:, :, 0]
                    xt, _ = tdec(pre, None, length_t)
                else:
                    skip = saved_t.pop(-1)
                    xt, _ = tdec(xt, skip, length_t)

        S = len(m.sources)
        x = x.view(B, S, -1, Fq, T)
        x = x * std[:, None] + mean[:, None]
        xt = xt.view(B, S, -1, length)
        xt = xt * stdt[:, None] + meant[:, None]
        return x, xt


def load_model(name: str, random: bool) -> HTDemucs:
    if random:
        torch.manual_seed(0)
        model = HTDemucs(sources=["drums", "bass", "other", "vocals", "guitar", "piano"],
                         channels=16, segment=Fraction(39, 5), t_layers=2)
    else:
        from demucs.pretrained import get_model
        bag = get_model(name)
        assert len(bag.models) == 1, "only single-model bags are supported"
        model = bag.models[0]
    assert isinstance(model, HTDemucs), type(model)
    model.eval()
    return model


def reference_separate(model: HTDemucs, wrapper: SpecInOut, mix: torch.Tensor):
    """Check that wrapper + external spec/ispec == model.forward."""
    z = model._spec(mix)
    spec = model._magnitude(z)
    x, xt = wrapper(mix, spec)
    out = model._ispec(model._mask(z, x), mix.shape[-1]) + xt
    return spec, x, xt, out


def write_test_vectors(model, directory, mix, spec, out):
    """Raw little-endian float32 files consumed by the Kotlin unit tests."""
    from demucs.apply import apply_model
    os.makedirs(directory, exist_ok=True)

    def save(name, t):
        np.ascontiguousarray(t.numpy(), dtype="<f4").tofile(os.path.join(directory, name))

    save("chunk_mix.f32", mix)
    save("chunk_spec.f32", spec)
    save("chunk_out.f32", out)
    # A track that is not a multiple of the segment, to exercise overlap-add and
    # the centred padding of the last chunk. Same normalisation as demucs.separate.
    torch.manual_seed(2)
    n = int(2.6 * model.segment * model.samplerate) + 1234
    t = torch.arange(n) / model.samplerate
    wav = torch.stack([torch.sin(2 * math.pi * 220 * t), torch.sin(2 * math.pi * 331 * t)])
    wav = 0.3 * wav + 0.05 * torch.randn(2, n)
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std()
    with torch.no_grad():
        est = apply_model(model, ((wav - mean) / std)[None], shifts=0, split=True,
                          overlap=0.25)[0]
    est = est * std + mean
    save("track_mix.f32", wav)
    save("track_out.f32", est)
    with open(os.path.join(directory, "track_len.txt"), "w") as f:
        f.write(str(n))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="htdemucs_6s")
    p.add_argument("--random", action="store_true", help="random weights (testing)")
    p.add_argument("--out", required=True)
    p.add_argument("--test-vectors", help="directory to write raw float32 test vectors to")
    args = p.parse_args()

    model = load_model(args.model, args.random)
    wrapper = SpecInOut(model).eval()
    length = int(model.segment * model.samplerate)
    print(f"sources={model.sources} samplerate={model.samplerate} length={length}")

    torch.manual_seed(1)
    mix = torch.randn(1, 2, length) * 0.1
    with torch.no_grad():
        spec, x, xt, out = reference_separate(model, wrapper, mix)
        full = model(mix)
    err = (out - full).abs().max().item()
    print(f"wrapper vs model.forward max abs err: {err:.3e}")
    assert err < 1e-3 * max(1.0, full.abs().max().item()), err

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    torch.onnx.export(
        wrapper, (mix, spec), args.out,
        input_names=["mix", "spec"], output_names=["spec_out", "wave_out"],
        opset_version=17, do_constant_folding=True, dynamo=False,
    )

    import onnx
    proto = onnx.load(args.out)
    for k, v in {"sources": ",".join(model.sources),
                 "samplerate": str(model.samplerate),
                 "nfft": str(model.nfft), "hop": str(model.hop_length)}.items():
        entry = proto.metadata_props.add()
        entry.key, entry.value = k, v
    onnx.save(proto, args.out)

    import onnxruntime as ort
    sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    ox, oxt = sess.run(None, {"mix": mix.numpy(), "spec": spec.numpy()})
    e1 = np.abs(ox - x.numpy()).max() / (np.abs(x.numpy()).max() + 1e-9)
    e2 = np.abs(oxt - xt.numpy()).max() / (np.abs(xt.numpy()).max() + 1e-9)
    print(f"onnx vs torch relative err: spec {e1:.3e} wave {e2:.3e}")
    assert e1 < 1e-3 and e2 < 1e-3

    if args.test_vectors:
        write_test_vectors(model, args.test_vectors, mix, spec, out)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
