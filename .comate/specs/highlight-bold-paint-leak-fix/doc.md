# 高亮规则加粗污染全文修复方案

## 一、问题现象

在阅读界面，给高亮规则选择「粗体」（`HighlightRule.fontWeight = 700`）后：

- 页面上**全部文字**都变成粗体，而不是只有正则命中的部分
- 切换一次日夜模式后恢复正常（只有命中文字加粗）
- 之前多次尝试修复 `finally` 复原逻辑均无效

## 二、根因分析

### 2.1 为什么切换日夜模式能"修好"

`app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt:99-110`

```kotlin
private val compiledHighlightRules: List<CompiledHighlightRule>
    get() {
        val configName = ReadBookConfig.durConfig.name
        val isNight = ReadStyleResolver.isNightTheme()
        val cacheKey = "$configName|${book.bookUrl}|${if (isNight) 1 else 0}"
        cachedHighlightRules?.takeIf { ... }
        ...
    }
```

缓存 key 里含 `isNight`。切换日夜 → key 变化 → 规则强制重新编译 + 章节全量重排版 →
`TextColumn` 重建 + `CanvasRecorder` 全部失效重录 → 画面恢复正常。

**这只是掩盖症状，不是修复。** 它证明了问题出在「渲染缓存里存了被污染的画面」，而不是规则匹配逻辑。

### 2.2 真正的污染源：共享 Paint 上的临时改动

`app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt:73-128`

当前 `draw()` 的做法是：**直接拿共享的 `ChapterProvider.contentPaint` / `titlePaint`，
在上面临时改 `isFakeBoldText` / `typeface` / `textSize` / `color`，画完在 finally 里改回去**：

```kotlin
val textPaint = if (textLine.isTitle) ChapterProvider.titlePaint else ChapterProvider.contentPaint
// ...
val originalFakeBold = textPaint.isFakeBoldText
try {
    if (needFakeBold) textPaint.isFakeBoldText = true      // 污染共享 paint
    drawText(canvas, ..., textPaint)
} finally {
    if (needFakeBold) textPaint.isFakeBoldText = originalFakeBold
}
```

同时，`TextLine.fastDrawTextLine()`（`TextLine.kt:195-231`）走的是**整行一次性绘制**的快速路径，
它从同一个共享 paint 拷贝状态：

```kotlin
val textPaint = if (isTitle) ChapterProvider.titlePaint else ChapterProvider.contentPaint
val paint = PaintPool.obtain()
paint.set(textPaint)            // ← 把共享 paint 当前状态整体复制过来
canvas.drawText(text, indentSize, text.length, startX + offsetX, lineBase - lineTop, paint)
```

`TextLine.kt:84` 决定走哪条路径：

```kotlin
} else if (column.textColor != null || ... || column.fontWeight != 400 || column.isItalic) {
    onlyTextColumn = false      // 有样式的行 → 逐列绘制
}
```

**污染链路**：

1. 含加粗列的行 `onlyTextColumn = false`，走 `TextColumn.draw()` 逐列绘制，把
   `contentPaint.isFakeBoldText` 置为 `true`
2. 不含样式的普通行 `onlyTextColumn = true`，走 `fastDrawTextLine()`，
   `paint.set(contentPaint)` **把 bold 状态整体复制过去** → 整行变粗
3. `optimizeRender` 开启时，`TextLine.draw()` 走 `canvasRecorder.recordIfNeededThenDraw`
   （`TextLine.kt:161-169`），把结果**录制并缓存**。被污染的画面就这样固化下来，
   后续帧直接重放缓存，不会自愈
4. 只有 `invalidate()` 才会重录 —— 而切换日夜模式恰好触发全量失效

### 2.3 为什么改 finally 没用

`finally` 的复原逻辑本身是正确的。但问题在于**存在污染窗口**：

- `TextColumn.draw()` 与 `fastDrawTextLine()` 竞争同一个共享 paint 对象
- `CanvasRecorder` 的录制时机不受 `draw()` 调用顺序保护
- 只要设计上还在共享对象上「临时改 → 用 → 改回」，任何一次时序错位就会固化到缓存里

**结论：必须消除共享 Paint 上的临时改动，而不是修补复原逻辑。**

### 2.4 附带缺陷：加粗字符宽度未重新测量

`TextChapterLayout.kt:1581-1612` `remeasureWithHighlightFonts()`：

```kotlin
if (fontPath.isEmpty() && fontWeight == 400 && !isItalic) { i++; continue }   // 1595
// ...
val typeface = TextColumn.getTypeface(fontPath, fontWeight, isItalic) ?: continue   // 1605
```

`TextColumn.getTypeface("")` → `applyStyleTypeface(null, 700, false)` →
`TextColumn.kt` companion 里 `val base = typeface ?: return null` 返回 **null** →
`?: continue` **跳过测量**。

