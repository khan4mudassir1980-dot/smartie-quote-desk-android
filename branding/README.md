# Branding

This folder holds the SMARTIE Quote Desk brand files: the app icon and the
logo shown on the opening intro. They are the **sources** — the Owner's
artwork, uploaded here through the GitHub web page in `c9b2e3c`. Nothing else
adds images to this folder, and nothing changes the four PNGs:
`LauncherIconFilesTest` holds each to its SHA-256 as uploaded.

## Which file feeds which asset

The app never reads this folder. `tools/branding/make-assets.py` (Python,
Pillow) reads the four files and writes the PNGs the app is built from, under
`app/src/main/res/`. Every one is the artwork scaled down — on a plain
`#F7F4FF` shape for the older launchers — never redrawn, recoloured or cropped.
Run it from the repository root after any change here:
`python3 tools/branding/make-assets.py`.

| Source | What is made from it | Where it shows |
|---|---|---|
| `QD_icon_mark_1024.png` | `mipmap-*/ic_launcher_foreground.png` — the mark at 44dp in the 108dp layer, inside the 66dp safe circle | The launcher icon from Android 8, in front of a solid `#F7F4FF` (`mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`); the Android 12+ splash |
| `QD_icon_mark_1024.png` | `mipmap-*/ic_launcher.png`, `ic_launcher_round.png` — the mark at 28dp on a 44dp `#F7F4FF` rounded square, and circle | The launcher icon on Android 6 to 7.1 |
| `QD_icon_monochrome_1024.png` | `mipmap-*/ic_launcher_monochrome.png` — placed exactly as the mark | Android 13+ themed icons |
| `QD_full_colour_1024.png` | `drawable-*/qd_logo.png` — the whole logo at 200dp | The intro (200dp) and sign-in (140dp), always on `#F7F4FF` |
| `QD_full_whitetext_1024.png` | Nothing yet | Kept for later — for a dark background; the intro and sign-in are always light |

`*` is each of `mdpi`, `hdpi`, `xhdpi`, `xxhdpi` and `xxxhdpi`.

## The brand colours

The Owner's, N5.12b. In the app they are `SmartieColors` in
`app/src/main/java/in/smartie/quotedesk/ui/theme/Color.kt`; the icon
background is also `@color/ic_launcher_background`.

| Colour | Hex | In the app |
|---|---|---|
| Primary purple | `#581FEB` | The primary colour — buttons, switches, the header rule, purple text |
| Deep indigo | `#3212BD` | A primary button while pressed; the purple tag's text |
| Light violet | `#A138FC` | The highlight — a line, never text: the field being typed in, a stepper holding a change, a card being dragged |
| Navy | `#08162C` | The logo's "Quote"; the intro's "by Smart India Enterprises" |
| Icon background | `#F7F4FF` | Behind the icon, the Android 12+ splash, the intro and sign-in — in dark mode too |
