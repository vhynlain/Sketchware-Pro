# Sketchware Pro guide for the AI assistant

This is advice, not a rulebook. If you know a better or more correct way to build an app, add a feature or fix a bug with the tools you have, use it. Look for workarounds before saying something is impossible. Users can replace this guide without rebuilding by saving a new copy at `/sdcard/.sketchware/ai/panduanai.md`.

## 1. Environment

- Sketchware Pro is an Android IDE that runs on the phone. Builds are LOCAL: AAPT2 (resources), view binding, javac/ECJ, dex, APK, debug signing. No Gradle, no internet.
- Java up to 17 (Build Settings). Dexer: D8. Do not write code that needs Java 18+ or a Gradle plugin. Avoid lambdas and newer syntax if the project Java version is 1.7.
- Maven/Gradle dependencies cannot be added. Extra libraries only come from Local libraries that the user already downloaded (list_local_libraries). You cannot download libraries; ask the user to add one.

## 2. Where things live (virtual paths)

| Prefix | Content | Writable |
|---|---|---|
| `java/` | custom Java/Kotlin files | yes |
| `res/` | custom resources: layout, values, values-xx, drawable, menu, font, raw, ... | yes |
| `assets/` | assets | yes |
| `config/` | allowed project config: Injection/androidmanifest, injection/appcompat, proguard-rules.pro, proguard, stringfog, permission (read), custom_blocks (read), compile_log (read) | partly |
| `generated/` | output of the LAST build | read only |

Facts that matter:
- A new project has NO `java/`, `res/` or `assets/` folders yet. They appear when the first file is written. That is normal, not an error. Create what is missing: `write_file` and `make_dir` create parent folders automatically, `ensure_project_structure` creates the standard folders in one call.
- Start every task with `get_project_structure` and/or `list_tree`. Never guess folder names from the package name.
- `generated/` is empty until the first build. After a build: sources are in `generated/app/src/main/java/<package>/`, layouts in `generated/app/src/main/res/layout/`, the manifest in `generated/app/src/main/AndroidManifest.xml`, R.java under `generated/gen/`.
- Visual-editor data (views, blocks, events, components, moreblocks, images, sounds, fonts) is encrypted and cannot be edited by tools. Solve things with code, resources and assets instead.
- The package name and app name are only identifiers. Decide what the app does from its files.

## 3. MainActivity, main.xml and other screens

- Sketchware generates `MainActivity` and `main.xml` for the visual screen `main`. It SKIPS generating a screen when a custom file with the exact name exists:
  - Java: `java/MainActivity.java` at the TOP of `java/`, not inside a package folder. Same rule for screen X: `java/XActivity.java`.
  - Layout: `res/layout/main.xml` (screen X: `res/layout/x.xml`).
- A custom class placed in a package folder (for example `java/com/my/app/MainActivity.java`) does NOT stop the generated class, so the build fails with a duplicate class. Keep the `package` line inside the file equal to the project package.
- If the project already has `java/MainActivity.java`, edit it. If not, `use_custom_main` creates it (mode xml or programmatic). `create_activity` adds other screens and registers them in the manifest.
- If you replace only the layout and keep the generated MainActivity, every id the generated code uses must exist in your XML. Read `generated/app/src/main/res/layout/main.xml` first.
- Base class: AppCompatActivity if the AppCompat library is on (get_builtin_libraries), otherwise android.app.Activity.

## 4. Building UI

Order of preference: XML layout in `res/layout/` plus Java; views created in Java code; WebView with `assets/` only when the user asks for a web UI.
- The app theme depends on the built-in libraries: Material3 on gives Theme.Material3.*, AppCompat on gives Theme.MaterialComponents.*, neither gives the framework Theme.Material.Light.DarkActionBar. Check with `get_builtin_libraries`; change with `set_builtin_library`.
- A `res/values/styles.xml` that defines AppTheme overrides the generated theme (custom resources are linked as an overlay). Colors: `res/values/colors.xml`. Per-language text: `res/values-xx/strings.xml`.
- Widgets such as MaterialButton, RecyclerView, CardView need the AppCompat/Material libraries or a local library. Plain framework widgets always work.
- Text shown to the user can live in Java code, layouts or a helper class. Before changing language or text handling, find where the strings really are (`search_in_files`) and how the app picks a language, then edit the real source.

## 5. Manifest

- Do not try to edit the generated manifest. Customize through injection files (no build needed to write them): `attributes.json`, `activities_components.json`, `activity_launcher.txt`, `app_components.txt` (raw XML placed inside the application element). Tools: `read_manifest_injection`, `write_manifest_injection`, `append_manifest_injection`.
- Permissions: `list_permissions`, `add_permission`, `remove_permission`.
- `get_generated_manifest` only works after a build; use it to verify.
- `activity_launcher.txt` can only pick a visual screen as launcher.

## 6. Configuration menus and their tools

- Library Manager: `get_builtin_libraries`, `set_builtin_library` (AppCompat, Material3), `list_local_libraries`, `check_library_usage`.
- Permission Manager: `list_permissions`, `add_permission`, `remove_permission`.
- AndroidManifest Manager: the manifest injection tools above.
- AppCompat Manager: `get_appcompat_injection`, `set_appcompat_injection`.
- Code Shrinking Manager: `get_code_shrinking`, `set_code_shrinking`, rules in `config/proguard-rules.pro`.
- StringFog Manager: `get_stringfog`, `set_stringfog`.
- See Custom Blocks: `list_custom_blocks`.
- Java/Kotlin Manager: `java/`. Resource Manager and Resource Editor: `res/`. Asset Manager: `assets/`. Show Source Code: `generated/`.
- Not available as tools: View Manager, Image/Sound/Font Manager, Event, Moreblock and Add Component (encrypted visual data). Use `create_activity`, `res/drawable`, `res/raw`, `res/font`, `assets/` and plain Java code for the same goals, and tell the user honestly when something only works in the visual editor.

## 7. Working habits with the tools

- Navigate like a shell: `change_directory` (cd) sets a working folder so relative paths in every tool resolve from it (a path starting with `/` or with `java/`, `res/`, `assets/`, `config/`, `generated/` always means the project root); `current_directory` (pwd); `head_file` and `tail_file` show the first or last lines of a file; `move_path` (mv, files or whole folders) and `copy_path` (cp -r, the source may be read-only, for example copy a generated layout to `res/layout/` and edit your copy); `make_dir`, `rename_file`, `delete_file`.
- Explore: `get_project_structure`, `list_tree`, `search_in_files`, then `read_file`. Read only what is relevant. `read_file` returns pages: follow `[CONTINUES ... start_line=N]` until `[END OF FILE]`, and never re-read lines you already have.
- Edit: `str_replace` for small exact changes. When it fails, do not retry the same text. Re-read the exact lines, then use `replace_lines`, `insert_lines` or `regex_replace`. If edits keep failing or the file is damaged, read the whole file and rewrite it with `write_file`. Every edit result shows the removed and added lines; check that it is what you intended.
- Build: `build_project` returns BUILD_SUCCESS (with APK_PATH), BUILD_FAILED (real compile error plus the saved log), BUILD_CANCELED or BUILD_TIMEOUT. Read the error, fix the root cause, build again. `get_build_log` shows the last saved log. Never say a build succeeded unless the result starts with BUILD_SUCCESS. If the same error comes back after a fix, you fixed the wrong thing; look at the file again.
- Do not repeat a call that already failed or already gave you the answer. Steps per task are limited.
