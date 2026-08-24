# 源规则帮助

* [阅读3.0(Legado)规则说明](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [书源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/source.html)
* [订阅源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/rss.html)
* 辅助键盘❓中可插入URL参数模板,打开帮助,js教程,正则教程,选择文件
* 规则标志, {{......}}内使用规则必须有明显的规则标志,没有规则标志当作js执行
```
@@ 默认规则,直接写时可以省略@@
@XPath: xpath规则,直接写时以//开头可省略@XPath
@Json: json规则,直接写时以$.开头可省略@Json
: regex规则,不可省略,只可以用在书籍列表和目录列表
```
* jsLib
> 注入JavaScript到RhinoJs引擎中，支持两种格式，可实现[函数共用](https://github.com/gedoor/legado/wiki/JavaScript%E5%87%BD%E6%95%B0%E5%85%B1%E7%94%A8)

> `JavaScript Code` 直接填写JavaScript片段  
> `{"example":"https://www.example.com/js/example.js", ...}` 自动复用已经下载的js文件

> 注意此处定义的函数可能会被多个线程同时调用，在函数里的全局变量内容将会共享使用，对其进行修改可能会出现竞争问题
> 函数内不可声明全局变量，函数外的全局变量不可再赋值，否则会抛出 `无法修改密封对象的属性` 异常

* 并发率
> 并发限制，单位ms，可填写两种格式

> `1000` 访问间隔1s  
> `20/60000` 60s内访问次数20  

* 书源类型: 文件
> 对于类似知轩藏书提供文件整合下载的网站，可以在书源详情的下载URL规则获取文件链接

> 通过截取下载链接或文件响应头头获取文件信息，获取失败会自动拼接`书名` `作者`和下载链接的`UrlOption`的`type`字段

> 压缩文件解压缓存会在下次启动后自动清理，不会占用额外空间  

* CookieJar
> 启用后会自动保存每次返回头中的Set-Cookie中的值，适用于验证码图片一类需要session的网站

* 登录UI
> 不使用内置webView登录网站，需要使用`登录URL`规则实现登录逻辑，可使用`登录检查JS`检查登录结果  
> 版本20221113重要更改：按钮支持调用`登录URL`规则里面的函数，必须实现`login`函数
```
规则填写示范
[
    {
        "name": "telephone",
        "type": "text"
    },
    {
        "name": "password",
        "type": "password"
    },
    {
        "name": "注册",
        "type": "button",
        "action": "http://www.yooike.com/xiaoshuo/#/register?title=%E6%B3%A8%E5%86%8C"
    },
    {
        "name": "获取验证码",
        "type": "button",
        "action": "getVerificationCode()",
        "style": {
            "layout_flexGrow": 0,
            "layout_flexShrink": 1,
            "layout_alignSelf": "auto",
            "layout_flexBasisPercent": -1,
            "layout_wrapBefore": false
        }
    }
]
```
* 登录URL
> 可填写登录链接或者实现登录UI的登录逻辑的JavaScript
```
示范填写
function login() {
    java.log("模拟登录请求");
    java.log(source.getLoginInfoMap());
}
function getVerificationCode() {
    java.log("登录UI按钮：获取到手机号码"+result.get("telephone"))
}

登录按钮函数获取登录信息
result.get("telephone")
login函数获取登录信息
source.getLoginInfo()
source.getLoginInfoMap().get("telephone")
source登录相关方法,可在js内通过source.调用,可以参考阿里云语音登录
login()
getHeaderMap(hasLoginHeader: Boolean = false)
getLoginHeader(): String?
getLoginHeaderMap(): Map<String, String>?
putLoginHeader(header: String)
removeLoginHeader()
setVariable(variable: String?)
getVariable(): String?
AnalyzeUrl相关函数,js中通过java.调用
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse( jsStr: String? = null, sourceRegex: String? = null) //返回访问结果,文本类型,书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response //返回访问结果,网络朗读引擎采用的是这个,调用登录后在调用这方法可以重新访问,参考阿里云登录检测
```

* 发现url格式
```json
[
  {
    "title": "xxx",
    "url": "",
    "style": {
      "layout_flexGrow": 0,
      "layout_flexShrink": 1,
      "layout_alignSelf": "auto",
      "layout_flexBasisPercent": -1,
      "layout_wrapBefore": false
    }
  }
]
```

* 请求头,支持http代理,socks4 socks5代理设置
> 注意请求头的key是区分大小写的  
> 正确格式 User-Agent Referer  
> 错误格式 user-agent referer
```
socks5代理
{
  "proxy":"socks5://127.0.0.1:1080"
}
http代理
{
  "proxy":"http://127.0.0.1:1080"
}
支持http代理服务器验证
{
  "proxy":"http://127.0.0.1:1080@用户名@密码"
}
注意:这些请求头是无意义的,会被忽略掉
```

* url添加js参数,解析url时执行,可在访问url时处理url,例
```
https://www.baidu.com,{"js":"java.headerMap.put('xxx', 'yyy')"}
https://www.baidu.com,{"js":"java.url=java.url+'yyyy'"}
```

* 增加js方法，用于重定向拦截
  * `java.get(urlStr: String, headers: Map<String, String>)`
  * `java.post(urlStr: String, body: String, headers: Map<String, String>)`
* 对于搜索重定向的源，可以使用此方法获得重定向后的url
```
(()=>{
  if(page==1){
    let url='https://www.yooread.net/e/search/index.php,'+JSON.stringify({
    "method":"POST",
    "body":"show=title&tempid=1&keyboard="+key
    });
    return source.put('surl',String(java.connect(url).raw().request().url()));
  } else {
    return source.get('surl')+'&page='+(page-1)
  }
})()
或者
(()=>{
  let base='https://www.yooread.net/e/search/';
  if(page==1){
    let url=base+'index.php';
    let body='show=title&tempid=1&keyboard='+key;
    return base+source.put('surl',java.post(url,body,{}).header("Location"));
  } else {
    return base+source.get('surl')+'&page='+(page-1);
  }
})()
```

* 图片链接支持修改headers
```
let options = {
"headers": {"User-Agent": "xxxx","Referrer":baseUrl,"Cookie":"aaa=vbbb;"}
};
'<img src="'+src+","+JSON.stringify(options)+'">'
```

* 字体解析使用
> 使用方法,在正文替换规则中使用,原理根据f1字体的字形数据到f2中查找字形对应的编码
```
<js>
(function(){
  var b64=String(src).match(/ttf;base64,([^\)]+)/);
  if(b64){
    var f1 = java.queryTTF(b64[1]);
    var f2 = java.queryTTF("https://alanskycn.gitee.io/teachme/assets/font/Source Han Sans CN Regular.ttf");
    // return java.replaceFont(result, f1, f2);
    return java.replaceFont(result, f1, f2, true); // 过滤掉f1中不存在的字形
  }
  return result;
})()
</js>
```

* 购买操作
> 可直接填写链接或者JavaScript，如果执行结果是网络链接将会自动打开浏览器,js返回true自动刷新目录和当前章节

* 图片解密
> 适用于图片需要二次解密的情况，直接填写JavaScript，返回解密后的`ByteArray`  
> 部分变量说明：java（仅支持[js扩展类](https://github.com/gedoor/legado/blob/master/app/src/main/java/io/legado/app/help/JsExtensions.kt)），result为待解密图片的`ByteArray`，src为图片链接

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var input = new Packages.java.io.ByteArrayInputStream(data)
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = input.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```

* 封面解密
> 同图片解密 其中result为待解密封面的`inputStream`

```js
java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv).decrypt(result)
```

```js
function decodeImage(data, key) {
  var out = new Packages.java.io.ByteArrayOutputStream()
  var byte
  while ((byte = data.read()) != -1) {
    out.write(byte ^ key)
  }
  return out.toByteArray()
}

decodeImage(result, key)
```


---

# 关联书籍 (Related Books) 配置规范

书源的 `ruleBookInfo.relatedBooks` 字段允许开发者声明一组关联书籍模块，用于在书籍详情页底部展示「关联书籍」横滑轮播。支持配置多个模块，每个模块有独立的标题和数据来源，例如「同作者作品」、「读这本书的人还在读」等。

---

## 1. 字段位置

在书源编辑器的 **详情页** 选项卡中，新增了 `relatedBooks` 字段。

```
详情页 → relatedBooks
```

对应 JSON 路径：`ruleBookInfo.relatedBooks`

---

## 2. 数据结构

`relatedBooks` 是一个包含多个模块定义对象的 JSON 数组。

### 模块字段

| 字段      | 类型       | 必须 | 说明                                     |
|:--------|:---------|:---|:---------------------------------------|
| **key** | `String` | 否  | 模块唯一标识。建议使用 `[a-z0-9_]` 字符。若未提供则使用 title。 |
| **title** | `String` | 是  | 模块标题，显示在轮播上方。如「同作者作品」。                  |
| **url** | `String` | 是  | 数据接口 URL。支持模板变量替换。                      |

### 两条硬性要求

1. **整个字段必须是 JSON 数组**，最外层的 `[ ]` 不能省，只配一个模块也一样。写成单个 `{...}` 会直接解析失败，日志里是「关联书籍规则不是合法 JSON 数组」。
2. **`url` 里的所有空白字符都会被删掉**（空格、换行、制表符）。所以 `@js:` 只能写成一个不依赖空格的表达式；需要多行逻辑时把它放进书源的 `jsLib` 里定义成函数，这里只写函数调用。


---

## 3. 工作原理

1. 当用户打开某本书的详情页时，如果该书源配置了 `relatedBooks`，系统会解析 JSON 数组。
2. 对每个模块，系统将 URL 中的模板变量替换为当前书籍的实际值，然后发起请求。
3. 返回的数据使用书源的 **发现规则**（`ruleExplore`）进行解析，获取书籍列表。
4. 解析后的书籍以横滑轮播的形式展示在详情页的操作按钮和书籍简介之间。
5. 当前查看的书籍会自动从结果中过滤掉，避免重复显示。
6. 如果某个模块请求失败或返回空列表，该模块会被静默跳过，不影响其他模块和详情页功能。

> **注意**：关联书籍的解析复用的是「发现」规则（`ruleExplore`），而非「详情页」规则（`ruleBookInfo`）。请确保书源的发现规则已正确配置。

---

## 4. URL 语法

URL 支持与 `exploreUrl` 相同的 JS 语法，包括 `@js:` 前缀、`<js></js>` 标签和 `{{...}}` 内嵌表达式。

### JS 上下文中的可用对象

| 对象       | 说明                   | 示例属性                                                                |
|:---------|:---------------------|:--------------------------------------------------------------------|
| `book`   | 当前书籍对象               | `book.name`, `book.author`, `book.kind`, `book.bookUrl`, `book.tocUrl`, `book.origin` |
| `source` | 当前书源对象               | `source.bookSourceUrl`, `source.getVariable()` 等                      |
| `cookie` | Cookie 存储             | `cookie.getKey(domain, key)`                                        |
| `page`   | 页码（固定为 1）            | `page`                                                              |
| `java`   | JS 扩展工具（`AnalyzeUrl`） | `java.ajax()`, `java.log()` 等                                       |

### 简单模板语法

对于简单的 URL，可以直接使用 `{{book.属性名}}` 语法：

```
https://example.com/search?keyword={{book.author}}&name={{book.name}}
```

**编码规则**：值出现在 query 段（`?` 之后）时会自动做 URL 编码；出现在**路径段**里不会，中文和特殊字符要自己编码：

```
https://example.com/category/{{java.net.URLEncoder.encode(book.kind,"UTF-8")}}
```

### `@js:` 表达式

需要逻辑处理时用 `@js:` 前缀。注意空白会被删掉，所以必须是**单个表达式**：

```
@js:"https://example.com/api/related?author="+java.net.URLEncoder.encode(book.author,"UTF-8")
```


---

## 5. 完整 JSON 示例

```json
[
  {
    "key": "same_author",
    "title": "同作者作品",
    "url": "https://example.com/search?keyword={{book.author}}&type=author&page=1"
  },
  {
    "key": "readers_also_read",
    "title": "读这本书的人还在读",
    "url": "https://example.com/api/related?book={{book.bookUrl}}&limit=20"
  },
  {
    "key": "same_genre",
    "title": "同类推荐",
    "url": "https://example.com/category/{{book.kind}}?page=1"
  }
]
```

以上配置会在详情页底部显示三行轮播：

```
┌─────────────────────────────────────────────┐
│ [操作按钮区域]                                  │
├─────────────────────────────────────────────┤
│ 同作者作品                                      │
│ [封面1] [封面2] [封面3] [封面4] →               │
│                                             │
│ 读这本书的人还在读                                │
│ [封面1] [封面2] [封面3] [封面4] →               │
│                                             │
│ 同类推荐                                       │
│ [封面1] [封面2] [封面3] [封面4] →               │
├─────────────────────────────────────────────┤
│ [书籍简介区域]                                  │
└─────────────────────────────────────────────┘
```

---

## 6. 简化示例

如果只需要一行关联书籍，JSON 数组只包含一个元素即可：

```json
[
  {
    "title": "同作者作品",
    "url": "https://example.com/search?keyword={{book.author}}"
  }
]
```

`key` 字段为可选，未提供时自动使用 `title` 作为标识。

---

## 7. URL 示例

### 按作者查找相关书籍

```
https://example.com/search?keyword={{book.author}}&type=author
```

### 按书籍名称查找同系列

```
https://example.com/search?keyword={{book.name}}&type=related
```

### 按分类查找同类书籍

```
https://example.com/category/{{book.kind}}?page=1
```

### 使用 JS 表达式

与 `exploreUrl` 一样，URL 支持 `@js:` 和 `<js>` 表达式。在 JS 上下文中，`book` 对象即当前书籍，可访问 `book.name`、`book.author`、`book.kind`、`book.bookUrl`、`book.tocUrl` 等属性，书源 `jsLib` 里定义的函数也可以直接调用。

**简单拼接**（单表达式，无空格）：

```
@js:"https://example.com/api/related?author="+java.net.URLEncoder.encode(book.author,"UTF-8")
```

**带条件判断**：用三元表达式，不要写 `if` 语句：

```
@js:"https://example.com/api/related?genre="+(/玄幻/.test(String(book.kind))?"fantasy":"other")
```

**复杂逻辑一律放 jsLib**。先在书源的 `jsLib` 里定义函数（那里可以正常换行、正常写空格）：

```js
function relatedUrl(b){
	const {java} = this;
	try{
		let id = JSON.parse(java.ajax(b.bookUrl)).authorId;
		return "https://example.com/api/related?author=" + id
	}catch(e){
		return ""
	}
}
```

然后 url 里只写一句调用：

```
@js:relatedUrl(book)
```

这样既绕开了「空白被删除」的限制，也便于复用和调试。返回空字符串时该模块会被跳过。


---

## 8. 显示逻辑

| 条件                                  | 行为                        |
|:------------------------------------|:-------------------------|
| `relatedBooks` 为空或未配置       | 不显示关联书籍模块               |
| JSON 格式错误                          | 跳过，不影响详情页其他内容；原因写入日志   |
| 某个模块的 URL 请求失败                    | 跳过该模块，其他模块正常显示；原因写入日志  |
| 某个模块返回空列表                         | 跳过该模块，其他模块正常显示；写入日志并带最终 url |
| 所有模块均无结果                          | 不显示关联书籍区域               |
| 结果中包含当前书籍                         | 自动过滤掉当前书籍               |
| 本地书籍（无书源）                         | 不显示关联书籍模块               |
| 切换书源时                              | 清空关联书籍，重新加载新源的数据       |

轮播不出来时，先去「我的 → 日志」看有没有以「关联书籍」开头的记录：一条都没有说明字段是空的；有记录则会直接说明是 JSON 不合法、请求失败，还是解析不出书籍。


---

## 9. 书源编辑器配置

在书源编辑器的 **详情页** 选项卡中：

| 字段                  | 值                                                                 |
|:--------------------|:------------------------------------------------------------------|
| relatedBooks | `[{"title":"同作者作品","url":"https://example.com/search?keyword={{book.author}}"}]` |

---

## 10. 最佳实践

1. **合理设置模块数量**：建议 1-3 个模块，过多的轮播行会影响页面体验。
2. **使用有意义的标题**：标题应清晰描述推荐来源，如「同作者作品」比「推荐」更具引导性。
3. **优先使用作者或分类**：按作者查找是最常见的关联方式，能有效推荐同作者的其他作品。
4. **避免过于宽泛的查询**：如果 URL 返回的结果与当前书籍关联性不强，用户体验会下降。
5. **确保发现规则兼容**：URL 返回的数据必须能被 `ruleExplore` 正确解析。
6. **简单场景用模板，复杂逻辑放 jsLib**：简单的 `{{book.author}}` 替换直接用模板语法；需要条件判断、二次请求、Cookie 处理时，在 `jsLib` 里写函数，url 里只写 `@js:函数名(book)`。不要试图在 url 里写多行脚本，空白会被删掉。
7. **测试边界情况**：测试作者名包含特殊字符（如 `&`、`#`、中文）时 URL 是否正常工作。query 段的模板变量会自动编码，路径段和 `@js:` 里都要自己调用 `java.net.URLEncoder.encode()`。
8. **控制返回数量**：建议服务端限制返回数量（如 10-20 本），过多的轮播内容会影响体验。
