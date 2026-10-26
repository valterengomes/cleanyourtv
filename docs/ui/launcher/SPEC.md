# LWNGE launcher: home screen spec

## 0. What's in this folder

| Path | Use |
|---|---|
| `SPEC.md` | This document: sizes in dp, tokens, focus, motion, screens, keys. Source of truth. |
| `screens/*.png` | The approved mockups rendered at 1920x1080 (1 dp = 2 px). Look at these first. |
| `reference/*.dc.html` + `reference/ll.mockup.css` | The mockups' source (web). Use them to read exact colours, sizes and copy; do not port the CSS, the launcher is native Android. Ignore `<x-dc>`, `<helmet>`, `support.js`; `<sc-for>`/`<sc-if>` are loops/conditionals with data in `renderVals()`. |

Boards: 01 Home default · 02 Home photo · 03 Home cinematic · 04 Focus states · 05 Island states · 06 Inputs (island open) · 07 Tile menu · 08 App library · 09 Launcher settings · 10 Screensaver · 11 Home Shelves · 12 First run · 13 Edge states · 14 Remote keys.

Mockups: canvas "LWNGE launcher redesign" (10 boards at 1920x1080 px). Target: Android TV, D-pad only, 1.5 to 2 GB RAM.

## 1. Units and canvas

- Mockups are 1920x1080 px. The TV canvas is **960x540 dp, 1 dp = 2 px** (xhdpi). Every value below is in dp; px in brackets.
- Safe area (5% overscan): **48 dp left/right [96], 27 dp top/bottom [54]**. Nothing focusable or textual outside it. Decorative layers (background, shelf, peeking grid row) may bleed.
- Minimum text: **12 sp [24 px]**.

## 2. Tokens

### Colour

| Token | Value | Use |
|---|---|---|
| `ink` | #0B0C0E | Base, text on focus |
| `cream` | #F5F2EC | Text, focus fill/rim |
| `bg` | #0F1215 | Default home background (flat colour) |
| `panel` | #16181C | Menus, sheets (opaque, no blur) |
| `panel2` | #1F2227 | Cards inside panels, selected category |
| `island` | #000000 | Island fill |
| `line` | cream 14% | Hairlines, island rim |
| `lineStrong` | cream 32% | Key caps, focused island rim |
| `text` | #F5F2EC | Primary |
| `text2` | #BAB7B0 | Secondary (9.4:1 on bg) |
| `text3` | #9A978F | Tertiary (6.1:1 on panel) |
| `onFocus` / `onFocus2` | #0B0C0E / #3B3D42 | Text on cream focus (17.5:1 / 9.7:1) |
| `ok` | #6FD39B | Signal, connected, switch on |
| `dim` | ink 0 to 60% (default 35-42%) | Over photos |
| `scrim` | ink 72% | Behind menus and the open island |
| `glow` | app colour, 50-60% alpha | Focus glow, per app |

App colour (`tint`, `glow`): extracted once per app from its banner/icon (Palette API, dominant muted swatch, lightness clamped so cream text stays ≥ 4.5:1) and cached.

### Spacing (dp)

`4 · 8 · 12 · 16 · 24 · 32 · 48`. Dock gap 24, grid gap 12, panel padding 8, card padding 12-14.

### Radius (dp)

Tile 10 · icon 9 · card/menu 14 · open island 22 · pills 999.

### Type (Outfit; sp [px])

| Role | Size | Weight |
|---|---|---|
| Clock | 84 [168] | 300, tabular |
| Clock, compact (cinematic) | 44 [88] | 300 |
| Screensaver clock | 120 [240] | 200 |
| Shelf title (cinematic) | 48 [96] | 500 |
| Focused app name | 32 [64] | 500 |
| Screen title | 22 [44] | 500 |
| Greeting | 18 [36] | 400, text2 |
| Date, large values | 16 [32] | 400 |
| Body, menu items, rows | 14 [28] | 500 |
| Labels, stats, hints | 12 [24] | 400-500 |

### Sizes (dp)

- Dock banner 152x85.5 (16:9), 5 visible, gap 24; dock top at y 302. The first tile scales from its left edge so its focused state never leaves the safe area.
- Grid tile 16:9, 4-7 per row (default 7: ~113x63.5), icon 32 with radius 9 on a tinted card.
- Island: rest 38 high, icons 19.5; live 38; focused tray 48; open width 590, max height 280.
- Menu items 42 high; settings rows 52 min; key caps 20 high.

## 3. Layout (home)

