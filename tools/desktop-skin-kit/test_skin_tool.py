"""Run without Forge or third-party packages: python -m unittest -v test_skin_tool.py"""
import copy
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import skin_tool as tool


def minimal():
    return {
        "version": 3, "id": "test-skin",
        "regions": [
            {"bounds": [0, 0, .8, .3], "documents": ["opponents"], "split": "ROWS"},
            {"bounds": [0, .4, .8, .3], "documents": ["FIELD_0"]},
            {"bounds": [0, .8, .8, .2], "documents": ["hands"], "split": "COLUMNS"},
            {"bounds": [.8, .8, .2, .2], "documents": ["REPORT_MESSAGE"]},
            {"bounds": [.8, 0, .2, .8], "documents": ["remaining"]},
        ],
        "scene": {"widgets": {
            "FIELD_1.AVATAR": [0, .3, .2, .05], "FIELD_1.DETAILS": [.2, .3, .6, .05],
            "PHASES_ACTIVE": [0, .35, .8, .05], "FIELD_0.AVATAR": [0, .7, .2, .05],
            "FIELD_0.DETAILS": [.2, .7, .6, .05],
        }},
    }


def files(config=None):
    return {"match-ui.json": json.dumps(config or minimal()).encode()}


class ValidationTest(unittest.TestCase):
    def test_adaptive_battlefield_requires_v4_and_preserves_legacy_modes(self):
        config = minimal()
        config['cards'] = {'battlefield': 'adaptive'}
        self.reject(config)
        config['version'] = 4
        self.assertTrue(tool.validate(files(config))[1]['ok'])
        config['cards']['battlefield'] = 'ADAPTIVE'
        self.reject(config)
        for version in (3, 4):
            config['version'] = version
            for mode in ('classic', 'lanes'):
                config['cards']['battlefield'] = mode
                self.assertTrue(tool.validate(files(config))[1]['ok'])

    def test_split_phase_renderer_version_scope_and_capacity(self):
        config = minimal()
        config["version"] = 4
        config["scene"]["renderers"] = {"PHASES_ACTIVE": "PHASES_SPLIT"}
        self.assertTrue(tool.validate(files(config))[1]["ok"])
        self.assertTrue(tool.supports(config, tool.documents(2, 1)))
        for count in (1, 3, 8):
            self.assertFalse(tool.supports(config, tool.documents(count, 1)))
        for version in (3, 2):
            config["version"] = version
            self.reject(config)
        config["version"] = 4
        for renderers in ({"FIELD_0.AVATAR": "PHASES_SPLIT"}, {"PHASES_ACTIVE": "ZONE_BUTTON"}):
            config["scene"]["renderers"] = renderers
            self.reject(config)

    def test_v4_controls_states_decorations_and_cards(self):
        config = minimal()
        config['version'] = 4
        config['cards'] = {'hand':'fan', 'handSpacing':.6, 'handArc':.7, 'hoverLift':.2,
                          'battlefield':'lanes','battlefieldAlign':'CENTER','battlefieldRowGap':8,
                          'badges':{'powerToughness':{'bounds':[.5,.8,.5,.2],'fill':'#10102080'}}}
        config['scene']['appearance'] = {'styles':{'button':{'shape':'HEXAGON', 'opacity':.8,
            'states':{'hover':{'fill':'#123456'},'disabled':{'textColor':'#FFFFFF'}}}}}
        self.assertTrue(tool.validate(files(config))[1]['ok'])
        config['version'] = 3
        self.reject(config)

    def test_v4_bad_states_and_ranges(self):
        for style in ({'states':{'hover':{'states':{}}}}, {'opacity':2}, {'shape':'STAR'},
                      {'showText':'false'}, {'textBounds':[0,0,2,1]}, {'borderWidth':100}):
            config = minimal()
            config['version'] = 4
            config['scene']['appearance'] = {'styles':{'button':style}}
            self.reject(config)
        for card in ({'hoverLift':1}, {'handSpacing':0}, {'battlefieldGap':.5}, {'battlefieldAlign':'MIDDLE'}):
            config = minimal()
            config['version'] = 4
            config['cards'] = card
            self.reject(config)

    def test_v4_texture_and_standalone_example(self):
        import make_v4_example
        config = minimal()
        config['version'] = 4
        config['scene']['appearance'] = {'background':{'path':'icon.png','mode':'NINE_SLICE','slices':[6,6,6,6]},
            'decorations':[{'image':{'path':'icon.png','mode':'CONTAIN'},'bounds':[0,0,1,1],'plane':'FOREGROUND'}]}
        package = files(config)
        package['icon.png'] = make_v4_example.png('life')
        self.assertTrue(tool.validate(package)[1]['ok'])
        config['scene']['appearance']['background']['slices'] = [32,32,32,32]
        package['match-ui.json'] = json.dumps(config).encode()
        with self.assertRaises(tool.Invalid):
            tool.validate(package)

    def reject(self, config):
        with self.assertRaises(tool.Invalid):
            tool.validate(files(config))

    def test_valid_and_capacity(self):
        config, report = tool.validate(files())
        self.assertTrue(report["ok"])
        for hands in (0, 1):
            for dev in (False, True):
                docs = tool.documents(2, hands, dev)
                self.assertTrue(tool.supports(config, docs))
                tool.arrange(config, docs)
        self.assertFalse(tool.supports(config, tool.documents(2, 2)))
        for count in (3, 4, 8):
            self.assertFalse(tool.supports(config, tool.documents(count, 1)))

    def test_unknown_and_version(self):
        for key, value in (("version", 2), ("version", "3"), ("id", "UPPER"), ("animation", True)):
            config = minimal()
            config[key] = value
            self.reject(config)

    def test_invalid_geometry(self):
        for rect in ([0, 0, 0, 1], [-.1, 0, 1, 1], [0, 0, 2, 1], [0, 0, True, 1], [0, 0, float('inf'), 1], [1, 2]):
            config = minimal()
            config["regions"][0]["bounds"] = rect
            self.reject(config)

    def test_fixed_overlap(self):
        config = minimal()
        config["scene"]["widgets"]["PHASES_ACTIVE"] = [0, 0, .1, .1]
        self.reject(config)

    def test_missing_phase_and_capacity(self):
        for key in ("PHASES_ACTIVE", "FIELD_0.AVATAR", "FIELD_1.DETAILS"):
            config = minimal()
            del config["scene"]["widgets"][key]
            self.reject(config)

    def test_duplicate_control(self):
        config = minimal()
        config["scene"]["widgets"]["FIELD_0.MANA"] = [0, .75, .8, .04]
        self.reject(config)

    def test_split_prompt(self):
        config = minimal()
        config["scene"]["widgets"]["PROMPT_OK"] = [0, .75, .1, .04]
        self.reject(config)

    def test_visibility_restriction(self):
        config = minimal()
        for key, condition in (("PHASES_ACTIVE", "STACK_NONEMPTY"), ("FIELD_0.AVATAR", "PLAYER_ACTIVE"), ("missing", "ALWAYS")):
            config["scene"]["visibility"] = {key: condition}
            self.reject(config)

    def test_renderer_restriction(self):
        config = minimal()
        config["scene"]["renderers"] = {"PHASES_ACTIVE": "ZONE_BUTTON"}
        self.reject(config)

    def test_document_omission_and_duplicate(self):
        config = minimal()
        config["regions"][-1]["documents"] = ["REPORT_STACK"]
        self.reject(config)
        config = minimal()
        config["regions"][-1]["documents"] = ["FIELD_0", "remaining"]
        self.reject(config)

    def test_remaining_order(self):
        config = minimal()
        config["regions"][-2:] = reversed(config["regions"][-2:])
        self.reject(config)

    def test_hidden_battlefield_tabs(self):
        config = minimal()
        config["regions"][0]["documents"] += ["REPORT_LOG"]
        config["regions"][0]["split"] = "TABS"
        self.reject(config)

    def test_floating_protected(self):
        config = minimal()
        config["scene"]["floating"] = {"REPORT_STACK": {"bounds": [0, .8, .3, .1]}}
        self.reject(config)

    def test_floating_duplicate_and_title(self):
        config = minimal()
        config["scene"]["floating"] = {"REPORT_STACK": {"bounds": [0, 0, .3, .1]}}
        config["regions"][-1]["documents"] = ["REPORT_STACK", "remaining"]
        self.reject(config)
        config["regions"][-1]["documents"] = ["remaining"]
        config["scene"]["widgets"]["STACK_STATUS"] = [0, .75, .3, .04]
        self.reject(config)

    def test_theme_colors_and_integer(self):
        for value in ({"text": "red"}, {"styles": {"button": {"hover": "#abcdef"}}},
                      {"styles": {"button": {"fontSize": 14.5}}}, {"styles": {"button": {"padding": 33}}}):
            config = minimal()
            config["scene"]["appearance"] = value
            self.reject(config)

    def test_asset_paths(self):
        for path in ("../outside.png", "C:/image.png", "images\\image.png", "missing.png"):
            config = minimal()
            config["scene"]["appearance"] = {"background": path}
            self.reject(config)

    def test_duplicate_json_and_encoding(self):
        for data in (b'{"version":3,"version":3}', b'{"x":NaN}', b'\xff', b'{'):
            with self.assertRaises((ValueError, UnicodeError)):
                tool.parse_config(data)

    def test_image_header_limits(self):
        png = b'\x89PNG\r\n\x1a\n' + b'\x00\x00\x00\x0dIHDR' + struct.pack('>II', 9000, 9000) + b'\0' * 9
        self.assertEqual(tool.image_size(png), (9000, 9000))
        config = minimal()
        config["scene"]["appearance"] = {"background": "large.png"}
        bundle = files(config)
        bundle["large.png"] = png
        with self.assertRaises(tool.Invalid):
            tool.validate(bundle)
        with self.assertRaises(tool.Invalid):
            tool.image_size(b'not an image')

    def test_font_tables(self):
        with self.assertRaises(tool.Invalid):
            tool.check_font(b'not a font')
        with self.assertRaises(tool.Invalid):
            tool.check_font(b'OTTO\x00\x01' + b'\0' * 6)

    def test_cards_and_surface(self):
        for cards in ({"hand": "fan", "fanDegrees": 61}, {"battlefield": "free"}, {"overlay": "custom"}):
            config = minimal()
            config["cards"] = cards
            self.reject(config)
        config = minimal()
        config["scene"]["surface"] = {"title": "false"}
        self.reject(config)

    def test_zip_security_and_root(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'skin.zip'
            for illegal in ('../escape.txt', '/abs.txt', 'C:/abs.txt', 'a\\b.txt', 'run.exe', 'CON.txt'):
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('match-ui.json', files()['match-ui.json'])
                    entry = zipfile.ZipInfo('temporary.txt')
                    entry.filename = illegal
                    archive.writestr(entry, b'x')
                with self.subTest(illegal=illegal), self.assertRaises(tool.Invalid):
                    tool.read_package(path)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('folder/match-ui.json', files()['match-ui.json'])
            with self.assertRaises(tool.Invalid):
                tool.read_package(path)

    def test_zip_case_link_and_count(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'skin.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('match-ui.json', b'{}')
                archive.writestr('MATCH-UI.JSON', b'{}')
            with self.assertRaises(tool.Invalid):
                tool.read_package(path)
            with zipfile.ZipFile(path, 'w') as archive:
                entry = zipfile.ZipInfo('link.txt')
                entry.create_system = 3
                entry.external_attr = 0o120777 << 16
                archive.writestr(entry, b'outside')
            with self.assertRaises(tool.Invalid):
                tool.read_package(path)
            with zipfile.ZipFile(path, 'w') as archive:
                for i in range(257):
                    archive.writestr(f'{i}.txt', b'')
            with self.assertRaises(tool.Invalid):
                tool.read_package(path)

    def test_pack_roundtrip_no_overwrite_and_preview(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'skin.zip'
            tool.pack(files(), output)
            before = output.read_bytes()
            tool.validate(tool.read_package(output))
            with self.assertRaises(FileExistsError):
                tool.pack(files(), output)
            self.assertEqual(before, output.read_bytes())
            page = Path(folder) / 'preview.html'
            tool.preview(minimal(), files(), page)
            self.assertIn('Content-Security-Policy', page.read_text(encoding='utf-8'))
            self.assertNotIn('<script', page.read_text(encoding='utf-8'))

    def test_directory_roundtrip(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / '中文皮肤'
            root.mkdir()
            (root / 'match-ui.json').write_bytes(files()['match-ui.json'])
            self.assertEqual(tool.read_package(root), files())
            self.assertEqual(tool.main(['pack', str(root), '--out', str(root / 'bad.zip'), '--json']), 1)
            self.assertFalse((root / 'bad.zip').exists())


if __name__ == '__main__':
    unittest.main()
