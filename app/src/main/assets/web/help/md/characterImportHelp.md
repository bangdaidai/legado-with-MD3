# 人物批量导入

在"**人物**"页面点击顶栏"**批量导入**"按钮，粘贴符合格式的人物 JSON，即可一次导入多条人物档案。同名人物会合并更新（别名合并去重、手动标记的主角身份不丢失）。

## JSON 格式

支持三种写法：`{"characters": [...]}` 包裹、人物数组 `[...]`、单个人物对象 `{...}`。

完整模板（可直接复制后修改，字段都可以留空或删除，除 `name` 外均有默认值）：

```json
{
  "characters": [
    {
      "name": "克莱恩·莫雷蒂",
      "aliases": ["愚者", "世界", "格尔曼·斯帕罗"],
      "voiceGender": "male",
      "voiceAgeBand": "young_adult",
      "role": "male_lead",
      "personality": "谨慎、冷静，善于伪装与周旋",
      "summary": "穿越者，值夜者成员，塔罗会创立者。",
      "evidence": "手动导入",
      "confidence": 0.9
    },
    {
      "name": "奥黛丽·霍尔",
      "aliases": ["正义"],
      "voiceGender": "female",
      "voiceAgeBand": "young_adult",
      "role": "female_supporting",
      "personality": "活泼天真，富有同情心",
      "summary": "霍尔伯爵之女，空想家途径序列 3 织梦人。",
      "evidence": "百科",
      "confidence": 0.8
    }
  ]
}
```

## 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| name | 是 | 人物名，缺省该条目会被跳过；与已有档案同名时合并更新 |
| aliases | 否 | 别名/代号/称号数组，如 `["愚者", "世界"]` |
| voiceGender | 否 | 朗读性别：`male` / `female` / `unknown`，缺省 `unknown` |
| voiceAgeBand | 否 | 朗读年龄段：`child` / `teen` / `young_adult` / `adult` / `elderly` / `unknown`，缺省 `unknown` |
| role | 否 | 角色定位：`male_lead`（男主）/ `female_lead`（女主）/ `male_supporting`（男配）/ `female_supporting`（女配）/ `unknown`，缺省 `unknown` |
| personality | 否 | 性格描述，用于朗读时的情感演绎 |
| summary | 否 | 人物简介 |
| evidence | 否 | 来源说明（如"百科"、"模型知识"），缺省"手动导入" |
| confidence | 否 | 可信度 0~1，缺省 `0.8` |

::: tip 提示
同名合并时，JSON 里留空的字段不会覆盖档案里已有的内容，可以只写需要补充的字段。导入后如果某条需要修改，可以点击对应人物进入详情页单独编辑；`role` 为主角的选项会自动参与主角高亮。
:::
