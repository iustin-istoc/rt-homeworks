"""
Generează diagrama de timp a celor trei taskuri ale device-ului, în două formate
din ACEEAȘI descriere a scenariului (deci sursa și imaginea nu pot diverge):

  diagrama-de-timp.drawio   sursă editabilă (draw.io / app.diagrams.net)
  diagrama-de-timp.png      imagine exportată (matplotlib)

Rulare:  python3 genereaza_diagrama_de_timp.py

Model: un singur procesor, priorități fixe cu preempțiune
periodic > sporadic > aperiodic. Duratele C sunt EXAGERATE ca să se vadă
(măsurat: C ≈ 1–2 ms la periodic); momentele de eliberare respectă codul.
"""
from xml.sax.saxutils import escape
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, FancyArrow

# ------------------------------------------------------------------ scenariul
T_END = 10.5                      # s
PX_PER_S = 120
X0 = 300                          # x pentru t = 0
W = int(X0 + T_END * PX_PER_S + 120)

LANES = {                         # y-ul liniei de bază a fiecărui task
    "periodic": 330,
    "sporadic": 540,
    "aperiodic": 740,
}
AXIS_Y = 820
H = 1240
BOX_H = 55

COL_RUN = {"periodic": "#A9C4EB", "sporadic": "#F8C99A", "aperiodic": "#D8A7C2"}
COL_BLUE, COL_RED = "#1F5FBF", "#C62828"

C_P, C_S, C_A = 0.10, 0.15, 0.15

# taskul periodic: T = 1 s; SET_INTERVAL 2000 executat la t = 6.2
# -> jobul 6 calculase deja r7 = 6 + 1; jobul 7 citește T = 2 -> r8 = 7 + 2 = 9
periodic_releases = [(0, 1), (1, 1), (2, 1), (3, 1), (4, 1), (5, 1), (6, 1), (7, 2), (9, 2)]

shapes = []   # (tip, parametri)


def x(t):
    return X0 + t * PX_PER_S


def run(lane, t0, t1):
    shapes.append(("box", dict(x=x(t0), y=LANES[lane] - BOX_H, w=(t1 - t0) * PX_PER_S, h=BOX_H,
                               fill=COL_RUN[lane], stroke="#333333", dashed=False)))


def ready(lane, t0, t1):
    shapes.append(("box", dict(x=x(t0), y=LANES[lane] - BOX_H * 0.6, w=(t1 - t0) * PX_PER_S, h=BOX_H * 0.6,
                               fill="#F2F2F2", stroke="#999999", dashed=True)))


def arrow(x1, y1, x2, y2, color, filled=True, dashed=False, head=True):
    shapes.append(("arrow", dict(x1=x1, y1=y1, x2=x2, y2=y2, color=color, filled=filled,
                                 dashed=dashed, head=head)))


def text(xx, yy, s, size=13, bold=False, color="#222222", align="left"):
    shapes.append(("text", dict(x=xx, y=yy, s=s, size=size, bold=bold, color=color, align=align)))


def release(lane, t):                       # eliberare periodică
    b = LANES[lane]
    arrow(x(t), b, x(t), b - 95, COL_BLUE)


def event_accepted(lane, t):
    b = LANES[lane]
    arrow(x(t), b, x(t), b - 95, COL_BLUE, filled=False, dashed=True)


def event_rejected(lane, t):
    b = LANES[lane]
    arrow(x(t), b, x(t), b - 80, COL_RED, dashed=True, head=False)
    text(x(t), b - 92, "✕", size=18, bold=True, color=COL_RED, align="center")


def deadline(lane, t, dx=-5):
    b = LANES[lane]
    arrow(x(t) + dx, b - 95, x(t) + dx, b, COL_RED)


# titlu
text(20, 40, "Diagramă de timp: taskurile device-ului (periodic > sporadic > aperiodic, cu preempțiune)",
     size=22, bold=True)
text(20, 78, "Timp în s. Un procesor, priorități fixe. C este exagerat ca să se vadă (măsurat: C ≈ 1–2 ms "
             "la periodic). T inițial 1 s, interval minim între sosiri 5 s.", size=13, color="#555555")

# liniile de bază și grila verticală
for lane, b in LANES.items():
    arrow(x(0), b, x(T_END), b, "#333333", head=False)
for t in range(0, 11):
    arrow(x(t), 150, x(t), AXIS_Y, "#DDDDDD", dashed=True, head=False)

# etichetele taskurilor
text(20, LANES["periodic"] - 70, "τ1 periodic – telemetrie", size=15, bold=True)
text(20, LANES["periodic"] - 46, "C = 0.1, T = 1 → 2 (SET_INTERVAL)", size=12, color="#444444")
text(20, LANES["periodic"] - 26, "D = T, prioritate maximă", size=12, color="#444444")
text(20, LANES["sporadic"] - 70, "τ2 sporadic – alarmă", size=15, bold=True)
text(20, LANES["sporadic"] - 46, "C = 0.15, D = 0.5", size=12, color="#444444")
text(20, LANES["sporadic"] - 26, "interval minim între sosiri 5", size=12, color="#444444")
text(20, LANES["aperiodic"] - 70, "τ3 aperiodic – comenzi", size=15, bold=True)
text(20, LANES["aperiodic"] - 46, "C = 0.15, fără termen (soft)", size=12, color="#444444")
text(20, LANES["aperiodic"] - 26, "prioritate minimă, FIFO", size=12, color="#444444")

