---
icon: dot
---

# Clickable holograms

!!!danger
This is the old documentation site for FancyInnovations, which is no longer maintained.
The new documentation site can be found at [fancyinnovations.com/docs/minecraft-plugins/fancyholograms](https://fancyinnovations.com/docs/minecraft-plugins/fancyholograms).
!!!

Holograms can run actions when a player clicks them. The actions are configured in the hologram file
(`plugins/FancyHolograms/holograms/<name>.yml`), followed by `/fancyholograms reload`.

```yaml
click:
  cooldown: 500            # milliseconds between clicks of the same player
  actions:
    hologram:              # clicking anywhere on the hologram
      any:
        - 'message: <gray>You clicked the hologram'
    shop:                  # used by lines starting with <action:shop>
      left:
        - 'player: shop'
      right:
        - 'console: give <player> diamond 1'
        - 'sound: entity.experience_orb.pickup 1 1'
      shift_right:
        - 'message: <gray>Shift + right click'
```

### Click types

Every action set can have the lists `left`, `right`, `shift_left`, `shift_right` and `any`:

- A shift click (while sneaking) without its own list uses `left` / `right`.
- A click type without its own list uses `any`.

### Clickable lines

Add `<action:id>` at the start of a line (like the other per-line tags) to use the action set `id` for that line:

```
<action:shop><gold>[Open shop]
```

For each click type, the actions of the line override the `hologram` actions. If the line has no actions for that
click type, the `hologram` actions are used.

### Permissions

An action set can require a permission. Players without it run the `deny` actions instead:

```yaml
    vip_shop:
      permission: shop.vip
      deny:
        - 'message: <red>This shop is only for VIP players'
        - 'sound: block.note_block.bass 1 0.5'
      any:
        - 'player: shop vip'
```

### Actions

| Action | Description |
|--------|-------------|
| `player: <command>` | Command executed by the player, as if typed in the chat |
| `console: <command>` | Command executed by the console |
| `suggest: <command>` / `suggest: <command> \| <text>` | Sends a chat message; clicking it puts the command in the chat input |
| `message: <text>` | Sends a message (MiniMessage and placeholders) |
| `sound: <sound> [volume] [pitch]` | Plays a sound to the player |
| `title: <title> [\| <subtitle>] [\| <fade in> <stay> <fade out>]` | Shows a title (times in ticks) |
| `actionbar: <text>` | Sends an action bar message |
| `teleport: [world] <x> <y> <z> [yaw pitch]` | Teleports the player (current world if no world is given) |
| `server: <server>` | Sends the player to another server of the proxy (BungeeCord/Velocity) |
| `delay: <ticks>` | Waits before running the next actions |

`<player>`, `<uuid>`, `<world>` and [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) placeholders
are replaced in every action, when the action runs (after a `delay`, the values are up to date).

Minecraft doesn't allow servers to write in the chat input directly, so `suggest` sends a clickable message.

### Notes

- Clicks are detected with invisible interaction entities that only exist for the players that see the hologram.
  They are only created for holograms with click actions.
- While looking at a clickable hologram, players can't break or place blocks behind it.
- Plugins can listen to the `HologramClickEvent` (and cancel it).
