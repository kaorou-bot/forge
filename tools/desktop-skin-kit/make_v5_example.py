#!/usr/bin/env python3
"""Build the data-only v5 showcase from the licensed Dusk Sanсtum assets. Never overwrites."""
import argparse
import copy
import json
import math
from pathlib import Path
import shutil


def build(base, out):
    if out.exists():
        raise ValueError('Choose a new output directory')
    source = json.loads((base / 'match-ui.json').read_text(encoding='utf-8'))
    if source['id'] != 'dusk-sanctum':
        raise ValueError('This generator requires the bundled dusk-sanctum source')
    shutil.copytree(base, out)
    c = copy.deepcopy(source)
    c.update(version=5, id='dusk-observatory')
    scene = c['scene']
    # Right sidebar keeps floating documents away from hands and response controls.
    c['regions'] = [r for r in c['regions'] if 'CARD_PICTURE' not in r['documents'] and 'REPORT_LOG' not in r['documents']]
    # Picture / full rules / utility reports share a readable tabbed area instead of
    # giving the rules a 5.5%-height strip below three other panels.
    sidebar = {'bounds':[.835,.012,.153,.55],'documents':['CARD_PICTURE','CARD_DETAIL','remaining']}
    c['regions'] += [copy.deepcopy(sidebar)]
    # The same lower slot shows the live stack while resolving, otherwise the log.
    scene['floating'] = {'REPORT_STACK':{'bounds':[.835,.58,.153,.305],'draggable':True,'visibleWhen':'STACK_NONEMPTY'},
                         'REPORT_LOG':{'bounds':[.835,.58,.153,.305],'draggable':True,'visibleWhen':'STACK_EMPTY'}}
    scene['widgets'].pop('STACK_STATUS',None)
    scene['anchors'] = {f'FIELD_{p}.AVATAR_IMAGE':{'maxWidth':150,'maxHeight':150,'aspectRatio':1,'horizontal':'CENTER','vertical':'CENTER'} for p in (0,1)}
    scene['anchors']['PHASES_ACTIVE'] = {'maxHeight':64,'minHeight':44}
    a = scene['appearance']
    a['text'] = '#F1F4EF'
    a['documents'] = {'default':{'margin':8,'paragraphGap':6,'align':'LEFT'},'REPORT_LOG':{'margin':10,'paragraphGap':8,'align':'LEFT'}}
    styles = a['styles']
    styles['avatar'] = {'shape':'POLYGON','polygon':[[.16,0],[.84,0],[1,.16],[1,.82],[.8,1],[.2,1],[0,.82],[0,.16]],
                        'border':'#D5B572','borderWidth':3,'fill':'#162A31','padding':0}
    styles['phase'] = {'fill':'#142D35E8','border':'#789E9D','textColor':'#F6EEE0','fontSize':12,'padding':1,'radius':8,
                       'markers':{'stop':'images/life.png','active':'images/library.png','yield':'images/exile.png'},
                       'states':{'selected':{'fill':'#256775','border':'#E9C478'},'hover':{'fill':'#385B62'},'pressed':{'fill':'#0C2029'}}}
    styles['PHASES_ACTIVE'] = {'padding':1,'fontSize':13,'textColor':'#DBDCCB'}
    styles['floating'] = {'fill':'#0B1929','border':'#B9A27A','borderWidth':1,'radius':14,'padding':7,'fontSize':15,'opacity':.78}
    # Avoid exact inherited styles masking the new category palette.
    for k in list(styles):
        if k.startswith(('floating.','PHASE.')) or k.endswith('.AVATAR_IMAGE'):
            del styles[k]
    styles['text'] = {'fontSize':16,'textColor':'#F1F4EF','fill':'#00000000','padding':0}
    styles['text.REPORT_LOG'] = {'fontSize':16,'textColor':'#F1F4EF','fill':'#00000000','padding':0}
    c['cards'].update(handCardWidthMax=145,battlefield='adaptive',battlefieldPartition=.5,landsSide='LEFT',battlefieldAlign='CENTER',hoverLift=.12)
    compact = {'id':'compact','maxWidth':1440,'minPlayers':2,'maxPlayers':2,'widgets':copy.deepcopy(scene['widgets']),
               'regions':copy.deepcopy(c['regions']),'anchors':copy.deepcopy(scene['anchors']), 'cards':copy.deepcopy(c['cards'])}
    # Make the central phase ribbon taller, with corresponding battlefield margins.
    compact['widgets']['PHASES_ACTIVE'] = [.18,.392,.64,.084]
    compact['regions'][0]['bounds'] = [.18,.13,.64,.25]
    compact['regions'][1]['bounds'] = [.18,.487,.64,.233]
    compact['cards']['handCardWidthMax'] = 112
    variants = [compact]
    for count in range(3,9):
        widgets = {k:copy.deepcopy(v) for k,v in scene['widgets'].items() if not k.startswith('FIELD_') and k != 'PHASES_ACTIVE'}
        widgets['PHASES_ACTIVE'] = [.01,.005,.81,.025]
        regions=[]
        def player(index,x,y,w,h):
            def box(xx,yy,ww,hh): return [round(x+xx*w,6),round(y+yy*h,6),round(ww*w,6),round(hh*h,6)]
            prefix=f'FIELD_{index}.'
            widgets[prefix+'AVATAR_IMAGE']=box(0,0,.12,.19)
            widgets[prefix+'NAME']=box(.13,0,.61,.09)
            widgets[prefix+'LIFE']=box(.75,0,.24,.19)
            widgets[prefix+'STATUS']=box(.13,.10,.61,.09)
            widgets[prefix+'PHASES']=box(0,.20,1,.105)
            for z,xx in (('LIBRARY',0),('GRAVEYARD',.14),('EXILE',.28)):
                widgets[prefix+'ZONE_'+z]=box(xx,.315,.13,.105)
            widgets[prefix+'MANA']=box(.43,.315,.43,.105)
            widgets[prefix+'OTHER_ZONES']=box(.87,.315,.13,.105)
            regions.append({'bounds':box(0,.435,1,.565),'documents':[f'FIELD_{index}']})
        cols=count-1 if count<=4 else math.ceil((count-1)/2)
        rows=math.ceil((count-1)/cols)
        cw=.81/cols; rh=.49/rows
        for p in range(1,count):
            row=(p-1)//cols
            row_cols=min(cols,count-1-row*cols)
            cell_width=.81/row_cols
            player(p,.01+((p-1)%cols)*cell_width,.045+row*rh,cell_width-.008,rh-.012)
        # Local player has a full-width board and phase controls.
        player(0,.01,.545,.802,.235)
        regions += [{'bounds':[.01,.79,.81,.138],'documents':['hands'],'split':'COLUMNS'},copy.deepcopy(sidebar)]
        variants.append({'id':f'players-{count}','minPlayers':count,'maxPlayers':count,'widgets':widgets,'regions':regions,
                         'renderers':{'PHASES_ACTIVE':'PHASES_OVERVIEW',**{k:'ZONE_BUTTON' for k in widgets if '.ZONE_' in k}},
                         'visibility':{f'FIELD_{i}.STATUS':'STATUS_NONEMPTY' for i in range(count)},
                         'anchors':{f'FIELD_{i}.AVATAR_IMAGE':{'aspectRatio':1,'maxHeight':120} for i in range(count)}})
    c['experience']={'defaults':{'fontScale':1,'handWidth':145,'panelOpacity':1,'decorationOpacity':.7,'layoutMode':'AUTO'},'variants':variants}
    (out/'match-ui.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (out/'README.md').write_text('''# 暮辉星台 · v5 功能示例

继承暮辉秘境素材与字体许可，展示桌面皮肤 v5；不是 Android 皮肤。

- 宽屏 / 1440 以下紧凑模式，以及 3～8 人独立布局。
- 头像多边形轮廓、等比例锚点、独立阶段状态标记。
- 双人阶段按钮上半对手 / 下半自己；多人每位玩家独立一组阶段。
- 生物居中靠近双方战场交界；地牌左、其他永久物右，各自不越过分界线。
- 两排按实际内容紧凑排列，空间不足时适度缩小；大量永久物仍保留滚动，避免无限缩小。
- 右上通过标签切换卡图 / 卡牌详情 / 战斗等内容，规则文字不再挤进底部细条。
- 右下有待结算内容时显示堆叠，结算完成后显示日志，不为空堆叠浪费整块空间。
- 堆叠、日志浮窗支持标题拖动、右下角缩放，右键收起/锁定/恢复。
- 布局 → 对战界面 → 皮肤个人设置：字号、手牌、面板/装饰透明度、紧凑模式。
- 布局制作提供几何编辑与草稿导出，不会改动安装包。

建议至少 1280×720；6～8 人建议 1920×1080 或更大。大量永久物会滚动，几何测试不能代替实际点击验收。
多副可见手牌仍回退安全布局，不会遮蔽被控制玩家的手牌。
更新同名作者 ID 的皮肤保留个人设置。需要观察完整默认外观时使用“重置个人设置”。
''',encoding='utf-8')
    return c


if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--base',required=True,type=Path); p.add_argument('--out',required=True,type=Path)
    a=p.parse_args(); build(a.base,a.out)