1. Background: flat `bg`, or photo (rotating set, crossfade) + `dim`, or time-of-day sky (flat colour per period, no gradient animation).
2. Clock area, top-left at the safe corner: greeting, clock, date, optional stats line `CPU x% · Memory x GB free · Storage x GB free` (refresh every 10 s, only while home is visible).
   - **Date position** is a setting (Settings › Clock area): *Under the clock* (default, 16 sp single line "Monday, 26 October") or *Beside the clock*.
   - Beside: a two-line block (weekday 22.5 sp / date 16 sp, weight 300, date in `text2`), 8 dp between the two lines (cap top to baseline trimmed), **bottom-aligned: the date's baseline sits on the clock's baseline**. Gap to the clock 16 dp. In Android: align the block's last baseline to the clock's baseline (`ConstraintLayout` `layout_constraintBaseline_toBaselineOf`). Compact clock (cinematic): weekday 16 sp / date 12 sp, 5 dp between lines, same rule.
3. **Island**, top centre at the safe top edge (§5).
4. Focused app name (32 sp) above the dock.
5. Dock: favourite apps as banner tiles.
6. "Recent apps" label + one grid row of the non-favourite apps used most recently (4 to 7 per row); the next row peeks below the safe area to signal scrolling. Every installed app lives in the App library (island › Apps), so Home never repeats the full list. Settings toggle: "Recent apps row".
7. Cinematic mode: the clock shrinks, the focused app's artwork fills the top 380 dp, and the app name moves onto the shelf (48 sp).

## 4. Focus

- One focused element at a time, always visible. Default style **Rim + glow**: scale 1.08 (dock) / 1.10 (grid), 2 dp cream rim, app-colour glow. Alternatives in settings: **Glass slab** (cream 16% slab + 42% hairline, 7 dp outset) and **Halo** (app-colour 1.5 dp ring + soft 6 dp ring).
- Glow and slab are **static 9-patch drawables** tinted at runtime (`setTint`), never live shadows or blur. One glow view, reused and moved to the focused tile.
- Rows, menu items, chips and island buttons: focus = cream fill + ink text (no scale beyond 1.04 for cards).
- Move mode (from tile menu): cream round chips ◀ ▶ beside the tile; ◀▶ moves it, OK drops, Back cancels.

## 5. Island (top dock)

Replaces the top bar (Inputs pill, network pill, Settings, library button) with one black pill, top centre.

| State | Trigger | Content |
|---|---|---|
| Rest | default | Inputs, Wi-Fi, Apps (2x2 grid), Settings (cog) icons in one even row, no divider, 19.5 dp, 13 dp apart |
| Live activity | HDMI hotplug or a network drop | "PlayStation 5 on HDMI 2" + OK key; 8 s, then back to rest; OK switches input |
| Focused (tray) | Up from the top row of the dock | Inputs · Wi-Fi · Apps · Settings as pills; ◀▶ move between them |
| Open | OK on a pill | Panel grows downward (590 dp wide); its tabs stay on top, content below: inputs grid, network card, apps = app library, settings = launcher / Android settings |

- Navigation: Down from the open island's tabs goes into the content; Back steps out one level (content → tabs → tray → rest, with focus back on the dock tile it came from). Home always collapses the island.
- Opening with OK, not on focus alone: passing through with Up never fills the screen by accident.
- Hidden option (Settings › Island): "Classic top bar" for anyone who prefers separate pills.
- Performance: the island is one view whose bounds animate (`ChangeBounds` or animating `width/height` on a single rounded-rect drawable), never a blur; content fades in after the shape settles.

### Island icons

One stroke family (1.75 dp at 24 dp grid, round caps), all drawn to the same optical box so no icon looks bigger than its neighbour: Inputs = box with arrow entering (18x16), Wi-Fi = 3 arcs + dot (20x13.4, centred on the box), Apps = 2x2 rounded squares (16x16), Settings = cog (19x19). Size in the rest pill: 19.5 dp; in the tray and tabs: 15 dp next to 14 sp labels.

### Other icons (same family)

Move = horizontal double arrow · Remove from Home = circle with minus · App info = circle with "i" · source cards: HDMI port (HDMI), TV with antenna (aerial) · chevrons ‹ › for values · check for "On Home". Size 15 dp in menus and rows, 20 dp in source cards.

## 6. Motion

| Event | Duration | Easing |
|---|---|---|
| Focus in (scale + glow fade) | 160 ms | `cubic-bezier(0.2, 0, 0, 1)` (decelerate) |
| Focus out | 120 ms | same |
| Dock/grid scroll to keep focus in view | 220 ms | decelerate |
| Island rest → tray | 200 ms | `cubic-bezier(0.32, 0.72, 0, 1)` |
| Island tray → open (shape) | 260 ms | same; content fades 120 ms, starting after 100 ms |
| Island close | 180 ms | accelerate `cubic-bezier(0.3, 0, 1, 1)` |
| Live activity in / out | 220 / 180 ms | island curve |
| Cinematic artwork swap | 300 ms crossfade, starts 250 ms after focus settles | linear |
| Menus, scrims | 150 ms fade + 8 dp rise | decelerate |
| Background photo change | 1200 ms crossfade, every hour | linear |
| Screensaver drift | position changes every 60 s: 600 ms fade out, move, 600 ms fade in | linear |

