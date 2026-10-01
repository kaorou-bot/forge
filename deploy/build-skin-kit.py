#!/usr/bin/env python3
"""Build a standalone authoring kit from explicitly selected repository assets.
No Forge binaries, credentials, user preferences, or unrelated worktree files.
"""
import argparse
import hashlib
import html
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


def between(text, start, end, replacement):
    left, remainder = text.split(start, 1)
    _, right = remainder.split(end, 1)
    return left + replacement + end + right


def standalone_spec(text):
    text = text.replace('(DesktopMatchSkin-v4.zh-CN.md)', '(v4扩展规范.md)')
    text = text.replace('背景设计记录见 [桌面对战接口](DesktopMatchUI.zh-CN.md)。', '')
    text = text.replace('(DesktopMatchSkin-Guide.zh-CN.md)', '(制作入门.md)')
    text = text.replace('以下源码路径均相对仓库根目录。', '本文已给出制作所需的数据接口。源码索引仅供需要扩展主程序时参考，独立制作不需要获取源码。')
    text = text.replace('1. 阅读目标仓库的相关解析器和本文，不依赖旧对话记忆。', '1. 阅读本文与独立工具说明；没有 Forge 或源码也可以完成数据皮肤制作和静态检查。')
    text = text.replace('skins/dusk-sanctum/', 'examples/dusk-sanctum/')
    text = text.replace('skins/dusk-sanctum', 'examples/dusk-sanctum')
    text = text.replace('## 代码依据和调用链', '## 可选的源码参考')
    text = text.replace('主要代码目录：', '以下类名用于解释实现依据，不是使用资料包的前置依赖。若以后获得 Forge 源码，其主要代码目录：')
    text = text.replace('3. 写明适用人数', '3. 写明适用人数')
    text = text.replace('5. 在导出 ZIP 前做静态检查、调用真实 Java 解析器、检查实际文档覆盖和容量。',
                        '5. 用附带 Python 工具检查配置、文档分配、资源、容量，并生成 ZIP；真实 Java 解析与游戏行为交由有主程序的验收者检查。')
    text = text.replace('6. 用目标客户端导入，再进行完整交互检查。没有运行环境时明确标记“仅静态验证，未实机验收”。',
                        '6. 独立制作到此可以交付。后续由有兼容客户端的人导入并做交互检查；交付时标记“仅静态验证，未实机验收”。')
    static = '''### 独立环境中的静态验证

不需要 Forge、Java 或源码。资料包根目录运行以下命令，需要 Python 3.10+，无需第三方库：

```text
python tools/skin_tool.py validate examples/dusk-sanctum --json
python tools/skin_tool.py preview examples/dusk-sanctum --out output/layout.html
python tools/skin_tool.py pack examples/dusk-sanctum --out output/skin.zip
python tools/skin_tool.py validate output/skin.zip --json
```

改成你自己的源目录进行制作；output 路径必须是新文件，工具不覆盖旧文件。完整工具契约见 [独立工具说明](独立工具说明.md)。

检查 JSON、字段、类型、几何、功能组合、资源和完整文档分配；工具输出 2～8 玩家及 0/1/2 副可见手牌的容量矩阵，支持的组合还检查开发模式与稀疏手牌编号。fallback 是需要客户端回退，不是该人数已具有专用场景。

工具不读取 Forge 配置、不联网、不加载游戏模型。图片与字体只做有限静态检查，不能证明 Java 解码或所有中文字形正常。严格检查也可能比客户端更保守；具体范围、例外和退出码见工具说明。

获得主程序后应继续导入与交互验收；只有需要开发或修订接口时才需源码。具备源码的开发者可调用 MatchUiLayout.read、scene.supports、arrange 并运行对应 Java 测试，作为额外验证，不是独立制作必须先完成的步骤。

'''
    text = between(text, '### 静态与真实解析验证', '### 手工验收矩阵', static)
    text = text.replace('先阅读目标项目实际解析器与 examples/dusk-sanctum 示例，再复制为新的独立目录。',
                        '先阅读本规范、独立工具说明与 examples/dusk-sanctum 示例，再复制为新的独立目录。无需取得 Forge 源码。')
    text = text.replace('交付 AI 生成的配置时，必须运行下面的 `arrange` 检查，不能只看 JSON 外形。',
                        '交付 AI 生成的配置时，必须运行附带工具的文档分配检查，不能只看 JSON 外形。')
    intro = '''> 独立资料包说明：本文、examples 和 tools 已覆盖制作所需资料，不要求安装 Forge 或下载源码。Python 工具的静态通过不等于实机通过；人工游戏验收是交付后的独立阶段。本文中的 Java 类名只是实现参考。

'''
    first, rest = text.split('\n', 1)
    return first + '\n\n' + intro + rest.lstrip()


