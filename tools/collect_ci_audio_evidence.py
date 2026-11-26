#!/usr/bin/env python3
"""Copy and re-verify the production Kotlin synthetic-DSP test artifact."""
from __future__ import annotations
import hashlib, json, shutil
from pathlib import Path
from tools.build_synthetic_render_evidence import BASE, checksums

ROOT=Path(__file__).resolve().parents[1]
STAGE=BASE/'audio-ci'
DEST=BASE/'audio'


def sha(path:Path)->str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()


def unique(name:str)->Path:
    matches=list(STAGE.rglob(name))
    if len(matches)!=1 or not matches[0].is_file() or matches[0].stat().st_size==0:
        raise RuntimeError(f'Expected exactly one non-empty {name} in junit-reports artifact; found {len(matches)}')
    return matches[0]


def main()->None:
    report_src=unique('T3_SYNTHETIC_AUDIO_REPORT.json')
    wav_src=unique('T3_SYNTHETIC_VOICE_PRIORITY.wav')
    report=json.loads(report_src.read_text(encoding='utf-8'))
    wav_sha=sha(wav_src)
    assert report['status']=='PASS_SYNTHETIC_DSP_ONLY'
    assert report['audio_file']['sha256']==wav_sha
    m=report['measured']
    assert m['phrase_voice_music_tone_margin_db']>=3.8
    assert m['phrase_music_to_baseline_ratio']<0.45
    assert m['short_pause_after_voice_music_ratio']<0.7
    assert m['post_release_music_tone_amplitude']>m['late_pause_music_tone_amplitude']*1.15
    assert m['max_peak_dbfs']<=-1.95
    DEST.mkdir(parents=True,exist_ok=True)
    report_dst=DEST/report_src.name;wav_dst=DEST/wav_src.name
    shutil.copy2(report_src,report_dst);shutil.copy2(wav_src,wav_dst)
    (DEST/'SYNTHETIC_AUDIO_SCOPE.txt').write_text(
        'Output from the production Kotlin AudioDuckingEngine unit test.\n'
        'Voice-like input is a deterministic harmonic surrogate, not speech.\n'
        'Spectral priority is an intelligibility proxy, not a human-language intelligibility claim.\n'
        'This test does not cover Media3 AAC end-to-end or phone acoustics.\n',encoding='utf-8')
    (DEST/'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in [wav_dst,report_dst,DEST/'SYNTHETIC_AUDIO_SCOPE.txt']),encoding='utf-8')
    shutil.rmtree(STAGE)
    checksums()
    print('SYNTHETIC_AUDIO_EVIDENCE_PASS',json.dumps(m,sort_keys=True))

if __name__=='__main__':main()
