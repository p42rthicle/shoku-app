"""
Bring back the INDB recipes that were left out of reference_indb.csv because INDB counts all the
frying oil in the pan (typically 480 ml or "2 C") as eaten.

How INDB computes a recipe (checked on Poori, ASC107, which it reproduces exactly: 738 kcal, 77.6 g fat):
  per 100 g = sum of ingredient nutrients / sum of raw ingredient weights * 100
  with ml counted as g, tsp = 5, tbsp = 15, C = 240 (fat balance on banana chips / chicken kebab),
  sunflower oil = 900 kcal and 100 g fat per 100 g, and no cooking water loss.
  Total weight W = (100 * unit_serving_kcal / kcal) * no_of_servings * size_of_servings, which matches
  the summed ingredient weights (checked below).

Correction, using only INDB's own numbers:
  food part (everything except the frying oil) = per100 * W / 100 - frying oil nutrients
  food raw weight Wf = W - O
  dry matter  = protein + carbs + fat + fibre of the food part + ash (1.5 % of Wf); water = Wf - dry
  fried items: the fryer drives water off until the item holds w_end water and a absorbed oil:
               cooked = dry / (1 - a - w_end)   (never more water than the raw food had)
  dishes where only one part is fried, or that go back into syrup/gravy: water unchanged,
               cooked = Wf / (1 - a)
  absorbed oil = a * cooked;  new per 100 g = (food part + absorbed oil) / cooked * 100

Absorbed oil (a, share of the cooked weight) and water left in the fried item (w_end):
  - puri / bhatura (thin rolled dough):              a 0.12, w_end 0.28  (8-12 % range)
  - samosa, spring roll, bread roll, cheese balls:  a 0.10, w_end 0.42 (moist filling)
  - kachori (drier stuffing, flaky crust):           a 0.10, w_end 0.30
  - gujiya / gunjia (sweet khoya filling):           a 0.10, w_end 0.15
  - mathri (short, dry dough):                       a 0.15, w_end 0.05
  - pakora, bhaji, bonda, vada, fritters (batter):   a 0.12, w_end 0.40
  - cutlet, tikki, kebab, croquette:                 a 0.08, w_end 0.50
  - battered / crumbed fish, chicken, meat:          a 0.05, w_end 0.55  (~5 % for coated fish/chicken)
  - thin crisps (sev, papdi, namak paras, chips):    a 0.20-0.30 per recipe, w_end 0.04
  - fried then soaked in syrup (gulab jamun, malpua): a 0.07, water unchanged
  - tawa / shallow fried (cheela, uttapam):          a 0.05, water unchanged
  - dishes where only one part is fried (kofta curry, dahi vada, chilli paneer, manchurian, raita):
                                                     a 0.04, water unchanged (oil spread over the dish)
Final moisture figures are typical for these foods (IFCT 2017 / USDA SR values for puri, samosa,
pakora, cutlets, fried fish, chips).
Sources for the ranges: Saguy & Dana (1998, J Food Eng) and Mellema (2003, Trends Food Sci Tech)
reviews of oil uptake in frying (doughs and batters 8-25 %, coated meat/fish 3-8 %, thin chips 20-40 %);
Indian snack measurements in Kochhar & Gertz, and ICMR-NIN guidance that fried snacks take up 10-20 %.
These are deliberately mid-range; the app marks reference values as typical (~), not exact.

Recipes with high fat but no frying (mayonnaise, dressings, pickles, tadka, chicken salad, walnut
chutney) are genuinely fatty and come back unchanged.

Usage: python fried_oil.py   (writes reference_indb_fried_rows.csv and reference_indb_merged.csv here)
"""
import csv
import os
import re
import sys

import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
# The INDB spreadsheets (INDB.xlsx, recipes.xlsx, recipes_servingsize.xlsx from
# github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-); outputs still go to HERE.
DATA = os.environ.get("INDB_DIR", HERE)
CURRENT = sys.argv[1] if len(sys.argv) > 1 else \
    os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "reference_indb.csv")

UNIT_G = {"g": 1, "ml": 1, "tsp": 5, "tbsp": 15, "C": 240, "sprig": 2, "nos": 10, "pinch": 0.3, "drops": 0.1, "sheet": 5}
FRY = re.compile(r"fry|frying", re.I)
OIL_KCAL, OIL_FAT = 9.0, 1.0  # per g: INDB sunflower oil / ghee as used in the frying lines

