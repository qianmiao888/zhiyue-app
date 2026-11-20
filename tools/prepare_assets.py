# -*- coding: utf-8 -*-
"""将公开唱诵音源转码进 APK，并预先计算端侧对齐所需的声学特征与逐字时间轴。

输出：
  assets/audio/<id>.m4a         24kHz 单声道 40kbps AAC
  assets/features/<id>.f32      帧数 x 32 的 log-mel 特征（float32 LE）
  assets/models/mel32.f32       32 x 4097 mel 滤波器组
  assets/ceremonies/*.json      法会模板（含 lineStarts / lineEnds / charStarts）
"""
import json
import os
import subprocess
import sys

import numpy as np
import soundfile as sf

FFMPEG = r"D:\python\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
SRC_DIR = r"d:\Buddha\.cache\audio\src"
SRC2_DIR = r"d:\Buddha\.cache\audio\src2"
SRC3_DIR = r"d:\Buddha\.cache\audio\src3"
APP_DIR = r"d:\Buddha\zhiyue\app\src\main\assets"

_PYLIBS = r"d:\Buddha\.cache\pylibs"
MODEL_DIR = r"d:\Buddha\.cache\fw-medium"   # medium：唱诵逐字准确率远高于 small
WHISPER_ALIGN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "whisper_align.py")
ALIGN_IN = r"d:\Buddha\.cache\align_in.json"
ALIGN_OUT = r"d:\Buddha\.cache\align_out.json"

SR = 16000
N_FFT = 8192
WIN = 6400          # 400ms
HOP = 1600          # 100ms
N_MELS = 32
FMIN, FMAX = 80, 2400

# ---------------------------------------------------------------- 法会定义
YANKOU = [
    ("UQYK0601", "yangzhi", 1, "杨枝净水", "赞",
     ["杨枝净水，遍洒三千，性空八德利人天，福寿广增延，灭罪消愆，火焰化红莲。",
      "南无清凉地菩萨摩诃萨（三称）"],
     "净坛起腔，法师振铃洒净", "合掌 · 观想甘露洒净"),
    ("UQYK0605", "jixiang", 5, "吉祥会启", "赞",
     ["吉祥会启，甘露门开，孤魂佛子降临来。",
      "闻法赴香斋，永脱轮回，幽暗一时开。"],
     "召请前起赞，腔调转缓", "随腔慢诵 · 心存慈悲"),
    ("UQYK0607", "shijia", 7, "释迦佛面犹如净满月", "赞佛",
     ["释迦佛面犹如净满月，亦如千日放光明。",
      "圆光普照于十方，慈悲喜舍皆具足。"],
     "赞佛偈，音区上行", "合掌 · 观佛面如满月"),
    ("UQYK0608", "wufang", 8, "五方五佛大威神", "赞",
     ["五方五佛大威神，结界降魔遍刹尘。",
      "今宵毗卢冠上现，一瞻一礼总归真。"],
     "五方佛赞，节奏短促有力", "结印 · 随句顶礼"),
    ("UQYK0622", "liuzi", 22, "六字大明王", "咒",
     ["唵嘛呢叭咪吽（反复持诵）",
      "六字大明王，功德普回向。"],
     "大明咒段落，可循环持诵", "持咒 · 计数"),
    ("UQYK0634", "zhaoqing", 34, "以此振铃伸召请", "召请",
     ["以此振铃伸召请，孤魂闻召愿降临。",
      "来临法会，受此甘露味。"],
     "振铃召请，段落边界明显", "振铃 · 观想孤魂来临"),
    ("UQYK0641", "qirulai", 41, "语诸佛子 · 七如来名", "称名",
     ["南无多宝如来　南无宝胜如来　南无妙色身如来",
      "南无广博身如来　南无离怖畏如来　南无甘露王如来",
      "南无阿弥陀如来"],
     "七如来名号，逐称逐礼", "称名 · 随句顶礼"),
    ("UQYK0653", "zunsheng", 53, "尊胜咒", "咒",
     ["南无薄伽伐帝　怛唎路枳也　钵啰底尾始瑟吒野　没驮野",
      "薄伽伐帝　怛你也他　唵　毗输驮耶　娑婆诃"],
     "佛顶尊胜陀罗尼，语速偏快", "持咒 · 观想光明清净"),
    ("UQYK0656", "jianwen", 56, "见闻如幻翳", "偈",
     ["见闻如幻翳，三界若空花。",
      "闻复翳根除，尘销觉圆净。"],
     "收束前的偈颂，语速放慢", "静听 · 随文入观"),
    ("UQYK0658", "wuxing", 58, "五姓孤魂薜荔多", "召请",
     ["五姓孤魂薜荔多，久处幽冥受苦辛。",
      "今遇良缘来赴会，闻法受食得超升。"],
     "召请文末段，接回向", "合掌 · 回向"),
]

