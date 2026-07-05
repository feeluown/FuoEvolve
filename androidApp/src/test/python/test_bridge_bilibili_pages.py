import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SRC_ROOT / "main" / "python"))

from feeluown.excs import MediaNotFound  # noqa: E402
from feeluown.media import Media, Quality  # noqa: E402
from fuo_mobile.bridge import FuoMobileBridge  # noqa: E402


def song(identifier, title, children=None):
    return SimpleNamespace(
        source="bilibili",
        identifier=identifier,
        title=title,
        artists=[],
        children=children or [],
    )


class _Library:
    def __init__(self, provider):
        self.provider = provider

    def get(self, provider_id):
        if provider_id == "bilibili":
            return self.provider
        return None


class _BilibiliProvider:
    def __init__(self):
        self.children = [
            song("paged_BV1xx__1", "P1"),
            song("paged_BV1xx__2", "P2"),
        ]
        self.calls = []

    def song_list_quality(self, song):
        return [Quality.Audio.hq]

    def song_get_media(self, song, quality):
        return None

    def song_select_media(self, song, policy=None):
        self.calls.append(song.identifier)
        if song.identifier == "BV1xx":
            raise MediaNotFound(reason=MediaNotFound.Reason.check_children)
        return Media("https://example.test/audio.m4s"), Quality.Audio.hq

    def song_get(self, identifier):
        return song("BV1xx", "parent", self.children)


class BilibiliPagesTest(unittest.TestCase):
    def test_select_media_uses_first_child_when_parent_has_multiple_pages(self):
        provider = _BilibiliProvider()
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._tracks = {}

        playback_song, media, quality, parts, current_part_index = bridge._select_media(song("BV1xx", "parent"), "")

        self.assertEqual("paged_BV1xx__1", playback_song.identifier)
        self.assertEqual("https://example.test/audio.m4s", media.url)
        self.assertEqual("HQ", quality)
        self.assertEqual(["paged_BV1xx__1", "paged_BV1xx__2"], [part.identifier for part in parts])
        self.assertEqual(0, current_part_index)
        self.assertEqual(["BV1xx", "paged_BV1xx__1"], provider.calls)
        self.assertIs(bridge._tracks["bilibili:paged_BV1xx__1"], playback_song)
        self.assertIs(bridge._tracks["bilibili:paged_BV1xx__2"], parts[1])

    def test_song_from_track_id_returns_requested_page_child(self):
        provider = _BilibiliProvider()
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._tracks = {}

        result = bridge._song_from_track_id("bilibili:paged_BV1xx__2")

        self.assertEqual("paged_BV1xx__2", result.identifier)


if __name__ == "__main__":
    unittest.main()
