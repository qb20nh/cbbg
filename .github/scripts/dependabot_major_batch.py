"""Assemble Dependabot snapshots through GitHub APIs; never execute PR code."""

import argparse
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request

import yaml


PREFIX = 'automation/dependabot-major-batch/'
BEGIN = '<!-- dependabot-major-batch:start -->'
END = '<!-- dependabot-major-batch:end -->'
DATA = '<!-- dependabot-major-batch:data\n'
SHA = re.compile(r'[0-9a-f]{40}')
UPDATE_TYPES = {'version-update:semver-' + kind for kind in ('major', 'minor', 'patch')}


class BatchError(Exception):
    pass


class ApiError(BatchError):
    def __init__(self, status, message):
        super().__init__(f'GitHub API {status}: {message}')
        self.status = status


class GitHub:
    def __init__(self, repo, token):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
            raise BatchError('Expected owner/repository')
        if not token:
            raise BatchError('GH_TOKEN is required')
        self.repo = repo
        self.token = token

    def request(self, method, path, data=None):
        request = urllib.request.Request(
            f'https://api.github.com/repos/{self.repo}/{path}',
            data=json.dumps(data).encode() if data is not None else None,
            method=method,
            headers={'Authorization': f'Bearer {self.token}',
                     'Accept': 'application/vnd.github+json',
                     'Content-Type': 'application/json',
                     'X-GitHub-Api-Version': '2022-11-28',
                     'User-Agent': 'cbbg-dependabot-major-batch'})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
                return response.status, json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read()).get('message', error.reason)
            except (ValueError, AttributeError):
                message = error.reason
            raise ApiError(error.code, f'{method} {path}: {message}') from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise ApiError(0, f'{method} {path}: {error.reason if hasattr(error, "reason") else error}') from None

    def pages(self, path):
        values = []
        separator = '&' if '?' in path else '?'
        page = 1
        while True:
            _, items = self.request('GET', f'{path}{separator}per_page=100&page={page}')
            if not isinstance(items, list):
                raise BatchError('Expected a paginated GitHub list')
            values.extend(items)
            if len(items) < 100:
                return values
            page += 1


def parse_numbers(text):
    if not text.strip():
        return []
    if not re.fullmatch(r'\s*[1-9][0-9]*(?:[\s,]+[1-9][0-9]*)*\s*', text):
        raise BatchError('PR numbers must be positive integers separated by commas or whitespace')
    numbers = [int(value) for value in re.split(r'[\s,]+', text.strip())]
    if len(numbers) != len(set(numbers)):
        raise BatchError('Duplicate PR numbers')
    return sorted(numbers)


def major_dependencies(message):
    documents = []
    try:
        for text in re.findall(r'(?ms)^---\r?\n(.*?)^\.\.\.[ \t]*\r?$', message):
            data = yaml.safe_load(text)
            if isinstance(data, dict) and 'updated-dependencies' in data:
                documents.append(data['updated-dependencies'])
    except yaml.YAMLError:
        return None
    if len(documents) != 1 or not isinstance(documents[0], list) or not documents[0]:
        return None
    dependencies = documents[0]
    if any(not isinstance(item, dict) or not isinstance(item.get('dependency-name'), str)
           or not item['dependency-name'] or item.get('update-type') not in UPDATE_TYPES
           for item in dependencies):
        return None
    if not any(item['update-type'] == 'version-update:semver-major' for item in dependencies):
        return None
    return [{'name': item['dependency-name'], 'version': str(item.get('dependency-version', '')),
             'update_type': item['update-type']} for item in dependencies]


