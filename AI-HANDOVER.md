# AI 交接文档 — Fcitx5 for Android / SCBH 定制

写给下一个接手这份代码的 AI（或人）。目标不是复述 README，而是把**为什么这么做、试过什么、哪里可能炸**讲清楚，让你能在不重走弯路的前提下继续改。

---

## 0. 一句话现状

在 `BryceWG/fcitx5-android-bibi-keyboard`（上游 `fcitx5-android` 的分支，自带语音/剪贴板同步等魔改）上做了三件事：给剪贴板加搜索、把变体从 `debug` 改名 `scbh`、修复跨变体用户数据导入。代码可编译出 APK，但**没有真机验证环境**（沙箱无 ARM 设备、无 KVM）。

---

## 1. 必须先建立的心智模型

这套代码不是普通 Android App，改之前请先理解四点：

### 1.1 输入法窗口是「全屏窗口 + insets」

`FcitxInputMethodService.setInputView()` 会把 InputView 的 `LayoutParams.height` 强制改成 `MATCH_PARENT`，`onConfigureWindow()` 又把窗口设成 `MATCH_PARENT × MATCH_PARENT`。真正「键盘占多高」完全由 `onComputeInsets()` 决定：

```kotlin
contentTopInsets = keyboardView.getLocationInWindow()[1]
visibleTopInsets = 同上
touchableInsets = TOUCHABLE_INSETS_VISIBLE
```

**推论**：想在键盘上方加任何东西，必须同时让 `contentTopInsets`/`visibleTopInsets` 跟上去，否则 App 不会让位、那块区域也**收不到触摸**（`TOUCHABLE_INSETS_VISIBLE` 只覆盖 visibleTop 以下的区域）。这是本次改动最容易踩的坑，见 `InputView.computeContentTop()`。

### 1.2 窗口管理器一次只挂一个窗口

`InputWindowManager.attachWindow()` 是「移除旧的 → 加新的」，`windowManager.view` 高度固定为键盘高度。所以**不能**同时显示 ClipboardWindow 和 KeyboardWindow，也不能在 ClipboardWindow 里塞键盘 —— 这是选择「面板 + 真实键盘」结构的原因。

### 1.3 UI 组件是 DI + 广播

- `InputView.setupScope()` 里 `scope += component`，组件通过 `manager.must<T>()` 取依赖；
- 任何组件实现 `InputBroadcastReceiver` 就会自动收到 `onInputPanelUpdate` / `onClientPreeditUpdate` / `onWindowAttached` 等广播（`InputBroadcaster` 用 `ScopeEvent.DependencyArrivedEvent` 自动注册）；
- `ClipboardSearchPanel` 就是靠这个拿到 preedit 和窗口切换事件的。

### 1.4 文本从引擎到 App 只有一条路

按键 → `FcitxKeyAction` → `service.postFcitxJob { sendKey(...) }` → **fcitx5 原生引擎** → 回调事件：

```
CommitStringEvent  → service.commitText()      → InputConnection.commitText()
ClientPreeditEvent → service.updateComposingText() → InputConnection.setComposingText()
KeyEvent(virtual)  → 退格/回车/方向键 → service.handleXxxKey() → IC 或 sendKeyEvent
DeleteSurroundingEvent → service.handleDeleteSurrounding() → IC.deleteSurroundingText()
```

**所有会写 App 文本的地方都在这 6 个函数里**，这也是本次拦截的唯一入口集合。

---

## 2. 需求一：`debug` → `SCBH`

改了三处：

| 位置 | 改动 |
|---|---|
| `build-logic/convention/.../AndroidAppConventionPlugin.kt` | `applicationIdSuffix = ".debug"` → `".scbh"` |
| `res/values/strings.xml`、`values-zh-rCN`、`values-zh-rTW` | `app_name_debug` → `Fcitx5 (SCBH)` / `小企鹅输入法 (SCBH)` |
| `AndroidPluginAppConventionPlugin.kt`、`lib/plugin-base/src/debug/AndroidManifest.xml` | 插件侧 `MAIN_APPLICATION_ID` 同步成 `.scbh`（否则插件 APK 认不到主程序） |

