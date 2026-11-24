# 书源脚本签名兼容

本文面向应用维护者，记录书源 JSON 未变、同步上游后却出现“书源验证失败”这类问题时，如何识别并避免 Java 对象字符串化导致的签名回归。

## 1. 先确认失败文案来自哪里

“书源验证失败”不一定由批量校验界面产生，也可能直接来自书源 jsLib 的守卫循环。排查时先搜索实际加载的 jsLib，确认弹窗调用方，再决定检查应用校验链路还是脚本签名。

书源 wrapper 的 `bookSourceUrl`、`jsLib` 地址不变，也不代表执行内容不变。wrapper 可能下载动态 JSON，jsLib 自身也可能更新或被缓存；订阅标记、版本号和缓存内容需要与签名问题分开核对。

## 2. Rhino 不会把 Java 对象当成普通 JS 对象序列化

本项目使用 Rhino 1.8.1。对 Java 对象执行 `JSON.stringify` 时，结果采用该对象的 `toString()`，不是遍历 Java Bean 属性生成 JSON：

```js
var value = new java.lang.StringBuilder("ExploreRule(bookList=null, pageParam=null)");
JSON.stringify(value);
// 返回字符串 "ExploreRule(bookList=null, pageParam=null)"
```

起点助手当前脚本会把 `loginUrl`、`loginUi`、`variableComment`、`concurrentRate`、`ruleSearch`、`ruleExplore`、`ruleBookInfo`、`ruleToc`、`ruleContent`、`jsLib` 放入指纹对象，再执行 `JSON.stringify` 和 `java.md5Encode16`。因此这些规则对象的 `toString()` 实际参与了签名输入。

Kotlin data class 默认生成的 `toString()` 会包含主构造参数中的全部属性，包括值为 `null` 的新增属性。给规则 data class 增加一个字段，即使书源 JSON 没有配置它，也可能改变签名。

## 3. 本次回归与修复

同步上游引入 `ExploreRule.pageParam` 后，默认字符串从：

```text
ExploreRule(bookList=..., ..., wordCount=...)
```

变为：

```text
ExploreRule(bookList=..., ..., wordCount=..., pageParam=null)
```

起点助手读取到的指纹随之改变，触发脚本自身的“书源验证失败”循环。此前 `BookInfoRule.relatedBooks` 也出现过同类问题。

修复方式是为 [`ExploreRule`](../../app/src/main/java/io/legado/app/data/entities/rule/ExploreRule.kt) 手写 `toString()`，保持同步前字段顺序与格式，并排除 `pageParam`。这与 [`BookInfoRule`](../../app/src/main/java/io/legado/app/data/entities/rule/BookInfoRule.kt) 排除 `relatedBooks` 的兼容做法一致；字段本身、Gson 序列化和翻页功能均保留。

## 4. 规则字段扩展约束

只要第三方书源可能对规则对象执行 `JSON.stringify`、字符串拼接、MD5 或签名，就必须把该对象的 Java `toString()` 视为外部兼容面：

- 新增、删除或调整主构造参数顺序前，先比较修改前后的字符串结果，不能只检查书源 JSON。
- 本 fork 新增、但不属于既有签名协议的字段，使用手写 `toString()` 排除；不要删除字段或功能来迁就脚本。
- 手写格式必须与旧版 data class 完全一致，包括类名、字段名、顺序、分隔符和 `null` 的文本形式。
- 如果确实需要更改签名协议，应同步书源脚本、校验端并明确版本迁移，不能把应用升级造成的隐式变化留给已有书源。
- 动态 JSON、jsLib 和订阅标记缓存要单独记录版本与内容哈希，不能仅凭 wrapper 地址判断书源是否变化。

## 5. 回归检查清单

修改 `BookSource` 或任一规则 data class 后，至少完成以下核对：

1. 对比同步基线到当前提交的规则类差异，列出新增、删除和重排的主构造属性。
2. 检查这些类是否有手写 `toString()`，并确认它仍排除不参与旧签名协议的字段。
3. 在项目同版本 Rhino 中验证 `JSON.stringify(javaObject)` 的实际结果，不能套用浏览器或 Node.js 对普通 JS 对象的经验。
4. 对同一份书源配置比较修改前后的完整指纹字符串和 MD5；预期变更时说明协议依据，非预期变更则恢复兼容格式。
5. 单独验证新增字段的业务行为，例如 `pageParam` 的翻页参数拼接，确保兼容修复没有禁用功能。
6. 真机复测时保留订阅确认、动态脚本加载和缓存命中这些前置条件，并区分脚本守卫弹窗与批量校验汇总错误。

## 6. 案例索引

- 2026-11-24：同步引入 `ExploreRule.pageParam`，导致起点助手签名输入漂移。
- 修复提交：`ac15c8d54`（`fix(source): 保持发现规则指纹兼容`）。
- 同类历史修复：`c87009c41`（`BookInfoRule` 排除 `relatedBooks`）。