# ---- τ1 periodic
for r, T in periodic_releases:
    release("periodic", r)
    run("periodic", r, r + C_P)
    if r + T <= T_END:
        deadline("periodic", r + T)
for r in (2, 4, 7):
    text(x(r) + 18, LANES["periodic"] - 108, "temp > prag", size=11, color=COL_RED)
text(x(7.15), LANES["periodic"] - 140, "jobul 7 citește T = 2 s → r₈ = 7 + 2 = 9 (fără derivă)",
     size=12, color=COL_BLUE)

# ---- τ2 sporadic: evenimente puse de τ1 la ~0.05 s după eliberarea lui
event_accepted("sporadic", 2.05)
ready("sporadic", 2.05, 2.10)
run("sporadic", 2.10, 2.25)
deadline("sporadic", 2.55, dx=0)
text(x(2.05) + 8, LANES["sporadic"] - 108, "acceptat (prima alarmă)", size=12, color=COL_BLUE)

event_rejected("sporadic", 4.05)
text(x(4.05) + 14, LANES["sporadic"] - 108, "respins: 4.05 − 2.05 = 2 < 5", size=12, color=COL_RED)

event_accepted("sporadic", 7.05)
ready("sporadic", 7.05, 7.10)
run("sporadic", 7.10, 7.25)
deadline("sporadic", 7.55, dx=0)
text(x(7.05) + 8, LANES["sporadic"] - 108, "acceptat: 7.05 − 2.05 = 5 ≥ 5", size=12, color=COL_BLUE)

# ---- τ3 aperiodic: SET_INTERVAL 2000 sosește la 5.95, e preemptat de τ1 la 6.0
event_accepted("aperiodic", 5.95)
run("aperiodic", 5.95, 6.00)
ready("aperiodic", 6.00, 6.10)
run("aperiodic", 6.10, 6.20)
text(x(5.95) - 200, LANES["aperiodic"] - 108, "comanda SET_INTERVAL 2000", size=12, color=COL_BLUE)
text(x(6.2) + 8, LANES["aperiodic"] - 20, "publică „OK SET_INTERVAL 2000” (R = 0.25)", size=12, color="#444444")

# ---- axa timpului
arrow(x(0), AXIS_Y, x(T_END) + 25, AXIS_Y, "#000000")
for t in range(0, 11):
    arrow(x(t), AXIS_Y - 8, x(t), AXIS_Y + 8, "#000000", head=False)
    text(x(t), AXIS_Y + 28, str(t), size=13, align="center")
text(x(T_END) + 35, AXIS_Y, "t (s)", size=15)

# ---- legendă
LY = 900
text(20, LY, "Legendă", size=15, bold=True)
shapes.append(("box", dict(x=20, y=LY + 25, w=70, h=34, fill=COL_RUN["periodic"], stroke="#333333", dashed=False)))
text(105, LY + 42, "jobul rulează (Running)", size=13)
shapes.append(("box", dict(x=430, y=LY + 25, w=70, h=34, fill="#F2F2F2", stroke="#999999", dashed=True)))
text(515, LY + 42, "jobul așteaptă procesorul (Ready)", size=13)
arrow(900, LY + 62, 900, LY + 20, COL_BLUE)
text(925, LY + 42, "eliberare periodică", size=13)
arrow(55, LY + 120, 55, LY + 78, COL_BLUE, filled=False, dashed=True)
text(105, LY + 100, "eveniment acceptat → eliberare", size=13)
arrow(465, LY + 120, 465, LY + 90, COL_RED, dashed=True, head=False)
text(465, LY + 82, "✕", size=18, bold=True, color=COL_RED, align="center")
text(515, LY + 100, "eveniment respins", size=13)
arrow(900, LY + 78, 900, LY + 120, COL_RED)
text(925, LY + 100, "termen-limită absolut d = r + D", size=13)

# ---- ce se vede
NY = 1060
text(20, NY, "Ce se vede în diagramă", size=15, bold=True)
notes = [
    "• τ1 este eliberat pe grilă (așteptare absolută); depășirile de prag la t = 2, 4, 7 pun câte un eveniment în coada τ2.",
    "• τ2: evenimentul de la 4.05 este respins (doar 2 s de la eliberarea precedentă); cel de la 7.05 este acceptat. R = 0.2 ≤ D = 0.5.",
    "• τ3 sosește la 5.95, este preemptat de τ1 la t = 6 (Ready până la 6.1) și termină la 6.2.",
    "• SET_INTERVAL are efect de la jobul următor celui care rula: r₇ = 7 (calculat cu T = 1), apoi r₈ = 9 (T = 2).",
]
for i, n in enumerate(notes):
    text(20, NY + 30 + i * 26, n, size=13)