**没改** `BuildConfig.BUILD_TYPE`（仍是 `debug`），因为 `PluginDescriptor.pluginPackageSuffix = ".${BuildConfig.BUILD_TYPE}"`，一改插件包名规则就乱了。变体名 ≠ 应用 id 后缀，这是上游的设计，保持即可。

> 另一个选项是新增一个真正的 `buildTypes { create("scbh") }`，但那要同步 `BuildMetadataPlugin`、`aboutLibraries`、`proguard`、`art profile` 等一堆按 variant 注册的任务，收益为零，风险不小。

---

## 3. 需求二：导入导出兼容

### 3.1 「User data does not match current application」的真因

`UserDataManager.import()` 原本是：

```kotlin
if (metadata.packageName != BuildConfig.APPLICATION_ID) errorRuntime(...)
```

官方版导出的 `metadata.packageName = org.fcitx.fcitx5.android`，SCBH 版是 `org.fcitx.fcitx5.android.scbh` → 必然拒绝。

**改法**：比较「同族包名」——剥掉末尾的 `debug/scbh/dev/release/nightly/beta` 再比。这样官方↔SCBH↔未来的其它变体互通，而真正的别的 App（比如某个也用了 fcitx5 名字的 fork）仍会被拒。

### 3.2 藏在后面的第二个坑（不修就「导入成功但设置全丢」）

`PreferenceManager.getDefaultSharedPreferences()` 的文件名 = `<applicationId>_preferences.xml`。跨变体导入时备份里是 `org.fcitx.fcitx5.android_preferences.xml`，而本机只读 `org.fcitx.fcitx5.android.scbh_preferences.xml`。

上游逻辑是 `copyRecursively(target, overwrite = true)`——**整目录替换**，于是旧文件名的 prefs 被原样拷进去，谁都不会读它：键盘布局、主题、剪贴板开关全部回到默认值，**而且不报错**。

**改法**：解压到临时目录后，先把 `*_preferences.xml` 改名成本机包名的文件，再拷贝（`UserDataManager.adoptSharedPrefs`）。

### 3.3 顺手加固的两点

- 导出前 `PRAGMA wal_checkpoint(TRUNCATE)`（`ClipboardManager.checkpointDatabase()`），保证 `databases/clbdb` 单文件自洽；
- 导出/导入都过滤/删除 `-shm`（SQLite 共享内存索引，属于临时文件，带着走反而可能让 SQLite 判定异常）。

**验证过**：备份里的 `clbdb` 是 Room v4、`room_master_table.identity_hash = b0fe6cdac09e0d7deaff17d8b45fe565`，与本仓库 `app/schemas/.../4.json` 的 hash **完全一致**，schema 也逐列相同（`id/text/pinned/timestamp/type/deleted/sensitive`），626 条历史可直接用。

### 3.4 其它可选方案（没采用）

| 方案 | 为什么不用 |
|---|---|
| 直接把 SCBH 的包名改回 `org.fcitx.fcitx5.android` | 会覆盖官方版、无法共存；用户明确要求共存 + SCBH 命名 |
| 导入时允许「任意包名」 | 会把别的 App 的数据倒进来，语义上就错了 |
| 导入后手动 `Settings → 恢复默认` 再逐项设置 | 用户要的是「能导入」，不是「能手动重设」 |

---

## 4. 需求三：剪贴板里用真正的输入法键盘搜索（重点）

### 4.1 为什么不能「简单放个 EditText」

剪贴板页在**输入法进程的窗口**里，不是 Activity。IME 窗口里的 `EditText` 拿不到系统输入法（系统不允许输入法对自己输入），焦点也会打架。上一版因此自己画了一套 QWERTY（`ClipboardSearchKeyboard.kt`，已删除）——能用，但**打不了中文**，词库/词频/候选词全都没有。

### 4.2 采用的方案：借键盘 + 文本拦截（`TextInputSink`）

```
InputView (MATCH_PARENT)
├── preedit（浮动 preedit，搜索时 suppressed）
├── ClipboardSearchPanel   ← 新增：查询行 + 结果列表
└── keyboardView (wrap_content, bottom)
    ├── KawaiiBar（就是候选词那一条！）
    └── windowManager.view（真实键盘 / 剪贴板页 / 选择器…）
```

