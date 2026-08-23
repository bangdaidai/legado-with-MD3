# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# Rhino JS 引擎：书源通过 Packages.xxx 反射访问这些类，不能被混淆
-keep class org.mozilla.javascript.** { *; }
# Hutool 兼容类：书源通过 Packages.cn.hutool.crypto.digest.DigestUtil 调用，不能被混淆
-keep class cn.hutool.crypto.digest.DigestUtil { *; }
-keep class com.script.** { *; }
-keep class io.legado.app.model.SharedJsScope { *; }
# HttpTTS 朗读脚本里的 voice 变量：混淆掉 getter 后 JS 读 voice.id 会变 undefined,
# 所有音色都会退回脚本自己的默认音色, 表现就是每个音色听起来一样
-keep class io.legado.app.domain.model.readaloud.HttpTtsVoice { *; }
# 同理: 云端 / 系统音色的合成参数也存在 traitsJson 里, 字段名被混淆后风格标签与语速全丢
-keep class io.legado.app.domain.model.readaloud.CloudTtsVoiceConfig { *; }
-keep class io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig { *; }
-dontwarn org.mozilla.javascript.**
-dontnote org.mozilla.javascript.**
# 数据类
-keep class **.data.entities.**{*;}
# Gson 保留字段信息，防止混淆后字段名改变导致反序列化失败
-keepattributes Signature
-keepattributes *Annotation*
# 保留 data class 的 companion object 中的 jsonDeserializer
-keepclassmembers class **.data.entities.rule.** {
    public static com.google.gson.JsonDeserializer jsonDeserializer;
}
# 保留所有 Rule 类的 companion object
-keepclassmembers class **.data.entities.rule.** {
    public static ** Companion;
    public static ** jsonDeserializer;
}
# 保留 Gson 需要的字段名
-keepclassmembernames class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 保留 Rule 类的类名，防止 Gson TypeAdapter 注册失效
-keepnames class **.data.entities.rule.ExploreRule
-keepnames class **.data.entities.rule.SearchRule
-keepnames class **.data.entities.rule.BookInfoRule
-keepnames class **.data.entities.rule.TocRule
-keepnames class **.data.entities.rule.ContentRule
-keepnames class **.data.entities.rule.ReviewRule
# Gson反序列化用的数据传输类
-keep class io.legado.app.model.translation.**{*;}
-keep class io.legado.app.domain.model.DictPair{*;}
-keep class io.legado.app.domain.model.BookDictionary{*;}
-keep class io.legado.app.domain.model.TextChunk{*;}
-keep class io.legado.app.domain.model.ModuleDef{*;}
-keep class io.legado.app.domain.model.ModuleItem{*;}
-keep class io.legado.app.domain.model.CustomSetItem{*;}
-keep class io.legado.app.data.repository.GoogleTranslateResponse{*;}
-keep class io.legado.app.data.repository.GoogleSentence{*;}
-keep class io.legado.app.data.repository.GoogleSpell{*;}
-keep class io.legado.app.data.repository.OpenAIResponse{*;}
-keep class io.legado.app.data.repository.OpenAIChoice{*;}
-keep class io.legado.app.data.repository.OpenAIMessage{*;}
# 关联书籍(ruleBookInfo.relatedBooks)的模块定义, R8 重命名字段会让 Gson 解析出空模块
-keep class io.legado.app.ui.book.info.RelatedBooksDef{*;}
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}
## ExoPlayer 如果还不能播放就取消注释这个
# -keep class com.google.android.exoplayer2.** {*;}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}
# 忽略 Ktor 在 Android 上对 Java SE 管理类的引用
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.IntellijIdeaDebugDetector
-keep,allowobfuscation class io.ktor.util.debug.** { *; }
