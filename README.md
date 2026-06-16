# math-reader-boox

[math-reader](https://github.com/mathslmy/math-reader) PWA 的 Boox 墨水屏专用 APK 封装：
功能与 math-reader 完全一致，仅手写部分接入 Boox 官方手写 SDK（onyxsdk-pen），
在 Boox Tab / Note / Go 系列（如 Tab 10.3 / Go 10.3 Gen2）上获得原生级低延迟书写体验。

## 工作原理

```
┌──────────────────────────────────────────────┐
│ MainActivity (全屏 WebView)                   │
│   ├── assets/www/  ← math-reader PWA 原样打包 │
│   ├── boox-pen.js  ← 页面加载后注入的适配器    │
│   └── TouchHelper  ← Onyx 手写 SDK 直渲染层   │
└──────────────────────────────────────────────┘
```

1. WebView 通过 `https://appassets.androidplatform.net` 同源加载打包在 assets 里的
   PWA（localStorage / IndexedDB 正常持久化）。`index.html` 基本与上游一致，仅带
   少量**墨水屏阅读增强补丁**（见下文「墨水屏阅读增强」），升级时需手动合并。
2. 注入的 `boox-pen.js` 自动探测当前可手写的画布（习题作答、PDF 批注、草稿纸、
   笔记、讲义画笔），把画布区域交给原生 `TouchHelper` 做 EPD 低延迟直渲染。
3. 抬笔后 SDK 回调整笔触点，适配器以合成 PointerEvent 回放给页面——PWA 自己的
   绘制、撤销、保存、AI 识别逻辑全部照常工作，数据仍存在 PWA 一侧。
4. PWA 工具栏切到橡皮擦时自动挂起直渲染，走 PWA 默认事件路径；笔杆侧橡皮
   （raw erasing）则映射回 PWA 的橡皮逻辑。
5. 另外桥接了 WebView 不支持的能力：`<input type="file">` 文件选择、
   blob/data 下载落盘（导出 PDF / JSON 备份）。
6. `BooxPenBridge` 在 WebView 的 `dispatchTouchEvent` 里记录每次按下的工具类型
   （手指 / 触控笔），通过 `BooxPenNative.getLastToolType()` 暴露给页面；
   `boox-pen.js` 据此提供 `window.__booxInput.isPen(e)` 给阅读界面做触控笔检测。

非 Boox 设备上 SDK 初始化失败时自动降级为普通 WebView 应用，功能不受影响；
`window.__booxInput` 仍可用（退化为标准 `PointerEvent.pointerType`）。

## 墨水屏阅读增强（index.html 本地补丁）

仅在「设置 → 墨水屏模式」开启时生效，区分手指与 Boox 触控笔：

- **手指点触** → 翻页（画笔模式、评论模式都翻页）。
- **触控笔 · 画笔模式** → 原生 `TouchHelper` 低延迟书写（翻页热区为透明覆盖层，
  触控笔由原生 SDK 按屏幕矩形拦截，手指仍可穿透热区翻页）。
- **触控笔 · 评论模式** → 在页面上勾选一片区域，抬笔后弹出「问AI」输入框；
  优先提取框选区域内的 PDF 文本（扫描件无文本时回退为截图）发给 AI，提示词与
  讲义「选择问AI」一致。AI 回答以**计数圆点 marker + comment 悬浮框**呈现
  （存为 `doc.annotations` 中 `type:'note', ai:true` 的标注，点圆点看回答、可删除）。

## 构建

GitHub Actions 在每次 push 后自动构建，到仓库 **Actions → Build APK → Artifacts**
下载 `math-reader-boox-debug`。

本地构建（需要 Android SDK，且能访问 `repo.boox.com`）：

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 安装

Boox 设备开启「未知来源安装」，把 APK 拷到设备上点击安装即可。

## 同步上游 math-reader

`manifest.json` / `sw.js` / 图标可直接覆盖；`index.html` 因带有「墨水屏阅读增强」
本地补丁，**不能整文件覆盖**，需手动合并上游改动后保留下列补丁点：

```bash
cp ../math-reader/{manifest.json,sw.js,icon-infinity-white.svg} \
   app/src/main/assets/www/
# index.html 手动 diff 合并，勿覆盖
```

`index.html` 的墨水屏补丁集中在这些标识符上（搜索即可定位）：
`booxReaderIsPen`、`bindEinkReaderTapZone`、`einkSelect*`、`einkExtractTextInBox`、
`askAIAboutReaderSelection`、`addDocAiComment`、`renderPageNoteMarkers`（`n.ai` 分支）、
以及 CSS 的 `.ai-marker` / `.ai-bubble` / `.eink-select-overlay`、HTML 的 `#readerSelectionMenu`。

`boox-pen.js` 依赖 PWA 中以下约定（变更时需同步调整适配器）：

| 画布 | 选择器 | 橡皮按钮 / 切换函数 |
|---|---|---|
| 习题作答 | `#exerciseDoingCanvas` | `#exEraserBtn` / `toggleExEraser()` |
| PDF 批注 | `.annotation-canvas` | `#readerEraserBtn` / `toggleReaderEraser()` |
| 笔记手写 | `#noteCanvas` | `#noteEraserBtn` / `toggleNoteEraser()` |
| 草稿纸 | `#draftCanvas`、`#lectureDraftCanvas` | — |
| 讲义画笔 | `#lectureDrawCanvas` | — |

## 关键文件

- `app/src/main/java/com/mathreader/boox/MainActivity.java` — WebView 壳、文件选择、下载、触控笔工具类型上报
- `app/src/main/java/com/mathreader/boox/BooxPenBridge.java` — TouchHelper 封装 + JS 桥 + 工具类型检测（`getLastToolType` / `isStylusActive`）
- `app/src/main/assets/boox-pen.js` — 画布探测 / 笔迹回放适配器 + 触控笔检测 `window.__booxInput`
- `app/src/main/assets/www/` — math-reader PWA（`index.html` 含墨水屏阅读增强补丁）