def inline(text):
    parts = re.split(r'(`[^`]+`)', text)
    output = []
    for part in parts:
        if part.startswith('`') and part.endswith('`'):
            output.append('<code>' + html.escape(part[1:-1]) + '</code>')
            continue
        escaped = html.escape(part)
        escaped = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', lambda m: '<a href="' +
                         (m[2][:-3] + '.html' if m[2].endswith('.md') else m[2]) + '">' + m[1] + '</a>', escaped)
        escaped = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', escaped)
        output.append(escaped)
    return ''.join(output)


def document_html(markdown):
    """Small escaped renderer for the controlled Markdown subset in this kit."""
    lines, body, i = markdown.splitlines(), [], 0
    title = lines[0].lstrip('# ') if lines else 'Forge skin toolkit'
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            i += 1
            code = []
            while i < len(lines) and not lines[i].startswith('```'):
                code.append(lines[i])
                i += 1
            body.append('<pre><code>' + html.escape('\n'.join(code)) + '</code></pre>')
        elif line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].startswith('|'):
                row = lines[i]
                if not re.fullmatch(r'[| :\-]+', row):
                    tag = 'th' if not rows else 'td'
                    rows.append('<tr>' + ''.join(f'<{tag}>{inline(c.strip())}</{tag}>' for c in row.strip('|').split('|')) + '</tr>')
                i += 1
            body.append('<div class="table"><table>' + ''.join(rows) + '</table></div>')
            continue
        elif re.match(r'^#{1,6} ', line):
            level = len(line) - len(line.lstrip('#'))
            body.append(f'<h{level}>' + inline(line[level:].strip()) + f'</h{level}>')
        elif line.startswith('- ') or re.match(r'^\d+\. ', line):
            ordered = not line.startswith('- ')
            tag = 'ol' if ordered else 'ul'
            items = []
            while i < len(lines) and (re.match(r'^\d+\. ', lines[i]) if ordered else lines[i].startswith('- ')):
                items.append('<li>' + inline(re.sub(r'^(?:- |\d+\. )', '', lines[i])) + '</li>')
                i += 1
            body.append(f'<{tag}>' + ''.join(items) + f'</{tag}>')
            continue
        elif line.startswith('> '):
            body.append('<aside>' + inline(line[2:]) + '</aside>')
        elif line.strip():
            body.append('<p>' + inline(line) + '</p>')
        i += 1
    return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>''' + html.escape(title) + '''</title><style>
