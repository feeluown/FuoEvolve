import base64
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT_DIR = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT_DIR / "shared" / "src" / "commonMain" / "python"))

from fuo_mobile.bridge import write_m4a_tags  # noqa: E402


class _FakeMP4:
    latest = None

    def __init__(self, path):
        self.path = path
        self.tags = None
        self.saved = False
        type(self).latest = self

    def add_tags(self):
        self.tags = {}

    def save(self):
        self.saved = True


class _FakeMP4Cover:
    FORMAT_JPEG = "jpeg"
    FORMAT_PNG = "png"

    def __init__(self, data, imageformat):
        self.data = data
        self.imageformat = imageformat


class M4aTagsTest(unittest.TestCase):
    def test_write_m4a_tags_includes_text_and_png_artwork(self):
        with (
            patch("mutagen.mp4.MP4", _FakeMP4),
            patch("mutagen.mp4.MP4Cover", _FakeMP4Cover),
        ):
            result = write_m4a_tags(
                "/tmp/song.m4a",
                "Song",
                "Artist",
                "Album",
                "image/png",
                base64.b64encode(b"cover").decode(),
            )

        audio = _FakeMP4.latest
        self.assertTrue(result)
        self.assertTrue(audio.saved)
        self.assertEqual(["Song"], audio.tags["\xa9nam"])
        self.assertEqual(["Artist"], audio.tags["\xa9ART"])
        self.assertEqual(["Album"], audio.tags["\xa9alb"])
        cover = audio.tags["covr"][0]
        self.assertEqual(b"cover", cover.data)
        self.assertEqual(_FakeMP4Cover.FORMAT_PNG, cover.imageformat)


if __name__ == "__main__":
    unittest.main()
