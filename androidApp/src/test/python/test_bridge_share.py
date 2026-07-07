import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SRC_ROOT / "main" / "python"))

from fuo_mobile.bridge import (  # noqa: E402
    FuoMobileBridge,
    media_item_to_dict,
    playlist_to_dict,
    provider_web_url,
    song_to_dict,
)


class _Library:
    def get(self, source):
        return SimpleNamespace(identifier=source, name=source)

    def song_get_web_url(self, song):
        return f"https://provider.example/songs/{song.identifier}"


class _Provider:
    def __init__(self, song):
        self.song = song

    def song_get(self, identifier):
        if identifier != self.song.identifier:
            raise RuntimeError("not found")
        return self.song


def song(source="netease", identifier="1811961337"):
    return SimpleNamespace(
        source=source,
        identifier=identifier,
        title="Igallta",
        artists=[SimpleNamespace(name="Se-U-Ra", identifier="artist1", source=source)],
        album=SimpleNamespace(name="Igallta", identifier="album1", source=source),
    )


class BridgeShareTest(unittest.TestCase):
    def test_song_to_dict_uses_core_web_url(self):
        data = song_to_dict(song(), _Library())

        self.assertEqual("https://provider.example/songs/1811961337", data["provider_url"])

    def test_playlist_and_media_items_include_provider_url(self):
        library = _Library()
        playlist = SimpleNamespace(source="netease", identifier="123", name="Daily")
        artist = SimpleNamespace(source="netease", identifier="456", name="Artist")
        album = SimpleNamespace(source="netease", identifier="789", name="Album")

        self.assertEqual(
            "https://y.music.163.com/m/playlist?id=123",
            playlist_to_dict(playlist, library)["provider_url"],
        )
        self.assertEqual(
            "https://y.music.163.com/m/artist?id=456",
            media_item_to_dict(artist, "artist", library)["provider_url"],
        )
        self.assertEqual(
            "https://y.music.163.com/m/album?id=789",
            media_item_to_dict(album, "album", library)["provider_url"],
        )

    def test_playlist_and_media_items_include_known_detail_counts(self):
        library = _Library()
        playlist = SimpleNamespace(source="bilibili", identifier="123", name="Favorite", count=42)
        artist = SimpleNamespace(source="netease", identifier="456", name="Artist", song_count=80, album_count=9)
        album = SimpleNamespace(source="netease", identifier="789", name="Album", song_count=12)
        unknown_playlist = SimpleNamespace(source="netease", identifier="321", name="Unknown")

        self.assertEqual(42, playlist_to_dict(playlist, library)["track_count"])
        self.assertEqual(80, media_item_to_dict(artist, "artist", library)["track_count"])
        self.assertEqual(9, media_item_to_dict(artist, "artist", library)["album_count"])
        self.assertEqual(12, media_item_to_dict(album, "album", library)["track_count"])
        self.assertIsNone(playlist_to_dict(unknown_playlist, library)["track_count"])

    def test_provider_web_url_returns_empty_for_unsupported_resource(self):
        self.assertEqual("", provider_web_url("bilibili", "album", "123"))
        self.assertEqual("https://www.bilibili.com/video/BV1xx", provider_web_url("bilibili", "song", "BV1xx"))

    def test_track_detail_returns_track_payload(self):
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        shared_song = song("qqmusic", "abc")
        bridge._tracks = {}
        bridge._get_provider = lambda provider_id: _Provider(shared_song)
        bridge.app = SimpleNamespace(library=_Library())

        payload = bridge.track_detail("qqmusic:abc")

        self.assertIn('"id": "qqmusic:abc"', payload)
        self.assertIn('"provider_url": "https://provider.example/songs/abc"', payload)


if __name__ == "__main__":
    unittest.main()
