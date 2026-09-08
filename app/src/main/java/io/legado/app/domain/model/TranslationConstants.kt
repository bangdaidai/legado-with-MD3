package io.legado.app.domain.model

object TranslationConstants {

    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_APP_AI = "app_ai"
    const val PROVIDER_GOOGLE = "google"
    const val MIN_TEMPERATURE = 0f
    const val MAX_TEMPERATURE = 2f
    const val DEFAULT_TEMPERATURE = 0.7f

    val providerDisplayNames = listOf("Google Translate", "应用 AI 接口")
    val providerValues = listOf(PROVIDER_GOOGLE, PROVIDER_APP_AI)

    val targetLanguages = listOf(
        "zh" to "简体中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "ar" to "العربية"
    )

    const val DEFAULT_PROMPT =
        """你是一名专业的文学翻译，请按以下要求进行翻译：

1. 保持原文段落数量和顺序不变
2. 保持原文的文学风格和语调
3. 不要总结、压缩或省略任何内容
4. 仅输出翻译结果，不添加评论或解释
5. 保持缩写/昵称的名称一致性（例如 Alexander → Alex → 同一人）。将昵称映射添加到词典中。

"""

    const val OUTPUT_FORMAT = """输出分为两部分：

需要记录的新专有名词、地名，以及翻译结果。

只选择最常见和最重要的术语（最多10个）放入词典。

输出格式如下，重要：**dictionary** 部分必须以英文单词 **[dictionary]** 开头，不能以任何其他单词开头。**result** 部分必须以英文单词 **[result]** 开头，不能以任何其他单词开头：
<example>
[dictionary]
Jack -> 杰克
Harry Port -> 哈利波特

[result]
...
</example>
    """
}
