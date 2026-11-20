# -*- coding: utf-8 -*-
"""构建期 AI 逐字时序识别模块。

用 faster-whisper small 对每段唱诵做带逐词时间戳的转写，再把转写字序列与参考
法本文本（去标点）做 Needleman-Wunsch 序列对齐，得到每个汉字的真实起始毫秒。

仅在构建机离线运行，不进 APK、不在端侧推理。输出被 prepare_assets.py 消费；
当转写对参考文本覆盖率过低（咒语/音译字）时返回 method="fallback"，由调用方
回退到能量法 build_timings。

环境隔离：本模块依赖工作区本地 d:\\Buddha\\.cache\\pylibs 下的 faster-whisper
（其自带 numpy 等），prepare_assets.py 通过子进程 + PYTHONPATH 调用，避免污染
主环境的 scipy/soundfile。
"""
import argparse
import bisect
import json
import os
import sys
import time

# ---- 注入工作区本地依赖（仅本子进程生效）----
_PYLIBS = r"d:\Buddha\.cache\pylibs"
if os.path.isdir(_PYLIBS) and _PYLIBS not in sys.path:
    sys.path.insert(0, _PYLIBS)
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HOME", r"d:\Buddha\.cache\hf")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")


# PyAV 兼容补丁：faster-whisper 1.2.x 调用 av.open(..., metadata_errors="ignore")，
# 但当前镜像可用的 PyAV 最高 19.0.2 不含该参数（19.0.3 才加入）。包装一层在 TypeError
# 时剥离该参数重试，保证解码链路可用；若未来 av 升级则走原路径。
def _patch_av_open():
    try:
        import av as _av
    except Exception:
        return
    _orig = _av.open

    def _compat_open(*args, **kwargs):
        try:
            return _orig(*args, **kwargs)
        except TypeError:
            kwargs.pop("metadata_errors", None)
            return _orig(*args, **kwargs)

    _av.open = _compat_open


_patch_av_open()

PUNCT = set("，。、；：！？（）「」『』《》〈〉…·—–“”‘’,.!?;:()[] \u3000\t\n\r")
MIN_DWELL_MS = 60          # 单字最小停留，避免快字挤成同一点
MATCH_THRESHOLD = 0.18     # 部分匹配（法本不全或音频有额外内容）也接受 AI 时序；覆盖度安全网兜底
CACHE_PATH = r"d:\Buddha\.cache\fw_transcribe_cache.json"


def is_content(ch):
    return ch not in PUNCT and not ch.isspace()


def ref_content_chars(lines):
    """按行顺序展开参考文本的内容字（跳标点），返回 [(char,)]。"""
    out = []
    for line in lines:
        for ch in line:
            if is_content(ch):
                out.append(ch)
    return out


CHUNK_SECONDS = 60  # 长音频分块转写，抑制小模型在长唱诵上的漂移/幻觉


def transcribe_units(model, audio_path, initial_prompt=""):
    """分块转写 → [(char, start_sec)]，时间戳为全音频绝对秒。
    把长音频切成 60s 块独立转写（每块带初始偏移），显著降低 small 模型在
    长唱诵上的幻觉退化；多字词在词时长内均分为单字。"""
    from faster_whisper.audio import decode_audio
    audio = decode_audio(audio_path, sampling_rate=16000)
    sr = 16000
    chunk = CHUNK_SECONDS * sr
    units = []
    for off in range(0, len(audio), chunk):
        seg = audio[off:off + chunk]
        t0 = off / sr
        try:
            segs, _info = model.transcribe(
                seg,
                language="zh",
                word_timestamps=True,
                vad_filter=False,         # 佛门唱腔会被 Silero VAD 整段判为非语音
                beam_size=1,
                condition_on_previous_text=True,
                initial_prompt=initial_prompt or None,
            )
            for s in segs:
                for w in (s.words or []):
                    word = (w.word or "").strip()
                    if not word:
                        continue
                    chars = [c for c in word if is_content(c)]
                    if not chars:
                        continue
                    start = float(w.start) + t0
                    end = float(w.end) + t0
                    if end <= start:
                        end = start + 0.05
                    k = len(chars)
                    for i, c in enumerate(chars):
                        cs = start + (end - start) * i / k
                        units.append((c, cs))
        except Exception as e:
            print(f"  [ai] chunk {t0:.0f}s error: {e}", flush=True)
    return units


