import copy
import hashlib
from pathlib import Path
import tempfile
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from source_package import SourcePackageError, verify_source_inventory


class SourcePackageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / 'src/Example.java'
        self.source.parent.mkdir()
        self.source.write_bytes(b'class Example {}')
        self.jar = self.root / 'sources.jar'
        self.inventory = {'schema': 1, 'sources': [{
            'source_path': 'src/Example.java', 'archive_path': 'Example.java',
            'sha256': hashlib.sha256(self.source.read_bytes()).hexdigest()}]}
        self.write()

    def write(self, contents=b'class Example {}', name='Example.java'):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr(name, contents)

    def verify(self):
        return verify_source_inventory(self.jar, self.inventory, self.root)

    def test_matching_sources(self):
        self.assertEqual(self.verify(), {'java_sources': 1})

    def test_missing_extra_and_changed_packaged_sources(self):
        for contents, name in [(b'class Other {}', 'Example.java'),
                               (b'class Example {}', 'Other.java')]:
            with self.subTest(name=name, contents=contents):
                self.write(contents, name)
                with self.assertRaises(SourcePackageError):
                    self.verify()

    def test_changed_checkout_source(self):
        self.source.write_bytes(b'class Example { int value; }')
        with self.assertRaisesRegex(SourcePackageError, 'Source file differs'):
            self.verify()

    def test_missing_checkout_source(self):
        self.source.unlink()
        with self.assertRaisesRegex(SourcePackageError, 'Missing'):
            self.verify()

    def test_duplicate_inventory_entries(self):
        self.inventory['sources'].append(copy.deepcopy(self.inventory['sources'][0]))
        with self.assertRaisesRegex(SourcePackageError, 'Duplicate'):
            self.verify()

    def test_invalid_paths(self):
        original = copy.deepcopy(self.inventory)
        for field in ('source_path', 'archive_path'):
            for value in ('../Example.java', '/Example.java', './Example.java',
                          'src//Example.java', 'src\\Example.java', 'Example.class', None):
                with self.subTest(field=field, value=value):
                    self.inventory = copy.deepcopy(original)
                    self.inventory['sources'][0][field] = value
                    with self.assertRaises(SourcePackageError):
                        self.verify()

    def test_invalid_schema_entries_and_hash(self):
        original = copy.deepcopy(self.inventory)
        for value in ({}, {'schema': True, 'sources': original['sources']},
                      {'schema': 1, 'sources': []}, {'schema': 1, 'sources': [None]}):
            with self.subTest(value=value):
                self.inventory = value
                with self.assertRaises(SourcePackageError):
                    self.verify()
        self.inventory = original
        self.inventory['sources'][0]['sha256'] = 'incorrect'
        with self.assertRaisesRegex(SourcePackageError, 'hash'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
