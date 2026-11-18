import json
import os
from pathlib import Path
import sys
import shutil
import subprocess
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from kernel_workspace_status import update_status


class KernelStampTests(unittest.TestCase):
    head = "5929004f86ffc02c0b750715ec5a2b1541d14065"
    release = "6.1.138-android14-11-g5929004f86ffc-ab10023717-4k"

    def fixture(self, dirty=""):
        return 'STABLE_SOURCE_DATE_EPOCHS {"common": "1740000000"}\nSTABLE_SCMVERSIONS ' + json.dumps({
            "common": "-g5929004f86ff" + dirty + "-ab10023717-4k", "build/kernel": "-g123456789abc-dirty"}) + "\n"

    def test_target_abbreviation_is_derived_from_real_source(self):
        value = update_status(self.fixture(), self.head, self.release)
        metadata = json.loads(value.split("STABLE_SCMVERSIONS ")[1])
        self.assertEqual("-g5929004f86ffc-ab10023717-4k", metadata["common"])
        self.assertEqual("-g123456789abc-dirty", metadata["build/kernel"])
        self.assertTrue(value.startswith('STABLE_SOURCE_DATE_EPOCHS {"common": "1740000000"}'))

    def test_dirty_markers_are_never_hidden(self):
        value = update_status(self.fixture("-dirty"), self.head, self.release)
        self.assertIn("-g5929004f86ffc-dirty-ab10023717-4k", value)

    def test_wrong_source_or_missing_metadata_is_rejected(self):
        for head, status in [("a" * 40, self.fixture()), (self.head, "STABLE_SCMVERSIONS {}\n"),
                             (self.head, "STABLE_SOURCE_DATE_EPOCHS {}\n"),
                             (self.head, 'STABLE_SCMVERSIONS {"common":"-g111111111111"}\n')]:
            with self.assertRaises(ValueError):
                update_status(status, head, self.release)

    def test_generic_build_keeps_upstream_metadata(self):
        self.assertEqual(self.fixture(), update_status(self.fixture(), self.head, ""))

    @unittest.skipIf(os.name == "nt", "Exec/shebang integration runs on the Linux Actions runner")
    def test_absolute_command_finds_kernel_root_from_another_working_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / "common"
            common.mkdir()
            subprocess.run(["git", "init", "-q", str(common)], check=True)
            subprocess.run(["git", "-C", str(common), "-c", "user.name=CI", "-c", "user.email=ci@example.invalid",
                            "commit", "-q", "--allow-empty", "-m", "fixture"], check=True)
            head = subprocess.check_output(["git", "-C", str(common), "rev-parse", "HEAD"], text=True).strip()
            script = root / "build/kernel/kleaf/workspace_status.sh"
            script.parent.mkdir(parents=True)
            original = 'STABLE_SCMVERSIONS ' + json.dumps({"common": "-g" + head[:12]})
            script.write_text("#!/bin/bash\nprintf '%s\\n' '" + original + "'\n", encoding="utf-8")
            wrapper = root / "qw_workspace_status.py"
            shutil.copy2(Path(__file__).resolve().parents[2] / "scripts/kernel_workspace_status.py", wrapper)
            wrapper.chmod(0o755)
            env = dict(os.environ, ABK_KO_EXPECTED_RELEASE="6.1.138-android14-11-g" + head[:13])
            result = subprocess.check_output([str(wrapper)], cwd="/", env=env, text=True)
            self.assertEqual("-g" + head[:13], json.loads(result.split(" ", 1)[1])["common"])


if __name__ == "__main__":
    unittest.main()
