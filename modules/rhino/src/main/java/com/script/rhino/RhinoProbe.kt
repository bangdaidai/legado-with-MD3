package com.script.rhino

/**
 * 临时探针：记录 JS 侧访问 Java 成员的顺序与取值。
 *
 * 混淆书源用字符下标拼方法名/属性名，fork 一旦改了签名或包装策略，
 * 名字就会落空且被书源自己吞掉，只有在包装层记下来才看得见。
 *
 * 问题定位后请整文件删除，并移除各 get() 里的 RhinoProbe.onGet 调用。
 */
object RhinoProbe {

    @Volatile
    var sink: ((owner: String, name: String, value: Any?) -> Unit)? = null

    fun onGet(owner: String, name: String, value: Any?) {
        sink?.invoke(owner, name, value)
    }
}
