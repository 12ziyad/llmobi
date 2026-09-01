"""Rewrite Catalog.kt so every fileBytes and sizeLabel matches the real file.

Estimated sizes drift from reality as publishers re-quantise, and an overstated
size makes the compatibility check reject models a phone could actually run.
This asks the CDN and writes back exactly what it says.

  python tools/fix_catalog_sizes.py
"""
import io
import re
import urllib.request

SRC = 'app/src/main/java/app/llmobi/data/Catalog.kt'
STR_RE = '"((?:[^"\\\\]|\\\\.)*)"'


def head(url):
    req = urllib.request.Request(url, method='HEAD',
                                 headers={'User-Agent': 'llmobi-catalog-fix/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            return r.status, int(r.headers.get('Content-Length') or 0)
    except Exception as e:
        return getattr(e, 'code', 0) or 0, 0


def size_label(n):
    """The string a person reads. One decimal is plenty; nobody needs megabytes."""
    g = n / 1073741824
    return f'{g:.1f} GB' if g >= 1 else f'{g:.2f} GB'.replace('0.', '0.')


def main():
    src = io.open(SRC, encoding='utf-8').read()
    blocks = re.findall(r'(ModelEntry\(.*?\n        \),)', src, re.S)
    print(f'checking {len(blocks)} models')

    out = src
    changed = 0
    for block in blocks:
        mid = re.search('id = ' + STR_RE, block)
        url = re.search('url = ' + STR_RE, block)
        if not (mid and url):
            continue
        status, real = head(url.group(1))
        if status != 200 or real <= 0:
            print(f'  {mid.group(1):<16} SKIPPED (http {status})')
            continue

        new_block = re.sub(r'fileBytes = [^,]+,', f'fileBytes = {real}L,', block)
        new_block = re.sub('sizeLabel = ' + STR_RE,
                           f'sizeLabel = "{size_label(real)}"', new_block)

        if new_block != block:
            out = out.replace(block, new_block)
            changed += 1
            print(f'  {mid.group(1):<16} -> {size_label(real)} ({real} bytes)')
        else:
            print(f'  {mid.group(1):<16} already correct')

    if changed:
        io.open(SRC, 'w', encoding='utf-8').write(out)
    print(f'updated {changed} models')


if __name__ == '__main__':
    main()
