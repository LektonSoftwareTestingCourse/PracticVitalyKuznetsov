#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import itertools
import re
import shutil
import subprocess
import sys
from math import prod
from pathlib import Path

COND_RE = re.compile(r'\[([^\]]+)\]\s*(<>|>=|<=|=|>|<)\s*"([^"]*)"')
ASSIGN_RE = re.compile(r'\[([^\]]+)\]\s*=\s*"([^"]*)"')

# --- Разбор модели ---

def parse_model(text: str):
    params: list[tuple[str, list[str]]] = []
    constraints: list[tuple[list[tuple[str, str, str]], list[tuple[str, str]]]] = []

    for raw in text.splitlines():
        line = raw.split('#', 1)[0].strip().rstrip(';').strip()
        if not line:
            continue

        if re.match(r'^IF\b', line, re.IGNORECASE):
            match = re.match(r'^IF\s+(.*?)\s+THEN\s+(.*)$', line,
                             re.IGNORECASE | re.DOTALL)
            if not match:
                raise ValueError(f'не удалось разобрать ограничение: {raw!r}')
            antecedent = _parse_conditions(match.group(1))
            consequent = _parse_assignments(match.group(2))
            constraints.append((antecedent, consequent))
        elif ':' in line:
            name, values = line.split(':', 1)
            parsed = [v.strip().strip('"') for v in values.split(',') if v.strip()]
            if not parsed:
                raise ValueError(f'параметр без значений: {raw!r}')
            params.append((name.strip(), parsed))
        else:
            raise ValueError(f'не удалось разобрать строку модели: {raw!r}')

    if not params:
        raise ValueError('в модели не найдено ни одного параметра')
    return params, constraints

def _parse_conditions(text: str):
    out = []
    for chunk in re.split(r'\s+AND\s+', text, flags=re.IGNORECASE):
        match = COND_RE.search(chunk)
        if not match:
            raise ValueError(f'не удалось разобрать условие: {chunk!r}')
        out.append((match.group(1).strip(), match.group(2), match.group(3)))
    return out

def _parse_assignments(text: str):
    out = []
    for chunk in re.split(r'\s+AND\s+', text, flags=re.IGNORECASE):
        match = ASSIGN_RE.search(chunk)
        if not match:
            raise ValueError(f'в THEN допустимы только присваивания: {chunk!r}')
        out.append((match.group(1).strip(), match.group(2)))
    return out

# --- Ограничения ---

def _compare(actual: str, op: str, expected: str) -> bool:
    if op in ('=', '=='):
        return actual == expected
    if op in ('<>', '!='):
        return actual != expected
    try:
        left, right = int(actual), int(expected)
    except ValueError:
        left, right = actual, expected
    if op == '>':
        return left > right
    if op == '<':
        return left < right
    if op == '>=':
        return left >= right
    if op == '<=':
        return left <= right
    raise ValueError(f'неизвестный оператор: {op!r}')

def partial_ok(assign: dict[str, str], constraints) -> bool:
    """Проверяет ограничения на (возможно неполном) назначении."""
    for antecedent, consequent in constraints:
        if all(p in assign and _compare(assign[p], op, value)
               for p, op, value in antecedent):
            for param, value in consequent:
                if param in assign and assign[param] != value:
                    return False
    return True

def _constraint_weight(name: str, constraints) -> int:
    weight = 0
    for antecedent, consequent in constraints:
        weight += sum(1 for p, _, _ in antecedent if p == name)
        weight += sum(1 for p, _ in consequent if p == name)
    return weight

def _dfs_order(names, constraints):
    """Сначала параметры, встречающиеся в ограничениях, — это даёт отсечение."""
    return sorted(names, key=lambda n: -_constraint_weight(n, constraints))

def _pair(p: str, v: str, q: str, w: str):
    return (p, v, q, w) if p < q else (q, w, p, v)

# --- Встроенный генератор ---

def _feasible(domains, names, constraints, fixed: dict[str, str]) -> bool:
    """Существует ли полное назначение, содержащее `fixed`."""
    assign = dict(fixed)
    if not partial_ok(assign, constraints):
        return False

    order = [n for n in _dfs_order(names, constraints) if n not in assign]

    def dfs(index: int) -> bool:
        if index == len(order):
            return True
        param = order[index]
        for value in domains[param]:
            assign[param] = value
            if partial_ok(assign, constraints) and dfs(index + 1):
                return True
            del assign[param]
        return False

    return dfs(0)

