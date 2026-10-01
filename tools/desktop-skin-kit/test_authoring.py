"""Regression cases derived from the industrial skin author's ten iterations."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest

import skin_tool as tool
from test_skin_tool import minimal, files


class AuthoringTest(unittest.TestCase):
    def test_capabilities_and_requirements(self):
        c = minimal()
        self.assertEqual(tool.required_features(c), ['scene-v3'])
        c['version'] = 4
        c['cards'] = {'hand': 'fan', 'handCardWidthMax': 90, 'battlefield': 'adaptive'}
        c['scene']['renderers'] = {'PHASES_ACTIVE': 'PHASES_SPLIT'}
        self.assertLessEqual(set(tool.required_features(c)), set(tool.capabilities()['features']))
        self.assertIn('hand-width-cap', tool.required_features(c))

    def test_inspector_padding_and_split_phase_measures(self):
        c = minimal()
        c['version'] = 4
        c['scene']['renderers'] = {'PHASES_ACTIVE': 'PHASES_SPLIT'}
        c['scene']['appearance'] = {'styles': {'phase': {'padding': 3}}}
        report = tool.inspect_layout(c, 1000, 600)
        phase = next(x for x in report['widgets'] if x['id'] == 'PHASES_ACTIVE')
        self.assertEqual(phase['outer_px'][2:], [800,30])
        self.assertEqual(phase['inner_size_px'], [794,24])
        self.assertEqual(phase['half_size_px'], [59,12])
        self.assertTrue(any('SMALL_PHASES' in w for w in report['warnings']))
        self.assertEqual(tool.pixel_bounds([.0005,0,.001,1],1000,600),[1,0,1,600])

    def test_fan_formula_includes_legacy_height_limit_and_width(self):
        self.assertEqual(tool.fan_card_width(634,228,.22),108)
        self.assertEqual(tool.fan_card_width(634,228,.22,80),80)
        self.assertEqual(tool.fan_card_width(50,228,.22),29)
        self.assertEqual(tool.fan_card_width(2000,1000),300)

    def test_exact_style_replaces_not_merges_with_category(self):
        c = minimal()
        c['scene']['appearance'] = {'styles': {'phase': {'padding': 20}, 'PHASES_ACTIVE': {'fontSize': 14}}}
        key, style = tool.style_for(c, 'PHASES_ACTIVE')
        self.assertEqual(key, 'PHASES_ACTIVE')
        self.assertNotIn('padding', style)

    def test_unused_style_and_misleading_phase_disabled_warning(self):
        c = minimal()
        c['version'] = 4
        c['scene']['appearance'] = {'styles': {'PHASE.DARW': {}, 'phase': {'states': {'disabled': {'opacity': .2}}},
                                               'text': {'textBounds': [0,0,1,1]}}}
        report = tool.validate(files(c))[1]
        self.assertTrue(any('UNUSED_STYLE' in w for w in report['warnings']))
        self.assertTrue(any('PHASE_STATE' in w for w in report['warnings']))
        self.assertTrue(any('TEXT_COMPONENT' in w for w in report['warnings']))

    def test_hand_cap_rejected_when_ineffective_or_out_of_range(self):
        for version, hand, cap in [(3,'fan',80),(4,'classic',80),(4,'fan',301),(4,'fan',15),(4,'fan',80.5),(4,'fan',True)]:
            c = minimal()
            c.update(version=version, cards={'hand': hand, 'handCardWidthMax': cap})
            with self.assertRaises(tool.Invalid):
                tool.validate(files(c))

    def test_conditional_status_scope_and_version(self):
        c = minimal()
        c['version'] = 4
        c['scene']['widgets']['FIELD_0.AVATAR_IMAGE'] = c['scene']['widgets'].pop('FIELD_0.AVATAR')
        c['scene']['widgets'].update({'FIELD_0.LIFE': [0,.75,.2,.025], 'FIELD_0.STATUS': [.2,.75,.2,.025]})
        c['scene']['visibility'] = {'FIELD_0.STATUS': 'STATUS_NONEMPTY'}
        self.assertTrue(tool.validate(files(c))[1]['ok'])
        for key in ('FIELD_0.LIFE','PHASES_ACTIVE'):
            wrong = copy.deepcopy(c)
            wrong['scene']['visibility'] = {key: 'STATUS_NONEMPTY'}
            with self.assertRaises(tool.Invalid):
                tool.validate(files(wrong))
        c['version'] = 3
        with self.assertRaises(tool.Invalid):
            tool.validate(files(c))

    def test_migration_changes_only_version_and_never_overwrites(self):
        original = files()
        original['README.md'] = '保留作者说明'.encode()
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / 'new.zip'
            tool.migrate(original, out)
            after = tool.read_package(out)
            expected = minimal()
            expected['version'] = 4
            self.assertEqual(json.loads(after['match-ui.json']),expected)
            self.assertEqual(after['README.md'],original['README.md'])
            self.assertEqual(json.loads(original['match-ui.json'])['version'],3)
            with self.assertRaises(FileExistsError):
                tool.migrate(original,out)

    def test_cli_reports_compatibility_and_refuses_nested_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)/'skin'
            root.mkdir()
            (root/'match-ui.json').write_bytes(files()['match-ui.json'])
            target = Path(temp)/'old.json'
            target.write_text(json.dumps({'format_versions':[3], 'features':[]}),encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()) as result:
                self.assertEqual(tool.main(['validate',str(root),'--target-capabilities',str(target),'--json']),1)
            self.assertIn('lacks features', result.getvalue())
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(tool.main(['inspect',str(root),'--out',str(root/'bad.json'),'--json']),1)
            self.assertFalse((root/'bad.json').exists())


if __name__ == '__main__':
    unittest.main()