body{max-width:1000px;margin:36px auto;padding:0 24px 64px;color:#243347;background:#f8fafc;font:16px/1.8 system-ui,"Microsoft YaHei",sans-serif}
h1,h2,h3{color:#102d48;line-height:1.4}h2{margin-top:2em;border-bottom:1px solid #d3dce5;padding-bottom:.3em}a{color:#1260a0}code{background:#e7edf3;padding:2px 4px;border-radius:3px}pre{background:#13283a;color:#e6f3fb;padding:18px;overflow:auto;border-radius:8px}pre code{background:none;padding:0}table{border-collapse:collapse;min-width:60%}td,th{border:1px solid #ccd5df;padding:8px 12px;text-align:left;vertical-align:top}.table{overflow:auto}th{background:#e7edf3}aside{background:#e4f0fa;border-left:4px solid #2672ac;padding:16px}li{margin:6px 0}
</style><main>''' + ''.join(body) + '</main></html>'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', required=True, type=Path, help='New standalone directory; never overwritten')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / 'tools/desktop-skin-kit'
    out = args.out.resolve()
    archive_path = out.parent / (out.name + '.zip')
    if out.exists() or archive_path.exists():
        raise SystemExit('Output already exists; choose a new path')
    out.mkdir(parents=True)
    for directory in ('docs', 'tools', 'examples/minimal', 'ready-to-import', 'previews', 'validation'):
        (out / directory).mkdir(parents=True, exist_ok=True)
    for name in ('skin_tool.py', 'test_skin_tool.py', 'test_authoring.py', 'test_v5.py', 'make_v4_example.py', 'make_v5_example.py'):
        shutil.copy2(source / name, out / 'tools' / name)
    shutil.copy2(root / 'forge-gui-desktop/src/main/resources/forge/skin-capabilities.json', out / 'tools/skin-capabilities.json')
    shutil.copy2(source / 'AUTHORING-WORKFLOW.md', out / 'docs/高效制作与排错.md')
    for name in ('README.md', 'NOTICE.md'):
        shutil.copy2(source / name, out / name)
    shutil.copy2(root / 'LICENSE', out / 'LICENSE.txt')
    shutil.copy2(source / 'TOOL-GUIDE.md', out / 'docs/独立工具说明.md')
    shutil.copy2(source / 'ACCEPTANCE.md', out / 'docs/验收清单.md')
    shutil.copy2(source / 'AI-TASK.txt', out / 'docs/AI任务模板.txt')
    shutil.copy2(root / 'docs/DesktopMatchSkin-v4.zh-CN.md', out / 'docs/v4扩展规范.md')
    shutil.copy2(root / 'docs/DesktopMatchSkin-v5.zh-CN.md', out / 'docs/v5扩展规范.md')
    guide = (root / 'docs/DesktopMatchSkin-Guide.zh-CN.md').read_text(encoding='utf-8')
    guide = guide.replace('源码中的原件在 `skins/dusk-sanctum/`', '资料包中的原件在 `examples/dusk-sanctum/`')
    guide = guide.replace('(DesktopMatchSkin-AI-Spec.zh-CN.md)', '(AI制作规范.md)')
    guide = guide.replace('(DesktopMatchSkin-v4.zh-CN.md)', '(v4扩展规范.md)')
    guide = guide.replace('(DesktopMatchSkin-v5.zh-CN.md)', '(v5扩展规范.md)')
    (out / 'docs/制作入门.md').write_text(guide, encoding='utf-8')
    spec = (root / 'docs/DesktopMatchSkin-AI-Spec.zh-CN.md').read_text(encoding='utf-8')
    (out / 'docs/AI制作规范.md').write_text(standalone_spec(spec), encoding='utf-8')
    shutil.copytree(root / 'skins/dusk-sanctum', out / 'examples/dusk-sanctum')
    shutil.copytree(root / 'skins/dusk-observatory', out / 'examples/dusk-observatory')
    subprocess.run([sys.executable, '-B', str(source / 'make_v4_example.py'), '--base', str(out / 'examples/dusk-sanctum'),
                    '--out', str(out / 'examples/v4-workbench')], check=True)
    skin_readme = (out / 'examples/dusk-sanctum/README.md').read_text(encoding='utf-8')
    skin_readme = skin_readme.split('## 制作自己的皮肤')[0] + '## 制作自己的皮肤\n\n请阅读资料包根目录的 README.md 以及 docs 中的独立说明。\n'
    (out / 'examples/dusk-sanctum/README.md').write_text(skin_readme, encoding='utf-8')
    configs = [json.loads(m) for m in re.findall(r'```json\s*\n([\s\S]*?)\n```', spec) if '"version"' in m]
    if len(configs) != 1:
        raise SystemExit('Expected exactly one full documented template')
    (out / 'examples/minimal/match-ui.json').write_text(json.dumps(configs[0], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (out / 'examples/minimal/README.md').write_text('# 最小双人骨架\n\n用于学习与验证布局，不是视觉成品。不带背景或字体；窄提示区域仍需实机调整。支持 match-ui v3，操作入口与文档完整。\n', encoding='utf-8')
    sys.dont_write_bytecode = True
    module_spec = importlib.util.spec_from_file_location('skin_tool', source / 'skin_tool.py')
    tool = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(tool)
    for name in ('minimal', 'dusk-sanctum', 'v4-workbench', 'dusk-observatory'):
        files = tool.read_package(out / 'examples' / name)
        config, report = tool.validate(files)
        tool.pack(files, out / 'ready-to-import' / (name + '.zip'))
        tool.validate(tool.read_package(out / 'ready-to-import' / (name + '.zip')))
        tool.preview(config, files, out / 'previews' / (name + '.html'))
        (out / 'validation' / (name + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    test = subprocess.run([sys.executable, '-B', '-m', 'unittest', '-v', 'test_skin_tool.py', 'test_authoring.py', 'test_v5.py'], cwd=out / 'tools', capture_output=True)
    (out / 'validation/tool-tests.txt').write_bytes(test.stdout + test.stderr)
    if test.returncode:
        raise SystemExit('Standalone tests failed; see validation/tool-tests.txt')
    # Human-readable offline views; Markdown remains the editable canonical source.
    for md in list((out / 'docs').glob('*.md')) + [out / 'README.md', out / 'NOTICE.md']:
        md.with_suffix('.html').write_text(document_html(md.read_text(encoding='utf-8')), encoding='utf-8')
    (out / '开始阅读.txt').write_text('Forge 独立皮肤制作资料包\n\n请用浏览器打开 开始阅读.html，或用文本编辑器打开 README.md。\n工具需 Python 3.10+，无需 Forge、源码、JDK 或第三方库。\n整个资料包不是可导入的皮肤；示例皮肤 ZIP 位于 ready-to-import。\n', encoding='utf-8')
    home = '''# Forge 独立皮肤制作资料包

无需 Forge 主程序与源码即可制作和静态校验；真实对局交互由使用者最后验收。

- [先读 README](README.md)
- [高效制作流程、尺寸诊断与排错](docs/高效制作与排错.md)
- [制作入门](docs/制作入门.md)
- [AI 制作规范](docs/AI制作规范.md)
- [v4 新增能力与字段](docs/v4扩展规范.md)
- [v5 响应式、个人设置与多人布局](docs/v5扩展规范.md)
- [暮辉星台 v5 示例](previews/dusk-observatory.html)
- [v4 接口工作台布局示意](previews/v4-workbench.html)
- [独立工具说明](docs/独立工具说明.md)
- [验收清单](docs/验收清单.md)
- [给 AI 的任务文本](docs/AI任务模板.txt)
- [暮辉秘境布局示意](previews/dusk-sanctum.html)
- [最小骨架布局示意](previews/minimal.html)
- [来源与许可](NOTICE.md)

可编辑示例在 examples，可直接导入的示例 ZIP 在 ready-to-import。工具位于 tools，只需要 Python 3.10 或更高版本。
'''
    (out / '开始阅读.html').write_text(document_html(home), encoding='utf-8')
    manifest = {"kit_version": tool.VERSION, "interface": "match-ui-v3/v4/v5", "reference_date": "2026-10-02",
                "python_minimum": "3.10", "requires_forge_for_authoring": False, "runtime_acceptance_required": True,
                "files": {p.relative_to(out).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(out.rglob('*')) if p.is_file()}}
    (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(out.rglob('*')):
            if path.is_file():
                archive.write(path, (Path(out.name) / path.relative_to(out)).as_posix())
    checksum = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    archive_path.with_suffix('.zip.sha256.txt').write_text(f'{checksum}  {archive_path.name}\n', encoding='utf-8')
    print(json.dumps({"directory": str(out), "zip": str(archive_path), "bytes": archive_path.stat().st_size,
                      "sha256": checksum, "included_files": len(manifest['files']) + 1}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    main()
