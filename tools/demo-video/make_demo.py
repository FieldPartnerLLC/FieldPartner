# Renders a synthetic "glasses view" of a furnace control board for emulator testing.
import math, os, random
from PIL import Image, ImageDraw, ImageFont
W, H, FPS, SECS = 504, 896, 15, 24
os.makedirs("frames", exist_ok=True)
def font(sz, bold=False):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
        if os.path.exists(p): return ImageFont.truetype(p, sz)
    return ImageFont.load_default()
F_S, F_M, F_L, F_B = font(16), font(20), font(26, True), font(18, True)
random.seed(4)
for i in range(FPS * SECS):
    t = i / FPS
    # head sway like a person wearing glasses
    dx = int(10 * math.sin(t * 0.9)) ; dy = int(8 * math.sin(t * 0.6 + 1))
    img = Image.new("RGB", (W, H), (58, 60, 64))
    d = ImageDraw.Draw(img)
    # furnace cabinet interior
    d.rectangle([20+dx, 40+dy, W-20+dx, H-40+dy], fill=(176, 178, 172), outline=(90, 90, 90), width=4)
    # rating plate
    d.rectangle([40+dx, 70+dy, W-40+dx, 230+dy], fill=(220, 222, 214), outline=(60, 60, 60), width=2)
    d.text((55+dx, 80+dy), "GAS FURNACE  -  CARRIER", font=F_B, fill=(20, 20, 20))
    d.text((55+dx, 110+dy), "MODEL: 58STA090-12", font=F_M, fill=(20, 20, 20))
    d.text((55+dx, 138+dy), "SERIAL: 2419A12345", font=F_M, fill=(20, 20, 20))
    d.text((55+dx, 166+dy), "INPUT 90,000 BTU/H  NAT GAS", font=F_S, fill=(20, 20, 20))
    d.text((55+dx, 190+dy), "115V 60Hz 1PH  MFD 05/2019", font=F_S, fill=(20, 20, 20))
    # control board (green PCB)
    bx, by = 70+dx, 280+dy
    d.rectangle([bx, by, bx+360, by+250], fill=(28, 110, 60), outline=(10, 50, 20), width=3)
    for k in range(9):  # terminal strip
        d.rectangle([bx+20+k*36, by+200, bx+46+k*36, by+235], fill=(230, 230, 230), outline=(40,40,40))
    for k, lab in enumerate(["R", "W", "Y", "G", "C", "", "", "", ""]):
        d.text((bx+27+k*36, by+180), lab, font=F_S, fill=(255,255,255))
    d.text((bx+20, by+15), "CONTROL BOARD  HK42FZ", font=F_B, fill=(240, 240, 240))
    d.rectangle([bx+40, by+60, bx+120, by+120], fill=(30, 30, 30))   # relay
    d.rectangle([bx+150, by+60, bx+200, by+150], fill=(200, 160, 40))  # fuse
    d.text((bx+152, by+155), "3A", font=F_S, fill=(255,255,255))
    # status LED: 3 flashes then 2 s pause (pressure switch open)
    cyc = t % 3.8
    led_on = cyc < 1.8 and (cyc % 0.6) < 0.3
    cx, cy = bx+290, by+90
    d.ellipse([cx-16, cy-16, cx+16, cy+16], fill=(255, 40, 30) if led_on else (90, 20, 20), outline=(20,20,20), width=2)
    d.text((cx-28, cy+22), "STATUS", font=F_S, fill=(255,255,255))
    # fault code label on the door
    lx, ly = 50+dx, 560+dy
    d.rectangle([lx, ly, lx+404, ly+190], fill=(250, 248, 235), outline=(60,60,60), width=2)
    d.text((lx+12, ly+8), "LED FLASH CODES", font=F_B, fill=(10,10,10))
    codes = ["1  LOCKOUT, FLAME LOSS", "2  PRESSURE SWITCH CLOSED", "3  PRESSURE SWITCH OPEN", "4  HIGH LIMIT OPEN", "6  FLAME SENSED, NO CALL", "ON  NORMAL OPERATION"]
    for k, c in enumerate(codes):
        d.text((lx+12, ly+38+k*24), c, font=F_S, fill=(10,10,10))
    # gray hose to pressure switch, slightly kinked
    d.line([(W-60+dx, 300+dy), (W-40+dx, 420+dy), (W-70+dx, 470+dy), (W-45+dx, 540+dy)], fill=(40,40,40), width=9)
    d.text((W-150+dx, 250+dy), "PRESS SW", font=F_S, fill=(20,20,20))
    # handheld CO meter in the lower corner (tech's hand), appears after 6 s
    if t > 6:
        mx, my = 290, 760
        d.rounded_rectangle([mx, my, mx+190, my+120], radius=14, fill=(250, 200, 30), outline=(30,30,30), width=3)
        d.rectangle([mx+15, my+15, mx+175, my+75], fill=(190, 215, 190))
        d.text((mx+22, my+22), "CO  %d ppm" % (8 + int(t) % 5), font=F_L, fill=(10,10,10))
        d.text((mx+22, my+85), "AMBIENT", font=F_S, fill=(10,10,10))
    # mild sensor noise
    if i % 2 == 0:
        for _ in range(250):
            x, y = random.randrange(W), random.randrange(H)
            d.point((x, y), fill=(random.randrange(256),)*3)
    img.save("frames/f%04d.png" % i)
print("frames", FPS*SECS)
