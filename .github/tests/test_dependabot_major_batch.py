"""Exercise batch selection and GitHub mutations without contacting GitHub."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml

from workflow_support import ROOT, script


SPEC = importlib.util.spec_from_file_location(
    'dependabot_major_batch', ROOT / '.github/scripts/dependabot_major_batch.py')
batch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(batch)

REPO = 'owner/repo'
BASE = 'a' * 40
CONTROLLER = 'b' * 40


def source(number, update_type='version-update:semver-major', **overrides):
    value = {
        'number': number, 'state': 'open', 'draft': False,
        'user': {'login': 'dependabot[bot]'},
        'base': {'ref': 'main', 'repo': {'full_name': REPO}},
        'head': {'ref': f'dependabot/{number}', 'sha': f'{number:040x}',
                 'repo': {'full_name': REPO}},
        'html_url': f'https://github.com/{REPO}/pull/{number}',
        'body': '', 'title': f'Update dependency {number}',
    }
    value.update(overrides)
    value['message'] = ('Update a dependency\n\n---\nupdated-dependencies:\n'
                        f'- dependency-name: dependency-{number}\n'
                        "  dependency-version: '2.0.0'\n"
                        f'  update-type: {update_type}\n...\n')
    return value


class FakeGitHub:
    """A minimal stateful API, including fast-forward rejection and conflicts."""

    def __init__(self, sources=()):
        self.repo = REPO
        self.pulls = {p['number']: copy.deepcopy(p) for p in sources}
        self.refs = {'main': BASE}
        self.trees = {BASE: BASE}
        self.parents = {BASE: []}
        self.calls = []
        self.conflicts = set()
        self.fail_create = None
        self.fail_body = False
        self.before_promote = None
        self.before_source_recheck = None
        self.compare_unchanged = False
        self.counter = 1000

    def commit(self, parent, source_sha):
        self.counter += 1
        sha = f'{self.counter:040x}'
        self.parents[sha] = [parent, source_sha]
        self.trees[sha] = self.trees.get(parent, parent) if self.compare_unchanged else sha
        return sha

    def ancestor(self, ancestor, sha):
        return ancestor == sha or any(self.ancestor(ancestor, parent)
                                      for parent in self.parents.get(sha, []))

    def pages(self, path):
        self.calls.append(('PAGES', path, None))
        if path.startswith('pulls?'):
            values = [p for p in self.pulls.values() if p['state'] == 'open']
            if 'head=' in path:
                ref = path.split('head=', 1)[1].split(':', 1)[-1]
                values = [p for p in values if p['head']['ref'] == ref]
            return copy.deepcopy(values)
        number = int(path.split('/')[1])
        p = self.pulls[number]
        return [{'sha': p['head']['sha'], 'author': {'login': p.get('commit_author', 'dependabot[bot]')},
                 'commit': {'message': p['message']}}]

    def request(self, method, path, data=None):
        self.calls.append((method, path, copy.deepcopy(data)))
        if method == 'GET' and path.startswith('git/ref/heads/'):
            ref = path.removeprefix('git/ref/heads/')
            if ref not in self.refs:
                raise batch.ApiError(404, 'Missing reference')
            return 200, {'object': {'sha': self.refs[ref]}}
        if method == 'GET' and path.startswith('git/commits/'):
            sha = path.split('/')[-1]
            return 200, {'tree': {'sha': self.trees.get(sha, sha)}}
        if method == 'GET' and path.startswith('pulls/'):
            number = int(path.split('/')[1])
            if self.before_source_recheck and self.pulls[number]['user']['login'] == 'dependabot[bot]':
                self.before_source_recheck(self.pulls[number])
            p = copy.deepcopy(self.pulls[number])
            if p['user']['login'] == 'github-actions[bot]':
                p['head']['sha'] = self.refs[p['head']['ref']]
            return 200, p
        if method == 'POST' and path == 'git/refs':
            ref = data['ref'].removeprefix('refs/heads/')
            if ref in self.refs:
                raise batch.ApiError(422, 'Reference exists')
            self.refs[ref] = data['sha']
            return 201, {'object': {'sha': data['sha']}}
        if method == 'POST' and path == 'merges':
            ref, sha = data['base'], data['head']
            assert ref != 'main', 'Never merge into main'
            if sha in self.conflicts:
                raise batch.ApiError(409, 'Merge conflict')
            if sha in self.parents.get(self.refs[ref], []):
                return 204, None
            self.refs[ref] = self.commit(self.refs[ref], sha)
            return 201, {'sha': self.refs[ref]}
        if method == 'POST' and path == 'pulls':
            if self.fail_create == 'denied':
                raise batch.ApiError(403, 'PR creation disabled')
            number = max([100, *self.pulls]) + 1
            p = source(number)
            p.update(body=data['body'], title=data['title'], draft=data['draft'],
                     user={'login': 'github-actions[bot]'})
            p['head'].update(ref=data['head'], sha=self.refs[data['head']])
            self.pulls[number] = p
            if self.fail_create == 'response-lost':
                raise batch.ApiError(0, 'Response lost')
            return 201, copy.deepcopy(p)
        if method == 'PATCH' and path.startswith('git/refs/heads/'):
            ref = path.removeprefix('git/refs/heads/')
            if self.before_promote:
                self.before_promote(self, ref)
            if data['force'] or not self.ancestor(self.refs[ref], data['sha']):
                raise batch.ApiError(422, 'Not a fast-forward')
            self.refs[ref] = data['sha']
            return 200, {'object': {'sha': data['sha']}}
        if method == 'PATCH' and path.startswith('pulls/'):
            if self.fail_body:
                raise batch.ApiError(500, 'Body update failed')
            p = self.pulls[int(path.split('/')[1])]
            p['body'] = data['body']
            return 200, copy.deepcopy(p)
        if method == 'DELETE' and path.startswith('git/refs/heads/'):
            ref = path.removeprefix('git/refs/heads/')
            assert ref != 'main'
            del self.refs[ref]
            return 204, None
        raise AssertionError((method, path, data))


class MetadataTest(unittest.TestCase):
    def test_api_paginates_prs_and_commits(self):
        api = batch.GitHub(REPO, 'test-token')
        for path in ['pulls?state=open&base=main', 'pulls/1/commits']:
            with self.subTest(path=path), patch.object(api, 'request', side_effect=[
                    (200, list(range(100))), (200, [100])]) as request:
                self.assertEqual(api.pages(path), list(range(101)))
                separator = '&' if '?' in path else '?'
                self.assertEqual(request.call_args_list[1].args,
                                 ('GET', path + separator + 'per_page=100&page=2'))

    def test_classifies_machine_metadata_not_title(self):
        p = source(1)
        self.assertTrue(batch.major_dependencies(p['message']))
        self.assertIsNone(batch.major_dependencies(source(2, 'version-update:semver-minor')['message']))
        self.assertIsNone(batch.major_dependencies(source(3, 'version-update:semver-patch')['message']))
        self.assertIsNone(batch.major_dependencies(source(4, 'unknown')['message']))
        self.assertIsNone(batch.major_dependencies('Bump dependency from 1 to 2'))

    def test_malformed_and_ambiguous_metadata_is_unclassified(self):
        self.assertIsNone(batch.major_dependencies('---\nupdated-dependencies: [\n...\n'))
        message = source(1)['message']
        self.assertIsNone(batch.major_dependencies(message + message))

    def test_mixed_group_keeps_associated_updates(self):
        message = source(1)['message'].replace('...\n',
            '- dependency-name: related\n  dependency-version: 1.1.0\n'
            '  update-type: version-update:semver-minor\n...\n')
        self.assertEqual(len(batch.major_dependencies(message)), 2)

    def test_selection_input(self):
        self.assertEqual(batch.parse_numbers('3, 1\n2'), [1, 2, 3])
        self.assertEqual(batch.parse_numbers(''), [])
        for text in ['0', '-1', '1,1', 'abc', '1; echo injected']:
            with self.subTest(text=text), self.assertRaises(batch.BatchError):
                batch.parse_numbers(text)


class BatchTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub([source(1), source(2)])

    def utility(self, attempt='1'):
        return batch.Batch(self.api, '123', attempt, CONTROLLER)

    def create(self, selected=''):
        result = self.utility().run('workflow_dispatch', 'refs/heads/main', {}, selected)
        self.assertEqual(result['status'], 'created')
        return self.api.pulls[result['pr_number']]

    def append(self):
        event = {'action': 'opened', 'pull_request': self.api.pulls[max(self.api.pulls)]}
        return self.utility('2').run('pull_request_target', 'refs/heads/main', event, '')

    def test_creates_draft_ordered_snapshot_and_never_changes_main(self):
        p = self.create()
        state = batch.read_state(p)
        self.assertEqual([s['number'] for s in state['sources']], [1, 2])
        self.assertTrue(p['draft'])
        self.assertEqual(self.api.refs['main'], BASE)
        self.assertEqual(state['controller'], CONTROLLER)

    def test_second_draft_is_refused(self):
        self.create()
        with self.assertRaisesRegex(batch.BatchError, 'draft already exists'):
            self.utility('2').run('workflow_dispatch', 'refs/heads/main', {}, '')

    def test_eligibility_and_explicit_invalid_selection(self):
        self.api.pulls[2]['user']['login'] = 'human'
        p = self.create('1')
        self.assertEqual(len(batch.read_state(p)['sources']), 1)
        other = FakeGitHub([source(1), source(2, draft=True)])
        with self.assertRaisesRegex(batch.BatchError, 'not eligible'):
            batch.Batch(other, '1', '1', CONTROLLER).run('workflow_dispatch', 'refs/heads/main', {}, '2')
        self.assertEqual(other.refs, {'main': BASE})

    def test_conflict_during_creation_removes_branch_and_creates_no_pr(self):
        self.api.conflicts.add(self.api.pulls[2]['head']['sha'])
        with self.assertRaises(batch.ApiError):
            self.utility().run('workflow_dispatch', 'refs/heads/main', {}, '')
        self.assertEqual(self.api.refs, {'main': BASE})
        self.assertEqual(len(self.api.pulls), 2)

    def test_empty_and_no_tree_changes_create_no_pr(self):
        empty = FakeGitHub([source(1, 'version-update:semver-patch')])
        self.assertEqual(batch.Batch(empty, '1', '1', CONTROLLER).run(
            'workflow_dispatch', 'refs/heads/main', {}, '')['status'], 'unchanged')
        self.api.compare_unchanged = True
        self.assertEqual(self.utility().run('workflow_dispatch', 'refs/heads/main', {}, '')['status'], 'unchanged')
        self.assertEqual(self.api.refs, {'main': BASE})

    def test_new_prs_are_appended_with_frozen_old_sources(self):
        p = self.create()
        old_sha = self.api.pulls[1]['head']['sha']
        self.api.pulls[1]['head']['sha'] = 'c' * 40
        self.api.pulls[102] = source(102)
        self.api.pulls[103] = source(103)
        result = self.append()
        self.assertEqual(result['status'], 'updated')
        state = batch.read_state(self.api.pulls[p['number']])
        self.assertEqual([s['number'] for s in state['sources']], [1, 2, 102, 103])
        self.assertEqual(state['sources'][0]['sha'], old_sha)
        self.assertEqual(self.api.refs['main'], BASE)
        self.assertFalse(any('/staging/' in ref for ref in self.api.refs))

    def test_source_synchronize_and_subset_drafts_are_ignored(self):
        self.create('1')
        self.api.pulls[102] = source(102)
        before = copy.deepcopy(self.api.refs)
        self.assertEqual(self.append()['status'], 'unchanged')
        event = {'action': 'synchronize', 'pull_request': self.api.pulls[1]}
        self.assertEqual(self.utility('3').run('pull_request_target', 'refs/heads/main', event, '')['status'], 'unchanged')
        self.assertEqual(self.api.refs, before)

    def test_ready_closed_and_merged_batches_are_ignored(self):
        for changes in [{'draft': False}, {'state': 'closed'}, {'state': 'closed', 'merged': True}]:
            with self.subTest(changes=changes):
                self.setUp()
                p = self.create()
                self.api.pulls[p['number']].update(changes)
                self.api.pulls[102] = source(102)
                before = copy.deepcopy(self.api.refs)
                self.assertEqual(self.append()['status'], 'unchanged')
                self.assertEqual(self.api.refs, before)

    def test_append_conflict_leaves_draft_unchanged(self):
        p = self.create()
        self.api.pulls[102] = source(102)
        self.api.conflicts.add(self.api.pulls[102]['head']['sha'])
        before = copy.deepcopy(self.api.pulls[p['number']])
        refs = copy.deepcopy(self.api.refs)
        with self.assertRaises(batch.ApiError):
            self.append()
        self.assertEqual(self.api.refs, refs)
        self.assertEqual(self.api.pulls[p['number']], before)

    def test_manual_edits_block_automatic_writes(self):
        p = self.create()
        self.api.refs[p['head']['ref']] = 'd' * 40
        self.api.pulls[102] = source(102)
        with self.assertRaisesRegex(batch.BatchError, 'managed head'):
            self.append()

    def test_fast_forward_rejects_concurrent_edit(self):
        p = self.create()
        self.api.pulls[102] = source(102)
        self.api.before_promote = lambda api, ref: api.refs.update({ref: 'd' * 40})
        with self.assertRaises(batch.ApiError):
            self.append()
        self.assertEqual(self.api.refs[p['head']['ref']], 'd' * 40)

    def test_body_failure_preserves_promoted_head_and_blocks_next_update(self):
        p = self.create()
        self.api.pulls[102] = source(102)
        self.api.fail_body = True
        with self.assertRaises(batch.ApiError):
            self.append()
        self.api.fail_body = False
        with self.assertRaisesRegex(batch.BatchError, 'managed head'):
            self.append()
        self.assertIn(p['head']['ref'], self.api.refs)

    def test_creation_denied_cleans_up_but_lost_response_preserves_pr(self):
        for failure in ['denied', 'response-lost']:
            with self.subTest(failure=failure):
                self.setUp()
                self.api.fail_create = failure
                with self.assertRaises(batch.ApiError):
                    self.utility().run('workflow_dispatch', 'refs/heads/main', {}, '')
                self.assertEqual(len(self.api.refs), 2 if failure == 'response-lost' else 1)

    def test_human_notes_outside_generated_section_survive(self):
        p = self.create()
        self.api.pulls[p['number']]['body'] = 'My review note.\n\n' + p['body'] + '\nKeep this too.'
        self.api.pulls[102] = source(102)
        self.append()
        body = self.api.pulls[p['number']]['body']
        self.assertTrue(body.startswith('My review note.'))
        self.assertTrue(body.endswith('Keep this too.'))

    def test_stale_source_snapshot_aborts_before_publish(self):
        self.api.before_source_recheck = lambda p: p['head'].update(sha='e' * 40)
        with self.assertRaisesRegex(batch.BatchError, 'changed'):
            self.utility().run('workflow_dispatch', 'refs/heads/main', {}, '')
        self.assertEqual(len(self.api.pulls), 2)
        self.assertEqual(self.api.refs, {'main': BASE})

    def test_multiple_managed_drafts_fail_closed(self):
        p = self.create()
        duplicate = copy.deepcopy(p)
        duplicate['number'] = 102
        self.api.pulls[102] = duplicate
        self.api.pulls[103] = source(103)
        with self.assertRaisesRegex(batch.BatchError, 'Multiple'):
            self.append()

    def test_malformed_managed_metadata_blocks_updates(self):
        p = self.create()
        self.api.pulls[p['number']]['body'] = 'Human replacement of metadata'
        self.api.pulls[102] = source(102)
        before = copy.deepcopy(self.api.refs)
        with self.assertRaisesRegex(batch.BatchError, 'metadata'):
            self.append()
        self.assertEqual(self.api.refs, before)

    def test_becoming_ready_during_assembly_prevents_promotion(self):
        p = self.create()
        self.api.pulls[102] = source(102)
        before = copy.deepcopy(self.api.refs)
        def mark_ready(source_pr):
            self.api.pulls[p['number']]['draft'] = False
        self.api.before_source_recheck = mark_ready
        with self.assertRaisesRegex(batch.BatchError, 'draft'):
            self.append()
        self.assertEqual(self.api.refs, before)

    def test_main_changing_during_creation_prevents_publication(self):
        calls = 0
        def change_main(source_pr):
            nonlocal calls
            calls += 1
            if calls == 3:
                self.api.refs['main'] = 'f' * 40
        self.api.before_source_recheck = change_main
        with self.assertRaisesRegex(batch.BatchError, 'main changed'):
            self.utility().run('workflow_dispatch', 'refs/heads/main', {}, '')
        self.assertEqual(self.api.refs, {'main': 'f' * 40})
        self.assertEqual(len(self.api.pulls), 2)

    def test_non_main_dispatch_is_rejected_without_writes(self):
        with self.assertRaisesRegex(batch.BatchError, 'from main'):
            self.utility().run('workflow_dispatch', 'refs/heads/other', {}, '')
        self.assertEqual(self.api.calls, [])


class BatchWorkflowTest(unittest.TestCase):
    def test_controller_uses_trusted_checkout_and_only_assembles(self):
        workflow = yaml.load((ROOT / '.github/workflows/dependabot-major-batch.yml').read_text(),
                             Loader=yaml.BaseLoader)
        self.assertEqual(workflow['on']['pull_request_target']['types'],
                         ['opened', 'reopened', 'ready_for_review'])
        job = workflow['jobs']['assemble']
        self.assertEqual(job['permissions'], {'contents': 'write', 'pull-requests': 'write'})
        self.assertEqual(job['steps'][0]['with'],
                         {'ref': '${{ github.workflow_sha }}', 'persist-credentials': 'false'})
        commands = '\n'.join(s.get('run', '') for s in job['steps'])
        self.assertNotIn('gradlew', commands)
        self.assertNotIn('unittest', commands)
        self.assertNotIn('${{', commands)
        self.assertIn('--pr-numbers "$PR_NUMBERS"', commands)
        self.assertEqual(workflow['concurrency']['cancel-in-progress'], 'false')

    def test_full_ci_selection_emits_all_catalog_targets_and_core(self):
        workflow = ROOT / '.github/workflows/gradle.yml'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'targets.json').write_text(json.dumps({'ciTargets': ['one', 'two']}))
            (root / 'gradlew').write_text(
                '#!/bin/bash\nset -eu\n'
                'printf "%s\\n" "$@" > args\nmkdir -p build\n'
                'printf \'{"include":[{"id":"one"},{"id":"two"}]}\' > build/batch-matrix.json\n')
            (root / 'gradlew').chmod(0o755)
            output = root / 'output'
            subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script('Select full batch CI', workflow)],
                           cwd=root, env=dict(os.environ, GITHUB_OUTPUT=str(output)),
                           capture_output=True, text=True, check=True)
            values = dict(line.split('=', 1) for line in output.read_text().splitlines())
            self.assertEqual(json.loads(values['matrix'])['include'], [{'id': 'one'}, {'id': 'two'}])
            self.assertEqual(values['build'], 'true')
            self.assertEqual(values['core'], 'true')
            self.assertIn('-Ptargets=one,two', (root / 'args').read_text().splitlines())
        routing = yaml.load(workflow.read_text(), Loader=yaml.BaseLoader)
        plan = routing['jobs']['plan']
        selection = next(s for s in plan['steps'] if s.get('id') == 'batch')
        self.assertIn("github.event_name == 'pull_request'", selection['if'])
        self.assertIn(batch.PREFIX, selection['if'])
        for name in ('matrix', 'build', 'core'):
            self.assertEqual(plan['outputs'][name],
                             '${{ steps.batch.outputs.' + name + ' || steps.targets.outputs.' + name + ' }}')
        self.assertEqual(routing['jobs']['core']['strategy']['matrix']['java'], ['8', '17', '21', '25'])


if __name__ == '__main__':
    unittest.main()
