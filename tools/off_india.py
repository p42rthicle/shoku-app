"""
Turn Open Food Facts search pages (JSON, as fetched by the off_india fetch script) into the app's
offline barcode file, android/app/src/main/assets/off_india.csv: products sold in India or with an Indian
(890) barcode. Only products with calories per 100 g are kept. Same rules as the app's online lookup (OpenFoodFacts.parse): ml when the pack size is a
volume, kcal from kJ when only kJ is given, first brand only.

Data: Open Food Facts (openfoodfacts.org), Open Database License (ODbL); contents under the
Database Contents License. Keep the credit in Settings if you ship it.

Usage:
  python3 off_india.py <en.openfoodfacts.org.products.csv.gz> [out.csv]   (daily export, recommended)
  python3 off_india.py <folder with p1.json, p2.json, ...> [out.csv]      (search API pages)
The export is at https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz (~1.5 GB,
tab-separated); it's streamed, never unpacked to disk.
"""
import csv, glob, gzip, json, os, re, sys

src = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "main", "assets", "off_india.csv")

def num(n, key):
    v = (n or {}).get(key)
    try:
        return float(v) if v not in (None, "") else None
    except (TypeError, ValueError):
        return None

def products():
    """Products as dicts shaped like the API's (code, product_name, brands, quantity, ..., nutriments)."""
    if src.endswith(".gz"):
        csv.field_size_limit(sys.maxsize)
        with gzip.open(src, "rt", encoding="utf-8", errors="replace", newline="") as f:
            for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
                # Sold in India, or made there (GS1 India barcodes start with 890; many such packs are
                # tagged only with the country they were scanned in, e.g. the US or France).
                if "en:india" not in (r.get("countries_tags") or "") and not (r.get("code") or "").startswith("890"):
                    continue
                n = {k: r.get(k) for k in ("energy-kcal_100g", "energy_100g", "proteins_100g", "carbohydrates_100g", "fat_100g", "fiber_100g")}
                yield {"code": r.get("code"), "product_name": r.get("product_name"), "brands": r.get("brands"), "quantity": r.get("quantity"),
                       "product_quantity": r.get("product_quantity"), "serving_quantity": r.get("serving_quantity"), "nutriments": n}
    else:
        for path in sorted(glob.glob(os.path.join(src, "p*.json")), key=lambda p: int(re.sub(r"\D", "", os.path.basename(p)) or 0)):
            yield from json.load(open(path)).get("products", [])

rows, seen, dropped = [], set(), 0
if True:
    for p in products():
        code = str(p.get("code") or "").strip()
        if not code.isdigit() or len(code) < 8 or code in seen:
            continue
        n = p.get("nutriments") or {}
        kcal = num(n, "energy-kcal_100g")
        if kcal is None and num(n, "energy_100g") is not None:
            kcal = num(n, "energy_100g") / 4.184
        if kcal is None or kcal <= 0 or kcal > 950:
            continue
        # Sanity: calories must roughly match the macros (4 kcal/g protein and carbs, 9 fat); entries that
        # don't (Parle-G listed at 3.4 kcal) are left out, so the app falls back to online or the label.
        pr, cb, ft = num(n, "proteins_100g"), num(n, "carbohydrates_100g"), num(n, "fat_100g")
        if None not in (pr, cb, ft):
            est = 4 * pr + 4 * cb + 9 * ft
            if est > 20 and not (0.7 * est <= kcal <= 1.3 * est + 25):
                dropped += 1
                continue
        name = (p.get("product_name") or p.get("product_name_en") or "").strip()
        if not name:
            continue
        brand = (p.get("brands") or "").split(",")[0].strip()
        quantity = (p.get("quantity") or "").lower()
        unit = "ml" if re.search(r"\d\s*(ml|cl|l)\b", quantity) else "g"
        pack = num(p, "product_quantity")
        if pack is None:
            m = re.match(r"^\s*(\d+(?:[.,]\d+)?)\s*(g|ml|l|kg)\b", quantity)
            if m:
                pack = float(m.group(1).replace(",", ".")) * (1000 if m.group(2) in ("l", "kg") else 1)
        serving = num(p, "serving_quantity")
        r1 = lambda v: "" if v is None else f"{v:.2f}".rstrip("0").rstrip(".")
        rows.append([code, name, brand, unit, r1(kcal), r1(num(n, "proteins_100g")), r1(num(n, "carbohydrates_100g")),
                     r1(num(n, "fat_100g")), r1(num(n, "fiber_100g")), r1(serving if serving and serving > 0 else None), r1(pack if pack and pack > 0 else None)])
        seen.add(code)

with open(out, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["code", "name", "brand", "unit", "kcal", "protein", "carbs", "fat", "fibre", "serving", "pack"])
    w.writerows(rows)
print(f"{len(rows)} products with nutrition -> {out} ({os.path.getsize(out) // 1024} KB); {dropped} dropped as inconsistent")