def read_state(pr):
    body = pr.get('body') or ''
    if body.count(BEGIN) != 1 or body.count(END) != 1:
        raise BatchError(f"PR #{pr['number']} has missing or ambiguous managed metadata")
    start, end = body.index(BEGIN), body.index(END)
    match = re.search(re.escape(DATA) + r'(.*?)\n-->', body[start:end], re.S)
    try:
        state = json.loads(match.group(1)) if match else None
        if not isinstance(state, dict) or state.get('schema') != 1:
            raise ValueError('Unsupported state')
        if state['mode'] not in ('all', 'subset') or state['branch'] != pr['head']['ref']:
            raise ValueError('Mismatched mode or branch')
        if not state['branch'].startswith(PREFIX):
            raise ValueError('Not a batch branch')
        if any(not isinstance(state[key], str) or not SHA.fullmatch(state[key])
               for key in ('base', 'head', 'controller')):
            raise ValueError('Invalid commit SHA')
        sources = state['sources']
        if not isinstance(sources, list) or not sources:
            raise ValueError('Missing sources')
        if any(not isinstance(s, dict) or type(s.get('number')) is not int or s['number'] <= 0
               or not isinstance(s.get('sha'), str) or not SHA.fullmatch(s['sha'])
               or not isinstance(s.get('dependencies'), list) or not s['dependencies']
               for s in sources):
            raise ValueError('Invalid sources')
        if len({s['number'] for s in sources}) != len(sources):
            raise ValueError('Duplicate sources')
        for source in sources:
            if any(not isinstance(d, dict) or not isinstance(d.get('name'), str)
                   or not isinstance(d.get('version'), str) or d.get('update_type') not in UPDATE_TYPES
                   for d in source['dependencies']):
                raise ValueError('Invalid dependency metadata')
        return state
    except (ValueError, KeyError, TypeError) as error:
        raise BatchError(f"PR #{pr['number']} has invalid managed metadata: {error}") from None


def render_body(body, state):
    lines = [BEGIN, '## Major dependency snapshot', '',
             f"Original base: `{state['base']}`", f"Current managed head: `{state['head']}`", '',
             '| Source PR | Pinned head | Updates |', '| --- | --- | --- |']
    for source in state['sources']:
        updates = '; '.join(f"{d['name']} → {d['version']} ({d['update_type'].removeprefix('version-update:semver-')})"
                            for d in source['dependencies']).replace('|', '\\|').replace('\n', ' ')
        lines.append(f"| [#{source['number']}](https://github.com/{state['repo']}/pull/{source['number']}) "
                     f"| `{source['sha']}` | {updates} |")
    lines += ['', 'This is an assembly snapshot, not a passing test result. '
              'Approve the PR workflows to run; every new head requires fresh checks.', '',
              ('New major PRs are appended while this PR is draft; existing source SHAs stay frozen.'
               if state['mode'] == 'all' else 'This explicit-subset batch stays fixed for diagnosis.'),
              'Mark ready only after the utility is idle. Merging and release acceptance remain manual.', '',
              DATA + json.dumps(state, separators=(',', ':')).replace('<', '\\u003c').replace('>', '\\u003e')
              + '\n-->', END]
    section = '\n'.join(lines)
    if BEGIN in body:
        start = body.index(BEGIN)
        end = body.index(END) + len(END)
        return body[:start] + section + body[end:]
    return section


