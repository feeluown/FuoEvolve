import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "shared" / "src" / "commonMain" / "python"))

import fuo_mobile.bridge  # noqa: E402, F401
from feeluown.i18n import human_readable_number, rfc1766_langcode, t  # noqa: E402


class MobileI18nTest(unittest.TestCase):
    def test_translation_falls_back_to_message_id(self):
        self.assertEqual("player-error", t("player-error", detail="failed"))

    def test_human_readable_number_keeps_compact_format(self):
        self.assertEqual("1.2万", human_readable_number(12_345, "zh_CN"))
        self.assertEqual("1.2K", human_readable_number(1_234, "en_US"))

    def test_language_code_is_available(self):
        self.assertTrue(rfc1766_langcode())


if __name__ == "__main__":
    unittest.main()
