# Admin UI refresh and vehicle preview corrections

`StormAdminPowerUIFix` compares current option objects, capability availability and
live values before rebuilding the powers panel. Irrelevant refresh events preserve
pending edits without reconstructing widgets. Opening the panel, applying an edit,
permission revocation or an external power change still refreshes it immediately.
No polling task is added.

`VehiclePreviewSkinTransformsPatch` is client-only. In the B42.20.4 vehicle preview
part renderer, an unavailable skin-transform array previously caused an exception
after allocating pooled render data. The guard returns that allocation to the pool
and skips the current part for that frame. A later frame renders normally once the
model is ready. Other rendering exceptions remain visible.

The transformer requires the expected skin-transform call pattern, and the test
weaves the installed game's actual `UI3DScene$VehicleRenderData` class. A separate
execution fixture verifies 100 unavailable-model frames release their allocations
and that rendering resumes once transforms exist.

With Java 25 and the installed game configured in `local.properties`:

```powershell
.\gradlew.bat :test --tests '*VehiclePreviewSkinTransformsPatchTest' -x :jacocoTestReport
lua src/test/lua/AdminPowerRefresh.lua
```

Both Java tests and the five Lua refresh checks pass. Lua checks also pass in the
installed B42.20.4 Kahlua interpreter with a stub UI backend. In-game preview asset
reproduction and high-population performance measurements remain pending. This
does not identify the separate native/process RAM growth or prove a general cure
for disappearing entities.
