**Download:** [PlaceRevertDropFix-1.0.0.jar](https://github.com/kuohsuanlo/PlaceRevertDropFix/releases/download/v1.0.0/PlaceRevertDropFix-1.0.0.jar) (Paper / Folia, sha256 `885fc43c368d938b58e65aa9af69a23ac22847c38e44f55f656118a22c8f0821`)

# PlaceRevertDropFix

A drop-in Paper / Folia plugin that stops an item-duplication bug present in every Paper-based server:
**when a protection plugin cancels a right-click with an item, Paper puts the blocks back, but whatever
those blocks dropped in the meantime stays on the ground.**

Works with any claim / region / town plugin (GriefPrevention, WorldGuard, Lands, Towny, ...), because it
does not care *who* cancelled. No commands, no permissions, no messages to players.

[中文說明在下面](#中文說明)

## The bug

When a player uses an item on a block, CraftBukkit (and therefore Paper, Folia and their forks):

1. takes a snapshot of every block the item is about to change,
2. lets vanilla run the item,
3. fires `BlockPlaceEvent` / `BlockMultiPlaceEvent` (`BlockFertilizeEvent` for bone meal),
4. if any plugin cancelled it, restores every snapshot, containers included.

Vanilla already ran in step 2, so anything it dropped is already an item entity in the world. The
snapshots only cover blocks, so those drops survive step 4.

The worst case is the last Eye of Ender on an End Portal frame: vanilla destroys the 3x3 inside the frame
**with drops** before placing the portal. If that use is cancelled, the containers inside come back with
their contents, and the dropped copy is still on the floor. The same happens to any block inside
(shulker boxes with contents, ore, diamond blocks, ...) and to the experience it drops.

Upstream report: [PaperMC/Paper#13586](https://github.com/PaperMC/Paper/issues/13586) (open, accepted).

## What the plugin does

One right-click is one synchronous call on one thread. The plugin:

- opens a small per-thread "window" at `PlayerInteractEvent` (MONITOR),
- remembers every item / experience orb spawned while it is open, plus the count of orbs already lying in a
  block that gets destroyed (vanilla can merge new experience into them without any event),
- when **that click's** place event ends up cancelled, removes what was spawned inside the reverted blocks
  and gives the absorbed orbs their old count back.

Result, as the player sees it:

| Situation | What happens |
|---|---|
| The protection plugin allows the use | Exactly vanilla. The portal opens, the chest breaks and drops its contents. |
| The protection plugin cancels the use | Nothing happens. The chest stays with its contents, nothing on the floor, the eye stays in the hand. |

If a badly behaved plugin un-cancels the event after MONITOR, the plugin notices on the next tick and gives
the removed drops back, so nothing is ever lost.

## Requirements

- Paper or Folia **1.20.6 or newer** (needs `ExperienceOrb#getCount` and the region scheduler API).
  On anything older, or on Spigot, the plugin disables itself and logs that the bug is **not** fixed.
- Tested on Paper 26.2 and on a Folia 26.2 fork.

## Install

Drop `PlaceRevertDropFix-<version>.jar` into `plugins/` and restart (do not hot-reload).

`config.yml`:

```yaml
# Print one console line every time drops are rolled back (also a cheap way to spot people trying it).
log-rollbacks: true
```

## Tests

`test-harness/` contains a test plugin that drives a fake player through the real server code path
(`ServerPlayerGameMode#useItemOn`) and a stand-in protection plugin. See [test-harness/README.md](test-harness/README.md).

Results on Paper 26.2 and on a Folia 26.2 fork, without -> with this plugin:

| Scenario | Without | With |
|---|---|---|
| Portal, not protected | vanilla | vanilla |
| Portal, protected, chest + shulker + ore + diamond block inside | **duplicated** | clean |
| Same, with unrelated items and orbs already on the ground | **duplicated** | clean, unrelated drops kept |
| Orbs already lying inside a destroyed block (merge by `count++`) | **xp gained** | clean |
| Hoe on rooted dirt, protected | **duplicated** | clean |
| Hoe on rooted dirt, protected, another plugin un-cancels at MONITOR | - | drop given back |
| Plain block place, protected, drops lying next to it | untouched | untouched |
| Synthetic cancelled `BlockPlaceEvent` elsewhere after a no-op click | untouched | untouched |

## Limits

- Only player item uses are covered (that is where Paper reverts). Block changes made by the game itself
  (dispensers, world generation) are not reverted by Paper, so they do not duplicate anything.
- A plugin that turns drops into inventory items at `ItemSpawnEvent` (some auto-pickup plugins) takes them
  before this plugin can see them.

## License

MIT

---

## 中文說明

Paper 系的伺服器（Paper、Folia 和它們的分支）都有一個物品複製漏洞：**玩家拿物品右鍵方塊、被保護插件取消時，Paper 會把方塊還原，但這段期間方塊噴出來的東西還留在地上。**

最嚴重的是在終界傳送門放最後一顆終界之眼。原版會先把門內 3×3 的方塊打掉並噴出掉落物，再放傳送門。這個動作如果被取消，裡面的箱子會連內容一起還原，地上卻多了一份。門內任何方塊都一樣（界伏盒連內容、礦石、鑽石磚等等），經驗球也是。上游回報：[PaperMC/Paper#13586](https://github.com/PaperMC/Paper/issues/13586)。

這個插件跟哪個領地插件無關（GriefPrevention、WorldGuard、Lands、Towny 都適用），因為它不管是誰取消的。沒有指令、沒有權限，也不會對玩家發任何訊息。

玩家看到的效果：

| 情況 | 結果 |
|---|---|
| 保護插件允許 | 跟原版完全一樣：門開了，箱子破掉、東西噴出來 |
| 保護插件取消 | 什麼都沒發生：箱子和裡面的東西都在，地上沒東西，眼睛還在手上 |

做法：同一下右鍵在伺服器裡是同一個執行緒上的一次同步呼叫。插件在 `PlayerInteractEvent` 開一個小窗口，記下這段期間生出來的掉落物和經驗球；如果這一下的放置事件最後被取消，就把落在被還原方塊裡的那些刪掉。沒被取消就什麼都不做。

需求：Paper 或 Folia 1.20.6 以上。更舊的版本或 Spigot 上，插件會自動停用，並在後台寫明「漏洞沒有被修」。

安裝：把 jar 放進 `plugins/`，然後重開伺服器（不要用熱重載）。