即：只加粗、不带自定义字体的字符，其宽度仍按常规字重测量，但绘制时用 `isFakeBoldText` 加粗
（合成加粗会让字形变宽）→ 轻微的字距/排版偏移。这是次要问题，一并修掉。

## 三、修复方案

### 核心原则

**任何针对单个 column 的样式改动，都必须发生在 Paint 副本上，绝不触碰
`ChapterProvider.contentPaint` / `titlePaint` 本体。**

项目已有 `PaintPool` 基础设施（`fastDrawTextLine` 在用），复用它即可，无需新增抽象。

### 3.1 改造 `TextColumn.draw()`

**文件**：`app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt`
**函数**：`draw(view: ContentTextView, canvas: Canvas)`

改动要点：

- 快速路径（无任何样式）保持不变：直接用共享 paint 绘制，不改任何状态，零开销
- 有样式路径：从 `PaintPool.obtain()` 取副本，`set(sharedPaint)` 后在副本上改，画完 `recycle()`
- 删掉 `try/finally` 复原逻辑 —— 副本用完即回收，不存在复原需求

```kotlin
override fun draw(view: ContentTextView, canvas: Canvas) {
    val sharedPaint = if (textLine.isTitle) {
        ChapterProvider.titlePaint
    } else {
        ChapterProvider.contentPaint
    }
    val renderStyle = ChapterProvider.renderStyle
    val drawColor = if (textLine.isReadAloud || isSearchResult) {
        renderStyle.textAccentColor
    } else {
        textColor ?: if (textLine.isTitle && renderStyle.titleColor != 0) {
            renderStyle.titleColor
        } else {
            renderStyle.textColor
        }
    }
    val titleTextSize = textLine.titleTextSize
    val hasFontSizeOffset = fontSizeOffset != 0
    val needSize = titleTextSize != null || hasFontSizeOffset
    val needColor = sharedPaint.color != drawColor
    val customTypeface = getCustomTypeface()
    val needTypeface = customTypeface != null
    // 合成加粗：字重 >= 700 时用 isFakeBoldText 保证任意字体都能变粗
    val needFakeBold = fontWeight >= 700

    if (!needSize && !needColor && !needTypeface && !needFakeBold) {
        // 无样式：直接用共享 paint，不做任何修改
        drawText(canvas, textLine.lineBase - textLine.lineTop, sharedPaint)
    } else {
        // 有样式：一律在副本上改，绝不污染共享 paint
        val paint = PaintPool.obtain()
        paint.set(sharedPaint)
        if (needSize) {
            val baseSize = titleTextSize ?: sharedPaint.textSize
            paint.textSize = if (hasFontSizeOffset) {
                baseSize + fontSizeOffset.toFloat().spToPx()
            } else {
                baseSize
            }
        }
        if (needColor) paint.color = drawColor
        if (needTypeface) paint.typeface = customTypeface
        if (needFakeBold) paint.isFakeBoldText = true
        drawText(canvas, textLine.lineBase - textLine.lineTop, paint)
        PaintPool.recycle(paint)
    }

    if (selected) {
        canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
    }
}
```

需新增 import：`io.legado.app.ui.book.read.page.provider.PaintPool`（按 `PaintPool` 实际包名调整）。

`drawText` 的签名 `(canvas, y, textPaint: android.text.TextPaint)` 保持不变，`PaintPool` 产出的
是 `TextPaint`，可直接传入。

### 3.2 同步改造 `TextHtmlColumn`

**文件**：`app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt`

该类有自己的 `textPaint`（`by lazy { TextPaint(ChapterProvider.contentPaint).apply { textSize = mTextSize } }`），
是独立实例，本身不污染共享 paint。但需要确认它的 `draw()` 是否也在共享 paint 上改动，
若有则同样改为副本方式；若已是独立实例则只需接上 `fontSizeOffset` / `fontWeight` 的应用逻辑保持一致。

### 3.3 修正加粗字符宽度测量

**文件**：`app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt`
**函数**：`remeasureWithHighlightFonts()`（约 1581-1612 行）

`getTypeface` 在无自定义字体时返回 null 是**合理设计**（避免把用户阅读字体替换成
`Typeface.DEFAULT` 造成整体观感异常，见 `TextColumn.kt` companion 注释）。
所以不应改 `getTypeface`，而应在测量侧处理：typeface 为 null 时，用 `isFakeBoldText`
让测量与绘制口径一致。