- 进入搜索：面板 `VISIBLE` + `attachWindow(KeyboardWindow)`；
- `FcitxInputMethodService.textInputSink = panel`，之后引擎产出的文本全部改投搜索框；
- 结果列表实时过滤（`ClipboardManager.searchEntries`，80 ms 防抖）；
- 退出：`textInputSink = null` + 面板 `GONE`。

这样做的好处是**输入体验不是「模仿」而是「就是」**：布局、高度、按键动效、长按符号、滑动、语音键、候选词、词库词频、用户自造词，全部由原代码提供，新旧代码量为负（删了一个自绘键盘）。

### 4.3 关键细节（每条都对应一个真实的坑）

1. **insets 必须跟着面板走**：见 §1.1。`InputView.computeContentTop()` 在面板可见时返回面板顶，否则返回键盘顶；`onComputeInsets` 改用它。`requestLayout` 后系统会重新 `dispatchOnComputeInternalInsets`，App 自动让位——这一点依赖真机验证。
2. **preedit 会重复显示**：引擎一边把 preedit 交给面板显示，一边 `PreeditComponent` 还会在键盘上方飘一条。加 `PreeditComponent.suppressed`，搜索期间让它一直 INVISIBLE。
3. **不能污染 App 的文本**：`updateComposingText()` 在 sink 生效时直接 return（不调 `setComposingText`）。注意必须在**进入搜索前**调用新增的 `service.discardComposing()`：如果用户是「拼音打了一半就去点剪贴板」，App 里已经有一段下划线预编辑文本，不主动清掉就会残留在输入框里。
4. **退格语义**：引擎有 preedit 时退格被引擎吃掉（不上屏、只改编码）；preedit 空时才 forward 给 `handleBackspaceKey`，此时才删查询的最后一个码点（用 `offsetByCodePoints`，别把 emoji 劈成两半）。
5. **回车**：`handleReturnKey` 被拦截 → 退出搜索回到列表，绝不触发 App 的 IME action。
6. **物理键盘**：sink 生效时 `FcitxEvent.KeyEvent` 的非 virtual 分支直接丢弃，避免硬件键盘把字符打进 App。
7. **离开搜索的时机**：`onStartInput(!restarting)`（换输入框）、`onWindowHidden`（收键盘）、以及 `onWindowAttached(非 KeyboardWindow)`（点了别的窗口）都会 `cancel() → dismiss()`。
8. **reset 的线程问题**：`service.postFcitxJob { }` 可能在**非主线程**执行，里面不要碰 View。`dismiss()` 里 reset 完要用 `service.lifecycleScope.launch { }` 回主线程再改 `preedit.suppressed`。
9. **KawaiiBar 状态机**：Title 态（带搜索框）只在「扩展窗口」挂载时出现；搜索时挂的是 KeyboardWindow，所以顶部栏会在 Idle（工具栏）/Candidate（候选词）之间切，**搜索框会随窗口切走**——因此查询内容和 preedit 必须由面板自己显示，不能依赖标题栏那个框。
10. **剪贴板页的搜索框只是入口**：它不可编辑（`isClickable` 的圆角框），点一下进入搜索态；不要试图让它显示查询内容，它会随着窗口切走。

### 4.4 考虑过但没采用的方案

| 方案 | 说明 | 为什么没选 |
|---|---|---|
| A. 自绘键盘 + 自己接拼音引擎 | 上一版做法 | 输入体验永远差一截（没有词库/词频/联想/自定义短语），代码量还大 |
| B. 复制/镜像一份 KeyboardWindow 到面板里 | 两套键盘 UI | 视图不能有两个 parent，复制会让 `keyboardView` 的尺寸/主题/prefs 双份维护 |
| C. 把 ClipboardWindow 顶高（原做法），键盘塞进同一窗口 | 需要 reparent 真实键盘视图 | `attach/detach` 会重置键盘内部状态，且 insets 仍是键盘高 |
| D. 给搜索建第二个 fcitx InputContext | 语义最干净 | 原生层 `InputContext` 与 Service/UI 强绑定（候选、preedit 都走同一个 event flow），改 native 风险极高 |
| E. 搜索时用 `WindowManager` 悬浮窗显示查询 | 类似输入法候选悬浮窗 | 悬浮窗拿不到按键焦点，还得处理权限，Android 12+ 限制多 |
| F. 让 KawaiiBar 在搜索时同时显示标题栏 + 候选 | 只改状态机 | 40dp 高度放不下，且会挤压候选词显示 |