LIANGHUANG = [
    ("LHBC_YTYL", "lh_yaotian", 1, "瑶天玉露", "赞",
     ["瑶天玉露，觉海金波，", "大千沙界洒芬陀，三昧涤尘疴。",
      "少水成多，一滴净山河。", "南无清凉地菩萨摩诃萨（三称）"],
     "起忏第一腔，法器起板", "合掌 · 观想甘露净坛"),
    ("LHBC_CCXXH", "lh_xianhua", 2, "虔诚献香花", "献供",
     ["虔诚献香花，智慧灯红焰交加。", "净瓶杨柳洒堪夸，橄榄共枇杷。",
      "蒙山雀舌茶奉献，酥酡普供养释迦。", "百宝明珠奉献佛菩萨，衣奉献，法王家。"],
     "献供赞，逐句献供", "献供 · 随文观想"),
    ("LHBC_FBZ", "lh_fobao", 3, "佛宝赞", "赞佛",
     ["佛宝赞无穷，功成无量劫中。", "巍巍丈六紫金容，觉道雪山峰。",
      "眉际玉毫光灿烂，照开六道昏蒙。", "龙华三会愿相逢，演说法真宗。"],
     "赞佛宝，音区上行", "合掌 · 观佛金容"),
    ("LHBC_SB", "lh_sengbao", 4, "僧宝赞", "赞",
     ["僧宝不思议，身披三事云衣。", "浮杯渡海刹那时，赴感应群机。",
      "堪作人天功德主，坚持戒行无违。", "我今稽首愿遥知，振锡杖提携。"],
     "赞僧宝，节奏平稳", "顶礼 · 随句称念"),
    ("LHBC_SBZ", "lh_sanbao", 5, "三宝赞", "称名",
     ["南无佛　南无法　南无僧"],
     "收束赞偈，接礼拜", "三称三宝 · 顶礼"),
    ("LHBC_YFX", "lh_yifeng", 6, "衣奉献", "献供",
     ["衣奉献，福田衣。", "百宝明珠奉献佛菩萨。", "衣奉献，法王家。"],
     "献衣供养，接回向", "献衣 · 回向"),
]

PUFU = [
    ("JDZXZ07", "pf_jdzx", 1, "戒定真香", "香赞",
     ["戒定真香，焚起冲天上。", "弟子虔诚，热在金炉上。",
      "顷刻氤氲，即遍满十方。", "众生祈求，免难消灾障。",
      "南无香云盖菩萨摩诃萨（三称）"],
     "普佛起腔，香赞", "上香 · 合掌"),
    ("CHFS04", "pf_yaoshizan", 2, "药师赞", "赞佛",
     ["药师佛，延寿王，光临水月坛场。", "悲心救苦降吉祥，免难消灾障。",
      "忏悔众等三世罪，愿祈福寿绵长。", "吉星高照沐恩光，如意保安康。"],
     "赞药师佛，祈福延寿", "合掌 · 观想药师琉璃光"),
    ("HXJ03", "pf_huixiang", 3, "回向偈", "回向",
     ["愿消三障诸烦恼，愿得智慧真明了。", "普愿罪障悉消除，世世常行菩萨道。"],
     "普佛回向，收束", "合掌 · 随文回向"),
]

