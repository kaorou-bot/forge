import copy
import json
import unittest
from pathlib import Path
import skin_tool as t
from test_skin_tool import minimal


class ExperienceTests(unittest.TestCase):
    def sample(self):
        root=Path(__file__).resolve().parents[2]/'skins/dusk-observatory'
        if not root.exists():
            root=Path(__file__).resolve().parents[1]/'examples/dusk-observatory'
        return t.read_package(root)

    def test_example_all_player_counts(self):
        files=self.sample(); config,report=t.validate(files)
        self.assertTrue(all(r['result']=='scene' for r in report['capacity'] if r['visible_hands']<2))
        for p in range(2,9):
            for w in (1280,1920,2560):
                chosen,variant=t.choose_config(config,w,p)
                self.assertTrue(t.supports(chosen,t.documents(p,1)))
                self.assertEqual(variant,('compact' if w<=1440 else 'base') if p==2 else f'players-{p}')

    def test_invalid_variant_and_anchor_rejected(self):
        files=self.sample(); c=t.parse_config(files['match-ui.json'])
        c['experience']['variants'][0]['maxWidth']=-1
        files['match-ui.json']=json.dumps(c).encode()
        with self.assertRaises(t.Invalid): t.validate(files)
        files=self.sample(); c=t.parse_config(files['match-ui.json'])
        c['scene']['anchors']['PHASES_ACTIVE']['maxHeight']=-2
        files['match-ui.json']=json.dumps(c).encode()
        with self.assertRaises(t.Invalid): t.validate(files)

    def test_required_capabilities_include_variant_features(self):
        c=t.parse_config(self.sample()['match-ui.json'])
        self.assertTrue({'experience-v5','anchors','multiplayer-phases','document-format','polygon-avatar','phase-markers','battlefield-partition'} <= set(t.required_features(c)))
        self.assertIn('stack-empty-visibility', t.required_features(c))

    def test_sidebar_rules_have_body_height_and_mutually_exclusive_floats(self):
        c=t.parse_config(self.sample()['match-ui.json'])
        for count in range(2,9):
            chosen,_=t.choose_config(c,1280,count)
            region=next(r for r in chosen['regions'] if 'CARD_DETAIL' in r['documents'])
            self.assertGreaterEqual(region['bounds'][3]*720,300)
            floats=chosen['scene']['floating']
            self.assertEqual(floats['REPORT_STACK']['bounds'],floats['REPORT_LOG']['bounds'])
            self.assertEqual(floats['REPORT_LOG']['visibleWhen'],'STACK_EMPTY')
            self.assertFalse(t.overlaps(region['bounds'],floats['REPORT_LOG']['bounds']))

    def test_unknown_nested_keys_not_ignored(self):
        files=self.sample(); c=t.parse_config(files['match-ui.json'])
        c['scene']['appearance']['documents']['default']['unknown']=True
        files['match-ui.json']=json.dumps(c).encode()
        with self.assertRaises(t.Invalid): t.validate(files)

    def test_tiny_document_strip_is_reported_not_silently_accepted_as_readable(self):
        c=t.parse_config(self.sample()['match-ui.json'])
        region=next(r for r in c['regions'] if 'CARD_DETAIL' in r['documents'])
        region['bounds'][3]=.055
        self.assertTrue(any(w.startswith('SHORT_DOCUMENT:') for w in t.inspect_layout(c,1920,1080)['warnings']))


if __name__=='__main__': unittest.main()
