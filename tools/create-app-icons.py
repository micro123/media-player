"""Generate density-specific legacy and adaptive launcher icons from the supplied art."""
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
source = Image.open(root / "assets/branding/app-icon-source.png").convert("RGBA")
# Only remove connected black around the outer tile; preserve all interior artwork.
mask = Image.new("RGB", source.size, "white")
mask.putdata([(0, 0, 0) if max(pixel[:3]) <= 12 else (255, 255, 255) for pixel in source.get_flattened_data()])
ImageDraw.floodfill(mask, (0, 0), (255, 0, 0))
alpha = Image.new("L", source.size)
alpha.putdata([0 if pixel == (255, 0, 0) else 255 for pixel in mask.get_flattened_data()])
source.putalpha(alpha)

res = root / "app/src/main/res"
for density, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    target = res / f"mipmap-{density}"
    target.mkdir(parents=True, exist_ok=True)
    source.resize((int(48 * scale),) * 2, Image.Resampling.LANCZOS).save(target / "ic_launcher.png", optimize=True)
    # The colored ring fits inside the central 66dp safe circle, including its shadow.
    size = int(108 * scale)
    art_size = int(size * .9)
    foreground = Image.new("RGBA", (size, size))
    foreground.alpha_composite(source.resize((art_size,) * 2, Image.Resampling.LANCZOS), ((size-art_size)//2,) * 2)
    foreground.save(target / "ic_launcher_foreground.png", optimize=True)

adaptive = res / "mipmap-anydpi"
adaptive.mkdir(parents=True, exist_ok=True)
(adaptive / "ic_launcher.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')
(res / "drawable/ic_launcher_monochrome.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:strokeColor="#FFFFFF" android:strokeWidth="8" android:fillColor="#00000000"
        android:pathData="M54,26 A28,28 0,1 1,54,82 A28,28 0,1 1,54,26" />
    <path android:fillColor="#FFFFFF" android:pathData="M45,39 Q43,38 43,41 L43,67 Q43,70 46,68 L69,56 Q72,54 69,52 Z" />
</vector>
''')
(res / "values/ic_launcher_colors.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#171B40</color>
</resources>
''')
