---
name: pz-ui-controller-support
description: >-
  Add controller (joypad / gamepad) support to a Lua UI window in a PZ mod —
  the user can navigate rows with the D-pad, confirm with A, and close with B,
  without any Java patch. Covers the five load-bearing pieces: base class,
  focus lifecycle, the `test == true` context-menu path, per-list A/B routing,
  and empty-list guarding. Triggers: "make the recovery window work with a
  controller", "controller UI support", "joypad support for my mod's window",
  "gamepad support", "add controller navigation to <mod>'s dialog",
  "why doesn't my mod's UI show up on controller", "dpad doesn't move in my
  mod window", "ISCollapsableWindow joypad", "ISScrollingListBox controller",
  "OnFillWorldObjectContextMenu setTest", "ISPanel joypad retrofit",
  "ISPanelJoypad", "ISTextEntryBox controller / on-screen keyboard",
  "controller support for a custom grid or stepper UI".
---

# Controller support for a PZ mod's Lua UI

Every PZ Lua UI ships as a chain of `ISUIElement`s, and every one of those
elements has controller hooks (`onGainJoypadFocus`, `onJoypadDown`,
`onJoypadDirUp/Down/Left/Right`). Mods usually skip those hooks because
`ISCollapsableWindow` / `ISPanel` alone don't wire anything up. Swapping the
base class to the joypad flavour (`ISCollapsableWindowJoypad`, or
`ISPanelJoypad` for a hand-rolled plain-`ISPanel` window) and handing focus
through to a `ISScrollingListBox` is 90% of the work.

**HARD RULE reminder.** Everything here is Lua on the client — no Byte Buddy,
no `StormClassTransformer`, no Java helper. Controller support is a
first-class part of the PZ base Lua UI framework; do NOT reach for a Java
patch even if the vanilla flow seems awkward.

## The five things that must be right

