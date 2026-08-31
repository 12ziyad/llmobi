"""Generate api/seed.sql from the Kotlin catalog.

The app ships a bundled catalog so the store works offline on first launch, and
the Worker serves the same list from D1. Generating one from the other means the
two can never quietly disagree.
"""
import io
import re

SRC = 'app/src/main/java/app/llmobi/data/Catalog.kt'
OUT = 'api/seed.sql'

src = io.open(SRC, encoding='utf-8').read()
blocks = re.findall(r'ModelEntry\((.*?)\n        \)', src, re.S)
print('found', len(blocks), 'models')

STR_RE = '"((?:[^"\\\\]|\\\\.)*)"'


def field(block, name):
    m = re.search(name + r'\s*=\s*' + STR_RE, block)
    if m:
        return m.group(1)
    m = re.search(name + r'\s*=\s*([^,\n]+)', block)
    return m.group(1).strip().rstrip(',').strip() if m else None


def file_bytes(block):
    v = field(block, 'fileBytes') or '0'
    m = re.match(r'gb\(([0-9.]+)\)', v)
    if m:
        return int(float(m.group(1)) * 1073741824)
    return int(v.replace('L', '').replace('_', ''))


def hexcolor(v):
    return '#%06X' % (int(v, 16) & 0xFFFFFF)


rows = []
for b in blocks:
    url = field(b, 'url') or ''
    m = re.match(r'https://huggingface\.co/([^/]+/[^/]+)/resolve/main/(.+)$', url)
    repo, hf_file = (m.group(1), m.group(2)) if m else ('', '')
    rows.append({
        'id': field(b, 'id'),
        'name': field(b, 'name'),
        'tagline': field(b, 'tagline'),
        'tier': (field(b, 'tier') or 'Tier.TINY').split('.')[-1].lower(),
        'category': field(b, 'category'),
        'icon': field(b, 'iconLetter'),
        'cs': hexcolor(field(b, 'colorStart')),
        'ce': hexcolor(field(b, 'colorEnd')),
        'size': field(b, 'sizeLabel'),
        'speed': field(b, 'speedHint'),
        'fb': file_bytes(b),
        'ram': int(field(b, 'minRamMb')),
        'ctx': int(field(b, 'ctxDefault')),
        'arch': field(b, 'arch'),
        'quant': field(b, 'quant'),
        'repo': repo,
        'hf_file': hf_file,
        'url': url,
        'lic': field(b, 'license'),
    })


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


lines = [
    '-- LLMobi catalog seed.',
    '-- Generated from Catalog.kt by tools/gen_seed.py - do not hand-edit.',
    '',
]
for r in rows:
    lines.append(
        'INSERT OR REPLACE INTO models '
        '(id,name,tagline,tier,category,icon_letter,color_start,color_end,'
        'size_label,speed_hint,file_bytes,min_ram_mb,ctx_default,arch,quant,'
        'hf_repo,hf_file,url,license,status,updated_at) VALUES ('
        + ','.join([
            q(r['id']), q(r['name']), q(r['tagline']), q(r['tier']), q(r['category']),
            q(r['icon']), q(r['cs']), q(r['ce']), q(r['size']), q(r['speed']),
            str(r['fb']), str(r['ram']), str(r['ctx']), q(r['arch']), q(r['quant']),
            q(r['repo']), q(r['hf_file']), q(r['url']), q(r['lic']), "'live'", '0',
        ])
        + ');'
    )

io.open(OUT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('wrote', OUT, 'with', len(rows), 'rows')