def _build_case(domains, order, constraints, uncovered) -> dict[str, str] | None:
    """Строит один тест-кейс, начиная с ещё не покрытого сочетания пар."""
    seed = min(uncovered)
    first, first_value, second, second_value = seed

    assign: dict[str, str] = {first: first_value, second: second_value}
    if not partial_ok(assign, constraints):
        return None

    remaining = [name for name in order if name not in assign]

    def dfs(index: int) -> bool:
        if index == len(remaining):
            return True
        param = remaining[index]
        scored = []
        for value in domains[param]:
            gain = 0
            for other, other_value in assign.items():
                if _pair(param, value, other, other_value) in uncovered:
                    gain += 2
            for later in remaining[index + 1:]:
                for candidate in domains[later]:
                    if _pair(param, value, later, candidate) in uncovered:
                        gain += 1
            scored.append((-gain, value))
        scored.sort()

        for _, value in scored:
            assign[param] = value
            if partial_ok(assign, constraints) and dfs(index + 1):
                return True
            del assign[param]
        return False

    return assign if dfs(0) else None

def _prune(cases, names):
    """Убирает строки, все пары которых покрыты другими строками."""
    def covered(case):
        return {_pair(p, case[p], q, case[q])
                for p, q in itertools.combinations(names, 2)}

    coverages = [covered(case) for case in cases]
    keep = list(range(len(cases)))
    for index in range(len(cases) - 1, -1, -1):
        others = set()
        for other in keep:
            if other != index:
                others |= coverages[other]
        if coverages[index] <= others:
            keep.remove(index)
    return [cases[i] for i in keep]

def generate_builtin(params, constraints, verbose=True):
    names = [name for name, _ in params]
    domains = dict(params)

    if verbose:
        print('  параметров: %d' % len(names))
        print('  ограничений: %d' % len(constraints))

    feasible_pairs = set()
    total_pairs = 0
    for p, q in itertools.combinations(names, 2):
        for v in domains[p]:
            for w in domains[q]:
                total_pairs += 1
                if _feasible(domains, names, constraints, {p: v, q: w}):
                    feasible_pairs.add(_pair(p, v, q, w))

    if verbose:
        print('  пар значений всего: %d, допустимых по ограничениям: %d'
              % (total_pairs, len(feasible_pairs)))

    order = _dfs_order(names, constraints)
    uncovered = set(feasible_pairs)
    cases: list[dict[str, str]] = []

    while uncovered:
        case = _build_case(domains, order, constraints, uncovered)
        if case is None:
            raise RuntimeError('не удалось построить покрывающий набор')
        cases.append(case)
        for p, q in itertools.combinations(names, 2):
            uncovered.discard(_pair(p, case[p], q, case[q]))
        if len(cases) > 1000:
            raise RuntimeError('превышен лимит строк набора')

    cases = _prune(cases, names)

    covered = set()
    for case in cases:
        for p, q in itertools.combinations(names, 2):
            covered.add(_pair(p, case[p], q, case[q]))

    return cases, feasible_pairs, covered

# --- Резервные инструменты ---

def generate_allpairspy(params, constraints, verbose=True):
    from allpairspy import AllPairs  # type: ignore

    names = [name for name, _ in params]
    domains = [values for _, values in params]

    def valid(row) -> bool:
        return partial_ok(dict(zip(names, row)), constraints)

    cases = [dict(zip(names, row)) for row in AllPairs(domains, filter_func=valid)]

    feasible_pairs = set()
    for p, q in itertools.combinations(names, 2):
        for v in domains[names.index(p)]:
            for w in domains[names.index(q)]:
                if _feasible(dict(params), names, constraints, {p: v, q: w}):
                    feasible_pairs.add(_pair(p, v, q, w))

    covered = set()
    for case in cases:
        for p, q in itertools.combinations(names, 2):
            covered.add(_pair(p, case[p], q, case[q]))

    return cases, feasible_pairs, covered

def generate_pict(model_path: Path, verbose=True):
    binary = shutil.which('pict')
    if not binary:
        raise RuntimeError('бинарь pict не найден в PATH')

    result = subprocess.run([binary, str(model_path)], capture_output=True,
                            text=True, check=True)
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    header = lines[0].split('\t')
    cases = [dict(zip(header, line.split('\t'))) for line in lines[1:]]

    if verbose:
        print('  инструмент: %s' % binary)

    return cases, None, None