1. **Base class is `ISCollapsableWindowJoypad`, not `ISCollapsableWindow`.**
   The joypad flavour is defined in
   `../project-zomboid-base/src/main/lua/client/ISUI/ISCollapsableWindowJoypad.lua`
   as `ISPanelJoypad:derive(...)` with every `ISCollapsableWindow` method
   mixed in. `ISPanelJoypad` is where `onJoypadDown` / `onJoypadDirUp` etc.
   actually live — `ISCollapsableWindow` has none of them. Windows that
   derive from plain `ISPanel` swap to `ISPanelJoypad` directly (same
   invariants; `ISPanelJoypad:new` initialises `joypadIndex`/`joypadButtons`
   and its `onJoypadDown` fallback is a safe no-op with no button rows, so a
   bare swap can't crash on stray buttons).
2. **You must explicitly grab joypad focus after `addToUIManager`.** Adding a
   window to the UI manager only makes it visible; the focused joypad target
   for a player still points at whatever it was pointing at before (usually
   the player HUD). Call `setJoypadFocus(playerNum, window)` right after
   `addToUIManager()` and only when `JoypadState.players[playerNum + 1]`
   is set — mouse users get no focus change.
3. **`OnFillWorldObjectContextMenu` gets called with `test == true` first on
   controller.** `ISWorldObjectContextMenu.createMenu` invokes the event
   twice on a pad: once with `test = true` just to poll "does any handler
   have anything to add", then again with `test = false` to actually build
   the menu. On the `test = true` pass every handler that wants to appear
   must call `ISWorldObjectContextMenu.setTest()` (which just flips a static
   flag). Skip that call and the controller menu closes as if empty even
   though mouse users still see your option.
4. **The `ISScrollingListBox` is where the real navigation happens.** The
   window has focus first, but you immediately forward focus into the list
   with `list:setJoypadFocused(true, joypadData)` + `setJoypadFocus(player,
   list)`. The list's own `onJoypadDirUp/Down` handles row navigation,
   `overrideAButtonFunction` runs on A, and B walks back to `joypadParent`
   (the window). Set `list.joypadParent = window` so B has somewhere to
   walk back to.
5. **Guard the A-button path against an empty list.**
   `ISScrollingListBox:onJoypadDown` for A does
   `self.overrideAButtonFunction(self.target, self.items[self.selected].item)`
   with no nil check. On a still-loading list (server round-trip pending) or
   a no-past-lives / no-results state, `items[selected]` is `nil` and the
   `.item` deref crashes with `attempt to index nil`. Override
   `onJoypadDown` in a small `ISScrollingListBox:derive` subclass and
   swallow A when the list is empty.

## Concrete pattern — a list + confirm-button window

The shape shows up in most mod dialogs: a scrollable list of things, one
button that acts on the selection. This is the whole flow.

```lua
require("ISUI/ISCollapsableWindowJoypad")

-- List subclass so A on empty doesn't crash and B closes the parent in ONE
-- press instead of the vanilla two (list → window; window B → close).
local MyList = ISScrollingListBox:derive("MyList")

function MyList:onJoypadDown(button, joypadData)
    if button == Joypad.BButton and self.parentWindow then
        self.parentWindow:close()
        return
    end
    if button == Joypad.AButton
        and (#self.items == 0 or not self.items[self.selected])
    then
        return
    end
    ISScrollingListBox.onJoypadDown(self, button, joypadData)
end

local MyWindow = ISCollapsableWindowJoypad:derive("MyWindow")

function MyWindow:new(x, y, width, height, playerNum)
    local o = ISCollapsableWindowJoypad.new(self, x, y, width, height)
    setmetatable(o, self); self.__index = self
    o.playerNum = playerNum or 0
    return o
end

function MyWindow:createChildren()
    ISCollapsableWindowJoypad.createChildren(self)

    self.listBox = MyList:new(6, self:titleBarHeight() + 28,
                              self.width - 12, self.height - 80)
    self.listBox:initialise(); self.listBox:instantiate()
    self.listBox.target = self
    self.listBox.parentWindow = self
    -- Called by the list on A when items[selected] is non-nil.
    -- Signature is (target, item) — target is what you set target= to above.
    self.listBox.overrideAButtonFunction = MyWindow.onListJoypadA
    self:addChild(self.listBox)

    self.confirmBtn = ISButton:new(self.width - 146, self.height - 30, 140, 24,
                                   "Confirm", self, MyWindow.onConfirm)
    self.confirmBtn:initialise(); self.confirmBtn:instantiate()
    self:addChild(self.confirmBtn)
end

function MyWindow.onListJoypadA(window, item)
    if window == nil or item == nil then return end
    window.selectedId = item.id
    window:onConfirm()
end

function MyWindow:onGainJoypadFocus(joypadData)
    ISCollapsableWindowJoypad.onGainJoypadFocus(self, joypadData)
    self.drawJoypadFocus = false  -- list draws its own focus border
    self.listBox:setJoypadFocused(true, joypadData)
    setJoypadFocus(self.playerNum, self.listBox)
end

function MyWindow:onLoseJoypadFocus(joypadData)
    ISCollapsableWindowJoypad.onLoseJoypadFocus(self, joypadData)
    self.listBox:setJoypadFocused(false, joypadData)
end

function MyWindow:onJoypadDown(button, joypadData)
    if button == Joypad.BButton then self:close(); return end
    ISCollapsableWindowJoypad.onJoypadDown(self, button, joypadData)
end

function MyWindow:close()
    self:setVisible(false)
    self:removeFromUIManager()
    if JoypadState.players and JoypadState.players[(self.playerNum or 0) + 1] then
        setJoypadFocus(self.playerNum or 0, nil)
    end
end
```

Opening it from a context-menu callback:

```lua
local function openWindow(worldobjects, playerNum)
    playerNum = playerNum or 0
    local w = MyWindow:new(x, y, W, H, playerNum)
    w:initialise(); w:addToUIManager()
    if JoypadState.players and JoypadState.players[playerNum + 1] then
        setJoypadFocus(playerNum, w)
    end
end
```

## Wiring the world-object context menu for controller

The context-menu handler needs the `test = true` gate AND must pass the
player index through so the callback knows which pad opened it. Vanilla
`context:addOption(name, target, onSelect, param1, ...)` passes `target,
param1, ...` into `onSelect`, so `player` becomes a positional arg on the
callback.

```lua
local function onFillWorldObjectContextMenu(player, context, worldobjects, test)
    if not thisMenuAppliesTo(worldobjects) then return end
    -- Controller poll: PZ calls with test=true first just to see if any
    -- handler has options. Signal yes, otherwise the pad menu closes empty.
    if test == true then
        return ISWorldObjectContextMenu.setTest()
    end
    context:addOption("Do the thing", worldobjects, openWindow, player)
end

Events.OnFillWorldObjectContextMenu.Add(onFillWorldObjectContextMenu)
```

`openWindow`'s signature is `(worldobjects, playerNum)` — matching the
`target, param1` positional-arg convention.

## Beyond the list — text entries, steppers, custom grids

Patterns for a hand-rolled plain-`ISPanel` window with custom-drawn
widgets; all verified against vanilla source.

**`ISTextEntryBox` has native pad support, but only for A and X.** When the
entry holds joypad focus, A opens the `OnScreenKeyboard` (it stashes
`osk.prevFocus` and hands focus back on confirm) and X clears when a clear
button is set. Every other button routes to
`self.parent:onJoypadDown_Descendant(...)`, which `ISUIElement` walks up the
parent chain as a no-op — and the entry never consults `joypadParent`. So
"set `joypadParent` on the entry so B backs out" does nothing: to make B or
X act from a focused entry (back out to a list, apply a typed price),
subclass the entry and intercept those buttons in `onJoypadDown` before
deferring to `ISTextEntryBox.onJoypadDown`.

**Entry + button dialogs: use the button-row grammar.** For a small dialog
(amount box + Send button), don't forward focus to a child — keep focus on
the window and register rows in creation order:
`self:insertNewLineOfButtons(self.amountEntry)`,
`self:insertNewLineOfButtons(self.sendBtn)`, then seed
`self.joypadIndexY = 1` / `self.joypadIndex = 1` (see
`ISDesignationZonePanel`). D-pad up/down moves between rows;
`ISPanelJoypad:onJoypadDown` routes A to `forceClick()` on buttons and A/X
into text entries. The focused control highlights itself (`ISTextEntryBox`
prerender draws a cyan double border when `joypadFocused`; `ISButton`
renders focus styling) — no extra rendering code needed.

**D-pad left/right on a list are free for steppers.**
`ISScrollingListBox:onJoypadDirLeft/Right` just forward to `joypadParent`,
so a list subclass can override them for per-row quantity/count steppers —
route through the same handler the mouse hit-test uses so clamping and
guards stay shared. Bumpers work too: `Joypad.LBumper` / `Joypad.RBumper`
reach the focused element's `onJoypadDown` on a short press (vanilla only
hijacks RBumper on a 250 ms *hold* for UI-navigation mode) — good for ±10
steps or cycling currency chips.

