import numpy as np, wave
SR=44100
def load(n):
    with wave.open(n) as w: return np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(float)/32768
def save(n,x,peak=0.97):
    x=x/(np.max(np.abs(x)) or 1)*peak
    with wave.open(n,'w') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x*32767).astype(np.int16).tobytes())
def lp(x,fc):
    rc=1/(2*np.pi*fc); a=(1/SR)/(rc+1/SR); y=np.empty_like(x); acc=0.0
    for i in range(len(x)): acc+=a*(x[i]-acc); y[i]=acc
    return y
def process(src, dst, win_ms, bass=0.6, fade_ms=55, tail_ms=None):
    x=load(src)
    env=np.convolve(np.abs(x), np.ones(int(SR*0.003))/int(SR*0.003), 'same')
    pk=env.max(); start=np.argmax(env>0.06*pk)
    start=max(0, start-int(SR*0.005))               # trim leading silence/padding
    end=min(len(x), start+int(SR*win_ms/1000))       # cut reverb tail -> "weniger Hall"
    seg=x[start:end].copy()
    # "satt": low-shelf bass boost (add a low-passed copy)
    seg = seg + bass*lp(seg, 180.0)
    # tail fade out
    fo=int(SR*fade_ms/1000); 
    if fo<len(seg): seg[-fo:]*=np.linspace(1,0,fo)
    fi=int(SR*0.001); seg[:fi]*=np.linspace(0,1,fi)
    save(dst, seg)
    print(f"{dst:16s} from {src:16s}  start={start/SR*1000:4.0f}ms  len={len(seg)/SR*1000:4.0f}ms")
# windows generous enough to keep all taps, short enough to kill most reverb
process("NormalPieceMove.wav","wood_move.wav",     220)
process("Capture.wav",        "wood_capture.wav",  240, bass=0.7)
process("Castle.wav",         "wood_castle.wav",   330)   # 3 taps
process("Check.wav",          "wood_check.wav",    300, bass=0.4)
process("Checkmate.wav",      "wood_checkmate.wav",380, bass=0.7)  # 2 taps
