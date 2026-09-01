"""Verify every catalog URL resolves, and report its real size.

Run this before shipping a catalog change. A dead link in the store is worse
than a missing model: the user taps Install, waits, and gets nothing.

  python tools/check_catalog.py            # check what the app ships
  python tools/check_catalog.py --q4_0     # also probe the Q4_0 variant
"""
import io
import re
import sys
import urllib.request

SRC = 'app/src/main/java/app/llmobi/data/Catalog.kt'
STR_RE = '"((?:[^"\\\\]|\\\\.)*)"'


def field(block, name):
    m = re.search(name + r'\s*=\s*' + STR_RE, block)
    if m:
        return m.group(1)
    m = re.search(name + r'\s*=\s*([^,\n]+)', block)
    return m.group(1).strip().rstrip(',').strip() if m else None


def head(url):
    """HEAD, following redirects, returning (status, content_length)."""
    req = urllib.request.Request(url, method='HEAD',
                                 headers={'User-Agent': 'llmobi-catalog-check/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            return r.status, int(r.headers.get('Content-Length') or 0)
    except Exception as e:
        code = getattr(e, 'code', None)
        return (code or 0), 0


def gb(n):
    return n / 1073741824


def main():
    also_q40 = '--q4_0' in sys.argv
    src = io.open(SRC, encoding='utf-8').read()
    blocks = re.findall(r'ModelEntry\((.*?)\n        \)', src, re.S)

    print(f'{"MODEL":<16} {"QUANT":<8} {"STATUS":<8} {"REAL GB":>8} {"LISTED":>8}  DELTA')
    print('-' * 68)

    bad = 0
    for b in blocks:
        mid = field(b, 'id')
        quant = field(b, 'quant')
        url = field(b, 'url')
        listed = field(b, 'fileBytes') or '0'
        m = re.match(r'gb\(([0-9.]+)\)', listed.strip())
        listed_bytes = int(float(m.group(1)) * 1073741824) if m else int(
            listed.replace('L', '').replace('_', ''))

        status, size = head(url)
        ok = status == 200 and size > 0
        if not ok:
            bad += 1
        delta = ''
        if ok and listed_bytes:
            d = (size - listed_bytes) / listed_bytes * 100
            delta = f'{d:+.0f}%' + ('  <-- FIX' if abs(d) > 5 else '')

        print(f'{mid:<16} {quant:<8} {status:<8} {gb(size):>8.2f} '
              f'{gb(listed_bytes):>8.2f}  {delta}')

        if also_q40 and 'Q4_0' not in (quant or ''):
            q40 = re.sub(r'[Qq]4_[Kk]_[Mm]|[Qq]5_[Kk]_[Mm]|[Qq]6_[Kk]', 'Q4_0', url)
            q40 = q40.replace('q4_k_m', 'q4_0').replace('q5_k_m', 'q4_0').replace('q6_k', 'q4_0')
            if q40 != url:
                s2, sz2 = head(q40)
                flag = 'available' if s2 == 200 else 'not published'
                print(f'{"":<16} {"Q4_0":<8} {s2:<8} {gb(sz2):>8.2f} {"":>8}  {flag}')

    print('-' * 68)
    print(f'{len(blocks)} models, {bad} broken')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
