import json
from pathlib import Path
import tempfile
import unittest

from runtime_catalog import load_catalog, select_targets


class RuntimeCatalogTests(unittest.TestCase):
    def test_exact_runtime_selection_keeps_shared_jar_loaders_separate(self):
        catalog = load_catalog()
        self.assertEqual([record['id'] for record in select_targets(catalog, '26.3-fabric,26.3-quilt')],
                         ['26.3-fabric', '26.3-quilt'])
        for selection in ('', 'unknown', '26.3-fabric,', '26.3-fabric,26.3-fabric'):
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                select_targets(catalog, selection)

    def test_invalid_records_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'catalog.json'
            for catalog in ({'schema': True, 'targets': [{'id': 'example'}]},
                            {'schema': 1, 'targets': []},
                            {'schema': 1, 'targets': [False]},
                            {'schema': 1, 'targets': [{'id': 'example'}, {'id': 'example'}]}):
                path.write_text(json.dumps(catalog))
                with self.subTest(catalog=catalog), self.assertRaises(ValueError):
                    load_catalog(path)
