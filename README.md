# Fcitx5 for Android — SCBH 定制版

基于 [BryceWG/fcitx5-android-bibi-keyboard](https://github.com/BryceWG/fcitx5-android-bibi-keyboard)（上游 `fcitx5-android` 的哔哩键盘分支）的定制构建。

**SCBH = Searchable Clipboard History**，本版本只做三件事，但都做到底：

1. 剪贴板支持**用真正的输入法键盘**搜索（中文、候选词、词库、词频与正式键盘完全一致）；
2. 构建变体从 `debug` 改名为 `scbh`，可与官方版**共存安装**；
3. 用户数据（词库 / 剪贴板 / 键盘布局与其它设置）**跨变体导入导出**，官方版导出的备份可以直接导入本版。

APK：`fcitx5-bibi-keyboard-scbh.apk`（arm64-v8a，debug 签名，包名 `org.fcitx.fcitx5.android.scbh`）。

### 交付物

| 文件 | 说明 |
|---|---|
| `fcitx5-bibi-keyboard-scbh.apk` | 可直接安装的 SCBH 构建（约 60 MB，arm64-v8a，`versionName 0.1.3-2-0-gdf8e032`，`versionCode 112`） |
| `fcitx5-android-scbh-src.tar.gz` | 完整工程源码（已排除第三方子模块、Gradle `.cxx/build` 产物与构建时生成的 `assets/usr`） |
| `fcitx5-android-scbh.patch` | 相对上游 `df8e032` 的 unified diff，20 个文件（含 4 个新增文件） |
| `README.md` | 本文档 |
| `AI-HANDOVER.md` | 给 AI/后续维护者的交接文档（踩坑记录、方案取舍、验证清单） |

源码包解压后补子模块即可编译：

```bash
git submodule update --init --recursive \
  lib/fcitx5/src/main/cpp/fcitx5 lib/fcitx5/src/main/cpp/prebuilt \
  lib/fcitx5-lua/src/main/cpp/fcitx5-lua lib/libime/src/main/cpp/libime \
  lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons
```


---

## 1. 剪贴板搜索

### 怎么用

1. 在任意输入框呼出键盘 → 工具栏的剪贴板按钮进入剪贴板页；
2. 标题栏右侧、垃圾桶左边有一个圆角搜索框「🔍 搜索剪贴板」，点它；
3. 键盘从底部升起（和平时打字**完全一样**的键盘），顶部多出一条检索面板：
   - `←` 退出搜索，回到剪贴板列表；
   - 中间是查询内容，拼音打字的**编码（preedit）**会实时显示在这里；
   - `×` 清空查询；
   - 下方是实时过滤的剪贴板结果（两列卡片，和列表页同一套 UI），点一下就粘贴到当前输入框；
4. 空白查询时列出最近条目，等于「随手挑一条粘贴」；
5. 回车 = 退出搜索。

### 怎么实现的（一句话）

不自己画键盘，而是**把输入法自己的键盘借过来**：搜索面板挂在 `InputView` 里、紧贴真实 `KeyboardWindow` 的上方，同时在 `FcitxInputMethodService` 上装一个 `TextInputSink`，把引擎本来要提交给 App 的文本改成提交给搜索框。于是：

| 项目 | 结果 |
|---|---|
| 键盘布局 / 高度 / 主题 | 直接复用 `KeyboardWindow`、`TextBasedKeyboard`，与正常打字 100% 一致 |
| 候选词条 | 直接复用 `KawaiiBarComponent` + `CandidateUi` |
| 词库 / 词频 / 用户自造词 | 同一个 fcitx5 引擎、同一份 `user.dict`、`user.history`、`customphrase` |
| 编辑行为 | 空格 / 退格 / 回车上屏 / 长按符号 / 滑动输入 / 语音键，全部照旧 |
| 宿主 App | 搜索期间**一个字符都不会写进 App**，`setComposingText` 被拦截 |

搜索匹配本身（`ClipboardSearch.kt`）在子串匹配（忽略大小写）之外还支持拼音：

- `weixin` → 命中「微信」
- `wx` → 命中「微信」（首字母缩写）
- `cq` / `zq` → 都能命中「重庆」（多音字）

拼音表是 `app/src/main/assets/clipboard_pinyin.txt`（GB2312 全部汉字，64 KB，每字一行 `字\t读音1,读音2`）。所以即使用户当前是英文输入法，打 `wx` 也能搜到中文条目。

---

## 2. SCBH 变体

| | 官方版 | 本版 |
|---|---|---|
| 包名 | `org.fcitx.fcitx5.android` | `org.fcitx.fcitx5.android.scbh` |
| 应用名 | Fcitx5 / 小企鹅输入法 | Fcitx5 (SCBH) / 小企鹅输入法 (SCBH) |
| 构建类型 | release | debug（debug 签名） |

实现方式是沿用上游「debug 变体加后缀」的机制，只把后缀和显示名换掉（`build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt`、`app/src/main/res/values*/strings.xml`）。两个包可以同时安装、同时在输入法列表里出现，互不干扰。

> 因为共用同一个 `applicationId` 前缀，两者**共享不了**数据目录：剪贴板历史、词库、设置都是各自独立的一份。想让新包继承旧数据，用下一节的导入导出。

---

## 3. 用户数据导入导出

### 能导出什么

设置 → 高级 → 「导出用户数据」打出的 zip 包含：

| zip 内路径 | 内容 |
|---|---|
| `external/data/pinyin/` | 拼音词库：`user.dict`（用户词/词频）、`user.history`（输入历史） |
| `external/data/pinyin/customphrase` | 自定义短语 |
| `external/data/table/` | 码表输入法的 `db.user.dict` / `db.history` |
| `external/data/punctuation/` | 标点映射 |
| `external/config/` | fcitx5 的 `profile`、`config` 与各 addon 配置（含**当前输入法方案**） |
| `external/theme/` | 自定义主题 |
| `databases/clbdb` | 剪贴板历史（含置顶、删除标记、敏感标记） |
| `shared_prefs/*.xml` | 全部 App 设置：**键盘布局、键盘高度、候选词数量/样式、主题、剪贴板开关**、最近使用列表等 |
| `metadata.json` | 导出时的包名 / versionCode / versionName / 时间戳 |

也就是说：**词库、剪切板、键盘布局在内，App 的所有用户数据都在里面**，换机/换变体只要带上这个 zip。

导出前会先对剪贴板数据库做一次 `PRAGMA wal_checkpoint(TRUNCATE)`，并把 `-shm` 这类临时共享内存文件排除掉，保证数据库文件是自洽的、可被直接打开的。

### 导入

设置 → 高级 → 「导入用户数据」→ 选择 zip → 确认（会覆盖本机数据）→ App 自动重启。

本版相对上游修了两个会直接让导入失败/静默失效的问题：

1. **包名校验过严**（就是「User data does not match current application」）。
   上游要求 `metadata.packageName == 本机 applicationId` 才允许导入，于是官方版（`…android`）导出的包无法导入 SCBH 版（`…android.scbh`）。现在改成比较**同族包名**：剥掉末尾的 `debug / scbh / dev / release / nightly / beta` 再比，同族即可导入；不同 App 的包仍然会被拒绝。
2. **SharedPreferences 文件名跟着包名走**。
   `PreferenceManager.getDefaultSharedPreferences()` 的文件名是 `<applicationId>_preferences.xml`。跨变体导入时，备份里是 `org.fcitx.fcitx5.android_preferences.xml`，本机只认 `…android.scbh_preferences.xml`，直接拷贝等于什么都没导入（设置/键盘布局全部丢失，而且不报错）。现在导入时会先把该文件改名成本机包名，再整体覆盖。

另外：剪贴板数据库 schema 与备份里的完全一致（Room version 4，`identity_hash = b0fe6cdac09e0d7deaff17d8b45fe565`），626 条历史可原样恢复。

---

## 4. 构建

环境（本仓库已在沙箱里验证过）：

- JDK 21、Gradle 9.6.1（wrapper 会自己下，国内可换镜像）
- Android SDK：`platforms;android-36`、`build-tools;36.1.0`、`ndk;28.0.13004108`、`cmake;3.31.6`
- 主机依赖：`extra-cmake-modules`、`gettext`（`msgfmt`）、`pkg-config`
- 原生依赖子模块：`fcitx5`、`fcitx5-lua`、`libime`（内含 kenlm）、`fcitx5-chinese-addons`、`prebuilt`

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug          # 产出 SCBH 包
./gradlew :app:assembleRelease        # 正式包，需要签名环境变量（见下）
```

release 签名走环境变量：`SIGN_KEY_FILE`（或 `SIGN_KEY_BASE64`）+ 上游 `signingConfigs.fromProjectEnv` 里定义的其它字段。

构建小贴士（都是这次踩过的坑）：

- 原生编译很吃内存，容器里建议 `ninja -j2`，并把 Gradle 堆限制在 2 GB：
  `-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"`；
- `extra-cmake-modules` 缺失会在 fcitx5 的 CMake 阶段直接失败；
- 缺少 `gettext` 时 `msgfmt` 找不到，同样在 CMake 阶段失败。

---

## 5. 与上游的差异（代码地图）

| 文件 | 改动 |
|---|---|
| `input/FcitxInputMethodService.kt` | 新增 `TextInputSink` 接口 + `textInputSink` 字段；在 `commitText` / `updateComposingText` / 退格 / 回车 / 方向键 / deleteSurrounding / 物理按键分支上拦截；新增 `discardComposing()`；`onComputeInsets` 改为跟随搜索面板的高度 |
| `input/clipboard/ClipboardSearchPanel.kt` | **新增**，搜索面板（查询显示 + 结果列表 + 与引擎的桥接） |
| `input/InputView.kt` | 挂载搜索面板（紧贴键盘上方）、注册组件、新增 `computeContentTop()` |
| `input/clipboard/ClipboardUi.kt` | 标题栏加「🔍 搜索剪贴板」入口（圆角框） |
| `input/clipboard/ClipboardWindow.kt` | 点搜索框 → 进入搜索面板；列表逻辑保持不变 |
| `input/clipboard/ClipboardAdapter.kt` | 抽出 `buildClipboardAdapter()`，列表页与搜索结果共用一套条目操作 |
| `input/bar/ui/TitleUi.kt` | 标题栏扩展（搜索框）排在标题文字之后，不再压住标题 |
| `input/preedit/PreeditComponent.kt` | 新增 `suppressed`，搜索时隐藏浮动 preedit（否则会和面板内的编码重复显示） |
| `data/clipboard/ClipboardSearch.kt` | **新增**，子串 + 拼音匹配 |
| `data/clipboard/db/ClipboardDao.kt`、`ClipboardManager.kt` | 新增 `allEntriesList()`、`searchEntries()`、`checkpointDatabase()` |
| `data/UserDataManager.kt` | 同族包名校验、SharedPreferences 改名、`-shm` 过滤 |
| `app/src/main/assets/clipboard_pinyin.txt` | **新增**，GB2312 拼音表 |
| `app/src/main/res/drawable/ic_baseline_close_24.xml` | **新增**，搜索框的清除图标 |
| `build-logic/.../AndroidAppConventionPlugin.kt` | `applicationIdSuffix = ".scbh"` |

完整补丁见 `fcitx5-android-scbh.patch`，源码见 `fcitx5-android-scbh-src.tar.gz`。

---

## 6. 已知限制

- **只有 arm64-v8a**。需要其它 ABI 时加 `-PBUILD_ABI=x86_64` 之类重编。
- **未做真机回归**：这套改动是在没有 ARM 设备的环境里编译通过的，搜索面板高度、insets 联动这些依赖真机表现，详见交接文档里的验证清单。
- 搜索面板目前是「一次性会话」：退出搜索后查询内容不保留，回到列表页。
- 图片类主题（自定义背景）下，搜索面板用的是主题纯色背景，不会显示背景图片。
- 拼音匹配表只覆盖 GB2312（约 6763 字），生僻字请直接打汉字或用子串匹配。

---

## 7. 许可

上游为 LGPL-2.1-or-later，本仓库改动沿用同一许可，新增文件保留 SPDX 头。
