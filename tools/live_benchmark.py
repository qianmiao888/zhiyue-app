# -*- coding: utf-8 -*-
"""导出固定样本现场识别基准所需的 PCM 与清单。

音频直接取自 APK 内置 m4a；识别、句字映射与统计均由生产 Kotlin 代码执行。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(r"d:\Buddha\zhiyue")
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = Path(os.environ.get("ZHIYUE_BENCHMARK_DIR", r"d:\Buddha\.cache\live_benchmark"))
FFMPEG = Path(r"D:\python\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe")


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def export():
    pcm_dir = OUT / "pcm"
    pcm_dir.mkdir(parents=True, exist_ok=True)
    decoded = {}
    ceremonies = []
    for json_path in sorted((ASSETS / "ceremonies").glob("*.json")):
        ceremony = json.loads(json_path.read_text(encoding="utf-8"))
        item = {
            "id": ceremony["id"],
            "json": str(json_path),
            "segments": [],
        }
        for segment in ceremony["segments"]:
            audio = ASSETS / segment["audio"]
            key = segment["audio"]
            if key not in decoded:
                pcm = pcm_dir / (audio.stem + ".s16le")
                if not pcm.exists() or pcm.stat().st_mtime < audio.stat().st_mtime:
                    subprocess.run([
                        str(FFMPEG), "-y", "-hide_banner", "-loglevel", "error",
                        "-i", str(audio), "-ac", "1", "-ar", "16000",
                        "-f", "s16le", "-acodec", "pcm_s16le", str(pcm),
                    ], check=True)
                decoded[key] = {
                    "pcm": str(pcm),
                    "audioSha256": sha256(audio),
                    "featureSha256": sha256(ASSETS / segment["features"]),
                }
            row = dict(segment)
            row.update(decoded[key])
            item["segments"].append(row)
        ceremonies.append(item)
    manifest = {
        "sampleRate": 16000,
        "channels": 1,
        "encoding": "s16le",
        "mel": str(ASSETS / "models" / "mel32.f32"),
        "assets": str(ASSETS),
        "ceremonies": ceremonies,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    manifest_path = OUT / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"manifest={manifest_path}")
    print(f"ceremonies={len(ceremonies)} segments={sum(len(c['segments']) for c in ceremonies)} unique_audio={len(decoded)}")


def refresh_features():
    """只从实际 m4a 重建参考特征，不改文本、时间轴或音频。"""
    import numpy as np
    import prepare_assets as prep

    export()
    filters = np.fromfile(ASSETS / "models" / "mel32.f32", dtype="<f4").reshape(prep.N_MELS, -1)
    done = set()
    for json_path in sorted((ASSETS / "ceremonies").glob("*.json")):
        ceremony = json.loads(json_path.read_text(encoding="utf-8"))
        for segment in ceremony["segments"]:
            key = segment["features"]
            if key in done:
                continue
            done.add(key)
            pcm = OUT / "pcm" / (Path(segment["audio"]).stem + ".s16le")
            mono = np.fromfile(pcm, dtype="<i2").astype(np.float32) / 32768.0
            feat = prep.logmel(mono, filters, prep.WIN, prep.HOP, prep.N_FFT)
            count = segment["frames"]
            if len(feat) < count:
                feat = np.pad(feat, ((0, count - len(feat)), (0, 0)), mode="edge")
            target = ASSETS / key
            feat[:count].astype("<f4").tofile(target)
            print(f"feature={target.name} frames={count}")
    export()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["export", "refresh-features"], nargs="?", default="export")
    args = parser.parse_args()
    if args.command == "refresh-features":
        refresh_features()
    else:
        export()