JIEDING_LINES = [
    "戒定真香，焚起冲天上。", "弟子虔诚，热在金炉上。",
    "顷刻氤氲，即遍满十方。", "众生祈求，免难消灾障。",
    "南无香云盖菩萨摩诃萨（三称）",
]
HUIXIANG_LINES = [
    "愿消三障诸烦恼，愿得智慧真明了。", "普愿罪障悉消除，世世常行菩萨道。",
]

AMITUOFO = [
    ("JDZXZ07", "am_jdzx", 1, "戒定真香", "香赞",
     JIEDING_LINES,
     "阿弥陀佛普佛起腔，香赞", "上香 · 合掌", "pf_jdzx"),
    ("MITUOZAN", "am_mituo", 2, "弥陀赞", "赞佛",
     ["弥陀佛大愿王，慈悲喜舍难量，眉间常放白毫光，度众生极乐邦。",
      "八德池中莲九品，七宝妙树成行。",
      "如来圣号若宣扬，接引往西方。",
      "弥陀圣号若称扬，同愿往西方。"],
     "赞阿弥陀佛，音区上行", "合掌 · 观想弥陀"),
    ("ZANLIXIFANG", "am_zanli", 3, "赞礼西方", "赞",
     ["赞礼西方，极乐清凉，莲池九品华香，宝树成行，常闻天乐铿锵。",
      "阿弥陀佛大放慈光，化导众生无量，降吉祥，现前众等歌扬，愿生安养。"],
     "赞礼西方极乐世界", "合掌 · 愿生西方"),
    ("AMTFSJINSE", "am_jinse", 4, "阿弥陀佛身金色", "赞佛",
     ["阿弥陀佛身金色，相好光明无等伦。",
      "白毫宛转五须弥，绀目澄清四大海。",
      "光中化佛无数亿，化菩萨众亦无边。",
      "四十八愿度众生，九品咸令登彼岸。"],
     "赞佛偈，观佛相好", "合掌 · 观佛金容"),
    ("BAIYUAN", "am_baiyuan", 5, "拜愿 · 南无阿弥陀佛", "称名",
     ["南无阿弥陀佛"],
     "称名拜愿，随腔念佛", "称名 · 顶礼"),
    ("HXJ03", "am_huixiang", 6, "回向偈", "回向",
     HUIXIANG_LINES,
     "普佛回向，收束", "合掌 · 随文回向", "pf_huixiang"),
]

CEREMONIES = [
    {
        "id": "yujia_yankou",
        "order": 1,
        "title": "瑜伽焰口",
        "subtitle": "悟禅法师唱诵 · 核心十段",
        "source": "音源：牟尼佛法流通网《瑜伽焰口》（悟禅法师唱诵）公开录音，仅用于比赛演示，不作商用。",
        "src_dir": SRC_DIR,
        "ext": ".mp3",
        "segments": YANKOU,
    },
    {
        "id": "lianghuang_chan",
        "order": 2,
        "title": "梁皇宝忏 · 起忏",
        "subtitle": "起忏六段赞偈 · 约 33 分钟",
        "source": "音源：牟尼佛法流通网《梁皇宝忏》起忏唱段公开录音，仅用于比赛演示，不作商用。",
        "src_dir": SRC2_DIR,
        "ext": ".mp3",
        "segments": LIANGHUANG,
    },
    {
        "id": "yaoshi_pufo",
        "order": 3,
        "title": "药师普佛",
        "subtitle": "香赞 · 药师赞 · 回向",
        "source": "音源：牟尼佛法流通网公开录音（戒定真香·能真法师；药师赞；回向偈），仅用于比赛演示，不作商用。",
        "src_dir": SRC2_DIR,
        "ext": ".mp3",
        "segments": PUFU,
    },
    {
        "id": "amituofo_pufo",
        "order": 4,
        "title": "阿弥陀佛普佛",
        "subtitle": "香赞 · 弥陀赞 · 赞礼西方 · 拜愿 · 回向",
        "source": "音源：muni-buddha 悟禅法师公开专辑（弥陀赞、赞礼西方、阿弥陀佛身金色、拜愿）与已缓存公开录音（戒定真香、回向偈），仅用于比赛演示，不作商用。",
        "src_dir": SRC3_DIR,
        "ext": ".mp3",
        "segments": AMITUOFO,
    },
]

