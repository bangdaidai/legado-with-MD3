package io.legado.app.utils

import java.util.Random

/**
 * 标签颜色工具类（F1 标签系统）
 * 用于根据标签名生成稳定且对比度良好的随机颜色。
 */
object TagColorUtils {

    /**
     * 将RGB颜色转换为HSL颜色空间
     */
    private fun rgbToHsl(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        var h = 0f
        val s: Float
        val l = (max + min) / 2f

        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)

            h = when (max) {
                r -> (g - b) / d + if (g < b) 6f else 0f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
        } else {
            s = 0f
        }

        return floatArrayOf(h * 360f, s, l)
    }

    /**
     * 将HSL颜色转换为RGB颜色
     */
    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f

        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255).toInt()
        val g = ((g1 + m) * 255).toInt()
        val b = ((b1 + m) * 255).toInt()

        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    /**
     * 生成随机颜色，确保在浅色和深色背景下都有良好的对比度。
     * 使用标签名称的哈希值作为随机种子，确保相同标签总是得到相同颜色。
     */
    fun generateRandomColor(tagName: String): Int {
        val hash = tagName.hashCode()
        val random = Random(hash.toLong())
        val h = random.nextFloat() * 360f
        val s = 0.7f + random.nextFloat() * 0.3f
        val l = 0.5f + random.nextFloat() * 0.3f
        return hslToRgb(h, s, l)
    }

    /**
     * 生成完全随机的中等偏亮颜色
     */
    fun generateRandomColor(): Int {
        val random = Random()
        val h = random.nextFloat() * 360f
        val s = 0.7f + random.nextFloat() * 0.3f
        val l = 0.5f + random.nextFloat() * 0.3f
        return hslToRgb(h, s, l)
    }
}
