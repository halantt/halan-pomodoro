# -*- coding: utf-8 -*-
"""把 freesound 真实录音加工成 60 秒无缝循环 mp3。"""
import os
import numpy as np
import lameenc
import soundfile as sf

SR = 44100
DUR = 60
RAW = os.environ.get('FS_RAW', 'fs_raw')          # 原始录音目录
OUT = os.environ.get('FLOWTIME_AUDIO', 'audio')  # 输出目录
rng = np.random.default_rng(7)


def load(path, target_sr=SR):
    x, sr = sf.read(path, dtype='float32')
    if x.ndim > 1:
        x = x.mean(1)
    if sr != target_sr:
        n = int(len(x) * target_sr / sr)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return x


def hp(x, lo=35):
    """去掉极低频隆隆与直流"""
    f = np.maximum(np.fft.rfftfreq(len(x), 1 / SR), 1e-6)
    m = 1 - np.exp(-(f / lo) ** 2)
    return np.fft.irfft(np.fft.rfft(x) * m, len(x))


def rms_norm(x, target):
    r = np.sqrt((x ** 2).mean()) + 1e-9
    return x * (target / r)


def xfade(a, b, fade):
    """把 b 交叉淡入接到 a 的尾部"""
    n = int(fade * SR)
    if len(a) < n or len(b) < n:
        return np.concatenate([a, b])
    r = 0.5 - 0.5 * np.cos(np.pi * np.linspace(0, 1, n))
    head = a[:-n]
    mid = a[-n:] * (1 - r) + b[:n] * r
    tail = b[n:]
    return np.concatenate([head, mid, tail])


def take(x, start, dur):
    i = int(start * SR)
    n = int(dur * SR)
    if i < 0: i = 0
    if i + n > len(x):
        n = len(x) - i
    return x[i:i + n].copy()


def make_loop(x, crossfade=3.0):
    n = int(DUR * SR)
    if len(x) < n + int(crossfade * SR):
        # 不够长就循环拼接
        need = n + int(crossfade * SR)
        reps = int(np.ceil(need / len(x)))
        x = np.tile(x, reps)
    xf = int(crossfade * SR)
    body = x[:n + xf]
    out = body[:n].copy()
    r = 0.5 - 0.5 * np.cos(np.pi * np.linspace(0, 1, xf))
    out[:xf] = out[:xf] * r + body[n:n + xf] * (1 - r)
    return out


def save(name, x, rms=0.085, bitrate=80):
    x = make_loop(x)
    x = hp(x, 35)
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.96
    x *= rms / (np.sqrt((x ** 2).mean()) + 1e-9)
    x = np.clip(x, -0.97, 0.97)
    pcm = (x * 32767).astype(np.int16).tobytes()
    e = lameenc.Encoder()
    e.set_bit_rate(bitrate); e.set_in_sample_rate(SR); e.set_channels(1); e.set_quality(3)
    data = e.encode(pcm) + e.flush()
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(data)
    print(f'{name:12s} {len(data)/1024:7.1f} KB  rms={np.sqrt((x**2).mean()):.3f}')


def cat_segments(files, seg_len, fade=2.5, rms=0.03, starts=None):
    """把多段不同录音按 rms 对齐后交叉拼接"""
    out = None
    for i, f in enumerate(files):
        x = load(os.path.join(RAW, f))
        st = starts[i] if starts else float(rng.uniform(0.5, max(0.6, len(x) / SR - seg_len - 1)))
        seg = take(x, st, seg_len)
        seg = hp(seg, 35)
        seg = rms_norm(seg, rms)
        out = seg if out is None else xfade(out, seg, fade)
    return out


if __name__ == '__main__':
    # 写字：四段不同录音拼接
    pen = cat_segments(['pen/383866.mp3', 'pen/333028.mp3', 'pen/849751.mp3', 'pen/333027.mp3'],
                       16.0, fade=3.0, rms=0.03)
    save('pen.mp3', pen, rms=0.075)

    # 溪流：单段 65 秒
    stream = cat_segments(['stream/584270.mp3'], 63.0, starts=[1.0], rms=0.05)
    save('stream.mp3', stream, rms=0.085)

    # 夏夜：两段拼接
    night = cat_segments(['night/746366.mp3', 'night/436528.mp3'], 32.0, fade=3.0, rms=0.02)
    save('night.mp3', night, rms=0.08)

    # 风扇：单段 60 秒（纯运行段）
    fan = cat_segments(['fan/170870.mp3'], 63.0, starts=[1.0], rms=0.05)
    save('fan.mp3', fan, rms=0.08)

    # 键盘：单段 101 秒，取中段
    kb = cat_segments(['keyboard/860569.mp3'], 63.0, starts=[12.0], rms=0.05)
    save('keyboard.mp3', kb, rms=0.07)

    # 咖啡厅：从 600 秒录音里取一段
    cafe = cat_segments(['cafe/422097.mp3'], 63.0, starts=[90.0], rms=0.05)
    save('cafe.mp3', cafe, rms=0.085)