**如果真机验证发现 insets 不联动**，备选顺序：① 面板改为放在 `keyboardView` 内部上方（需要把 `updateKeyboardSize()` 一起改，让 `windowManager.view` 高度 = 键盘高 - 面板高）；② 面板高度做成 0/有 两档并由 `service.requestShowSelf()` 强制重算 insets；③ 退回「全屏覆盖式搜索页」（面板占满 IME 区域，不显示键盘上方的候选栏，但那样就不满足「和正式键盘一样高」）。

---

## 5. 构建环境（复现要点）

| 项 | 值 |
|---|---|
| JDK | 21（`/usr/lib/jvm`） |
| Gradle | 9.6.1（`mirrors.cloud.tencent.com/gradle/` 下，比 services.gradle.org 快得多） |
| SDK | `platforms;android-36`、`build-tools;36.1.0`、`ndk;28.0.13004108`、`cmake;3.31.6` |
| 系统依赖 | `extra-cmake-modules`、`gettext`、`pkg-config` |
| 原生源码 | `lib/fcitx5/src/main/cpp/{fcitx5,prebuilt}`、`lib/libime/.../libime`（含 `src/libime/core/kenlm`）、`lib/fcitx5-lua/...`、`lib/fcitx5-chinese-addons/...`，全部按 `git ls-tree HEAD` 的 commit 拉取（GitHub 直连太慢时用 `ghfast.top` 代理 tarball） |

踩过的坑，按出现顺序：

1. `sdkmanager` 直连 Google 只有几十 KB/s → 用 `SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/` 后正常。
2. 原生编译阶段 OOM（容器内存限制约 4 GB）：`ninja` 默认 `-j$(nproc)` 直接打爆。解决办法是给 `$ANDROID_HOME/cmake/3.31.6/bin/ninja` 套一层脚本，强制 `-j 2`（`ninja.real` 才是真身）。**这个 wrapper 是环境级的，不在仓库里**，换机器要重做。
3. `CMake Error: ECM` → `apt install extra-cmake-modules`。
4. `msgfmt not found` → `apt install gettext`。
5. Gradle 依赖下载慢 → `~/.gradle/init.gradle.kts` 里把仓库换成阿里云/腾讯镜像（同样是环境级配置）。

构建命令：

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
/opt/gradle/gradle-9.6.1/bin/gradle :app:assembleDebug --console=plain \
  -Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"
```

---

## 6. 没验证的部分 / 真机验证清单

沙箱里没有 ARM 设备、没有 KVM，**所有 UI 行为都只是编译通过 + 代码走查**。请按下面顺序验证，任何一条不通过都优先怀疑 §4.3 里对应的条目：

1. 剪贴板页 → 点搜索框 → 键盘是否正常升起（不是空白、不是只剩候选栏）；
2. 面板是否出现在键盘**正上方**，并且 App 内容有没有被遮住（insets 是否跟着变）；
3. 拼音输入 `nihao` → 顶部候选栏是否出现、点候选是否上屏到查询框；
4. 退格：有编码时删编码，编码空时删查询字符；
5. 回车 → 是否回到剪贴板列表；
6. 点结果条目 → 是否粘贴到 App 且**没有**把编码/查询文字漏进 App；
7. 搜索中切到别的输入框 / 收键盘 → 面板是否自动消失、App 是否残留下划线文本；
8. 退出搜索后 IME 高度是否恢复（App 是否重新占满）；
9. 深色/浅色主题、有边框主题下颜色是否可读；
10. `设置 → 高级 → 导入用户数据` 选官方版备份 → 是否导入成功、剪贴板是否有历史、键盘布局是否保留。

---

## 7. 后续可以做的

- 搜索结果高亮命中的字词（目前只有列表）；
- 搜索历史 / 记住上次查询；
- 面板高度可拖拽（`keyboardHeightPercent` 那套 prefs 里加一个）；
- `ClipboardSearch.matches()` 目前是全表扫 + 内存过滤（626 条无压力，上万条建议建 FTS 或预生成拼音列）；
- 面板支持「长按条目 → 编辑/置顶」的完整右键菜单（现在复用 `ClipboardAdapter`，其实已经有了，只是面板没测）。