PUNCT = set("，。、；：！？（）「」『』《》〈〉…·—–“”‘’,.!?;:()[] \u3000")


# ---------------------------------------------------------------- 基础工具
def hz_to_mel(f):
    f = np.asarray(f, dtype=np.float64)
    f_sp = 200.0 / 3
    mels = f / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    log_t = f >= min_log_hz
    return np.where(log_t, min_log_mel + np.log(np.maximum(f, 1e-10) / min_log_hz) / logstep, mels)


def mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    f_sp = 200.0 / 3
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    freqs = f_sp * m
    log_t = m >= min_log_mel
    return np.where(log_t, min_log_hz * np.exp(logstep * (m - min_log_mel)), freqs)


def mel_filterbank(sr, n_fft, n_mels, fmin, fmax):
    fftfreqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = mel_to_hz(np.linspace(hz_to_mel(fmin), hz_to_mel(fmax), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    weights = np.zeros((n_mels, fftfreqs.size), dtype=np.float64)
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0.0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels])
    weights *= enorm[:, None]
    return weights.astype(np.float32)


def ensure_dir(p):
    os.makedirs(p, exist_ok=True)


def load_mono(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if sr == SR:
        return mono
    if sr < SR:
        from scipy.signal import resample_poly
        return resample_poly(mono, SR, sr).astype(np.float32)
    ratio = sr / SR
    n_out = int(len(mono) / ratio)
    idx = np.arange(n_out, dtype=np.float64) * ratio
    out = np.empty(n_out, dtype=np.float32)
    step = SR * 5
    for s in range(0, n_out, step):
        e = min(s + step, n_out)
        positions = idx[s:e]
        left = np.floor(positions).astype(np.int64)
        right = np.minimum(left + 1, len(mono) - 1)
        a = positions - left
        out[s:e] = (1.0 - a) * mono[left] + a * mono[right]
    return out


def load_packaged_mono(path):
    """解码实际打包的 m4a，保证参考特征与用户播放/外放的样本同源。"""
    raw = subprocess.check_output([
        FFMPEG, "-hide_banner", "-loglevel", "error", "-i", str(path),
        "-ac", "1", "-ar", str(SR), "-f", "s16le", "-acodec", "pcm_s16le", "-",
    ])
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def logmel(mono, filters, win, hop, n_fft):
    n = 1 + max(0, (len(mono) - win) // hop)
    padded = np.pad(mono, (0, max(0, (n - 1) * hop + win - len(mono))), mode="constant")
    window = np.hanning(win).astype(np.float32)
    feat = np.empty((n, filters.shape[0]), dtype=np.float32)
    chunk = 256
    for s in range(0, n, chunk):
        e = min(s + chunk, n)
        idx = np.arange(win)[None, :] + hop * np.arange(s, e)[:, None]
        frames = padded[idx] * window[None, :]
        spec = np.abs(np.fft.rfft(frames, n=n_fft, axis=1)) ** 2
        mel = spec @ filters.T
        feat[s:e] = np.log(mel + 1e-10).astype(np.float32)
    feat -= feat.mean(axis=1, keepdims=True)
    feat /= (feat.std(axis=1, keepdims=True) + 1e-6)
    return feat


def frame_rms(mono, win, hop):
    n = 1 + max(0, (len(mono) - win) // hop)
    padded = np.pad(mono, (0, max(0, (n - 1) * hop + win - len(mono))), mode="constant")
    out = np.empty(n, dtype=np.float32)
    chunk = 512
    for s in range(0, n, chunk):
        e = min(s + chunk, n)
        idx = np.arange(win)[None, :] + hop * np.arange(s, e)[:, None]
        frames = padded[idx]
        out[s:e] = np.sqrt(np.mean(frames * frames, axis=1))
    return out


def voiced_mask(rms):
    thr = max(float(np.percentile(rms, 25)) * 1.55, float(rms.max()) * 0.06, 1e-4)
    v = rms > thr
    # 闭运算：填补短于 6 帧的气口
    i = 0
    n = len(v)
    while i < n:
        if not v[i]:
            j = i
            while j < n and not v[j]:
                j += 1
            if 0 < i and j < n and (j - i) <= 6:
                v[i:j] = True
            i = j
        else:
            i += 1
    return v


def snap_to_pause(frame, voiced, max_dist=22):
    n = len(voiced)
    best = frame
    best_d = max_dist + 1
    i = 0
    while i < n:
        if not voiced[i]:
            j = i
            while j < n and not voiced[j]:
                j += 1
            mid = (i + j) // 2
            d = abs(mid - frame)
            if d <= max_dist and d < best_d:
                best, best_d = mid, d
            i = j
        else:
            i += 1
    return best


def char_units(line):
    return [0 if (ch in PUNCT) else 1 for ch in line]


REPEAT_KINDS = {"咒", "称名", "圣号", "回向", "赞", "赞佛", "献供", "香赞", "召请", "偈"}


def expand_repeats(lines, duration_ms, kind, target_char_ms=1300):
    """梵呗一句往往反复唱多轮。按唱诵时长展开重复轮次，
    让逐字游标以接近真实语速推进，而不是一字停留十几秒。"""
    if kind not in REPEAT_KINDS:
        return lines
    chars = sum(sum(char_units(l)) for l in lines)
    if chars <= 0 or duration_ms <= 0:
        return lines
    avg = duration_ms / chars
    if avg <= 3000:
        return lines
    cycles = max(1, min(24, int(round(avg / target_char_ms))))
    return lines * cycles


def build_timings(lines, rms):
    """按唱诵能量分布，生成逐句与逐字时间轴（毫秒）。"""
    n = len(rms)
    v = voiced_mask(rms)
    vidx = np.where(v)[0]
    if len(vidx) < 20:
        step = max(1, n // (len(lines) + 1))
        starts = [i * step * HOP // SR * 1000 for i in range(len(lines))]
        ends = [(i + 1) * step * HOP // SR * 1000 for i in range(len(lines))]
        char_starts = [[s for _ in line] for line, s in zip(lines, starts)]
        return starts, ends, char_starts

    weights = [max(1, sum(char_units(l))) for l in lines]
    total = sum(weights)
    cum = np.cumsum(weights)[:-1]

    bounds = []
    for c in cum:
        pos = int(round(c / total * len(vidx)))
        f = int(vidx[min(len(vidx) - 1, pos)])
        f = snap_to_pause(f, v)
        bounds.append(f)

    for i in range(1, len(bounds)):
        bounds[i] = max(bounds[i], bounds[i - 1] + 10)

    starts_f = [0] + bounds
    ends_f = bounds + [n - 1]

    line_starts, line_ends, char_starts = [], [], []
    for idx, line in enumerate(lines):
        f0 = starts_f[idx]
        f1 = max(f0 + 5, ends_f[idx])
        line_starts.append(int(f0 * HOP / SR * 1000))
        line_ends.append(int(f1 * HOP / SR * 1000))
        units = char_units(line)
        weight = sum(units)
        vf = list(range(f0, f1))
        if weight == 0:
            char_starts.append([line_starts[-1]] * len(line))
            continue
        acc = 0.0
        times = []
        for u in units:
            pos = int(round(acc / weight * len(vf)))
            pos = min(pos, len(vf) - 1)
            times.append(int(vf[pos] * HOP / SR * 1000))
            acc += u
        char_starts.append(times)
    return line_starts, line_ends, char_starts


# ---------------------------------------------------------------- 主流程
def find_source(prefer_dir, src_id, ext):
    """在多个音源缓存目录中查找，返回第一个有效文件路径。"""
    for d in (prefer_dir, SRC2_DIR, SRC3_DIR, SRC_DIR):
        for e in (ext, ".wav", ".m4a", ".ogg", ".mp3"):
            p = os.path.join(d, src_id + e)
            if os.path.exists(p) and os.path.getsize(p) > 10000:
                return p
    return None


def run_ai_align(jobs):
    """jobs: [{id, audio, lines, durationMs}] → {id: result}。子进程失败返回空 dict。"""
    if not jobs:
        return {}
    with open(ALIGN_IN, "w", encoding="utf-8") as f:
        json.dump({"items": jobs}, f, ensure_ascii=False)
    model = MODEL_DIR if (os.path.isdir(MODEL_DIR) and os.listdir(MODEL_DIR)) else "small"
    env = dict(os.environ)
    env["PYTHONPATH"] = _PYLIBS + os.pathsep + env.get("PYTHONPATH", "")
    env["HF_ENDPOINT"] = "https://hf-mirror.com"
    env["HF_HOME"] = r"d:\Buddha\.cache\hf"
    env["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    print(f"[ai] running whisper_align on {len(jobs)} segments (model={model})...", flush=True)
    try:
        subprocess.run(
            [sys.executable, WHISPER_ALIGN, "--input", ALIGN_IN, "--output", ALIGN_OUT, "--model", model],
            env=env, check=True,
        )
    except Exception as e:
        print(f"[ai] align subprocess failed: {e}; all segments fall back to energy", flush=True)
        return {}
    try:
        with open(ALIGN_OUT, "r", encoding="utf-8") as f:
            results = json.load(f)
        return {r["id"]: r for r in results}
    except Exception as e:
        print(f"[ai] cannot read align output: {e}; all fall back to energy", flush=True)
        return {}


def main():
    audio_dir = os.path.join(APP_DIR, "audio")
    feat_dir = os.path.join(APP_DIR, "features")
    model_dir = os.path.join(APP_DIR, "models")
    cer_dir = os.path.join(APP_DIR, "ceremonies")
    for d in (audio_dir, feat_dir, model_dir, cer_dir):
        ensure_dir(d)

    filters = mel_filterbank(SR, N_FFT, N_MELS, FMIN, FMAX)
    filters.tofile(os.path.join(model_dir, "mel32.f32"))
    print("mel filters:", filters.shape)

    # Phase A：解析每段（转码/特征/能量/展开文本），收集 AI 对齐任务
    ceremonies_data = []
    align_jobs = []
    for cer in CEREMONIES:
        segs = []
        for tup in cer["segments"]:
            src_id, sid, no, title, kind, lines, hint, action = tup[:8]
            asset_sid = tup[8] if len(tup) > 8 else None
            src = find_source(cer["src_dir"], src_id, cer["ext"])
            if not src:
                print("SKIP missing", src_id)
                continue
            asset = asset_sid or sid            # 复用音轨：不重复转码/提特征
            m4a = os.path.join(audio_dir, asset + ".m4a")
            if not os.path.exists(m4a) or os.path.getsize(m4a) < 1000:
                subprocess.run([FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-i", src,
                                "-ac", "1", "-ar", "24000", "-c:a", "aac", "-b:a", "40k", m4a], check=True)
            mono = load_mono(src)
            dur_ms = int(round(len(mono) / SR * 1000))
            n_frames = 1 + max(0, (len(mono) - WIN) // HOP)
            feat_path = os.path.join(feat_dir, asset + ".f32")
            need_feat = (not os.path.exists(feat_path)
                         or os.path.getsize(feat_path) != n_frames * N_MELS * 4)
            if need_feat:
                feat = logmel(load_packaged_mono(m4a), filters, WIN, HOP, N_FFT)
                if len(feat) < n_frames:
                    feat = np.pad(feat, ((0, n_frames - len(feat)), (0, 0)), mode="edge")
                feat[:n_frames].tofile(feat_path)
            rms = frame_rms(mono, WIN, HOP)
            lines_orig = list(lines)                          # 原始法本：AI 对齐的参考
            lines = expand_repeats(lines, dur_ms, kind)       # 展开：仅能量法回退时显示
            info = dict(sid=sid, asset=asset, no=no, title=title, kind=kind,
                        lines=lines, lines_orig=lines_orig,
                        rms=rms, dur_ms=dur_ms, n_frames=n_frames, hint=hint, action=action)
            segs.append(info)
            align_jobs.append({"id": sid, "audio": src, "lines": lines_orig, "durationMs": dur_ms})
        ceremonies_data.append((cer, segs))

    # Phase B：一次子进程完成所有段的 AI 逐字对齐（模型只加载一次）
    ai_results = run_ai_align(align_jobs)

    # Phase C：组装 JSON（AI 优先，失败/低覆盖回退能量法）
    for cer, segs in ceremonies_data:
        segments_json = []
        for info in segs:
            res = ai_results.get(info["sid"])
            if res and res.get("method") == "ai":
                display_lines = res["lines"]
                line_starts = res["lineStarts"]
                line_ends = res["lineEnds"]
                char_starts = res["charStarts"]
                method = "ai"
                match_rate = res.get("matchRate")
            else:
                display_lines = info["lines"]
                line_starts, line_ends, char_starts = build_timings(info["lines"], info["rms"])
                method = "energy"
                match_rate = None
            durs = []
            for row in char_starts:
                for a, b in zip(row, row[1:]):
                    durs.append(b - a)
            if durs:
                dmin, dmax, davg = min(durs), max(durs), sum(durs) / len(durs)
                mr = f" rate={match_rate:.2f}" if match_rate is not None else ""
                print(f"{info['sid']:12s} {method:7s} {info['title'][:10]:10s} "
                      f"{info['dur_ms']/1000:6.1f}s lines={len(display_lines)} "
                      f"dwell min={dmin} max={dmax} avg={davg:.0f}ms{mr}")
            else:
                print(f"{info['sid']:12s} {method:7s} {info['title'][:10]:10s} (no dwell)")
            segments_json.append({
                "id": info["sid"],
                "no": info["no"],
                "title": info["title"],
                "kind": info["kind"],
                "method": method,
                "matchRate": match_rate,
                "audio": "audio/" + info["asset"] + ".m4a",
                "features": "features/" + info["asset"] + ".f32",
                "frames": info["n_frames"],
                "durationMs": info["dur_ms"],
                "lines": display_lines,
                "lineStarts": line_starts,
                "lineEnds": line_ends,
                "charStarts": char_starts,
                "hint": info["hint"],
                "action": info["action"],
            })
        ceremony = {
            "id": cer["id"], "order": cer["order"], "title": cer["title"],
            "subtitle": cer["subtitle"], "source": cer["source"], "segments": segments_json,
        }
        path = os.path.join(cer_dir, cer["id"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(ceremony, f, ensure_ascii=False, indent=2)
        print("written", path, "segments:", len(segments_json))


if __name__ == "__main__":
    sys.exit(main())