# (a, w_end) per category; w_end None = water unchanged (only part of the dish is fried, or it goes into syrup)
CAT = {
    "flat": (0.12, 0.28), "pastry": (0.10, 0.42), "kachori": (0.10, 0.30), "sweetpastry": (0.10, 0.15), "mathri": (0.15, 0.05),
    "batter": (0.12, 0.40), "cutlet": (0.08, 0.50), "coated": (0.05, 0.55),
    "syrup": (0.07, None), "shallow": (0.05, None), "part": (0.04, None),
}
CODES = {
    "flat": "ASC107 ASC108 ASC109 ASC110 ASC111 ASC143 ASC464 BFP114 BFP115 BFP116 BFP117",
    "pastry": "ASC361 ASC362 ASC363 ASC383 BFP431 OSR152 ASC409",
    "kachori": "ASC365 BFP118",
    "sweetpastry": "ASC280 ASC347",
    "mathri": "ASC364 BFP564",
    "batter": "OSR075 OSR076 ASC351 ASC352 ASC353 ASC354 ASC355 ASC356 ASC357 ASC358 ASC359 ASC360 ASC378 ASC379 ASC473 "
              "BFP415 BFP416 BFP425 BFP436 BFP437 BFP566 OSR118 BFP539 OSR110",
    "cutlet": "ASC366 ASC367 ASC368 ASC369 ASC370 ASC371 ASC457 BFP424 BFP427 BFP428 BFP430 ASC377 ASC380 "
              "ASC456 ASC237 BFP274 BFP275",
    "coated": "ASC236 ASC238 ASC243 ASC247 ASC248 BFP226 BFP420 BFP421 OSR063",
    "syrup": "ASC348 BFP386 ASC349 OSR016",
    "shallow": "ASC148 ASC461 ASC462",
    "part": "ASC090 ASC118 ASC137 ASC168 ASC198 ASC199 ASC200 ASC201 ASC202 ASC203 ASC204 ASC205 ASC206 ASC207 "
            "ASC214 ASC216 ASC218 ASC224 ASC225 ASC249 ASC277 ASC279 BFP196 BFP201 BFP203 BFP207 BFP210 BFP221 "
            "BFP246 BFP249 BFP250 BFP291 OSR111 OSR114 OSR108 OSR116 ASC375",
}
# Thin crisps: absorbed share per recipe, fried nearly dry.
CRISP = {
    "OSR113": (0.22, 0.04),  # rice murukku: extruded dough fried crisp, like papdi
    "OSR119": (0.30, 0.04),  # banana chips: thin slices take up more, like potato chips (30-40 %)
    "OSR147": (0.25, 0.04),  # sev
    "OSR148": (0.22, 0.04),  # papdi
    "ASC458": (0.20, 0.04),  # soyabean namak paras
    "ASC460": (0.20, 0.04),  # spinach peanut namak paras
    "BFP568": (0.20, 0.04),  # poshtik namak paras
    "BFP270": (0.20, 0.10),  # crispy okra
    "ASC046": (0.15, 0.30),  # sesame toast (fried bread)
    "ASC372": (0.15, 0.30),  # cheese toast (fried bread)
}
# Already in reference_indb.csv but with all the frying oil counted (they slipped past the first
# filter); recomputed and replaced in the merged file.
RECHECK = {"OSR075", "OSR076", "OSR113"}
NOT_FRIED = {"BFP310", "BFP312", "BFP446", "OSR052", "OSR059", "OSR060", "OSR086", "ASC266", "ASC268", "BFP095", "BFP167", "BFP169"}

PARAMS = {c: CAT[k] for k, v in CODES.items() for c in v.split()}
PARAMS.update(CRISP)


def rows(path):
    return list(openpyxl.load_workbook(os.path.join(DATA, path), read_only=True).worksheets[0].iter_rows(values_only=True))


def grams(amount, unit):
    return (amount or 0) * UNIT_G.get(unit or "", 0)


def aliases(name):
    m = re.search(r"\((.*)\)", name)
    return ", ".join(p.strip() for p in m.group(1).split("/")) if m else ""


