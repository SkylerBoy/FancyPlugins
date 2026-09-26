---
order: 6
icon: book
---
# FAQ

!!!danger
This is the old documentation site for FancyInnovations, which is no longer maintained.
The new documentation site can be found at [fancyinnovations.com/docs/minecraft-plugins/fancyholograms](https://fancyinnovations.com/docs/minecraft-plugins/fancyholograms).
!!!

### How to modify each line in a hologram?

Text holograms support per-line settings using tags at the start of a line:

```
/holo edit <hologram> setLine 1 <scale:2><background:transparent><gold>Big title
/holo edit <hologram> setLine 2 <scale:0.6><text_shadow:true>small subtitle
```

| Tag | Values |
|-----|--------|
| `<scale:x>` / `<scale:x,y,z>` | Multiplied with the hologram scale |
| `<background:color>` / `<bg:color>` | Named color, `#RRGGBB`, `#AARRGGBB`, `transparent` or `default` |
| `<text_shadow:true/false>` | Text shadow |
| `<see_through:true/false>` | See through blocks |
| `<align:left/center/right>` | Text alignment |
| `<offset:x,y,z>` | Extra translation for the line |

Only tags at the very beginning of the line are parsed. Settings that are not set are inherited from the hologram.
Consecutive lines with the same settings are rendered by the same display entity.

### How to add a blank line?

To add a blank line in a hologram, use `<reset>` on a new line.

### How to make holograms clickable?

Holograms currently aren't clickable themselves, but [here's](tutorials/clickable-holograms.md) a workaround

### How to make the hologram not to rotate?

To make a hologram not rotate, the billboarding must be set to FIXED.

Example: `/holo edit <hologram> billboard FIXED`

Once complete, you must set the hologram's rotation with the rotate and rotatepitch commands.

### How to edit the holograms via the data file?

Every hologram is stored in its own file inside the `plugins/FancyHolograms/holograms/` folder.
You can organize the files in subfolders (as deep as you want), e.g. `holograms/lobby/spawn/welcome.yml`.
The name of the hologram is the file name (without `.yml`).

1. Run /fancyholograms save
2. Back up the holograms folder in case something goes wrong
3. Edit, move or create the files as desired
4. Run /fancyholograms reload after saving the files

The old `holograms.yml` file is migrated automatically and renamed to `holograms-old.yml`.