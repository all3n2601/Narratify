import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release_metadata", Path(__file__).parents[1] / "release_metadata.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseMetadataTests(unittest.TestCase):
    def test_stable_release(self):
        result = module.metadata("1.2.3", 12, publish=True)
        self.assertEqual(result, {"version": "1.2.3", "version_code": "12", "tag": "v1.2.3", "prerelease": "false", "publish": "true"})

    def test_preview_and_validation_run(self):
        result = module.metadata("0.1.0-rc.1", 13, publish=False)
        self.assertEqual(result["prerelease"], "true")
        self.assertEqual(result["publish"], "false")

    def test_invalid_or_unsafe_versions(self):
        for version in ["v1.0.0", "01.0.0", "1.0", "1.0.0-01", "1.0.0-rc..1", "1.0.0\npublish=true", "1.0.0/../../file", "$(echo secret)", "1.0.0+metadata"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                module.metadata(version, 1, publish=True)

    def test_invalid_build_number(self):
        for code in [0, -1, 2_100_000_001]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                module.metadata("1.0.0", code, publish=False)


if __name__ == "__main__":
    unittest.main()
