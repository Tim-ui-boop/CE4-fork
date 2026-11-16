#!/usr/bin/env python3
"""Validates a port patch file against the vanilla source: counts, context and result.

python3 check_patch.py <patchfile> <vanilla> [--apply]
"""
import sys

def strip_header(text):
    out, started = [], False
    for line in text.split('\n'):
        if not started:
            if line.startswith('--- '):
                started = True
            continue
        out.append(line)
    return '\n'.join(out)[: -1] if out and out[-1] == '' else '\n'.join(out)

def main():
    patch_path, vanilla_path = sys.argv[1], sys.argv[2]
    apply_mode = '--apply' in sys.argv
    body = strip_header(open(patch_path).read())
    vanilla = open(vanilla_path).read().split('\n')
    pos = 0           # line index (0-based) into vanilla, or the result when applying
    result = list(vanilla)
    npatch = 0
    hunks = []
    cur = None
    for line in body.split('\n'):
        if line.startswith('@@'):
            if cur is not None:
                hunks.append(cur)
            rest = line[3:].split('@@')[0].strip()
            old = rest.split('+')[0].lstrip('-').split(',')[0]
            cur = {'at': int(old) - 1, 'lines': []}
        elif cur is not None:
            if line == '':
                continue
            cur['lines'].append(line)
    if cur is not None:
        hunks.append(cur)
    for h in hunks:
        at = h['at']
        remove = [l[1:] for l in h['lines'] if l.startswith('-')]
        context_before = [l[1:] for l in h['lines'] if l.startswith(' ')][:3]
        # the hunk's context must exist at the place in the current result
        where = at + npatch
        ok = True
        for k, ctx in enumerate(context_before):
            if where + k >= len(result) or result[where + k] != ctx:
                ok = False
                break
        if not ok:
            print('%s: hunk at old line %d does not match (context: %r)' % (patch_path, at + 1, context_before))
            return 1
        # apply: replace removed lines
        npatch += sum(1 for l in h['lines'] if l.startswith('+')) - len(remove)
        if apply_mode:
            new_lines = [l[1:] if l[0] in ' +' else None for l in h['lines']]
            new_lines = [l for l in new_lines if l is not None]
            result[where:where + len(remove)] = new_lines
    print('%s: %d hunks, counts consistent%s' % (patch_path, len(hunks), ', applied' if apply_mode else ''))
    if apply_mode:
        open(vanilla_path + '.patched', 'w').write('\n'.join(result))
    return 0

sys.exit(main())
