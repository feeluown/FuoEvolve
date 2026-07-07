import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(SRC_ROOT / "main" / "python"))

from fuo_mobile.bridge import FuoMobileBridge, provider_capabilities  # noqa: E402


class _Library:
    def __init__(self, provider):
        self.provider = provider

    def get(self, source):
        return self.provider if source == self.provider.identifier else None


class _Provider:
    identifier = "netease"
    name = "网易云音乐"

    def __init__(self, logged_in=True):
        self.user = object() if logged_in else None
        self.song = SimpleNamespace(
            source=self.identifier,
            identifier="song1",
            title="Song",
            artists=[],
            album=None,
        )
        self.playlist = SimpleNamespace(
            source=self.identifier,
            identifier="playlist1",
            name="Mine",
        )
        self.added = []
        self.removed = []

    def get_current_user_or_none(self):
        return self.user

    def song_get(self, identifier):
        if identifier != self.song.identifier:
            raise RuntimeError("song not found")
        return self.song

    def playlist_get(self, identifier):
        if identifier != self.playlist.identifier:
            raise RuntimeError("playlist not found")
        return self.playlist

    def current_user_list_playlists(self):
        return [self.playlist]

    def playlist_add_song(self, playlist, song):
        self.added.append((playlist.identifier, song.identifier))
        return True

    def playlist_remove_song(self, playlist, song):
        self.removed.append((playlist.identifier, song.identifier))
        return True


class ProviderOperationBridgeTest(unittest.TestCase):
    def bridge(self, provider):
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge._tracks = {}
        bridge._playlists = {}
        bridge._media_items = {}
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._get_provider = lambda provider_id: provider
        return bridge

    def test_capabilities_hide_ytmusic_remove_song(self):
        provider = _Provider()
        self.assertTrue(provider_capabilities(provider)["can_add_song_to_playlist"])
        self.assertTrue(provider_capabilities(provider)["can_remove_song_from_playlist"])

        provider.identifier = "ytmusic"
        self.assertTrue(provider_capabilities(provider)["can_add_song_to_playlist"])
        self.assertFalse(provider_capabilities(provider)["can_remove_song_from_playlist"])

    def test_playlist_targets_require_login(self):
        bridge = self.bridge(_Provider(logged_in=False))

        with self.assertRaisesRegex(RuntimeError, "未登录"):
            bridge.playlist_operation_targets("netease:song1")

    def test_add_song_to_playlist(self):
        provider = _Provider()
        bridge = self.bridge(provider)

        payload = json.loads(bridge.playlist_add_song("playlist:netease:playlist1", "netease:song1"))

        self.assertTrue(payload["success"])
        self.assertEqual([("playlist1", "song1")], provider.added)

    def test_remove_song_from_playlist(self):
        provider = _Provider()
        bridge = self.bridge(provider)

        payload = json.loads(bridge.playlist_remove_song("playlist:netease:playlist1", "netease:song1"))

        self.assertTrue(payload["success"])
        self.assertEqual([("playlist1", "song1")], provider.removed)

    def test_add_rejects_cross_provider_song(self):
        provider = _Provider()
        bridge = self.bridge(provider)
        bridge._tracks["qqmusic:song1"] = SimpleNamespace(
            source="qqmusic",
            identifier="song1",
            title="Other",
            artists=[],
            album=None,
        )

        payload = json.loads(bridge.playlist_add_song("playlist:netease:playlist1", "qqmusic:song1"))

        self.assertFalse(payload["success"])
        self.assertEqual([], provider.added)


if __name__ == "__main__":
    unittest.main()
