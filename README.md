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
   PWA（localStorage / IndexedDB 正常持久化），`index.html` 与上游**字节一致，零修改**。
2. 注入的 `boox-pen.js` 自动探测当前可手写的画布（习题作答、PDF 批注、草稿纸、
   笔记、讲义画笔），把画布区域交给原生 `TouchHelper` 做 EPD 低延迟直渲染。
3. 抬笔后 SDK 回调整笔触点，适配器以合成 PointerEvent 回放给页面——PWA 自己的
   绘制、撤销、保存、AI 识别逻辑全部照常工作，数据仍存在 PWA 一侧。
4. PWA 工具栏切到橡皮擦时自动挂起直渲染，走 PWA 默认事件路径；笔杆侧橡皮
   （raw erasing）则映射回 PWA 的橡皮逻辑。
5. 另外桥接了 WebView 不支持的能力：`<input type="file">` 文件选择、
   blob/data 下载落盘（导出 PDF / JSON 备份）。

非 Boox 设备上 SDK 初始化失败时自动降级为普通 WebView 应用，功能不受影响。

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

PWA 文件原样打包，升级 = 直接覆盖：

```bash
cp ../math-reader/{index.html,manifest.json,sw.js,icon-infinity-white.svg} \
   app/src/main/assets/www/
```

`boox-pen.js` 依赖 PWA 中以下约定（变更时需同步调整适配器）：

| 画布 | 选择器 | 橡皮按钮 / 切换函数 |
|---|---|---|
| 习题作答 | `#exerciseDoingCanvas` | `#exEraserBtn` / `toggleExEraser()` |
| PDF 批注 | `.annotation-canvas` | `#readerEraserBtn` / `toggleReaderEraser()` |
| 笔记手写 | `#noteCanvas` | `#noteEraserBtn` / `toggleNoteEraser()` |
| 草稿纸 | `#draftCanvas`、`#lectureDraftCanvas` | — |
| 讲义画笔 | `#lectureDrawCanvas` | — |

## 关键文件

- `app/src/main/java/com/mathreader/boox/MainActivity.java` — WebView 壳、文件选择、下载
- `app/src/main/java/com/mathreader/boox/BooxPenBridge.java` — TouchHelper 封装 + JS 桥
- `app/src/main/assets/boox-pen.js` — 画布探测 / 笔迹回放适配器
- `app/src/main/assets/www/` — math-reader PWA 原样拷贝