**Custom grids: keep focus on the window.** For a hand-rolled grid of cell
panels (not an `ISScrollingListBox`), don't try to make cells focusable —
leave joypad focus on the window itself and implement
`onJoypadDirUp/Down/Left/Right` + `onJoypadDown` at window level, moving
your existing selection state and reusing the mouse-path select/scroll
functions so the selected cell stays on-screen. Gate every pad action on the
same enable conditions the equivalent button's prerender enforces (purchase
in flight, affordability, selection present).

## Focus lifecycle — who owns focus when

| Moment | Focus owner |
|---|---|
| Player triggers the world context menu on pad | `ISContextMenu` (its `origin = <player HUD>`) |
| Player presses A on our option | Context menu closes → focus resets to `origin` → our `onSelect` runs |
| Our callback calls `setJoypadFocus(playerNum, window)` | The window |
| Window's `onGainJoypadFocus` fires next frame → forwards to list | The list |
| Player presses D-pad up/down | List internal (`ISScrollingListBox:onJoypadDirUp/Down`) |
| Player presses A on a row | `overrideAButtonFunction(window, item)` → `window:onConfirm()` → `window:close()` |
| Player presses B on the list | Our `MyList:onJoypadDown` calls `window:close()` |
| `window:close()` → `setJoypadFocus(playerNum, nil)` | Nothing (control returns to gameplay) |

If focus ever "sticks" — the pad seems dead after closing — it's almost
always the `setJoypadFocus(playerNum, nil)` line missing from `close()`.

## Gotchas

**B needs to be handled at the list level to close in one press.**
`ISScrollingListBox:onJoypadDown` treats B as "walk back to `joypadParent`"
— useful when the list is a child of a bigger navigable panel, but for a
window whose whole point IS the list, that means B once un-focuses the
list, and B again closes the window. Users hate this. Subclass the list and
short-circuit B to `parentWindow:close()`.

**`ISCollapsableWindowJoypad.new` uses `.new(self, ...)`, not `:new(...)`.**
The colon form goes through `ISPanelJoypad`'s metatable and hands you back
the wrong `self`. Every reference implementation uses the dot form. Copy it
verbatim; do not "clean up".

