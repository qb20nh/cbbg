import copy
import unittest

from install_fabric_parity_runtime import validate_profile


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
