"""Render original, explicitly labelled README interaction diagrams (not recordings).
Usage: python render_demos.py --font /path/to/NotoSansCJK-Regular.ttc
Requires Pillow; uses fictional examples only. No network, app access or private data.
"""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFont

P = argparse.ArgumentParser()
P.add_argument('--font', required=True)
A = P.parse_args()
OUT = Path(__file__).resolve().parent
W, H = 900, 520
BG, INK, MUTED = '#F1F5FA', '#17263E', '#52647B'
def font(n): return ImageFont.truetype(A.font, n, index=2)
def frame(title, step, count, subtitle):
    im = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((16,16,884,504), 24, fill='white')
    d.text((40,30), title, font=font(30), fill=INK)
    d.text((40,78), subtitle, font=font(21), fill=MUTED)
    d.text((750,38), f'{step} / {count}', font=font(23), fill='#3168C8')
    d.line((40,118,860,118), fill='#DFE7F1', width=2)
    d.rounded_rectangle((40,462,860,493), 8, fill='#EDF2F8')
    d.text((55,465),'交互示意 · 非实机录屏 · 虚构内容 · 不代表设备验收',font=font(18),fill=MUTED)
    return im,d

def text(d,xy,s,n=25,c=INK):d.text(xy,s,font=font(n),fill=c)
def box(d,r,fill='#F1F5FB',outline=None):d.rounded_rectangle(r,14,fill=fill,outline=outline,width=2)
def badge(d,xy,s):
    x,y=xy; width=int(d.textlength(s,font=font(21)))+26
    box(d,(x,y,x+width,y+36),'#E4EDFC');text(d,(x+13,y+3),s,21,'#2D5EAC')
def save(name,frames):
    seq=[];dur=[]
    for i,f in enumerate(frames):
        seq.append(f);dur.append(1500)
        nxt=frames[(i+1)%len(frames)]
        for t in (0.25,0.5,0.75):seq.append(Image.blend(f,nxt,t));dur.append(80)
    seq[0].save(OUT/name,save_all=True,append_images=seq[1:],duration=dur,loop=0,optimize=True,disposal=2)
    return seq[0]

frames=[]
for i in range(5):
    im,d=frame('长会话查找 / 模型选择',i+1,5,['查找当前会话的完整消息正文','继续加载更早记录，不只搜已显示内容','上一处 / 下一处，切换高亮预览','选择匹配项，定位原消息','名称放不下时，改用可搜索选择面板'][i])
    if i<4:
        box(d,(46,140,854,196),'#F1F6FF');text(d,(65,151),'查找：离线模型',26);text(d,(630,154),['0 / …','2 / 5','3 / 5','3 / 5'][i],23,'#3168C8');text(d,(745,150),'↑  ↓',26)
        box(d,(60,218,840,350));text(d,(84,236),['正在检索消息正文……','找到早期记录中的匹配','下一处：模型管理说明','原消息 · 已定位'][i],25)
        if i>0:
            text(d,(84,282),'可以重新导入',26);box(d,(257,283,377,322),'#FFE69B');text(d,(261,282),'离线模型',26);text(d,(388,282),'，保留原文件。',26)
        badge(d,(65,384),['仅当前会话 · 不搜索图片文字','加载历史 → 更新计数','逐处匹配 → 显示上下文','点击定位 → 回到原消息'][i])
    else:
        box(d,(50,140,850,191));text(d,(73,149),'搜索模型或服务商',25,MUTED)
        box(d,(50,211,850,323),'#E8F0FD');text(d,(75,226),'示例服务商 / Example Long Model Name',25);text(d,(75,270),'完整名称自然换行，不裁掉尾部',24,'#3168C8')
        badge(d,(65,384),'此处是模型菜单；会话统计未作变更')
    frames.append(im)
firsts=[save('chat-search.gif',frames)]

frames=[]
for i in range(5):
    im,d=frame('本机离线 / 模型管理',i+1,5,['八款 profile，集中在一个本机入口','用户主动下载或导入，权重不打包','确认删除指定模型的已安装权重','删除范围清楚，不移除识别引擎','保留导入能力，需要时可以重新安装'][i])
    if i in (0,1,4):
        box(d,(50,141,850,223),'#E8F0FD');text(d,(76,153),'SenseVoice Small Q8_0',27);text(d,(76,190),'唯一推荐 · 其他七款可选',20,MUTED)
        box(d,(50,241,850,342));text(d,(76,256),'Qwen3-ASR / SenseVoice / Vosk',25);text(d,(76,300),['已有有效选择不强制迁移','下载 / 导入：由用户主动发起','原始 ZIP / GGUF 与引擎仍保留'][[0,1,4].index(i)],23,MUTED)
        badge(d,(65,387),['按需安装，不内置模型','识别失败，不静默切到云端','重新导入 → 校验 → 可用'][[0,1,4].index(i)])
    else:
        box(d,(85,142,815,356),'#FFF7E8', '#EBD9B5');text(d,(111,158),'删除 Vosk 小模型的已安装权重？',27);text(d,(111,215),'仅删除对应私有权重与安装元数据',24);text(d,(111,259),'保留：引擎、其他模型、原始 ZIP / GGUF',23);text(d,(i==2 and 508 or 298,306),'取消      确认删除' if i==2 else '需等待真实服务确认，结果不明不报成功',22,'#8B5715')
        badge(d,(65,387),'录音 / 加载 / 识别期间拒绝删除' if i==2 else '空闲缓存先释放，再执行安全删除')
    frames.append(im)