class Batch:
    def __init__(self, api, run_id, attempt, controller):
        if not str(run_id).isdigit() or not str(attempt).isdigit() or not SHA.fullmatch(controller):
            raise BatchError('Expected workflow run identity and controller SHA')
        self.api = api
        self.run_identity = f'{run_id}-{attempt}'
        self.controller = controller
        self.report = {'status': 'unchanged', 'repo': api.repo, 'controller': controller,
                       'selected': [], 'excluded': [], 'cleanup_errors': []}

    def get(self, path):
        return self.api.request('GET', path)[1]

    def head(self, branch):
        return self.get('git/ref/heads/' + branch)['object']['sha']

    def identity_reason(self, pr):
        if pr.get('state') != 'open' or pr.get('draft'):
            return 'not an open, non-draft PR'
        if pr.get('user', {}).get('login') != 'dependabot[bot]':
            return 'not authored by Dependabot'
        if (pr.get('base', {}).get('ref') != 'main'
                or pr['base'].get('repo', {}).get('full_name') != self.api.repo
                or (pr.get('head', {}).get('repo') or {}).get('full_name') != self.api.repo):
            return 'not a same-repository PR targeting main'
        if not SHA.fullmatch(pr.get('head', {}).get('sha', '')):
            return 'invalid source SHA'
        return None

    def select(self, prs, numbers=None):
        by_number = {p['number']: p for p in prs}
        selected = []
        for number in sorted(numbers if numbers is not None else by_number):
            pr = by_number.get(number)
            reason = self.identity_reason(pr) if pr else 'not an open PR targeting main'
            dependencies = None
            if not reason:
                commits = self.api.pages(f'pulls/{number}/commits')
                commit = commits[-1] if commits else {}
                if (commit.get('sha') != pr['head']['sha']
                        or (commit.get('author') or {}).get('login') != 'dependabot[bot]'):
                    reason = 'head commit is not the recorded Dependabot commit'
                else:
                    dependencies = major_dependencies(commit.get('commit', {}).get('message', ''))
                    if not dependencies:
                        reason = 'not a classified semantic major update'
            if reason:
                self.report['excluded'].append({'number': number, 'reason': reason})
                if numbers is not None:
                    raise BatchError(f'PR #{number} is not eligible: {reason}')
            else:
                selected.append({'number': number, 'sha': pr['head']['sha'], 'dependencies': dependencies})
        self.report['selected'] = selected
        return selected

    def recheck_sources(self, sources):
        for source in sources:
            pr = self.get(f"pulls/{source['number']}")
            if self.identity_reason(pr) or pr['head']['sha'] != source['sha']:
                raise BatchError(f"PR #{source['number']} changed since selection; rerun the utility")

    def find_draft(self, prs):
        drafts = [p for p in prs if p.get('draft') and p.get('state') == 'open'
                  and p.get('user', {}).get('login') == 'github-actions[bot]'
                  and p.get('head', {}).get('ref', '').startswith(PREFIX)
                  and (p['head'].get('repo') or {}).get('full_name') == self.api.repo
                  and p.get('base', {}).get('ref') == 'main']
        if len(drafts) > 1:
            raise BatchError('Multiple managed drafts exist; mark the others ready or close them')
        if not drafts:
            return None
        pr = self.get(f"pulls/{drafts[0]['number']}")
        if pr.get('state') != 'open' or not pr.get('draft'):
            return None
        state = read_state(pr)
        if state.get('repo') != self.api.repo:
            raise BatchError('Managed metadata belongs to another repository')
        return pr, state

    def new_branch(self, branch, base):
        try:
            self.head(branch)
        except ApiError as error:
            if error.status != 404:
                raise
        else:
            raise BatchError(f'Utility branch already exists: {branch}')
        try:
            self.api.request('POST', 'git/refs', {'ref': 'refs/heads/' + branch, 'sha': base})
        except ApiError as error:
            if error.status == 0:
                self.report['cleanup_errors'].append(
                    f'Branch creation outcome is unknown; inspect {branch} before deleting or retrying')
            raise

    def assemble(self, branch, sources):
        tip = self.head(branch)
        for source in sources:
            try:
                status, result = self.api.request('POST', 'merges', {
                    'base': branch, 'head': source['sha'],
                    'commit_message': f"build(deps): include Dependabot PR #{source['number']}"})
            except ApiError as error:
                raise ApiError(error.status, f"Adding PR #{source['number']}: {error}") from None
            if status == 201:
                tip = result['sha']
            elif status == 204:
                tip = self.head(branch)
            else:
                raise BatchError(f'Unexpected merge response: {status}')
            self.report['staging_head'] = tip
        return tip

    def cleanup(self, branch, expected):
        try:
            current = self.head(branch)
            if current != expected:
                raise BatchError(f'Temporary branch changed; retained {branch} at {current}')
            self.api.request('DELETE', 'git/refs/heads/' + branch)
        except ApiError as error:
            if error.status != 404:
                self.report['cleanup_errors'].append(str(error))
        except BatchError as error:
            self.report['cleanup_errors'].append(str(error))

    def create(self, prs, selection):
        sources = self.select([p for p in prs if p.get('user', {}).get('login') == 'dependabot[bot]'],
                              selection or None)
        if not sources:
            self.report['reason'] = 'No eligible major PRs'
            return self.report
        base = self.head('main')
        self.recheck_sources(sources)
        branch = PREFIX + self.run_identity
        self.report.update(branch=branch, base=base, staging_head=base)
        self.new_branch(branch, base)
        keep = False
        try:
            tip = self.assemble(branch, sources)
            if self.get('git/commits/' + base)['tree']['sha'] == self.get('git/commits/' + tip)['tree']['sha']:
                self.report['reason'] = 'Selected changes are already present'
                return self.report
            self.recheck_sources(sources)
            if self.head('main') != base:
                raise BatchError('main changed during assembly; rerun the utility')
            state = {'schema': 1, 'repo': self.api.repo, 'mode': 'subset' if selection else 'all',
                     'branch': branch, 'base': base, 'head': tip,
                     'controller': self.controller, 'sources': sources}
            self.report['snapshot'] = state
            _, pr = self.api.request('POST', 'pulls', {
                'title': 'build(deps): batch major Dependabot updates', 'base': 'main',
                'head': branch, 'draft': True, 'body': render_body('', state)})
            keep = True
            self.report.update(status='created', pr_number=pr['number'], pr_url=pr['html_url'], head=tip)
            return self.report
        finally:
            if not keep:
                # A timed-out POST may have created the PR. Never delete its branch blindly.
                try:
                    attached = self.api.pages('pulls?state=all&head=' + self.api.repo.split('/')[0] + ':' + branch)
                    if attached:
                        self.report.update(pr_number=attached[0]['number'], pr_url=attached[0]['html_url'])
                    else:
                        self.cleanup(branch, self.report['staging_head'])
                except BatchError as error:
                    self.report['cleanup_errors'].append(f'Could not determine PR creation outcome: {error}')

    def append(self, prs, pr, state):
        if state['mode'] == 'subset':
            self.report['reason'] = 'Explicit-subset draft remains fixed'
            return self.report
        if pr['head']['sha'] != state['head']:
            raise BatchError('Draft branch differs from the managed head; inspect manual edits or failed metadata updates')
        included = {s['number'] for s in state['sources']}
        candidates = [p for p in prs if p['number'] > pr['number'] and p['number'] not in included
                      and p.get('user', {}).get('login') == 'dependabot[bot]']
        sources = self.select(candidates)
        if not sources:
            self.report['reason'] = 'No new eligible major PRs'
            return self.report
        self.recheck_sources(sources)
        staging = PREFIX + 'staging/' + self.run_identity
        self.report.update(branch=state['branch'], pr_number=pr['number'], pr_url=pr['html_url'],
                           previous_head=state['head'], staging_head=state['head'])
        self.new_branch(staging, state['head'])
        try:
            tip = self.assemble(staging, sources)
            fresh = self.get(f"pulls/{pr['number']}")
            if fresh.get('state') != 'open' or not fresh.get('draft'):
                raise BatchError('Batch is no longer an open draft; no changes promoted')
            if fresh['head']['sha'] != state['head'] or read_state(fresh) != state:
                raise BatchError('Draft changed during assembly; no changes promoted')
            self.recheck_sources(sources)
            # Recheck draft state after source reads, immediately before the ref mutation.
            fresh = self.get(f"pulls/{pr['number']}")
            if (fresh.get('state') != 'open' or not fresh.get('draft')
                    or fresh['head']['sha'] != state['head'] or read_state(fresh) != state):
                raise BatchError('Draft changed before promotion; no changes promoted')
            updated = dict(state, head=tip, sources=state['sources'] + sources,
                           controller=self.controller)
            self.report['snapshot'] = updated
            if tip != state['head']:
                self.api.request('PATCH', 'git/refs/heads/' + state['branch'], {'sha': tip, 'force': False})
            self.report['head'] = tip
            self.api.request('PATCH', f"pulls/{pr['number']}", {'body': render_body(fresh.get('body') or '', updated)})
            self.report['status'] = 'updated'
            return self.report
        finally:
            self.cleanup(staging, self.report['staging_head'])

    def run(self, event_name, ref, event, selection_text):
        if event_name == 'workflow_dispatch':
            if ref != 'refs/heads/main':
                raise BatchError('Run the utility from main')
            selection = parse_numbers(selection_text)
        elif event_name == 'pull_request_target':
            if (event.get('action') not in ('opened', 'reopened', 'ready_for_review')
                    or self.identity_reason(event.get('pull_request') or {})):
                self.report['reason'] = 'Not an eligible source PR event'
                return self.report
            selection = []
        else:
            raise BatchError('Unsupported utility trigger')
        prs = self.api.pages('pulls?state=open&base=main')
        active = self.find_draft(prs)
        if event_name == 'workflow_dispatch':
            if active:
                raise BatchError(f"A managed draft already exists: {active[0]['html_url']}")
            return self.create(prs, selection)
        if not active:
            self.report['reason'] = 'No managed draft to update'
            return self.report
        return self.append(prs, *active)


