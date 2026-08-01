# 高亮九宫格优化（切图 / 外扩 / 图片大小）

- [x] Task 1: HighlightRule 实体新增 4 个外扩字段
    - 1.1: 在 `HighlightRule.kt` 增加 `bgPaddingStart/End/Top/Bottom: Float = 0f`
    - 1.2: 在 `DatabaseMigrations.kt` 新增 migration（ALTER TABLE 加 4 列 REAL NOT NULL DEFAULT 0）
    - 1.3: `AppDatabase.kt` 版本号 +1

- [x] Task 2: Column 数据传递链增补 4 个 padding 字段
    - 2.1: `CharStyle.kt` 增加 `bgPadStart/bgPadEnd/bgPadTop/bgPadBottom`
    - 2.2: `TextBaseColumn.kt` 接口增加对应属性
    - 2.3: `TextColumn.kt` / `TextHtmlColumn.kt` 实现属性
    - 2.4: `TextChapterLayout.kt` 排版时从 HighlightRule 读取 padding 写入 CharStyle

- [x] Task 3: 新增 NinePatchDrawHelper（contain 缩放 + clipRect 单步绘制）
    - 3.1: 创建 `help/highlight/NinePatchDrawHelper.kt`
    - 3.2: 实现 `draw(canvas, bitmap, left, top, right, bottom, leftFrac, rightFrac, topFrac, bottomFrac, paint)` — 计算 contain 缩放系数 s、9 块 srcRect/dstRect、clipRect 绘制
    - 3.3: 边界防护：rectW/rectH ≤ 0 return、角块之和 > rect 时 wM/hM = 0

- [x] Task 4: TextLine.drawBgImageSegment 替换九宫格分支
    - 4.1: `bgImageFit == 3` 分支改为：计算 drawLeft/drawTop/drawRight/drawBottom（叠加 4 个 padding）→ 调用 `NinePatchDrawHelper.draw(...)`
    - 4.2: 删除 `drawNineSliceCenter` 方法
    - 4.3: 删除 `drawNineSliceFrames` 方法
    - 4.4: 删除 `NineSliceFrameData` data class（若无其它引用）
    - 4.5: 删除 `bgScaledBitmapCache` 及其所有 get/put 调用（单步绘制不再需要预缩放位图）
    - 4.6: 更新 `drawBgImageSegment` 签名，接收 padStartDp/padEndDp/padTopDp/padBottomDp 参数

- [x] Task 5: TextChapterLayout.calcNineSliceMargin 叠加 padding
    - 5.1: 函数参数增加 `padStartDp: Float, padEndDp: Float`
    - 5.2: 返回的 marginLeft/marginRight 叠加 `padStartDp.dpToPx()` / `padEndDp.dpToPx()`
    - 5.3: 上下外扩用 `halfGap` 作为最大值上限 `coerceAtMost(halfGap)`

- [x] Task 6: Bitmap 解码策略优化
    - 6.1: 保留 `.9.png` 不采样逻辑（`isRawNinePatchPath` → `inSampleSize = 1`）
    - 6.2: 新增 `MAX_CORNER_PX = 256` 常量
    - 6.3: 在 `loadBgBitmap` 后增加"角块解码上限"判断：如果最大角块 > MAX_CORNER_PX，追加二次 inSampleSize

- [x] Task 7: HighlightRuleEditSheet 编辑器增加外扩表单 + 预览
    - 7.1: `NinePatchEditorDialog` 底部新增"外扩边距"卡片：4 行 Slider + 数字（范围 -16..32dp，步进 1）
    - 7.2: 状态绑定到 rule 的 `bgPaddingStart/End/Top/Bottom`
    - 7.3: Preview 面板：示例文字（长行 + 短行）叠加 Canvas `NinePatchDrawHelper.draw` 实时渲染
    - 7.4: 新增字符串资源（values/strings.xml、values-zh-rCN、values-zh-rTW）

- [x] Task 8: 清理 + 验证
    - 8.1: 移除 `drawNineSliceCenter`/`drawNineSliceFrames`/`NineSliceFrameData` 导致的无用 import
    - 8.2: 移除 `bgScaledBitmapCache` 导致的无用 import / 引用
    - 8.3: 检查 `TextLine` 中 `nineSliceFrames: MutableList` 参数传递链，若已无任何使用则删除该参数及调用处
