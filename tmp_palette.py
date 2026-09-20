import subprocess, sys, struct

def raw(png, w=12, h=26):
    # scale to w x h and dump rgb24
    out = subprocess.run(["ffmpeg","-v","quiet","-i",png,"-vf",f"scale={w}:{h}","-f","rawvideo","-pix_fmt","rgb24","-"],capture_output=True)
    return out.stdout

def analyze(png):
    W,H = 12,26
    b = raw(png,W,H)
    px = [[tuple(b[(y*W+x)*3:(y*W+x)*3+3]) for x in range(W)] for y in range(H)]
    flat = [p for row in px for p in row]
    avg = tuple(sum(c[i] for c in flat)//len(flat) for i in range(3))
    print(f"file={png}")
    print(f"avg=#{avg[0]:02X}{avg[1]:02X}{avg[2]:02X}")
    for y,row in enumerate(px):
        print(f"r{y:02d} " + " ".join(f"{p[0]:02X}{p[1]:02X}{p[2]:02X}" for p in row))

for f in sys.argv[1:]:
    analyze(f)
    print()