**Don't set `joypadFocused = true` by hand.** Always call
`setJoypadFocused(true, joypadData)`. The method also assigns
`joypadData.focus = self` and calls `updateJoypadFocus(joypadData)` — the
raw-field-set path skips those and produces a "focus is here but no border
draws and nothing responds" ghost state.

**`setJoypadFocused(true, ...)` auto-selects row 1 only when `selected ==
-1`.** Windows that use `selected = 0` as "no selection" (common in mods)
never trigger it — default the selection yourself on focus gain AND when
rows arrive after a server round-trip, guarded on `list.joypadFocused`, or
pad users land on a list with no highlight where A does nothing.

**`drawJoypadFocus` only matters for `ISCollapsableWindow`-based windows.**
Its only consumer is `ISCollapsableWindow:render`; `ISPanelJoypad` never
reads it. On an `ISPanelJoypad`-derived window the flag is dead code either
way — the focus highlight comes from the child widgets themselves.

**A custom `doDrawItem` that ignores `selected` makes pad nav invisible.**
Mouse-only lists whose rows route clicks straight to per-row controls often
never draw a selection highlight. Add a selected-row fill guarded on
`self.joypadFocused` so pad users can see the cursor while mouse sessions
look exactly as before.

**`autoAddJoypadButton` on custom elements.** `ISButton` /
`ISScrollingListBox` / `ISTickBox` / `ISComboBox` / `ISTextEntryBox` have
`autoAddJoypadButton = true` and get picked up by
`ISPanelJoypad:autoGenerateJoypadButtonsLists()`. Any custom
`ISUIElement`-derived widget you want in the tab-order needs
`o.autoAddJoypadButton = true` set in its `:new`. Vanilla defaults to
`false` on the base class.

**Two overlapping ways to lay out button rows.** Either call
`self:insertNewLineOfButtons(btn1, btn2, ...)` for each row explicitly, or
call `self:autoGenerateJoypadButtonsLists()` after all children are added
and let PZ walk descendants and group by absolute Y. For a list-driven
window you usually don't need either — you forward focus straight into the
list and never navigate the window's own button grid.

**`getSpecificPlayer(0)` vs `self.playerNum`.** In single-player and
single-client MP they're the same. In split-screen (`playerNum` 1/2/3) they
diverge, and hard-coded `getSpecificPlayer(0)` sends commands as the wrong
player. Track `playerNum` on the window and thread it through
`sendClientCommand`, `HaloTextHelper.addGoodText`,
`ISTimedActionQueue.add`, etc.

## Verifying it works

Restart the client — do NOT try to hot-reload this change. Two PZ Lua
hot-reload traps combine to leave the game running the old code with no
warning:

1. **`reloadLuaFile` compiles with `rewriteEvents=true`, which silently
   swallows every `Events.X.Add(fn)` call** in the reloaded file. Your new
   `OnFillWorldObjectContextMenu` handler never registers, and PZ keeps
   invoking the old one — the one closed over the old `openWindow`, the old
   window class, everything you just changed.
2. **String-named callbacks are cached in `LuaManager.luaFunctionMap`
   forever.** Only `reloadLuaFile(<abs path>)` clears the map; a raw
   `/reload` eval redefines the field but the game keeps calling the old
   function object. Anything the game resolves by name (some sprite-config
   / crafting hooks) stays pinned to the pre-edit function.

The base-class swap, the `Events.OnFillWorldObjectContextMenu`
re-registration, and the per-instance methods bound in `createChildren` all
depend on those hooks running fresh. Hot-reload leaves them stale. Full
client restart is the reliable path.

If you really need to iterate without restarting (uncommon; not required
for the pattern in this skill), the working workaround is: rsync to the
Steam Workshop dev-mod dir → `reloadLuaFile(<abs path>)` to clear the
function map → in the same raw eval, re-register a **permanent trampoline**
with `Events.X.Remove(tramp); Events.X.Add(tramp)` (idempotent). The
trampoline resolves the real handler from the module table at call time,
so subsequent reloads only swap a table field. `Events.X.Remove` by
reference across reloads is unreliable, so without a trampoline you stack
orphaned handlers every iteration.

Once running, on a controller:

- Trigger the world context menu near the target object. Your option must
  appear — if it doesn't, `setTest()` is missing.
- A on your option → window opens with the list already highlighting row 1.
  If the window opens but no row is highlighted, `onGainJoypadFocus` didn't
  forward focus to the list.
