import copy
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import install_fabric_parity_runtime as installer
from install_fabric_parity_runtime import validate_profile
from runtime_catalog import load_catalog, select_targets


class FabricProfileTests(unittest.TestCase):
    def setUp(self):
        self.target = {"minecraft": "26.3", "dependencies": {"loader": "0.19.5"}}
        self.profile = {
            "id": "fabric-loader-0.19.5-26.3",
            "inheritsFrom": "26.3",
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "libraries": [{"name": "net.fabricmc:fabric-loader:0.19.5"}],
        }

    def test_matching_profile(self):
        self.assertEqual(validate_profile(self.profile, self.target), self.profile["id"])

    def test_each_26_1_patch_uses_its_own_runtime_profile(self):
        catalog = load_catalog()
        for target_id in ('26.1-fabric', '26.1.1-fabric', '26.1.2-fabric'):
            with self.subTest(target=target_id), tempfile.TemporaryDirectory() as temp:
                target = select_targets(catalog, target_id)[0]
                identity = 'fabric-loader-0.19.5-' + target['minecraft']
                profile = dict(self.profile, id=identity, inheritsFrom=target['minecraft'])
                content = json.dumps(profile).encode()
                response = types.SimpleNamespace(content=content, json=lambda: profile,
                                                 raise_for_status=lambda: None)
                requests = types.ModuleType('requests')
                urls = []
                requests.get = lambda url, timeout: (urls.append(url), response)[1]
                install = types.ModuleType('minecraft_launcher_lib.install')
                runtime = Path(temp) / 'runtime'

                def install_version(selected, location, callback):
                    self.assertEqual(selected, identity)
                    jar = location / 'libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar'
                    jar.parent.mkdir(parents=True)
                    jar.write_bytes(b'loader')

                install.install_minecraft_version = install_version
                with (patch.object(sys, 'argv', ['installer', '--target', target_id,
                                                '--runtime', str(runtime)]),
                      patch.object(installer, 'version', return_value='8.0'),
                      patch.dict(sys.modules, {'requests': requests,
                                                'minecraft_launcher_lib': types.ModuleType('minecraft_launcher_lib'),
                                                'minecraft_launcher_lib.install': install}),
                      patch.object(installer, 'fabric_command', return_value=['java']),
                      patch.object(installer, 'capture_runtime', return_value={'fixture': True})):
                    installer.main()
                self.assertEqual(urls, ['https://meta.fabricmc.net/v2/versions/loader/'
                                        + target['minecraft'] + '/0.19.5/profile/json'])
                receipt = json.loads((runtime / 'cbbg-install-receipt.json').read_text())
                self.assertEqual((receipt['target'], receipt['profile'], receipt['installed']),
                                 (target_id, identity, True))

    def test_explicit_loader_does_not_change_catalog_target(self):
        profile = copy.deepcopy(self.profile)
        profile['id'] = 'fabric-loader-0.19.3-26.3'
        profile['libraries'] = [{'name': 'net.fabricmc:fabric-loader:0.19.3'}]
        self.assertEqual(validate_profile(profile, self.target, '0.19.3'), profile['id'])
        self.assertEqual(self.target['dependencies']['loader'], '0.19.5')
        with self.assertRaises(ValueError):
            validate_profile(profile, self.target, '0.19.4')

    def test_wrong_runtime_identity(self):
        for key in ("id", "inheritsFrom", "mainClass"):
            with self.subTest(key=key):
                profile = dict(self.profile, **{key: "wrong"})
                with self.assertRaises(ValueError):
                    validate_profile(profile, self.target)

    def test_wrong_missing_or_duplicate_loader(self):
        for libraries in ([], [{"name": "net.fabricmc:fabric-loader:0.19.4"}],
                          self.profile["libraries"] * 2):
            with self.subTest(libraries=libraries):
                profile = copy.deepcopy(self.profile)
                profile["libraries"] = libraries
                with self.assertRaises(ValueError):
                    validate_profile(profile, self.target)


if __name__ == "__main__":
    unittest.main()
