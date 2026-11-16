#!/usr/bin/env python3
"""One-off check: every re4_log/snprintf/logLine call in enemy_random.cpp passes
one argument per format specifier (the missing-args crash class of 17.11.2026)."""
import re

SRC = 'port/whole/runtime/enemy_random.cpp'
src = open(SRC).read()


def split_args(src, open_paren):
    depth = 0
    args = []
    cur = []
    i = open_paren
    while i < len(src):
        c = src[i]
        if c == '(':
            depth += 1
            if depth > 1:
                cur.append(c)
        elif c == ')':
            depth -= 1
            if depth == 0:
                args.append(''.join(cur))
                return args, i
            cur.append(c)
        elif c == ',' and depth == 1:
            args.append(''.join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    return args, i


bad = 0
for m in re.finditer(r'\b(re4_log|snprintf|logLine)\s*\(', src):
    args, _ = split_args(src, m.end() - 1)
    name = m.group(1)
    if name == 're4_log':
        fmt_i, skip = 0, 0
    elif name == 'logLine':
        fmt_i, skip = 0, 0
    else:
        fmt_i, skip = 2, 2
    if len(args) <= fmt_i:
        continue
    fmt = args[fmt_i]
    quoted = re.findall(r'"(?:[^"\\]|\\.)*"', fmt)
    fmt_text = ''.join(q[1:-1] for q in quoted)
    spec = re.findall(r'%(?!%)[0-9# +\'\-\.]*[dioxXufFeEgGaAcspn]', fmt_text)
    nfmt = len(spec)
    narg = len(args) - 1 - skip
    if nfmt > narg:
        line = src[:m.start()].count('\n') + 1
        print('MISMATCH line %d %s: format wants %d, has %d args' % (line, name, nfmt, narg))
        bad += 1
print('%d mismatch(es)' % bad)