def needleman_wunsch(ref, hyp):
    """返回对齐后每个 ref 位置对应的 hyp 下标（未匹配为 None）与匹配数。"""
    n, m = len(ref), len(hyp)
    if n == 0:
        return [], 0
    if m == 0:
        return [None] * n, 0
    # dp[i][j] 最小编辑距离；match=0, sub=1, gap=1
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        ri = ref[i - 1]
        dpi = dp[i]
        dpi1 = dp[i - 1]
        for j in range(1, m + 1):
            cost = 0 if ri == hyp[j - 1] else 1
            dpi[j] = min(dpi1[j - 1] + cost, dpi1[j] + 1, dpi[j - 1] + 1)
    # 回溯
    aligned = [None] * n
    matched = 0
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dp[i][j] == dp[i - 1][j - 1] + (0 if ref[i - 1] == hyp[j - 1] else 1):
            if ref[i - 1] == hyp[j - 1]:
                aligned[i - 1] = j - 1
                matched += 1
            else:
                aligned[i - 1] = j - 1  # 替换：借用该 hyp 时间
            i -= 1
            j -= 1
        elif i > 0 and (j == 0 or dp[i][j] == dp[i - 1][j] + 1):
            i -= 1  # ref 多出，无时间
        else:
            j -= 1  # hyp 多出，跳过
    return aligned, matched


def fill_times(aligned, wh_units, ref_len):
    """把对齐下标转成每个 ref 字的时间（秒），未匹配在相邻匹配间线性插值。"""
    times = [None] * ref_len
    for i, j in enumerate(aligned):
        if j is not None and 0 <= j < len(wh_units):
            times[i] = wh_units[j][1]
    # 线性插值填补 None
    known = [(i, t) for i, t in enumerate(times) if t is not None]
    if not known:
        return None  # 全无时间 → 回退
    # 前段
    first_i, first_t = known[0]
    for i in range(first_i):
        times[i] = first_t
    # 后段
    last_i, last_t = known[-1]
    for i in range(last_i + 1, ref_len):
        times[i] = last_t
    # 中段插值
    for a in range(len(known) - 1):
        i0, t0 = known[a]
        i1, t1 = known[a + 1]
        if i1 - i0 > 1:
            span = i1 - i0
            for k in range(1, span):
                times[i0 + k] = t0 + (t1 - t0) * k / span
    return times


def _onset_at_frac(onsets, lo, hi, frac):
    """取 [lo, hi] 区间内第 frac 分位的真实音节 onset 时间（秒）。"""
    a = bisect.bisect_right(onsets, lo + 1e-6)
    b = bisect.bisect_left(onsets, hi - 1e-6)
    seg = onsets[a:b]
    if not seg:
        return lo + (hi - lo) * frac
    k = int(round(frac * (len(seg) - 1)))
    return seg[min(len(seg) - 1, max(0, k))]


def fill_times_rhythm(aligned, wh_units, ref_len):
    """未匹配字用 whisper 检测到的真实音节 onset 时间填充，而非线性插值。
    这样锚点稀疏处 dwell 仍跟随真实音频节奏，不会出现数十秒的假长音。"""
    times = [None] * ref_len
    for i, j in enumerate(aligned):
        if j is not None and 0 <= j < len(wh_units):
            times[i] = wh_units[j][1]
    known = [(i, t) for i, t in enumerate(times) if t is not None]
    if len(known) < 2:
        return None
    onsets = sorted(t for _, t in wh_units)
    first_i, first_t = known[0]
    last_i, last_t = known[-1]
    # 前段：首个匹配之前的字，落在 [0, first_t] 的 onset 上
    for m in range(first_i):
        frac = (m + 1) / (first_i + 1)
        times[m] = _onset_at_frac(onsets, 0.0, first_t, frac)
    # 后段：末个匹配之后的字，落在 [last_t, 音频末尾] 的 onset 上
    trailing = ref_len - 1 - last_i
    tail_hi = max(last_t, onsets[-1] if onsets else last_t)
    for off in range(trailing):
        frac = (off + 1) / (trailing + 1)
        times[last_i + off + 1] = _onset_at_frac(onsets, last_t, tail_hi, frac)
    # 中段：相邻匹配之间的字，按分位取该区间内的真实 onset
    for a in range(len(known) - 1):
        i0, t0 = known[a]
        i1, t1 = known[a + 1]
        gap = i1 - i0 - 1
        for k in range(1, gap + 1):
            frac = k / (gap + 1)
            times[i0 + k] = _onset_at_frac(onsets, t0, t1, frac)
    return times