def summary(report):
    lines = ['# Dependabot major batch', '', f"Status: **{report['status']}**", '']
    for key in ('reason', 'error', 'pr_url', 'base', 'previous_head', 'head'):
        if key in report:
            lines.append(f"{key}: {report[key]}")
    lines += ['', 'Assembly only. Test results belong to the PR workflows.', '']
    for source in report.get('selected', []):
        lines.append(f"- Selected #{source['number']} at `{source['sha']}`")
    for excluded in report.get('excluded', []):
        lines.append(f"- Excluded #{excluded['number']}: {excluded['reason']}")
    for error in report.get('cleanup_errors', []):
        lines.append(f'- Cleanup requires attention: {error}')
    if report.get('snapshot'):
        lines += ['', 'The snapshot artifact includes the managed head and source SHAs. '
                  'If promotion succeeded but the PR body update failed, repair the metadata from '
                  'this artifact after inspecting the branch; later automatic updates stop on a head mismatch.']
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pr-numbers', default='')
    parser.add_argument('--output', type=Path, default=Path('build/dependabot-major-batch'))
    args = parser.parse_args()
    report = {'status': 'failed'}
    utility = None
    failed = False
    try:
        api = GitHub(os.environ.get('GITHUB_REPOSITORY', ''), os.environ.get('GH_TOKEN', ''))
        utility = Batch(api, os.environ.get('GITHUB_RUN_ID', ''), os.environ.get('GITHUB_RUN_ATTEMPT', ''),
                        os.environ.get('CONTROLLER_SHA', ''))
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        report = utility.run(os.environ.get('GITHUB_EVENT_NAME', ''), os.environ.get('GITHUB_REF', ''),
                             event, args.pr_numbers)
    except (BatchError, OSError, ValueError, KeyError) as error:
        report = utility.report if utility else report
        report.update(status='failed', error=str(error))
        failed = True
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'snapshot.json').write_text(json.dumps(report, indent=2) + '\n')
    text = summary(report)
    (args.output / 'summary.md').write_text(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write(text)
    print(text)
    return 1 if failed or report.get('cleanup_errors') else 0


if __name__ == '__main__':
    raise SystemExit(main())
