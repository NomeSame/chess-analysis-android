import numpy as np, wave
SR=44100; rng=np.random.RandomState(1)
def load(n):
    with wave.open(n) as w: return np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(float)/32768
def save(n,x,peak=0.96):
    x=x/(np.max(np.abs(x)) or 1)*peak
    with wave.open(n,'w') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x*32767).astype(np.int16).tobytes())
def lp(x,fc):
    rc=1/(2*np.pi*fc); a=(1/SR)/(rc+1/SR); y=np.empty_like(x); acc=0.0
    for i in range(len(x)): acc+=a*(x[i]-acc); y[i]=acc
    return y
def resample(x,s):
    idx=np.arange(0,len(x),s); return np.interp(idx,np.arange(len(x)),x)
def synth_knock(n,f):
    t=np.arange(n)/SR
    s=lp(rng.randn(n),1500)*np.exp(-t/0.006)
    s+=0.5*np.sin(2*np.pi*f*t)*np.exp(-t/0.02)+0.4*np.sin(2*np.pi*(f/2)*t)*np.exp(-t/0.04)
    return s
def trim_window(src,win_ms):
    x=load(src); e=np.convolve(np.abs(x),np.ones(int(SR*0.003))/int(SR*0.003),'same')
    start=max(0,np.argmax(e>0.06*e.max())-int(SR*0.005))
    return x[start:start+int(SR*win_ms/1000)].copy()
def finish(seg,dst,fade_ms=55):
    fo=int(SR*fade_ms/1000); seg[-fo:]*=np.linspace(1,0,fo)
    fi=int(SR*0.001); seg[:fi]*=np.linspace(0,1,fi); save(dst,seg)

# WOOD = light derivation (close to original): trim + tail-cut + bass + normalize
def wood(src,dst,win_ms,bass):
    seg=trim_window(src,win_ms); seg=seg+bass*lp(seg,180.0); finish(seg,dst)
    print("wood ",dst)
# TEST = heavy: + pitch-shift + synth-knock blend + saturation
def test(src,dst,win_ms,semis,bass,sat,blend,synth_f):
    seg=trim_window(src,win_ms); seg=resample(seg,2**(semis/12.0)); seg=seg+bass*lp(seg,180.0)
    n=len(seg); k=synth_knock(n,synth_f); k*=(np.max(np.abs(seg)) or 1)/(np.max(np.abs(k)) or 1)
    seg=(1-blend)*seg+blend*k; seg=np.tanh(sat*seg)/np.tanh(sat); finish(seg,dst)
    print("test ",dst)

refs=[("NormalPieceMove","move",220),("Capture","capture",240),("Castle","castle",330),
      ("Check","check",300),("Checkmate","checkmate",380)]
for src,name,win in refs:
    wood(f"{src}.wav", f"wood_{name}.wav", win, 0.7 if name in("capture","checkmate") else 0.5 if name!="check" else 0.4)
test("NormalPieceMove.wav","test_move.wav",     220,-2.0,0.5,2.2,0.32,300)
test("Capture.wav",        "test_capture.wav",  240,-1.5,0.7,2.6,0.32,360)
test("Castle.wav",         "test_castle.wav",   330,-2.0,0.5,2.2,0.32,300)
test("Check.wav",          "test_check.wav",    300,-1.0,0.4,1.9,0.26,600)
test("Checkmate.wav",      "test_checkmate.wav",380,-3.0,0.8,2.8,0.36,180)

# --- TESTSYNTH = 70% own synth (envelope-shaped) + 30% pitch-shifted original coloring ---
def fit(x,n):
    return x[:n] if len(x)>=n else np.pad(x,(0,n-len(x)))
def mix70(src,dst,win_ms,semis,synf,synlp,bass=0.5,sat=1.9,synth_share=0.70):
    seg=trim_window(src,win_ms); n=len(seg); t=np.arange(n)/SR
    env=np.convolve(np.abs(seg),np.ones(int(SR*0.004))/int(SR*0.004),'same')
    env=env/(env.max() or 1)
    syn=(lp(rng.randn(n),synlp) + 0.5*np.sin(2*np.pi*synf*t) + 0.3*np.sin(2*np.pi*2*synf*t))*env
    syn=syn/(np.max(np.abs(syn)) or 1)
    orig=fit(resample(seg,2**(semis/12.0)),n); orig=orig/(np.max(np.abs(orig)) or 1)
    mix=synth_share*syn + (1-synth_share)*orig
    mix=mix+bass*lp(mix,180.0)
    mix=np.tanh(sat*mix)/np.tanh(sat)
    finish(mix,dst); print("testsynth ",dst)

mix70("NormalPieceMove.wav","testsynth_move.wav",     220,-2.0,300,1400,0.5)
mix70("Capture.wav",        "testsynth_capture.wav",  240,-1.5,200,2200,0.7,2.2)
mix70("Castle.wav",         "testsynth_castle.wav",   330,-2.0,300,1400,0.5)
mix70("Check.wav",          "testsynth_check.wav",    300,-1.0,600,3800,0.4,1.6)
mix70("Checkmate.wav",      "testsynth_checkmate.wav",380,-3.0,150,1500,0.8,2.4)