```kotlin
private fun remeasureWithHighlightFonts(
    text: String,
    charStyles: Array<CharStyle?>,
    textPaint: TextPaint,
    widthsArray: FloatArray
) {
    val measurePaint = TextPaint(textPaint)
    var i = 0
    while (i < text.length) {
        val style = charStyles[i]
        val fontPath = style?.fontPath.orEmpty()
        val fontWeight = style?.fontWeight ?: 400
        val isItalic = style?.isItalic ?: false
        // 只要字体/字重/斜体任一非默认就需要重新测量
        if (fontPath.isEmpty() && fontWeight == 400 && !isItalic) { i++; continue }
        // 找连续使用同一字体且同一字重/斜体的区间
        val segStart = i
        i++
        while (i < text.length) {
            val s = charStyles[i]
            if (s?.fontPath.orEmpty() != fontPath || (s?.fontWeight ?: 400) != fontWeight || (s?.isItalic ?: false) != isItalic) break
            i++
        }
        val segEnd = i
        // typeface 为 null 表示无自定义字体（仅字重/斜体变化），
        // 此时保持原 typeface，靠 isFakeBoldText 与绘制端口径一致
        val typeface = TextColumn.getTypeface(fontPath, fontWeight, isItalic)
        measurePaint.typeface = typeface ?: textPaint.typeface
        measurePaint.isFakeBoldText = typeface == null && fontWeight >= 700
        val segLen = segEnd - segStart
        val segWidths = FloatArray(segLen)
        measurePaint.getTextWidths(text, segStart, segEnd, segWidths)
        segWidths.copyInto(widthsArray, segStart)
    }
}
```

注意：循环内复用 `measurePaint`，每段都显式赋值 `typeface` 与 `isFakeBoldText`，
避免上一段状态残留到下一段。

## 四、影响文件清单

| 文件 | 修改类型 | 影响函数 |
|---|---|---|
| `app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextColumn.kt` | 改造 | `draw()` |
| `app/src/main/java/io/legado/app/ui/book/read/page/entities/column/TextHtmlColumn.kt` | 核对/对齐 | `draw()` |
| `app/src/main/java/io/legado/app/ui/book/read/page/provider/TextChapterLayout.kt` | 修正 | `remeasureWithHighlightFonts()` |

不涉及数据库、DI、导航、Compose 层改动。

## 五、边界与异常处理

- **PaintPool 泄漏**：`obtain()` 后必须 `recycle()`。当前写法是线性流程无提前 return，
  正常路径必定回收。若后续在中间插入 return，需改回 try/finally 包裹 recycle。
- **快速路径零回归**：无样式列仍直接用共享 paint，不引入 PaintPool 开销，
  保证正常阅读性能不下降（这是绝大多数 column 的路径）。
- **`textPaint.color` 判断基准**：原代码用 `textPaint.color != drawColor` 判断是否需要改色，
  改造后用 `sharedPaint.color` 作基准，语义不变。
- **`titleTextSize` 基准值**：原代码在需要时读 `textPaint.textSize` 作为 baseSize，
  改造后读 `sharedPaint.textSize`，语义不变（此时副本尚未被改）。
- **CanvasRecorder 缓存**：修复后不再有污染写入，已被污染的缓存会在下次
  `invalidate()`（翻页/配置变更/日夜切换）时自然清除。用户无需手动操作。

## 六、数据流

```
HighlightRule(fontWeight=700)
  ↓ TextChapterLayout.toCharStyle()
CharStyle(fontWeight=700)
  ↓ TextChapterLayout 构造 column（~1457 / ~1744 行）
TextColumn(fontWeight=700)
  ↓ TextLine.addColumn() → onlyTextColumn=false（TextLine.kt:84）
TextLine 走逐列绘制路径（checkFastDraw() 返回 false）
  ↓ TextColumn.draw()
【修复后】PaintPool 副本上设 isFakeBoldText=true → 绘制 → recycle
共享 contentPaint 全程未被修改
  ↓
其他无样式行走 fastDrawTextLine()，paint.set(contentPaint) 拿到干净状态 → 正常字重
```

## 七、预期结果

1. 高亮规则选粗体后，**仅正则命中的文字**变粗，其余文字保持正常字重
2. 无需切换日夜模式，保存规则后即时正确生效
3. 加粗文字的字符宽度测量与绘制口径一致，不再有轻微字距偏移
4. 正常阅读（无高亮样式）性能无回归 —— 快速路径未引入额外对象分配

## 八、验证方式

编译验证：

```bash
.\gradlew.bat :app:compileAppDebugKotlin
```

手工验证步骤（需真机/模拟器）：

1. 新建高亮规则，pattern 填一个只匹配少量文字的正则（如某个具体词），样式只选「粗体」
2. 保存 → 回到阅读页 → **确认只有命中词变粗，其余正文正常**
3. 翻几页，确认后续页面同样只有命中词变粗
4. 不切换日夜模式的前提下，编辑规则取消粗体 → 确认全文恢复正常字重
5. 切换日夜模式，确认表现一致（不再是"靠切换才正确"）
6. 叠加测试：同时设置粗体 + 背景色 + 下划线，确认各样式互不干扰、不外溢