# ------------------------------------------------------------------ draw.io
def to_drawio(path):
    cells, n = [], 2

    def nid():
        nonlocal n
        n += 1
        return f"c{n}"

    for kind, p in shapes:
        if kind == "box":
            style = (f"rounded=0;whiteSpace=wrap;html=1;fillColor={p['fill']};strokeColor={p['stroke']};"
                     + ("dashed=1;" if p["dashed"] else ""))
            cells.append(f'<mxCell id="{nid()}" value="" style="{style}" vertex="1" parent="1">'
                         f'<mxGeometry x="{p["x"]:.1f}" y="{p["y"]:.1f}" width="{p["w"]:.1f}" '
                         f'height="{p["h"]:.1f}" as="geometry"/></mxCell>')
        elif kind == "arrow":
            end = ("endArrow=block;endFill=" + ("1" if p["filled"] else "0") + ";") if p["head"] else "endArrow=none;"
            style = (f"html=1;{end}strokeColor={p['color']};strokeWidth=2;"
                     + ("dashed=1;" if p["dashed"] else ""))
            cells.append(f'<mxCell id="{nid()}" value="" style="{style}" edge="1" parent="1">'
                         f'<mxGeometry relative="1" as="geometry">'
                         f'<mxPoint x="{p["x1"]:.1f}" y="{p["y1"]:.1f}" as="sourcePoint"/>'
                         f'<mxPoint x="{p["x2"]:.1f}" y="{p["y2"]:.1f}" as="targetPoint"/>'
                         f'</mxGeometry></mxCell>')
        elif kind == "text":
            w = max(30, len(p["s"]) * p["size"] * 0.6)
            xx = p["x"] - w / 2 if p["align"] == "center" else p["x"]
            style = (f"text;html=1;align={p['align']};verticalAlign=middle;fontSize={p['size']};"
                     f"fontColor={p['color']};" + ("fontStyle=1;" if p["bold"] else ""))
            cells.append(f'<mxCell id="{nid()}" value="{escape(p["s"], {chr(34): "&quot;"})}" style="{style}" '
                         f'vertex="1" parent="1"><mxGeometry x="{xx:.1f}" y="{p["y"] - p["size"]:.1f}" '
                         f'width="{w:.1f}" height="{p["size"] * 2}" as="geometry"/></mxCell>')
    xml = (f'<mxfile host="genereaza_diagrama_de_timp.py"><diagram id="timp" name="Diagramă de timp">'
           f'<mxGraphModel dx="{W}" dy="{H}" grid="0" gridSize="10" guides="1" tooltips="1" connect="1" '
           f'arrows="1" fold="1" page="1" pageScale="1" pageWidth="{W}" pageHeight="{H}" math="0" shadow="0">'
           f'<root><mxCell id="0"/><mxCell id="1" parent="0"/>' + "".join(cells) +
           '</root></mxGraphModel></diagram></mxfile>\n')
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)


# ------------------------------------------------------------------ PNG
def to_png(path):
    fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, W)
    ax.set_ylim(H, 0)
    ax.axis("off")
    fig.patch.set_facecolor("white")
    for kind, p in shapes:
        if kind == "box":
            ax.add_patch(Rectangle((p["x"], p["y"]), p["w"], p["h"], facecolor=p["fill"], edgecolor=p["stroke"],
                                   linestyle="--" if p["dashed"] else "-", linewidth=1.2, zorder=3))
        elif kind == "arrow":
            ls = (0, (4, 3)) if p["dashed"] else "-"
            if p["head"]:
                ax.annotate("", xy=(p["x2"], p["y2"]), xytext=(p["x1"], p["y1"]), zorder=4,
                            arrowprops=dict(arrowstyle="-|>,head_width=0.45,head_length=0.9",
                                            color=p["color"], lw=2, linestyle=ls,
                                            fc=p["color"] if p["filled"] else "white",
                                            shrinkA=0, shrinkB=0))
            else:
                lw = 1 if p["color"] == "#DDDDDD" else 2
                ax.plot([p["x1"], p["x2"]], [p["y1"], p["y2"]], color=p["color"], lw=lw, linestyle=ls,
                        zorder=1 if lw == 1 else 4)
        elif kind == "text":
            ax.text(p["x"], p["y"], p["s"], fontsize=p["size"] * 0.75, color=p["color"],
                    fontweight="bold" if p["bold"] else "normal", ha=p["align"], va="center",
                    family="DejaVu Sans", zorder=5)
    fig.savefig(path, dpi=150, facecolor="white")
    plt.close(fig)


if __name__ == "__main__":
    to_drawio("diagrama-de-timp.drawio")
    to_png("diagrama-de-timp.png")
    print("generat: diagrama-de-timp.drawio, diagrama-de-timp.png")