def build_char_starts(lines, times_sec):
    """把展平的内容字时间映射回逐行逐字（标点继承前一字时间），返回 ms。"""
    char_starts = []
    it = iter(times_sec)
    prev = 0.0
    for line in lines:
        row = []
        for ch in line:
            if is_content(ch):
                t = next(it)
                if t is None:
                    t = prev
                prev = t
                row.append(int(round(t * 1000)))
            else:
                row.append(int(round(prev * 1000)))
        char_starts.append(row)
    return char_starts


def enforce_monotonic(char_starts, duration_ms):
    """跨句展平后保证单调非减且相邻 ≥ MIN_DWELL_MS，再写回。"""
    flat = []
    idx_map = []  # (line, col)
    for li, row in enumerate(char_starts):
        for ci, v in enumerate(row):
            flat.append(v)
            idx_map.append((li, ci))
    last = -10 ** 9
    for k in range(len(flat)):
        v = max(flat[k], last + MIN_DWELL_MS)
        if v > duration_ms:
            v = duration_ms
        flat[k] = v
        last = v
    for k, (li, ci) in enumerate(idx_map):
        char_starts[li][ci] = flat[k]
    return char_starts


def best_expansion(units, lines, max_cycles=40):
    """自动判定音频实际重复轮次：转写一次，对 1..max_cycles 倍重复算匹配率，
    取最优率（容差 0.02 内取最大轮次），让称名/反复句随音频真实推进，
    只唱一遍的偈语则保持 1 倍。返回 (对齐用的 lines, 最优匹配率)。"""
    hyp = [u[0] for u in units]
    base = ref_content_chars(lines)
    if not base or not hyp:
        return lines, 0.0
    rates = []
    for cyc in range(1, max_cycles + 1):
        ref = base * cyc
        _, matched = needleman_wunsch(ref, hyp)
        rates.append((cyc, matched / len(ref)))
    best_rate = max(r for _, r in rates)
    for cyc, r in reversed(rates):
        if r >= best_rate - 0.02:
            return lines * cyc, best_rate
    return lines, best_rate


