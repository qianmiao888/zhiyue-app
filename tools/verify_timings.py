# -*- coding: utf-8 -*-
"""校验逐字/逐句时间轴的单调性、覆盖完整性、推进速度与 AI 时序质量。"""
import json
import math
import os
import sys

CER_DIR = r"d:\Buddha\zhiyue\app\src\main\assets\ceremonies"
PUNCT = set("，。、；：！？（）「」『』《》〈〉…·—–“”‘’,.!?;:()[] \u3000")

failures = []
method_stats = {"ai": 0, "energy": 0, "unknown": 0}
ai_dwell_samples = []  # (sid, std, max/min)


def simulate(seg, step=100):
    """按 step 毫秒步进，返回 (行序列, 字序列)。step=80 与实际回放推进一致。

    字下标与 App 的 charAt 一致：落在标点上时回退到最近的内容字。
    """
    lines = seg["lines"]
    starts = seg["lineStarts"]
    chars = seg["charStarts"]
    out = []
    for t in range(0, seg["durationMs"], step):
        line = 0
        for i, s in enumerate(starts):
            if t >= s:
                line = i
            else:
                break
        times = chars[line] if line < len(chars) else []
        ci = 0
        for i, s in enumerate(times):
            if t >= s:
                ci = i
            else:
                break
        text = lines[line] if line < len(lines) else ""
        while ci > 0 and ci < len(text) and text[ci] in PUNCT:
            ci -= 1
        out.append((line, ci))
    return out


def content_dwells(lines, chars):
    """收集段内所有相邻内容字的停留时长。"""
    deltas = []
    for i, line in enumerate(lines):
        times = chars[i] if i < len(chars) else []
        idx = [k for k, ch in enumerate(line) if ch not in PUNCT]
        for a, b in zip(idx, idx[1:]):
            deltas.append(times[b] - times[a])
    return deltas


for name in sorted(os.listdir(CER_DIR)):
    if not name.endswith(".json"):
        continue
    cer = json.load(open(os.path.join(CER_DIR, name), encoding="utf-8"))
    print(f"\n== {cer['title']} ({len(cer['segments'])} 段)")
    for seg in cer["segments"]:
        lines, starts, ends, chars = seg["lines"], seg["lineStarts"], seg["lineEnds"], seg["charStarts"]
        sid = seg["id"]
        method = seg.get("method", "unknown")
        method_stats[method] = method_stats.get(method, 0) + 1
        problems = []
        if len(starts) != len(lines) or len(ends) != len(lines) or len(chars) != len(lines):
            problems.append("时间轴数量与句数不一致")
        # 首句起点允许 >0（AI 前奏静音），仅禁止负值
        if starts and starts[0] < 0:
            problems.append(f"首句起点={starts[0]} 为负")
        for i in range(1, len(starts)):
            if starts[i] <= starts[i - 1]:
                problems.append(f"句起点非递增 @{i}")
            if ends[i - 1] != starts[i]:
                problems.append(f"句边界不连续 @{i}")
        for i, line in enumerate(lines):
            if len(chars[i]) != len(line):
                problems.append(f"第{i+1}句字数不匹配 {len(chars[i])}/{len(line)}")
            if any(chars[i][k] > chars[i][k + 1] for k in range(len(chars[i]) - 1)):
                problems.append(f"第{i+1}句字起点非单调")
            # 句字一致性：首字起点 ≈ lineStarts
            if chars[i] and abs(chars[i][0] - starts[i]) > 100:
                problems.append(f"第{i+1}句首字与句起点差 {abs(chars[i][0]-starts[i])}ms > 100ms")
        # 跨句不回跳
        for i in range(len(chars) - 1):
            if chars[i] and chars[i + 1] and chars[i][-1] > chars[i + 1][0] + 50:
                problems.append(f"跨句回跳 @{i+1}->{i+2}")

        seq = simulate(seg)
        line_seq = [s[0] for s in seq]
        if any(line_seq[k + 1] < line_seq[k] for k in range(len(line_seq) - 1)):
            problems.append("逐字推进出现回跳")

        # 演示回放流程：按 80ms 推进，必须每句都显示、每句都点亮、结束落在末句末字
        demo = simulate(seg, 80)
        shown_lines = {l for l, _ in demo}
        if len(shown_lines) != len(lines):
            problems.append(f"演示回放有 {len(lines) - len(shown_lines)} 句未显示")
        skipped = 0
        for li, line in enumerate(lines):
            shown_chars = {c for l, c in demo if l == li}
            need = [k for k, ch in enumerate(line) if ch not in PUNCT]
            if need and not any(k in shown_chars for k in need):
                problems.append(f"第{li+1}句整句未点亮")
            # 停留短于推进步长（80ms）的字会跳过高亮，量小可接受，仅计数
            skipped += sum(1 for k in need if k not in shown_chars)
        if demo and lines:
            last_content = [k for k, ch in enumerate(lines[-1]) if ch not in PUNCT]
            if last_content:
                if demo[-1][0] != len(lines) - 1 or demo[-1][1] < last_content[-1]:
                    problems.append(
                        f"演示回放结束于第{demo[-1][0]+1}句第{demo[-1][1]+1}字，未达末句末字"
                    )
        if skipped:
            print(f"       · 跳过高亮 {skipped} 字（停留 < 80ms 推进步长）")

        weights = [sum(1 for ch in l if ch not in PUNCT) for l in lines]
        total_chars = sum(weights)
        per_char = seg["durationMs"] / max(1, total_chars)
        deltas = content_dwells(lines, chars)
        max_dwell = max(deltas) if deltas else 0
        min_dwell = min(deltas) if deltas else 0
        if per_char < 200:
            problems.append(f"平均每字 {per_char:.0f}ms 过快")
        if method == "ai":
            max_cap = 60000   # AI 允许真实长音/段间停顿（rhythm 填充已消除假长音）
        else:
            max_cap = 9500
        if max_dwell > max_cap:
            problems.append(f"单字最长停留 {max_dwell}ms")
        if min_dwell and min_dwell < 60:
            problems.append(f"单字最短停留 {min_dwell}ms")

        # AI 段：句内停留必须非等时长（体现真实节奏）
        variety_note = ""
        if method == "ai" and len(deltas) >= 8:
            mean = sum(deltas) / len(deltas)
            std = math.sqrt(sum((d - mean) ** 2 for d in deltas) / len(deltas))
            pos = [d for d in deltas if d > 0]
            ratio = (max(pos) / min(pos)) if pos else 0
            ai_dwell_samples.append((sid, std, ratio))
            if std <= 0 or ratio <= 1.5:
                problems.append(f"AI 段 dwell 近似等时长 std={std:.0f} max/min={ratio:.2f}")
            variety_note = f" std={std:.0f} max/min={ratio:.2f}"

        status = "OK" if not problems else "FAIL"
        mr = f" rate={seg['matchRate']:.2f}" if seg.get("matchRate") is not None else ""
        print(f"  {status} {sid:12s} {method:7s} {seg['title'][:12]:14s} 行{len(lines)} 字{total_chars} "
              f"均{per_char:5.0f}ms/字 段长{seg['durationMs']/1000:5.1f}s{mr}{variety_note}")
        for p in problems:
            print("      -", p)
            failures.append(f"{name}:{sid}:{p}")

print("\n方法分布:", method_stats)
if ai_dwell_samples:
    print("AI 段 dwell 抽样:")
    for sid, std, ratio in ai_dwell_samples[:10]:
        print(f"  {sid:12s} std={std:6.0f}ms max/min={ratio:.2f}")

print("\nFAILURES:", len(failures))
sys.exit(1 if failures else 0)
