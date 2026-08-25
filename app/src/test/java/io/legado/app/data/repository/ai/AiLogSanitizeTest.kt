package io.legado.app.data.repository.ai

import com.google.gson.internal.LinkedTreeMap
import io.legado.app.domain.model.AiLogStep
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLogSanitizeTest {

    @Test
    fun sanitizeDropsForeignStepElements() {
        // 复现崩溃数据：R8 丢泛型后 Gson 把 steps 元素反序列化成 LinkedTreeMap，
        // 页面刷新时 entry.steps.map { it.relativeMs } 会抛 ClassCastException
        @Suppress("UNCHECKED_CAST")
        val steps = listOf(
            AiLogStep(relativeMs = 10, label = "发送请求"),
            LinkedTreeMap<String, Any>().apply {
                put("relativeMs", 20L)
                put("label", "脏数据")
            },
        ) as List<AiLogStep>
        val entry = AiLogEntry(timeMillis = 1L, kind = "generate", steps = steps)

        val result = sanitizeLoadedEntries(listOf(entry))

        assertEquals(1, result.single().steps.size)
        assertEquals(AiLogStep(relativeMs = 10, label = "发送请求"), result.single().steps.single())
    }

    @Test
    fun sanitizeKeepsCleanStepsUntouched() {
        val steps = listOf(
            AiLogStep(relativeMs = 5, label = "建立连接"),
            AiLogStep(relativeMs = 80, label = "首字符到达"),
        )
        val entry = AiLogEntry(timeMillis = 2L, kind = "generate", steps = steps)

        val result = sanitizeLoadedEntries(listOf(entry))

        assertEquals(steps, result.single().steps)
    }

    @Test
    fun sanitizeFallsBackNullStepsToEmpty() {
        // Gson 反射按字段名填充，JSON 里 steps 为 null 时会把 null 注入非空字段
        val entry = GSON.fromJson("""{"timeMillis":3,"kind":"generate","steps":null}""", AiLogEntry::class.java)
        assertNull(entry.steps)

        val result = sanitizeLoadedEntries(listOf(entry))

        assertTrue(result.single().steps.isEmpty())
    }

    @Test
    fun sanitizeDefaultsNullStepLabel() {
        // 历史版本字段名不一致时 Gson 缺失 label 会把 null 注入非空字段，直接构造会触发
        // "Parameter specified as non-null is null" 崩溃
        val step = GSON.fromJson("""{"relativeMs":10}""", AiLogStep::class.java)
        assertNull(step.label)
        val entry = AiLogEntry(timeMillis = 4L, kind = "generate", steps = listOf(step))

        val result = sanitizeLoadedEntries(listOf(entry))

        assertEquals("", result.single().steps.single().label)
    }
}
