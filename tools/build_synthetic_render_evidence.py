#!/usr/bin/env python3
"""Extract auditable transition/text control frames and media metadata."""
from __future__ import annotations
import csv, hashlib, json, math, os, shutil, subprocess
from pathlib import Path
import cv2
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "render-evidence/synthetic"
T3_REF = ROOT / "test-fixtures/t3-reference-v0.4"
T4_ZIP = ROOT / "vitrine-video-app/src/main/assets/packages/COMMERCE-T4-1.0.0.zip"


def sha(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def ffprobe(video: Path) -> dict:
    binary=os.environ.get('FFPROBE_BIN') or shutil.which('ffprobe')
    if not binary:
        raise RuntimeError('ffprobe is required to produce requested stream metadata')
    text=subprocess.check_output([binary,'-v','error','-show_entries','format=filename,format_name,duration,size,bit_rate:stream=index,codec_name,codec_type,width,height,r_frame_rate,avg_frame_rate,nb_frames,duration,sample_rate,channels','-of','json',str(video)],text=True)
    data=json.loads(text)
    (video.parent/(video.name+'.ffprobe.json')).write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    return data


def add_samples(rows, category, identity, source, variant, motion, frames, details=''):
    for frame,label in frames:
        rows.append({'frame':int(frame),'category':category,'id':identity,'source_or_motion':source,'variant_or_motion':variant,'sample':label,'details':details})


def extract(video:Path, outdir:Path, rows:list[dict], tag:str):
    byframe={}
    for row in rows:byframe.setdefault(row['frame'],[]).append(row)
    target=outdir/'frames'
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True,exist_ok=True)
    cap=cv2.VideoCapture(str(video))
    if not cap.isOpened():raise RuntimeError(f'Cannot open {video}')
    thumbs=[]
    try:
        for frame in sorted(byframe):
            cap.set(cv2.CAP_PROP_POS_FRAMES,frame)
            ok,arr=cap.read()
            if not ok:raise RuntimeError(f'Missing {tag} frame {frame}')
            path=target/f'frame-{frame:04d}.jpg'
            if not cv2.imwrite(str(path),arr,[cv2.IMWRITE_JPEG_QUALITY,92]):raise RuntimeError(f'JPEG write failed: {path}')
            rgb=cv2.cvtColor(arr,cv2.COLOR_BGR2RGB)
            image=Image.fromarray(rgb).resize((180,320),Image.Resampling.LANCZOS)
            canvas=Image.new('RGB',(180,368),(17,17,17));canvas.paste(image,(0,28))
            d=ImageDraw.Draw(canvas)
            try:font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',11)
            except OSError:font=ImageFont.load_default()
            kinds=','.join(sorted({r['category'] for r in byframe[frame]}))
            d.text((4,4),f'{tag} f{frame} · {frame/30:.2f}s',font=font,fill='white')
            d.text((4,16),kinds[:27],font=font,fill=(255,209,102))
            thumbs.append((frame,canvas))
    finally:cap.release()
    cols=5;cellw,cellh=180,368;rowsn=math.ceil(len(thumbs)/cols)
    sheet=Image.new('RGB',(cols*cellw,rowsn*cellh),(17,17,17))
    for i,(frame,im) in enumerate(thumbs):sheet.paste(im,((i%cols)*cellw,(i//cols)*cellh))
    sheet.save(outdir/'contact-sheet.jpg',quality=92)
    with (outdir/'frame-map.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=['frame','time_seconds','category','id','source_or_motion','variant_or_motion','sample','details'],lineterminator='\n')
        writer.writeheader()
        for r in sorted(rows,key=lambda x:(x['frame'],x['category'],x['id'],x['sample'])):
            writer.writerow({**r,'time_seconds':f"{r['frame']/30:.6f}"})


def t3():
    out=BASE/'T3';video=next(out.glob('*.mp4'))
    contract=json.loads((T3_REF/'reverse-engineering/T3_TRANSITIONS_30S.json').read_text())
    cfg=json.loads((T3_REF/'config/T3_TEXT_MOTION_V0_4_0.json').read_text())
    rows=[]
    for e in contract['timeline_events']:
        start,end=e['frames'];mid=(start+end)//2
        add_samples(rows,'transition',str(e['event']),e['transition_id'],e.get('variant',''),'',[(start,'start'),(mid,'middle'),(end,'end')],e['parameter_profile'])
    for g in cfg['graphics']:
        start,reveal,end=g['start_f'],g['reveal_end_f'],g['end_f_exclusive']
        frames=[(start,'entry_start'),((start+reveal-1)//2,'reveal_middle'),(reveal-1,'reveal_end'),(end-1,'window_end')]
        add_samples(rows,'text_motion',g['id'],g['motion'],g['motion'],g['motion'],frames,','.join(g['fields']))
    metadata=ffprobe(video)
    extract(video,out,rows,'T3')
    report=json.loads((out/'T3_SILENT_RENDER_REPORT.json').read_text())
    assert len(contract['timeline_events'])==17 and report.get('all_17_events_applied') is True
    assert len(cfg['graphics'])==4 and report.get('all_4_graphics_applied') is True
    assert metadata['streams'][0]['width']==720 and metadata['streams'][0]['height']==1280
    (out/'EVIDENCE_SCOPE.json').write_text(json.dumps({
        'template':'RESTAURATION-T3','render_mode':'Python reference renderer, host-side synthetic engineering clips',
        'video':video.name,'video_sha256':sha(video),'size_bytes':video.stat().st_size,
        'input_fixture_sha256':{f'S{i}':sha(ROOT/f'test-fixtures/t3_fixture/captures/S{i}.mp4') for i in range(1,5)},
        'ffprobe':metadata,'transitions':17,'text_motion_groups':4,
        'transition_execution_counts':{str(e['event']):next(r['applied_frame_count'] for r in report['events'] if r['event']==e['event']) for e in contract['timeline_events']},
        'all_transition_events_executed':report['all_17_events_applied'],'all_text_groups_executed':report['all_4_graphics_applied'],
        'important_limit':'This proves execution by the checked-in Python reference renderer, not by Android Media3/GL. Candidate transition profile is explicitly unapproved in its source JSON; it is not an approval of exact transition fidelity.'
    },ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def t4():
    out=BASE/'T4';video=out/'Promotion-01.mp4'
    proof=json.loads((out/'preuve.json').read_text())
    plan=proof['partition'] if 'partition' in proof else None
    # The renderer's proof includes both cut records and text windows. Read the
    # canonical partition shipped in the embedded T4 package for stable mapping.
    import zipfile
    with zipfile.ZipFile(T4_ZIP) as z:
        partition=json.loads(z.read('montage/partition.json'))
    rows=[]
    cuts=partition['effets']['coupes']
    for i,c in enumerate(cuts):
        frame=int(c['image'])
        add_samples(rows,'camera_cut',str(i+1),c['cadre'],c['cadre'],'hard_cut',[(max(0,frame-1),'before'),(frame,'cut_frame')],f"shot={c['image']}")
    for i,w in enumerate(partition['texte']['fenetres']):
        start,end=int(w['debut']),int(w['fin'])
        frames=[(start,'window_start'),((start+end-1)//2,'window_middle'),(end-1,'window_end')]
        add_samples(rows,'text_window',str(i+1),w['type'],w['type'],'contract_window',frames,f"white={w.get('blanc','')};accent={w.get('accent','')}")
    metadata=ffprobe(video)
    extract(video,out,rows,'T4')
    assert metadata['streams'][0]['width']==720 and metadata['streams'][0]['height']==1280
    assert len(cuts)==26 and len(partition['texte']['fenetres'])==7
    (out/'EVIDENCE_SCOPE.json').write_text(json.dumps({
        'template':'COMMERCE-T4','render_mode':'COMMERCE-T4 package Python reference renderer, host-side synthetic engineering clips',
        'video':video.name,'video_sha256':sha(video),'size_bytes':video.stat().st_size,
        'input_fixture_sha256':{f'S{i}':sha(ROOT/f'test-fixtures/t3_fixture/captures/S{i}.mp4') for i in range(1,5)},
        'ffprobe':metadata,'camera_cut_events':len(cuts),'text_windows':len(partition['texte']['fenetres']),
        'important_limit':'This uses the actual packaged T4 Python renderer and exact package typography/timeline, but engineering fixture clips, not founder footage. T4 source contains hard cuts and camera-cut edits, not 17 T3 transition profiles.'
    },ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def checksums():
    files=sorted(p for p in BASE.rglob('*') if p.is_file() and p.name!='SHA256SUMS.txt')
    (BASE/'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.relative_to(BASE).as_posix()}\n' for p in files),encoding='utf-8')
    # Verify the just-written list rather than trusting the writer.
    for line in (BASE/'SHA256SUMS.txt').read_text().splitlines():
        digest,rel=line.split('  ',1)
        assert sha(BASE/rel)==digest,rel


if __name__=='__main__':
    t3();t4();checksums()
    print('SYNTHETIC_EVIDENCE_PASS T3 transitions=17 text_groups=4; T4 cuts=26 text_windows=7; videos=2; checksums=verified')
