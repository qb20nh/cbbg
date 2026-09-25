import copy
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_release import release
import curseforge_files


class CurseForgeListingTest(unittest.TestCase):
    def page(self, index, identifiers, total):
        return {'data': [{'id': identifier, 'modId': 1408371} for identifier in identifiers],
                'pagination': {'index': index, 'pageSize': 50,
                               'resultCount': len(identifiers), 'totalCount': total}}

    def test_reads_every_page(self):
        pages = [self.page(0, [1, 2], 3), self.page(2, [3], 3)]
        with patch.object(release, 'get_json', side_effect=pages) as fetch:
            files = curseforge_files.project_files(1408371, fetch)
        self.assertEqual([file['id'] for file in files], [1, 2, 3])
        self.assertTrue(fetch.call_args_list[1].args[0].endswith('index=2&pageSize=50'))

    def test_empty_project_is_a_valid_listing(self):
        self.assertEqual(curseforge_files.project_files(1408371, lambda url: self.page(0, [], 0)), [])

    def test_incomplete_or_changed_pages_are_rejected(self):
        for page in [self.page(1, [], 2), self.page(0, [2], 2), self.page(1, [2], 3)]:
            with self.subTest(page=page), patch.object(release, 'get_json', side_effect=[
                    self.page(0, [1], 2), page]) as fetch:
                with self.assertRaises(ValueError):
                    curseforge_files.project_files(1408371, fetch)

    def test_duplicate_files_and_wrong_projects_are_rejected(self):
        for page in [self.page(0, [1, 1], 2), self.page(0, [1], 1)]:
            if len(page['data']) == 1:
                page['data'][0]['modId'] = 99
            with self.subTest(page=page), self.assertRaisesRegex(ValueError, 'Wrong project or duplicate'):
                curseforge_files.project_files(1408371, lambda url: page)

    def test_api_limit_cannot_produce_partial_success(self):
        with self.assertRaisesRegex(ValueError, 'pagination'):
            curseforge_files.project_files(1408371, lambda url: self.page(0, [1], 10001))


class CurseForgeFileTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.artifact = Path(temporary.name) / 'candidate.jar'
        self.data = b'candidate file'
        self.artifact.write_bytes(self.data)
        self.sha256 = hashlib.sha256(self.data).hexdigest()
        self.metadata = {'id': 123, 'modId': 1408371, 'isAvailable': True, 'fileStatus': 4,
                         'fileName': self.artifact.name, 'fileLength': len(self.data),
                         'downloadUrl': 'https://example.com/candidate.jar'}
        self.changelog = '<p>Release notes</p>'

    def fetch(self, url):
        return {'data': self.changelog if url.endswith('/changelog') else copy.deepcopy(self.metadata)}

    def snapshot(self, download=None):
        return curseforge_files.file_snapshot(1408371, 123, self.artifact, self.fetch,
                                              download=download or (lambda url, size: self.sha256))

    def test_snapshot_records_content_hash_and_remote_metadata(self):
        result = self.snapshot()
        self.assertEqual(result['sha256'], self.sha256)
        self.assertEqual(result['metadata'], self.metadata)
        self.assertEqual(result['changelog'], self.changelog)
        self.assertEqual(result['file_id'], 123)

    def test_pending_or_unavailable_file_is_rejected(self):
        for field, value in [('fileStatus', 3), ('fileStatus', 21), ('isAvailable', False)]:
            with self.subTest(field=field, value=value):
                original = self.metadata[field]
                self.metadata[field] = value
                with self.assertRaisesRegex(ValueError, 'approved and available'):
                    self.snapshot()
                self.metadata[field] = original

    def test_wrong_file_identity_size_or_name_is_rejected(self):
        for field, value in [('modId', 99), ('id', 456), ('fileName', 'other.jar'), ('fileLength', 1)]:
            with self.subTest(field=field):
                original = self.metadata[field]
                self.metadata[field] = value
                with self.assertRaises(ValueError):
                    self.snapshot()
                self.metadata[field] = original

    def test_different_download_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'content differs'):
            self.snapshot(lambda url, size: '0' * 64)

    def test_remote_changes_during_download_are_rejected(self):
        def change(url, size):
            self.changelog = '<p>Changed notes</p>'
            return self.sha256
        with self.assertRaisesRegex(ValueError, 'metadata changed'):
            self.snapshot(change)

    def test_local_changes_during_download_are_rejected(self):
        def change(url, size):
            self.artifact.write_bytes(b'changed')
            return self.sha256
        with self.assertRaisesRegex(ValueError, 'Local artifact changed'):
            self.snapshot(change)

    def test_download_checks_length_without_sending_api_credentials(self):
        for payload in (self.data, self.data[:-1], self.data + b'extra'):
            with self.subTest(length=len(payload)), patch('urllib.request.urlopen',
                                                          return_value=io.BytesIO(payload)) as request:
                if payload == self.data:
                    self.assertEqual(curseforge_files.download_sha256(
                        self.metadata['downloadUrl'], len(self.data)), self.sha256)
                else:
                    with self.assertRaises(ValueError):
                        curseforge_files.download_sha256(self.metadata['downloadUrl'], len(self.data))
                self.assertEqual(set(key.lower() for key in request.call_args.args[0].headers), {'user-agent'})


if __name__ == '__main__':
    unittest.main()