- D-pad up/down cycles rows with wrap. If it doesn't move, focus is on the
  window, not the list.
- A on a row confirms and closes. B closes in one press.

## Common failures

| Symptom | Cause |
|---|---|
| Controller menu opens near the object but our option is absent | Missing `ISWorldObjectContextMenu.setTest()` in the `test == true` branch. |
| Our option is present but pressing A does nothing | The callback signature doesn't match; `context:addOption` passes `target, param1, param2, ...` — the first arg is what you set as `target=` in the addOption call, NOT the player. Rewrite the callback as `(target, playerNum)` and always pass `player` as `param1`. |
| Window opens but D-pad doesn't move anything | `setJoypadFocus(playerNum, window)` never ran, or `onGainJoypadFocus` didn't call `setJoypadFocus(playerNum, self.listBox)`. Focus is stuck on the player HUD. |
| A on an empty list crashes with `attempt to index nil` | `overrideAButtonFunction` is set on a list with `items == {}`. Subclass and swallow A when empty (see the `MyList:onJoypadDown` snippet above). |
| B takes two presses to close the window | The list is walking B back to `joypadParent`. Handle B directly in the list's `onJoypadDown` (call `parentWindow:close()`) instead of relying on the vanilla parent walk. |
| After closing, controller feels frozen | `close()` doesn't reset joypad focus. Add `setJoypadFocus(self.playerNum, nil)` when `JoypadState.players[playerNum + 1]` is set. |
| Focus border draws on the window AND the list at the same time | `self.drawJoypadFocus = true` (default from `ISCollapsableWindowJoypad`). Set it to `false` in `onGainJoypadFocus` after forwarding to the list — the list draws its own border. (`ISCollapsableWindow`-based windows only; `ISPanelJoypad` never reads the flag.) |
| List has focus but no row is highlighted and A does nothing | The window uses `selected = 0` as "none"; vanilla auto-select fires only on `selected == -1`. Default to row 1 on focus gain and on refresh, guarded on `joypadFocused`. |
| B (or any non-A/X button) is dead while a text entry has focus | `ISTextEntryBox` only handles A (on-screen keyboard) and X (clear); everything else no-op walks `parent:onJoypadDown_Descendant` and `joypadParent` is ignored. Subclass the entry and intercept in `onJoypadDown`. |
| Changes don't take effect after edit | Hot-reload traps: `reloadLuaFile` compiles with `rewriteEvents=true` and drops `Events.X.Add(fn)` calls; `LuaManager.luaFunctionMap` pins string-named callbacks forever. Restart the client — see the "Verifying it works" section for the trampoline workaround if you really need to iterate live. |

## Reference implementations

Read these in the vanilla PZ source for the wider grammar:

- `../project-zomboid-base/src/main/lua/client/ISUI/ISPanelJoypad.lua` —
  every joypad hook, `insertNewLineOfButtons`, `setISButtonForA/B/X/Y`,
  `autoGenerateJoypadButtonsLists`, `getJoypadFocus`, `setJoypadFocus`.
- `../project-zomboid-base/src/main/lua/client/ISUI/ISScrollingListBox.lua`
  — `onJoypadDirUp/Down` at 721/735, `onJoypadDown` at 778 (the
  A-crash-on-empty path), `setJoypadFocused` at 17,
  `overrideAButtonFunction` semantics.
- `../project-zomboid-base/src/main/lua/client/ISUI/ISCollapsableWindowJoypad.lua`
  — 47 lines, worth reading once.
- `../project-zomboid-base/src/main/lua/client/ISUI/ISContextMenu.lua` —
  joypad navigation of the context menu itself, `origin` field, `closeAll`
  focus reset.
- `../project-zomboid-base/src/main/lua/client/ISUI/ISWorldObjectContextMenu.lua`
  — `createMenu` at 169, the `setTest` doc at 148, the two-phase
  `OnFillWorldObjectContextMenu` trigger at 251.
- `../project-zomboid-base/src/main/lua/client/ISUI/ISDesignationZonePanel.lua`
  — a cleanly structured "list + button row" window with joypad support and
  the `insertNewLineOfButtons` grid pattern.
- `../project-zomboid-base/src/main/lua/client/ISUI/Gamepad/JoyPadSetup.lua`
  — `Joypad.AButton` / `BButton` / textures, `JoypadState.players`,
  `getJoypadData`, `setJoypadFocus`, `updateJoypadFocus`,
  `JoypadControllerData:onPressButton` dispatch.
