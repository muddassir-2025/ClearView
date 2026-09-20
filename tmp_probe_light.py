import subprocess, sys, collections

def rgb(png, w, h):
    out = subprocess.run(["ffmpeg","-v","quiet","-i",png,"-vf",f"scale={w}:{h}","-f","rawvideo","-pix_fmt","rgb24","-"],capture_output=True).stdout
    return [[tuple(out[(y*w+x)*3:(y*w+x)*3+3]) for x in range(w)] for y in range(h)]

def crop_avg(png, w, h, y0f, y1f):
    # full-res scale at w x H then average the row band
    H = 2400
    im = rgb(png, w, H)
    y0, y1 = int(H*y0f), int(H*y1f)
    cells = [p for row in im[y0:y1] for p in row]
    n = len(cells)
    return tuple(sum(c[i] for c in cells)//n for i in range(3))

def dominant(png, top=14, w=108, h=240):
    im = rgb(png, w, h)
    c = collections.Counter(p for row in im for p in row)
    out = []
    for (r,g,b), n in c.most_common(top):
        out.append((f"#{r:02X}{g:02X}{b:02X}", n * 100.0 / (w*h)))
    return out

def hexs(t): return f"#{t[0]:02X}{t[1]:02X}{t[2]:02X}"

for f in sys.argv[1:]:
    print("==", f)
    for name, a, b in [("status bar 0-3%",0.0,0.03),("app bar 3-8%",0.03,0.08),
                       ("background band 33-36%",0.33,0.36),("bottom nav 88-95%",0.88,0.95),
                       ("system nav 97-100%",0.97,1.0)]:
        print(f"  {name:24s} {hexs(crop_avg(f,60,2400,a,b))}")
    print("  dominant:")
    for hx, pct in dominant(f):
        print(f"    {hx}  {pct:5.1f}%")