firsts.append(save('offline-models.gif',frames))

frames=[]
for i in range(5):
    im,d=frame('消息操作 / 离线朗读',i+1,5,['统一用户消息与助手回复的操作菜单','原位选择需要引用的一句话','保留输入草稿，不自动发送引用','保存记忆与保存技能，独立预览确认','消息朗读暂停后续读，支持进度定位'][i])
    if i==0:
        for row,items in enumerate([['复制','选取文字','朗读','分享','删除'],['引用追问','保存记忆','保存技能','新对话继续','编辑 / 重生成']]):
            for col,t in enumerate(items):
                x=44+col*164;y=157+row*90;box(d,(x,y,x+154,y+72));text(d,(x+9,y+22),t,20)
        badge(d,(65,383),'同一套菜单 · 不把保存入口合并')
    elif i==1:
        box(d,(50,150,850,296));text(d,(75,174),'这是虚构的演示消息。',26);box(d,(72,222,552,265),'#FFE69B');text(d,(78,224),'只引用这句话，不带入整段正文。',26)
        text(d,(80,330),'复制     朗读     追问     完成',25,'#3168C8')
    elif i==2:
        box(d,(50,149,850,341));text(d,(77,167),'引用：只引用这句话……',25,'#3168C8');text(d,(77,231),'原有草稿：请帮我解释一下',26);text(d,(77,292),'保留草稿，等待用户继续编辑',23,MUTED)
        badge(d,(65,383),'不自动发送 · 不修改原消息')
    elif i==3:
        box(d,(60,149,840,344));text(d,(86,167),'保存记忆 · 可编辑预览',27);text(d,(86,228),'示例：将信息整理为简洁要点。',25);text(d,(86,290),'取消                              确认保存',25,'#3168C8')
        badge(d,(65,383),'明确确认 → 实际写入 → 读回核对')
    else:
        box(d,(55,149,845,347));text(d,(83,165),'消息离线音频播放器',27);text(d,(83,223),'播放 → 暂停 → 从断点继续',25);d.line((88,292,805,292),fill='#CAD7E8',width=7);d.line((88,292,430,292),fill='#3168C8',width=7);d.ellipse((418,280,442,304),fill='#3168C8');text(d,(84,315),'进度定位 / 分句控制',19,MUTED)
        badge(d,(65,383),'需要已安装的离线音色 · 不暗中转云端')
    frames.append(im)
firsts.append(save('message-actions.gif',frames))

frames=[]
for i in range(4):
    im,d=frame('保留小布 / 超级小爱接管',i+1,4,['保留熟悉的厂商助手界面入口','桥接核对真实调用身份与签名','通过校验后交给 Eta 的运行时执行','准备期间取消，不在取消后继续启动'][i])
    labels=['小布 / 超级小爱','Eta 鉴权桥接','Eta Runtime']
    for j,label in enumerate(labels):
        y=142+j*88;active=(i==j) or (i==3 and j==1)
        box(d,(130,y,770,y+60),'#E4EEFF' if active else '#F0F4F9', '#3168C8' if active else None)
        text(d,(i==3 and j==1 and 318 or 325,y+12),'取消保护' if i==3 and j==1 else label,26,'#3168C8' if active else INK)
        if j<2:text(d,(438,y+58),'↓',23,MUTED)
    badge(d,(65,414),['原界面入口保留，不是新增官方背书','身份校验失败 → 拒绝执行','依赖对应 ROM / 权限 / Hook 配置','实机兼容仍待验收，不承诺全机型'][i])
    frames.append(im)
firsts.append(save('vendor-entry.gif',frames))
# Contact sheet for visual QA, outside the repository.
preview=Image.new('RGB',(W*2,H*2),BG)
for i,f in enumerate(firsts):preview.paste(f,((i%2)*W,(i//2)*H))
preview.save(OUT.parents[3]/'readme-preview.png')
for p in OUT.glob('*.gif'):
    with Image.open(p) as im: print(p.name,p.stat().st_size,im.size,im.n_frames)