# --- Запись результата ---

def write_cases(path: Path, names, cases):
    lines = ['\t'.join(names)]
    for case in cases:
        lines.append('\t'.join(case[name] for name in names))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def read_extra(path: Path, names, domains, constraints):
    """Читает критические сочетания, добавленные в набор вручную."""
    rows = []
    for number, raw in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = raw.split('#', 1)[0].strip()
        if not line:
            continue
        cells = [cell.strip() for cell in line.split('\t')]
        if len(cells) != len(names):
            raise ValueError('%s:%d — ожидалось %d значений, получено %d'
                             % (path, number, len(names), len(cells)))
        row = dict(zip(names, cells))
        for name, value in row.items():
            if value not in domains[name]:
                raise ValueError('%s:%d — значение %r недопустимо для параметра %s'
                                 % (path, number, value, name))
        if not partial_ok(row, constraints):
            raise ValueError('%s:%d — строка противоречит ограничениям модели'
                             % (path, number))
        rows.append(row)
    return rows

def covered_pairs(names, cases):
    covered = set()
    for case in cases:
        for p, q in itertools.combinations(names, 2):
            covered.add(_pair(p, case[p], q, case[q]))
    return covered

def combine(domains):
    return prod(len(values) for values in domains)

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description='Генератор попарного набора для моделей СМП (резервный путь).')
    parser.add_argument('model', type=Path, help='файл модели (PICT-формат)')
    parser.add_argument('output', type=Path, help='файл набора (TSV)')
    parser.add_argument('--extra', type=Path, default=None,
                        help='TSV с критичными сочетаниями, добавленными вручную')
    parser.add_argument('--tool', default='auto',
                        choices=['auto', 'builtin', 'allpairspy', 'pict'])
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    verbose = not args.quiet
    params, constraints = parse_model(args.model.read_text(encoding='utf-8'))
    names = [name for name, _ in params]
    domains = [values for _, values in params]

    if verbose:
        print('Модель: %s' % args.model)
        print('  комбинаций полного перебора: %d' % combine(domains))

    tool = args.tool
    cases = None
    feasible_pairs = covered = None

    if tool in ('auto', 'pict'):
        try:
            cases, feasible_pairs, covered = generate_pict(args.model, verbose)
        except Exception as exc:  # noqa: BLE001
            if tool == 'pict':
                print('Ошибка: %s' % exc, file=sys.stderr)
                return 1
            if verbose:
                print('  PICT недоступен (%s) — переход к резервному режиму' % exc)

    if cases is None and tool in ('auto', 'allpairspy'):
        try:
            cases, feasible_pairs, covered = generate_allpairspy(params, constraints, verbose)
            if verbose:
                print('  инструмент: allpairspy')
            if feasible_pairs - covered:
                if verbose:
                    print('  allpairspy не покрыл %d допустимых пар — '
                          'переход к встроенному генератору'
                          % len(feasible_pairs - covered))
                cases = None
        except ImportError:
            if tool == 'allpairspy':
                print('Ошибка: пакет allpairspy не установлен', file=sys.stderr)
                return 1
            if verbose:
                print('  allpairspy не установлен — переход к встроенному генератору')

    if cases is None:
        if verbose:
            print('  инструмент: встроенный жадный генератор')
        cases, feasible_pairs, covered = generate_builtin(params, constraints, verbose)

    pairwise_rows = len(cases)

    if args.extra:
        extras = read_extra(args.extra, names, dict(params), constraints)
        seen = {tuple(case[name] for name in names) for case in cases}
        added = [row for row in extras if tuple(row[name] for name in names) not in seen]
        cases.extend(added)
        if verbose:
            print('  строк попарного набора: %d' % pairwise_rows)
            print('  добавлено критичных сочетаний вручную: %d' % len(added))

    covered = covered_pairs(names, cases)
    write_cases(args.output, names, cases)

    if verbose:
        print('  строк в наборе: %d' % len(cases))
        if feasible_pairs:
            print('  покрытие допустимых пар: %.1f%% (%d из %d)'
                  % (100.0 * len(covered & feasible_pairs) / len(feasible_pairs),
                     len(covered & feasible_pairs), len(feasible_pairs)))
        print('Записано: %s' % args.output)

    return 0


if __name__ == '__main__':
    raise SystemExit(main())
