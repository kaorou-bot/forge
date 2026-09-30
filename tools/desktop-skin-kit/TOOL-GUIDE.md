# 独立工具使用说明

工具要求 Python 3.10+，没有第三方依赖。输入可以是皮肤目录，也可以是 ZIP；目录根或 ZIP 根必须直接有 match-ui.json。这里只支持版本 3 的内置控件 scene 皮肤，不支持旧版本区域布局或需要自定义 Java 的 CUSTOM_*。

## 命令

在资料包根目录执行：

```text
python tools/skin_tool.py validate examples/dusk-sanctum
python tools/skin_tool.py validate ready-to-import/dusk-sanctum.zip --json
python tools/skin_tool.py preview examples/dusk-sanctum --out output/layout.html
python tools/skin_tool.py pack examples/dusk-sanctum --out output/new-skin.zip
```

`validate` 不修改输入。`preview` 和 `pack` 先校验，再创建新文件，不覆盖已有文件。所有输出必须在皮肤目录之外。ZIP 校验不解压到磁盘；最多读取限定大小的数据，不执行包内内容。

`--json` 返回机器可读报告，可用于 AI 工作流；命令成功退出码 0，校验或输出失败为 1，参数语法错误通常为 2。通过报告的 `ok: true` 仍只代表静态通过，必须保留 warnings。

```json
{
  "ok": true,
  "format": "match-ui-v3",
  "id": "my-skin",
  "warnings": ["Static checks only ..."],
  "capacity": [
    {"players": 2, "visible_hands": 1, "result": "scene"},
    {"players": 3, "visible_hands": 1, "result": "arena_fallback"}
  ]
}
```

上面是简化报告形状，不是固定的完整结果。`arena_fallback` 表示该人数与手牌组合不被此场景支持，将依赖客户端回退，不表示工具已经运行了竞技场界面。

## 检查范围

- 严格 UTF-8 JSON、重复键、字段、枚举、类型和数值范围。
- 固定区域与控件的几何、重复组合、必要组件、可见性、renderer 和浮层约束。
- 2～8 玩家、0/1/2 副可见手牌的容量矩阵；对支持的组合进一步检查普通/开发模式文档分配及稀疏手牌编号。
- 文档缺失、重复、remaining 的顺序陷阱、提示和手牌被标签页遮住的情况。
- 包根层级、扩展名、条目数、解压大小、重复名称、路径穿越、符号链接和加密 ZIP。
- 被引用资源是否存在；PNG/JPEG 头部尺寸与像素预算；TTF/OTF 基础表目录范围。
- 没带字体、未发现字体许可文件、浮层初始位置覆盖预览等警告。

## 刻意保守的检查

为跨平台可移植性，本工具比客户端某些输入路径更严格：只接受标准 JSON 类型，拒绝所有重复键、点目录、反斜杠、符号链接、Windows 保留文件名等。目录中的无关文件也会被检查，不会悄悄忽略 `.DS_Store`、脚本或设计源稿。把它们放在皮肤目录之外。

自定义 Java 控件、版本 1/2、无 scene 的配置不是本工具目标。不要为让它通过而偷偷改主程序；确有需要时另行扩展工具并建立与客户端的对应测试。

## 无法替代的验证

Python 标准库工具不包含 Java/Swing/ImageIO，也没有游戏引擎。因此：

- 图片尺寸头部通过不证明完整像素流可解码，字体表目录通过不证明中文无缺字。
- 它不检查素材版权、没有恶意图片解码的完整安全保证，素材仍应来自可信来源。
- 预览不模拟字体、牌面、选中状态、支付、目标连线、隐藏信息权限、浮层重定位或帧率。
- 不执行真实安装、资源缓存或游戏逻辑，不改变用户 Forge 配置。
- 工具与客户端实现可能随版本变化产生差异，最终以目标程序导入和人工验收为准。

## 运行工具测试

```text
cd tools
python -m unittest -v test_skin_tool.py
```

测试使用系统临时目录，不依赖 Forge、不联网、不读用户套牌。它验证工具本身，不会自动把新作品标记为完成；新作品仍须单独执行 validate、pack 和导入验收。

## 常见错误

| 错误 | 处理 |
| --- | --- |
| root must contain match-ui.json | 输入了资料包根或多套了一层目录，改为单个皮肤目录 |
| unknown keys | 使用了不支持字段，查看 AI 制作规范 |
| Document assigned twice | 检查选择器重复和 remaining 顺序 |
| Layout omits documents | 补齐缺失文档，不要删除真实手牌和提示 |
| Fixed geometry overlap | 根据预览调整位置，不能靠条件隐藏绕过几何检查 |
| Scene supports no tested scenario | 补齐玩家控件，检查配置玩家与实际人数是否对应 |
| File exists | 工具不覆盖旧输出，请换文件名或由你自行整理旧输出 |
| Unsupported file | 将脚本、图片原稿和输出移出皮肤目录 |

生成的 HTML 是单文件离线示意图，背景嵌入文件中，没有外部资源请求或脚本。鼠标停在矩形上可看 ID 和坐标；详细列表在图下方。
