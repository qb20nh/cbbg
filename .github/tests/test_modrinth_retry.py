import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_release import release
import modrinth_retry


class ModrinthRetryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.candidate = self.root / 'candidate.json'
        self.candidate.write_text('{}')
        self.metadata_path = self.root / 'metadata.json'
        upload = {'project_id': 'UBlXUQbC', 'version_name': 'cbbg 1.4.0+mc26.3-fabric',
                  'version_number': '1.4.0+mc26.3-fabric', 'version_type': 'release',
                  'changelog': 'Release notes', 'loaders': ['fabric'], 'game_versions': ['26.3'],
                  'required_projects': ['fabric-api']}
        self.record = {'targets': ['26.3-fabric'], 'modrinth': upload}
        self.existing = {'id': 'Version1', 'project_id': upload['project_id'],
                         'name': upload['version_name'], 'version_number': upload['version_number'],
                         'version_type': 'release', 'status': 'listed', 'changelog': 'Release notes',
                         'loaders': ['fabric'], 'game_versions': ['26.3'],
                         'dependencies': [{'project_id': 'P7dR8mSH', 'dependency_type': 'required'}],
                         'files': []}
        for kind in ('artifact', 'sources'):
            path = self.root / (kind + '.jar')
            data = kind.encode()
            path.write_bytes(data)
            self.record[kind] = {'path': path.name, 'sha256': hashlib.sha256(data).hexdigest()}
            self.existing['files'].append({'filename': path.name, 'hashes': {
                'sha512': hashlib.sha512(data).hexdigest()}, 'primary': kind == 'artifact',
                'file_type': 'sources-jar' if kind == 'sources' else None})
        self.metadata = {'schema': 1, 'manifest_sha256': modrinth_retry.digest(self.candidate),
                         'release': 'v1.4.0', 'source_commit': 'a' * 40, 'records': [self.record]}
        self.expected = copy.deepcopy(self.metadata)
        self.enterContext(patch.object(release, 'candidate_metadata', return_value=self.expected))
        self.versions = [self.existing]
        self.urls = []

    def fetch(self, url):
        self.urls.append(url)
        if url.endswith('/version'):
            return self.versions
        if url.endswith('/fabric-api'):
            return {'id': 'P7dR8mSH'}
        self.assertEqual(url, modrinth_retry.API + '/project/UBlXUQbC')
        return {'id': 'UBlXUQbC', 'slug': 'cbbg'}

    def plan(self):
        self.metadata_path.write_text(json.dumps(self.metadata))
        return modrinth_retry.plan_upload(self.candidate, self.metadata_path,
                                          '26.3-fabric', self.root, fetch=self.fetch)

    def test_matching_upload_is_reused_with_remote_identity(self):
        result = self.plan()
        self.assertEqual(result['action'], 'reuse')
        self.assertEqual(result['version_id'], 'Version1')
        self.assertEqual(result['url'], 'https://modrinth.com/mod/cbbg/version/Version1')
        self.assertEqual(result['artifact'], self.record['artifact'])

    def test_new_version_requires_upload(self):
        self.versions = []
        result = self.plan()
        self.assertEqual(result['action'], 'upload')
        self.assertNotIn('version_id', result)

    def test_conflicting_metadata_is_rejected(self):
        for field, value in [('name', 'other'), ('version_type', 'beta'), ('status', 'draft'),
                             ('changelog', 'other'), ('loaders', ['fabric', 'quilt']),
                             ('game_versions', ['26.2']), ('project_id', 'another')]:
            with self.subTest(field=field):
                original = self.existing[field]
                self.existing[field] = value
                with self.assertRaisesRegex(ValueError, 'different'):
                    self.plan()
                self.existing[field] = original

    def test_conflicting_file_hashes_names_and_roles_are_rejected(self):
        for field, value in [('hashes', {'sha512': '0' * 128}), ('filename', 'other.jar'),
                             ('primary', False), ('file_type', 'sources-jar')]:
            with self.subTest(field=field):
                original = self.existing['files'][0][field]
                self.existing['files'][0][field] = value
                with self.assertRaises(ValueError):
                    self.plan()
                self.existing['files'][0][field] = original

    def test_missing_sources_and_extra_files_are_rejected(self):
        original = copy.deepcopy(self.existing['files'])
        for files in (original[:1], original + [original[0]]):
            with self.subTest(count=len(files)):
                self.existing['files'] = files
                with self.assertRaisesRegex(ValueError, 'different files'):
                    self.plan()

    def test_changed_dependencies_are_rejected(self):
        for dependencies in ([], [{'project_id': 'P7dR8mSH', 'dependency_type': 'optional'}],
                              [{'project_id': 'P7dR8mSH', 'dependency_type': 'required',
                                'version_id': 'specific'}]):
            with self.subTest(dependencies=dependencies):
                self.existing['dependencies'] = dependencies
                with self.assertRaisesRegex(ValueError, 'different dependencies'):
                    self.plan()

    def test_duplicate_version_numbers_are_rejected(self):
        self.versions.append(copy.deepcopy(self.existing))
        with self.assertRaisesRegex(ValueError, 'Multiple Modrinth versions'):
            self.plan()

    def test_edited_publishing_labels_are_rejected_before_requests(self):
        self.record['modrinth']['loaders'].append('quilt')
        with self.assertRaisesRegex(ValueError, 'checked candidate'):
            self.plan()
        self.assertEqual(self.urls, [])

    def test_changed_local_files_are_rejected_even_for_new_upload(self):
        self.versions = []
        (self.root / 'artifact.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.plan()

    def test_read_failure_does_not_become_upload_permission(self):
        self.fetch = lambda url: (_ for _ in ()).throw(OSError('request failed'))
        with self.assertRaisesRegex(OSError, 'request failed'):
            self.plan()


if __name__ == '__main__':
    unittest.main()
