import numpy as np, wave
SR=44100; rng=np.random.RandomState(0)

def lowpass(x, fc):
    rc=1.0/(2*np.pi*fc); a=(1.0/SR)/(rc+1.0/SR)
    y=np.empty_like(x); acc=0.0
    for i in range(len(x)): acc+=a*(x[i]-acc); y[i]=acc
    return y
def env(n,tau): return np.exp(-(np.arange(n)/SR)/tau)

def hit(dur, click_lp, click_tau, res, sub=None, sub_amp=0.0, sub_tau=0.03,
        out_lp=1500, sat=2.0):
    """Percussive knock = lowpassed NOISE impact (dominant) + very short low resonance(s) + body sub.
       res = list of (freq, amp, tau). Short taus => 'knock' not 'bell'."""
    n=int(SR*dur); t=np.arange(n)/SR
    # impact = filtered noise burst, fast decay -> the 'tock'
    sig = lowpass(rng.randn(n), click_lp) * env(n, click_tau) * 1.0
    # brief tonal color (kept very short so it doesn't ring metallic)
    for f,a,tau in res:
        sig += a*np.sin(2*np.pi*f*t)*env(n,tau)
    # low body 'sub' for fullness
    if sub: sig += sub_amp*np.sin(2*np.pi*sub*t)*env(n,sub_tau)
    sig = lowpass(sig, out_lp)
    sig = np.tanh(sat*sig)/np.tanh(sat)
    fi=int(SR*0.0008); sig[:fi]*=np.linspace(0,1,fi)
    fo=int(SR*0.012);  sig[-fo:]*=np.linspace(1,0,fo)
    return sig

def taps(parts, gaps_ms):
    total=int(SR*(sum(gaps_ms))/1000)+len(parts[-1])
    out=np.zeros(total)
    for i,p in enumerate(parts):
        pos=int(SR*sum(gaps_ms[:i])/1000)
        out[pos:pos+len(p)]+=p
    return out
def save(name,x,peak=0.95):
    x=x/(np.max(np.abs(x)) or 1)*peak
    with wave.open(name,'w') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x*32767).astype(np.int16).tobytes())
    print("wrote",name)

# move: soft low tock, noise-led, tiny resonance, short
save("wood_move.wav", hit(0.11, click_lp=1300, click_tau=0.007,
     res=[(300,0.35,0.012)], sub=150, sub_amp=0.6, sub_tau=0.025, out_lp=1500))
# capture: harder/louder, a touch brighter click + stronger body
save("wood_capture.wav", hit(0.13, click_lp=2200, click_tau=0.009,
     res=[(360,0.4,0.012)], sub=150, sub_amp=0.9, sub_tau=0.035, out_lp=2200, sat=2.4))
# castle: three soft taps
mv=lambda: hit(0.10, click_lp=1300, click_tau=0.006, res=[(300,0.3,0.010)], sub=160, sub_amp=0.55, sub_tau=0.022, out_lp=1500)
save("wood_castle.wav", taps([mv(),mv(),mv()],[0,41,92]))
# check: brighter knock (more click HF), slightly longer, still not a bell
save("wood_check.wav", hit(0.16, click_lp=3800, click_tau=0.012,
     res=[(520,0.3,0.018),(900,0.18,0.012)], sub=260, sub_amp=0.4, sub_tau=0.03, out_lp=3800, sat=1.8))
# checkmate: two deep punchy thuds
ck=lambda: hit(0.16, click_lp=1500, click_tau=0.010, res=[(180,0.4,0.014)], sub=110, sub_amp=1.0, sub_tau=0.05, out_lp=1600, sat=2.6)
save("wood_checkmate.wav", taps([ck(),ck()],[0,128]))
