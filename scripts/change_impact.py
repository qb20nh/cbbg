"""Select development checks from changed paths and target dependencies."""

import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess

from targets import ROOT, build_matrix, select_ci_targets


def select_checks(catalog, paths):
    targets = catalog['targets']
    all_ids = {target['id'] for target in targets}
    owners = {target['id']: target.get('artifactOf', target['id']) for target in targets}
    by_id = {target['id']: target for target in targets}
    affected = set()
    checks = set()
    changes = []
    for path in sorted(set(paths)):
        parts = PurePosixPath(path).parts
        if not path or path.startswith('/') or '..' in parts or '\\' in path:
            raise ValueError('Expected a repository-relative path: ' + path)
        selected = set()
        if path.startswith('docs/') or path in {'README.md', 'CHANGELOG.md'}:
            reason = 'documentation'
            checks.add('documentation')
        elif path.startswith(('build-config/publishing/', '.github/scripts/', '.github/tests/')):
            reason = 'publication tooling'
            checks.add('publication')
        else:
            checks.update({'catalog', 'compile', 'unit', 'runtime'})
            if path.startswith('core/'):
                reason = 'shared core'
                selected = all_ids.copy()
                checks.add('core-java-8-17-21-25')
            else:
                for target in targets:
                    owner = by_id[owners[target['id']]]
                    prefixes = list(owner.get('sourceGroups', []))
                    prefixes.append('renderers/' + owner['renderer'])
                    if owner.get('buildProfile'):
                        prefixes.append('build-config/' + owner['buildProfile'])
                    if any(path.startswith(prefix + '/') for prefix in prefixes):
                        selected.add(target['id'])
                if selected:
                    # Planned targets without source declarations cannot be excluded.
                    selected.update(target['id'] for target in targets
                                    if not by_id[owners[target['id']]].get('sourceGroups'))
                    reason = 'catalog dependencies; includes targets with undeclared sources'
                else:
                    selected = all_ids.copy()
                    reason = 'unknown dependency; all targets selected'
                    checks.add('core-java-8-17-21-25')
            changed_owners = {owners[target] for target in selected}
            selected.update(target for target, owner in owners.items() if owner in changed_owners)
        affected.update(selected)
        changes.append({'path': path, 'reason': reason, 'targets': sorted(selected)})
    return {'changes': changes, 'targets': sorted(affected), 'checks': sorted(checks)}


def changed_paths(root, base, head):
    # Disabled rename detection includes both the removed and added locations.
    result = subprocess.run(['git', 'diff', '--name-only', '--no-renames', '-z',
                             base, head, '--'], cwd=root, check=True, capture_output=True)
    return result.stdout.decode('utf-8').rstrip('\0').split('\0') if result.stdout else []


def ci_plan(catalog, report):
    selected = [target['id'] for target in select_ci_targets(catalog)
                if target['id'] in report['targets']]
    rows = build_matrix(catalog, ','.join(selected)) if selected else []
    return {'matrix': {'include': rows}, 'build': bool(rows),
            'core': 'core-java-8-17-21-25' in report['checks']}


def catalog_at(commit):
    result = subprocess.run(['git', 'show', commit + ':targets.json'], cwd=ROOT,
                            check=True, capture_output=True, text=True)
    catalog = json.loads(result.stdout)
    if catalog.get('schema') != 1:
        raise ValueError('Unsupported target catalog schema at ' + commit)
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    try:
        def resolve(revision):
            return subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options',
                                   revision + '^{commit}'], cwd=ROOT, check=True,
                                  capture_output=True, text=True).stdout.strip()
        head_commit = resolve(args.head)
        catalog = catalog_at(head_commit)
        if args.base and set(args.base) == {'0'}:
            report = {'base': None, 'head': head_commit,
                      **select_checks(catalog, ['<initial-push>'])}
        else:
            report = compare_commits(resolve(args.base), head_commit)
        report['ci'] = ci_plan(catalog, report)
        if args.github_output:
            with args.github_output.open('a', encoding='utf-8') as output:
                for name, value in report['ci'].items():
                    output.write(name + '=' + json.dumps(value, separators=(',', ':')) + '\n')
        print(json.dumps(report, indent=2))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.error(str(error))


def compare_commits(base_commit, head_commit):
    # Both catalogs matter when dependencies or source locations change.
    paths = changed_paths(ROOT, base_commit, head_commit)
    before = select_checks(catalog_at(base_commit), paths)
    after = select_checks(catalog_at(head_commit), paths)
    return {'base': base_commit, 'head': head_commit,
            'targets': sorted(set(before['targets']) | set(after['targets'])),
            'checks': sorted(set(before['checks']) | set(after['checks'])),
            'before': before['changes'], 'after': after['changes']}


if __name__ == '__main__':
    main()