Rules: animate only `scaleX/Y`, `alpha`, `translation` (plus the island's bounds); hardware layers during animation only; no running animation while nothing is moving; at most one crossfade on screen.

### Reduced motion

Applied when Settings › Animations = Reduced, or when Android's animator duration scale = 0.

- Focus: no scale; rim and glow appear instantly (0 ms) or with an 80 ms fade.
- Island: no shape morph; panels fade in place over 120 ms.
- Scrolling: jumps without easing.
- Cinematic: artwork changes without crossfade, after the 250 ms debounce.
- Screensaver: position still changes (burn-in protection) as an instant cut.
- **Off**: everything instant, including fades.

## 7. Screens

- **App library** (island › Apps): 7-column grid of every installed app, A to Z; filter chips All / On Home / Not on Home; a cream check badge marks apps on Home. OK toggles On Home (the hint updates: "Add to Home" / "Remove from Home"); hold OK → Open, App info. Back closes.
- **Inputs** (island › Inputs): one card per source (HDMI 1-n, ARC, aerial) with live signal state (filled dot = signal, outline = none) and the device name when known. OK switches.
- **Tile menu** (hold OK on a dock or grid tile): panel anchored beside the tile, everything else under the scrim: Move · Remove from Home · App info. In the grid, "Add to Home" replaces "Remove from Home".
- **Launcher settings**: two panes. Left: sections (Look, Clock area, Dock and apps, Background, Island, Screensaver, About). Right: rows; ◀▶ change values in place, OK toggles switches. Back from the right pane returns to the left one.
- **Screensaver**: pure black, clock 120 sp weight 200 at 82% cream, date under it at 62%; drifts as in §6; any key wakes to home with focus where it was.
- **Home layout: Shelves** (Settings › Layout › Home layout = Grid | Shelves): compact clock top-left, island, then one row per group: Watch, Listen, Play, Tools (row title 12 sp caps + app count). Apps are grouped by their Play Store category on first run; hold OK › Move lets the user drop an app into another row. Inside a row, most used first. Tiles 140x79 dp, gap 14 dp, banner art or icon on tint. Rows scroll vertically; the focused row keeps a full-brightness title.
- **First run**: 3 steps, left column with step list (current step cream number). 1 Favourites: 4-column grid of installed apps, OK picks/unpicks, up to 8, counter on top, Continue / Skip for now. 2 Your name: on-screen keyboard field for the greeting (skippable). 3 Photos: QR/hint to add photos from the LWNGE app on the computer, or skip. Re-run from Settings › About.
- **Edge states** (board 13): empty dock (dashed placeholders, the first is focusable: OK opens the favourites picker) · more than 5 favourites (dock slides, next tile peeks, page dots under it) · app without banner art (its icon on a card tinted with the icon colour) · long names (tiles cut with "…"; the big focused name gets the full width before it cuts) · no internet (island shows a warn-coloured Wi-Fi-off icon + "No internet" until it reconnects; OK opens Wi-Fi) · still loading (flat placeholders, no shimmer, no spinners).
- **Remote keys** (board 14): Up from the dock opens the island tray; Back walks out one level at a time and always returns focus to the element that opened the current layer; Home closes everything and focuses the first favourite; leaving an app returns focus to the tile that opened it; the key that wakes the screensaver is never passed on.

## 8. Spacing rules (from design review)

- Every chip, pill, button, card and row keeps at least 12 dp horizontal padding [24 px] between its edge and any text or icon; island pills 15 dp [30 px], rows 16 dp [32 px].
- Focused elements grow: leave at least 12 dp [24 px] between a focused (scaled) element and its neighbours.
- Labels never wrap inside source cards or chips: put extra detail in the secondary line (e.g. "HDMI 3" / "Soundbar · ARC").

## 9. Performance budget (1.5-2 GB TVs)

- No `RenderEffect` blur. The cinematic shelf uses the app banner downscaled to 48 px wide and upscaled with bilinear filtering (a cheap blur), cached per app.
- Background photos decoded at screen size (`inSampleSize`), RGB_565 for photos, maximum 2 bitmaps in memory (current + next).
- ≤ 3 full-screen layers at once (background, dim, content). Scrims only while a menu is open.
- Grid in a `RecyclerView` (or Leanback `VerticalGridView`) with stable ids and fixed sizes; preload banners for the dock and the first 2 grid rows only.
- Target: focus moves at 60 fps; frame time under 16 ms on a Mali-G31 class GPU.