def align_segment(units_cache, model, audio_path, lines, duration_ms):
    """返回 dict: method, lines, lineStarts, lineEnds, charStarts, matchRate。"""
    # 用参考文本前段作 initial_prompt，偏置模型朝目标字转写，提升匹配率
    prompt = "".join(lines)[:220]
    try:
        wh_units = units_cache.get(audio_path)
        if wh_units is None:
            wh_units = transcribe_units(model, audio_path, initial_prompt=prompt)
            units_cache.put(audio_path, wh_units)
    except Exception as e:
        print(f"  [ai] transcribe error {os.path.basename(audio_path)}: {e}", flush=True)
        return _fallback(lines)
    if len(wh_units) == 0:
        print(f"  [ai] no words {os.path.basename(audio_path)} → fallback", flush=True)
        return _fallback(lines)
    aligned_lines, match_rate = best_expansion(wh_units, lines)
    ref = ref_content_chars(aligned_lines)
    hyp = [u[0] for u in wh_units]
    cycles = len(aligned_lines) // max(1, len(lines))
    print(f"  [ai] {os.path.basename(audio_path):24s} ref={len(ref):4d} hyp={len(hyp):4d} "
          f"cycles={cycles} rate={match_rate:.2f}", flush=True)
    if match_rate < MATCH_THRESHOLD or len(ref) == 0:
        return _fallback(lines)
    aligned, _ = needleman_wunsch(ref, hyp)
    anchors = [wh_units[j][1] for j in aligned if j is not None]
    if len(anchors) < 2:
        print(f"  [ai] {os.path.basename(audio_path)} too few matched ({len(anchors)}) → fallback",
              flush=True)
        return _fallback(lines)
    times = fill_times_rhythm(aligned, wh_units, len(ref))
    if times is None:
        return _fallback(lines)
    dur_s = duration_ms / 1000.0
    first_t, last_t = min(anchors), max(anchors)
    # 覆盖度：首个匹配落在前 40%（容 10s 前奏），匹配跨度 ≥ 35% 时长；
    # 不满足视为转写与法本偏差过大，回退能量法。
    if first_t > dur_s * 0.40 + 10.0 or (last_t - first_t) < dur_s * 0.35:
        print(f"  [ai] {os.path.basename(audio_path)} coverage fail first={first_t:.1f}s "
              f"last={last_t:.1f}s dur={dur_s:.1f}s → fallback", flush=True)
        return _fallback(lines)
    char_starts = build_char_starts(aligned_lines, times)
    char_starts = enforce_monotonic(char_starts, duration_ms)
    # 兜底：出现 >60s 的单字停留属退化输出，回退能量法
    max_dwell = 0
    for row in char_starts:
        for a, b in zip(row, row[1:]):
            if b - a > max_dwell:
                max_dwell = b - a
    if max_dwell > 60000:
        print(f"  [ai] {os.path.basename(audio_path)} max_dwell={max_dwell}ms → fallback", flush=True)
        return _fallback(lines)
    line_starts, line_ends = [], []
    n = len(aligned_lines)
    for i in range(n):
        line_starts.append(char_starts[i][0] if char_starts[i] else 0)
        if i + 1 < n:
            line_ends.append(char_starts[i + 1][0] if char_starts[i + 1] else duration_ms)
        else:
            line_ends.append(duration_ms)
    return {
        "method": "ai",
        "lines": aligned_lines,
        "matchRate": round(match_rate, 3),
        "lineStarts": line_starts,
        "lineEnds": line_ends,
        "charStarts": char_starts,
    }


def _fallback(lines):
    """标记回退：调用方用能量法重算。"""
    return {"method": "fallback", "lineStarts": [], "lineEnds": [], "charStarts": []}


# ---------------- 转录缓存（按音频路径，避免重复转写）----------------
class TranscribeCache:
    def __init__(self, path, model=""):
        self.path = path
        self.data = {}
        self.dirty = False
        self.prefix = (model or "") + "||"   # 按模型命名空间，避免 small/medium 串缓存
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    self.data = json.load(f)
            except Exception:
                self.data = {}

    def get(self, audio_path):
        v = self.data.get(self.prefix + audio_path)
        if v is None:
            return None
        return [tuple(x) for x in v]

    def put(self, audio_path, units):
        self.data[self.prefix + audio_path] = [[c, t] for c, t in units]
        self.dirty = True

    def save(self):
        if self.dirty:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False)
            self.dirty = False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--model", default="small")
    ap.add_argument("--cache", default=CACHE_PATH)
    args = ap.parse_args()

    with open(args.input, "r", encoding="utf-8") as f:
        payload = json.load(f)
    items = payload["items"]
    print(f"[ai] loading model {args.model} ...", flush=True)
    t0 = time.time()
    from faster_whisper import WhisperModel
    model = WhisperModel(args.model, device="cpu", compute_type="int8")
    print(f"[ai] model loaded in {time.time()-t0:.1f}s, items={len(items)}", flush=True)

    cache = TranscribeCache(args.cache, args.model)
    results = []
    for idx, it in enumerate(items, 1):
        t1 = time.time()
        res = align_segment(cache, model, it["audio"], it["lines"], it["durationMs"])
        res["id"] = it.get("id")
        results.append(res)
        print(f"  [{idx}/{len(items)}] {it.get('id')} → {res['method']} "
              f"({time.time()-t1:.1f}s)", flush=True)
        if idx % 5 == 0:
            cache.save()
    cache.save()
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False)
    print(f"[ai] done, wrote {args.output}", flush=True)


if __name__ == "__main__":
    main()