def main():
    ind_rows = rows("INDB.xlsx")
    head = {k: i for i, k in enumerate(ind_rows[0])}
    ind = {r[0]: r for r in ind_rows[1:]}
    recipes = {}
    for r in rows("recipes.xlsx")[1:]:
        recipes.setdefault(r[1], []).append(r)
    serving = {r[1]: r for r in rows("recipes_servingsize.xlsx")[1:]}

    with open(CURRENT, newline="", encoding="utf-8") as f:
        current = list(csv.DictReader(f))
    have = {r["code"] for r in current}
    missing = [c for c in ind if c not in have or c in RECHECK]
    print(f"INDB recipes {len(ind)}, in current CSV {len(have)}, missing {len(missing)}")

    out, report, skipped = [], [], []
    for code in missing:
        r = ind[code]
        g = lambda k: float(r[head[k]] or 0.0)
        name = r[1].strip()
        k100 = g("energy_kcal")
        per100 = {"kcal": k100, "protein": g("protein_g"), "carbs": g("carb_g"), "fat": g("fat_g"), "fibre": g("fibre_g")}
        s = serving[code]
        n_serv, size, label = (s[6] or 1), (s[8] or 1), s[10]
        us = r[head["unit_serving_energy_kcal"]]
        w_serv = 100.0 * us / k100 if us and k100 else None
        lines = recipes.get(code, [])
        w_sum = sum(grams(x[11], x[12]) for x in lines)
        # INDB's own total weight (from its serving data) is exact; summing household measures
        # (tsp, tbsp) with a flat 5/15 g is only a cross-check.
        W = w_serv * n_serv * size if w_serv else w_sum
        if w_sum and abs(W - w_sum) / w_sum > 0.05:
            print(f"  note {code}: INDB weight {W:.0f} g vs summed ingredients {w_sum:.0f} g")

        if code in NOT_FRIED:
            new, cooked = per100, W
        else:
            fry_lines = [x for x in lines
                         if re.search(r"oil|ghee|fat", f"{x[10]}", re.I) and grams(x[11], x[12]) >= 100
                         and (FRY.search(f"{x[4]} {x[5]} {x[6]}") or grams(x[11], x[12]) >= 300)]
            O = sum(grams(x[11], x[12]) for x in fry_lines)
            if not O or code not in PARAMS:
                skipped.append((code, name, "no frying line found" if not O else "no category"))
                continue
            a, w_end = PARAMS[code]
            food = {k: v * W / 100.0 for k, v in per100.items()}
            food["kcal"] -= O * OIL_KCAL
            food["fat"] -= O * OIL_FAT
            if food["fat"] < 0:  # rounding in INDB's own weights; never below zero
                food["kcal"] -= food["fat"] * 9.0
                food["fat"] = 0.0
            wf = W - O
            if w_end is None:
                cooked = wf / (1 - a)
            else:
                dry = food["protein"] + food["carbs"] + food["fat"] + food["fibre"] + 0.015 * wf
                water0 = max(wf - dry, 0.0)
                cooked = dry / (1 - a - w_end)
                if w_end * cooked > water0:
                    cooked = (dry + water0) / (1 - a)
            oil = a * cooked
            food["kcal"] += oil * OIL_KCAL
            food["fat"] += oil * OIL_FAT
            new = {k: v / cooked * 100.0 for k, v in food.items()}
            report.append((code, name, k100, new["kcal"], new["fat"], a, w_end))

        serving_g = cooked / (n_serv * size) if label else None
        if serving_g is not None and serving_g > 1000:
            label, serving_g = None, None
        out.append({
            "code": code, "name": name,
            "kcal": round(new["kcal"], 1), "protein": round(new["protein"], 2), "carbs": round(new["carbs"], 2),
            "fat": round(new["fat"], 2), "fibre": round(new["fibre"], 2),
            "serving_label": label or "", "serving_g": round(serving_g) if serving_g else "",
            "aliases": aliases(name),
        })

    fields = ["code", "name", "kcal", "protein", "carbs", "fat", "fibre", "serving_label", "serving_g", "aliases"]
    with open(os.path.join(HERE, "reference_indb_fried_rows.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(sorted(out, key=lambda x: x["code"]))
    with open(os.path.join(HERE, "reference_indb_merged.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(sorted([r for r in current if r["code"] not in RECHECK] + out, key=lambda x: x["code"]))

    print(f"added {len(out)} (corrected {len(report)}, unchanged {len(out) - len(report)}), skipped {len(skipped)}")
    for s in skipped:
        print("  skipped", s)
    for code, name, old, new, fat, a, w_end in sorted(report, key=lambda x: x[0]):
        print(f"  {code} {name[:48]:48} {old:6.0f} -> {new:5.0f} kcal  fat {fat:4.1f}  (a {a}, water {w_end})")


if __name__ == "__main__":
    main()
