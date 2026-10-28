# CKSplitter (Android)

An Android app that splits songs into separate stems, **entirely on the phone** (no
server, works offline):

| Stem | |
|---|---|
| Vocals | lead and backing vocals |
| Drums | kit and percussion |
| Bass | bass guitar / synth bass |
| Guitar | acoustic and electric guitars |
| Piano | piano and keys |
| Other | everything else (synths, strings, …) |
| Instrumental | everything except the vocals (karaoke track) |

It uses Meta's [Demucs](https://github.com/facebookresearch/demucs) **htdemucs_6s**
model (Hybrid Transformer Demucs, 6 sources), run with ONNX Runtime.

## Getting the APK

Every push that changes `stem-splitter/` runs the **CKSplitter APK** GitHub
Actions workflow. When it finishes:

* the APK is attached to the **`stem-splitter-latest`** release on the repository's
  *Releases* page. Open that page on your phone, download `CKSplitter.apk` and
  install it. You may need to allow "Install unknown apps" for your browser.
* it's also uploaded as the `CKSplitter-apk` workflow artifact.

Requires Android 10 or newer. A phone with 6 GB+ RAM is recommended.

## Using it

1. **Choose song**. Pick any audio file (MP3, WAV, FLAC, M4A/AAC, OGG/Opus, …), or
   share an audio file to *CKSplitter* from another app.
2. **Split into stems**. Processing runs in the background with a progress
   notification. Expect roughly 1–3× the song's length on a recent phone.
3. The mixer opens when it's done. Play all stems in sync, change each stem's volume,
   mute (M) or solo (S) it, and share individual stems or all of them.

Stems are saved as 44.1 kHz 16-bit WAV files in
`Music/CKSplitter/<song name>/`, where any music app, file manager or DAW can
use them. Long-press a song in the *Library* to delete it.

CI signs release builds with the key in the `CKSPLITTER_KEYSTORE` (base64 keystore,
alias `cksplitter`) and `CKSPLITTER_KEYSTORE_PASSWORD` repository secrets, so each new
build installs as an update. Without them it falls back to a throwaway debug key, and
you have to uninstall before installing a new build.

## How it works

```
audio file ─▶ MediaCodec decode ─▶ resample to 44.1 kHz ─▶ 7.8 s chunks (25 % overlap)
           ─▶ STFT (Kotlin) ─▶ HTDemucs (ONNX Runtime) ─▶ iSTFT + time branch (Kotlin)
           ─▶ overlap-add ─▶ WAV per stem
```

* `tools/export_onnx.py` exports the pretrained PyTorch model to ONNX. The STFT and
  iSTFT are taken out of the graph, because ONNX/mobile runtimes handle them poorly,
  and done in Kotlin instead.
* `core/` is a plain Kotlin/JVM module with the DSP (`Spectrogram`), the demucs
  chunking/overlap-add (`Separator`), resampling and WAV writing. Its tests check it
  against PyTorch demucs's `apply_model` output to within floating-point error.
* `app/` is the Android app: decoding, the foreground service, MediaStore output and
  the real-time stem mixer.

## Building locally

Needs JDK 17, the Android SDK and Python 3.13.

```sh
cd stem-splitter
pip install -r tools/requirements.txt --extra-index-url https://download.pytorch.org/whl/cpu
python tools/export_onnx.py --model htdemucs_6s --out app/src/main/assets/stems.onnx
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk

# core tests vs. PyTorch (random weights, no download):
python tools/export_onnx.py --random --out build/vectors/model.onnx --test-vectors build/vectors
./gradlew -PcoreOnly :core:test -PtestVectors=$PWD/build/vectors
```

## License note

Demucs and its pretrained weights are released by Meta under the MIT license.
