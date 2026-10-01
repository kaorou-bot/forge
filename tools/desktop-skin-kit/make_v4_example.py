"""Build a v4 interface demonstration from the bundled example, using original geometric test icons.
Python standard library only. Does not modify the input skin or an existing output directory.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil
import struct
import zlib


def png(kind, size=64):
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data) & 0xffffffff)
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            u, v = x / (size - 1), y / (size - 1)
            if kind == 'library':
                inside = any(.18 + i*.07 < u < .65+i*.07 and .18+i*.1 < v < .55+i*.1 for i in range(3))
            elif kind == 'graveyard':
                inside = (.25 < u < .75 and .4 < v < .85) or ((u-.5)**2+(v-.4)**2 < .25**2 and v < .4)
            elif kind == 'exile':
                inside = abs(u-.5)+abs(v-.5) < .4 and abs(u-.5)+abs(v-.5) > .23
            elif kind == 'life':
                inside = abs(u-.5) < .35 and abs(v-.5) < .12 or abs(v-.5) < .35 and abs(u-.5) < .12
            elif kind == 'frame':
                distance = max(abs(v-.5)/.5, abs(u-.5)/.5 + abs(v-.5)/1.0)
                inside = .82 < distance < .97
            elif kind == 'button':
                inside = True
            else:
                inside = .12 < ((u-.5)**2+(v-.5)**2)**.5 < .38 or (abs(u-.5)<.04 and .2<v<.55)
            if not inside:
                pixels.extend((0,0,0,0))
            elif kind == 'button':
                pixels.extend((70,125,145,255) if min(x,y,size-1-x,size-1-y)<5 else (20,40,60,230))
            else:
                pixels.extend((140,215,230,255))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',size,size,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


def build(base, output):
    base, output = Path(base), Path(output)
    config = json.loads((base / 'match-ui.json').read_text(encoding='utf-8'))
    if config.get('id') != 'dusk-sanctum':
        raise ValueError('This generates a demonstration from dusk-sanctum, NOT a migration. Use skin_tool.py migrate for a custom v3 skin.')
    if output.exists():
        raise ValueError('Output already exists; choose a new directory')
    shutil.copytree(base, output)
    config = json.loads((base / 'match-ui.json').read_text(encoding='utf-8'))
    config['version'], config['id'] = 4, 'v4-workbench'
    for kind in ('library','graveyard','exile','life','frame','button','phase'):
        (output/'images'/f'{kind}.png').write_bytes(png(kind))
    appearance = config['scene']['appearance']
    appearance['background'] = {'path':'images/table.png','mode':'COVER'}
    appearance['decorations'] = [
        {'image':{'path':'images/button.png','mode':'TILE'}, 'bounds':[.2,.125,.6,.005], 'opacity':.35, 'order':0},
        {'image':{'path':'images/phase.png','mode':'CONTAIN'}, 'bounds':[.45,.34,.1,.065], 'opacity':.14, 'rotation':15,'plane':'FOREGROUND'}]
    styles = appearance['styles']
    styles['button'].update({'image':{'path':'images/button.png','mode':'NINE_SLICE','slices':[6,6,6,6]},
        'states':{'hover':{'border':'#E0F8FF','textColor':'#FFFFFF'},'pressed':{'opacity':.6},'disabled':{'opacity':.3,'textColor':'#AAAAAA'}}})
    styles['avatar'].update({'shape':'HEXAGON','frame':{'path':'images/frame.png','mode':'STRETCH'},'borderWidth':2})
    styles['life'].update({'icon':{'path':'images/life.png','mode':'CONTAIN'},'iconBounds':[0,.1,.32,.8],'textBounds':[.32,0,.68,1]})
    for player in (0,1):
        for zone in ('library','graveyard','exile'):
            styles[f'FIELD_{player}.ZONE_{zone.upper()}'] = dict(copy.deepcopy(styles['zone']),
                icon={'path':f'images/{zone}.png','mode':'CONTAIN'}, iconBounds=[.08,.03,.84,.68],textBounds=[0,.72,1,.28])
    for phase in ('UPKEEP','DRAW','MAIN1','COMBAT_BEGIN','COMBAT_DECLARE_ATTACKERS','COMBAT_DECLARE_BLOCKERS',
                  'COMBAT_FIRST_STRIKE_DAMAGE','COMBAT_DAMAGE','COMBAT_END','MAIN2','END_OF_TURN','CLEANUP'):
        styles['PHASE.'+phase] = dict(copy.deepcopy(styles['phase']),icon={'path':'images/phase.png','mode':'CONTAIN'},
                iconBounds=[0,0,.22,1],textBounds=[.22,0,.78,1],states={'selected':{'fill':'#327B8C'},'hover':{'fill':'#436070'}})
    styles['floating.CARD_DETAIL']={'fill':'#10202D99','border':'#83C4CC','radius':12,'padding':6,'fontSize':15}
    styles['text']={'fontSize':14,'textColor':'#F0F7FF'}
    for region in config['regions']:
        if 'CARD_DETAIL' in region['documents']:
            region['documents'].remove('CARD_DETAIL')
            region['bounds']=[.83,.12,.165,.26]
    config['scene']['floating'].setdefault('CARD_DETAIL', {'bounds':[.83,.40,.165,.29],'draggable':True})
    config['cards'].update({'battlefield':'adaptive','battlefieldAlign':'CENTER','battlefieldRowGap':8,'battlefieldGap':6,
        'handSpacing':.65,'handArc':.7,'hoverLift':.18,
        'badges':{'powerToughness':{'bounds':[.5,.8,.5,.2],'fill':'#10202DD0','fontSize':14},
                  'counters':{'bounds':[0,0,1,.45],'fill':'#10202DA0','fontSize':11}}})
    (output/'match-ui.json').write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'README.md').write_text('# v4 接口工作台\n\n功能验证示例，不是正式美术成品。需要支持 match-ui v4 的桌面测试客户端；cn0930 正式版不支持。\n\n展示：几何图标、六边形头像框、生物居中、半透明文字浮窗、图片九宫格、多状态按钮、手牌抬升和角标配置。\n\nphase.png 为占位图，可为每个 PHASE.* 条目换成独立图片。\n',encoding='utf-8')
    with (output/'ASSETS.md').open('a',encoding='utf-8') as f:
        f.write('\n新增 library/graveyard/exile/life/frame/button/phase.png 由 make_v4_example.py 的几何公式生成，随工具按 GPL-3.0-or-later 提供。\n')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    build(args.base,args.out)
