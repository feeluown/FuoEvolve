import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


class DesktopBridgeTest(unittest.TestCase):
    def test_rpc_create_and_call(self):
        root = Path(__file__).resolve().parents[3]
        bridge_script = root / "src" / "main" / "python" / "desktop_bridge.py"
        with tempfile.TemporaryDirectory() as temp_dir:
            module_dir = Path(temp_dir) / "fuo_mobile"
            module_dir.mkdir()
            (module_dir / "__init__.py").write_text("", encoding="utf-8")
            (module_dir / "bridge.py").write_text(
                textwrap.dedent(
                    """
                    class FakeBridge:
                        def providers(self):
                            return '{"providers":[{"provider_id":"fake","provider_name":"Fake"}]}'

                    def create_bridge(providers_json):
                        return FakeBridge()
                    """
                ),
                encoding="utf-8",
            )
            process = subprocess.Popen(
                [sys.executable, str(bridge_script)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env={**os.environ, "FUO_ANDROID_PYTHON_DIR": temp_dir},
            )
            try:
                self._send(process, 1, "create_bridge", ['{"enabled":["fake"]}'])
                self.assertEqual(self._read(process)["ok"], True)
                self._send(process, 2, "providers", [])
                response = self._read(process)
                self.assertEqual(response["ok"], True)
                self.assertEqual(json.loads(response["result"])["providers"][0]["provider_id"], "fake")
            finally:
                process.terminate()
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()
                process.stderr.close()

    def test_migrates_previous_appimage_login_state(self):
        root = Path(__file__).resolve().parents[3]
        bridge_script = root / "src" / "main" / "python" / "desktop_bridge.py"
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            home_dir = temp_path / "home"
            target_data_dir = temp_path / "target-data"
            old_data_dir = home_dir / ".local" / "share" / "fuo-evolve" / "feeluown"
            old_data_dir.mkdir(parents=True)
            (old_data_dir / "qqmusic_user_info.json").write_text('{"cookies": {"uin": "1"}}', encoding="utf-8")

            module_dir = temp_path / "fuo_mobile"
            module_dir.mkdir()
            (module_dir / "__init__.py").write_text("", encoding="utf-8")
            (module_dir / "bridge.py").write_text(
                textwrap.dedent(
                    f"""
                    from pathlib import Path

                    class FakeBridge:
                        def providers(self):
                            migrated = Path({str(target_data_dir / "qqmusic_user_info.json")!r}).is_file()
                            return '{{"providers":[{{"provider_id":"fake","provider_name":"Fake","migrated":' + str(migrated).lower() + '}}]}}'

                    def create_bridge(providers_json):
                        return FakeBridge()
                    """
                ),
                encoding="utf-8",
            )
            feeluown_dir = temp_path / "feeluown"
            feeluown_dir.mkdir()
            (feeluown_dir / "__init__.py").write_text("", encoding="utf-8")
            (feeluown_dir / "consts.py").write_text(
                textwrap.dedent(
                    f"""
                    HOME_DIR = {str(temp_path / "home-dir")!r}
                    DATA_DIR = {str(target_data_dir)!r}
                    STATE_DIR = {str(temp_path / "state-dir")!r}
                    CACHE_DIR = {str(temp_path / "cache-dir")!r}
                    """
                ),
                encoding="utf-8",
            )

            process = subprocess.Popen(
                [sys.executable, str(bridge_script)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env={**os.environ, "HOME": str(home_dir), "FUO_ANDROID_PYTHON_DIR": temp_dir},
            )
            try:
                self._send(process, 1, "create_bridge", ['{"enabled":["fake"]}'])
                self.assertEqual(self._read(process)["ok"], True)
                self._send(process, 2, "providers", [])
                response = self._read(process)
                self.assertEqual(response["ok"], True)
                provider = json.loads(response["result"])["providers"][0]
                self.assertEqual(provider["migrated"], True)
            finally:
                process.terminate()
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()
                process.stderr.close()

    def _send(self, process, request_id, method, args):
        process.stdin.write(json.dumps({"id": request_id, "method": method, "args": args}) + "\n")
        process.stdin.flush()

    def _read(self, process):
        line = process.stdout.readline()
        if not line:
            self.fail(process.stderr.read())
        return json.loads(line)


if __name__ == "__main__":
    unittest.main()
