import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

from feeluown.library import SearchType
from feeluown.media import Media


ROOT_DIR = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT_DIR / "shared" / "src" / "commonMain" / "python"))

from fuo_mobile.bridge import FEATURE_DEFS, FuoMobileBridge, provider_capabilities  # noqa: E402


class _Library:
    def __init__(self, provider):
        self.provider = provider

    def get(self, source):
        return self.provider if source == self.provider.identifier else None

    def search(self, keyword, type_in=None, source_in=None):
        search_type = type_in[0]
        if source_in and self.provider.identifier not in source_in:
            return []
        if search_type == SearchType.so:
            return [SimpleNamespace(songs=[self.provider.song])]
        if search_type == SearchType.pl:
            return [SimpleNamespace(playlists=[self.provider.playlist])]
        if search_type == SearchType.ar:
            return [SimpleNamespace(artists=[self.provider.artist])]
        if search_type == SearchType.al:
            return [SimpleNamespace(albums=[self.provider.album])]
        if search_type == SearchType.vi:
            return [SimpleNamespace(videos=[self.provider.video])]
        return []


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
        self.artist = SimpleNamespace(
            source=self.identifier,
            identifier="artist1",
            name="Artist",
        )
        self.album = SimpleNamespace(
            source=self.identifier,
            identifier="album1",
            name="Album",
        )
        self.video = SimpleNamespace(
            source=self.identifier,
            identifier="video1",
            title="MV",
            artists_name="Artist",
            cover="https://example.com/mv.jpg",
            duration=123,
        )
        self.comment = SimpleNamespace(
            identifier="comment1",
            user=SimpleNamespace(name="Listener"),
            content="Nice",
            liked_count=12,
            time=34,
        )
        self.added = []
        self.removed = []
        self.disliked = []
        self.created = []
        self.deleted = []

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

    def playlist_create_by_name(self, name):
        self.created.append(name)
        return SimpleNamespace(source=self.identifier, identifier="created", name=name)

    def playlist_delete(self, identifier):
        self.deleted.append(identifier)
        return True

    def current_user_dislike_create_songs_rd(self):
        return [self.song]

    def current_user_dislike_add_song(self, song):
        self.disliked.append(("add", song.identifier))
        return True

    def current_user_dislike_remove_song(self, song):
        self.disliked.append(("remove", song.identifier))
        return True

    def song_list_similar(self, song):
        return [SimpleNamespace(
            source=self.identifier,
            identifier="song2",
            title="Similar",
            artists=[],
            album=None,
        )]

    def song_list_hot_comments(self, song):
        return [self.comment]

    def song_get_mv(self, song):
        return self.video

    def video_select_media(self, video, policy=None):
        return Media("https://example.com/video.mp4"), "sd"


class _SavedLoginProvider:
    def __init__(self, identifier):
        self.identifier = identifier
        self.name = identifier
        self.user = None
        self.auth_calls = []

    def get_current_user_or_none(self):
        return self.user

    def auth(self, user):
        self.auth_calls.append(user)
        self.user = user

    def current_user_list_playlists(self):
        return []


class ProviderOperationBridgeTest(unittest.TestCase):
    def bridge(self, provider):
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge._tracks = {}
        bridge._playlists = {}
        bridge._media_items = {}
        bridge._videos = {}
        bridge._dynamic_features = {}
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._get_provider = lambda provider_id: provider
        return bridge

    def test_capabilities_hide_ytmusic_remove_song(self):
        provider = _Provider()
        self.assertTrue(provider_capabilities(provider)["can_add_song_to_playlist"])
        self.assertTrue(provider_capabilities(provider)["can_remove_song_from_playlist"])
        self.assertTrue(provider_capabilities(provider)["can_create_playlist"])
        self.assertTrue(provider_capabilities(provider)["can_add_disliked_song"])

        provider.identifier = "ytmusic"
        self.assertTrue(provider_capabilities(provider)["can_add_song_to_playlist"])
        self.assertFalse(provider_capabilities(provider)["can_remove_song_from_playlist"])

    def test_create_delete_and_dislike_operations(self):
        provider = _Provider()
        bridge = self.bridge(provider)

        created = json.loads(bridge.playlist_create("netease", "New List"))
        deleted = json.loads(bridge.playlist_delete("playlist:netease:playlist1"))
        disliked = json.loads(bridge.dislike_song("netease:song1", True))
        restored = json.loads(bridge.dislike_song("netease:song1", False))

        self.assertTrue(created["result"]["success"])
        self.assertTrue(deleted["success"])
        self.assertTrue(disliked["success"])
        self.assertTrue(restored["success"])
        self.assertEqual(["New List"], provider.created)
        self.assertEqual(["playlist1"], provider.deleted)
        self.assertEqual([("add", "song1"), ("remove", "song1")], provider.disliked)

    def test_new_provider_features_are_registered(self):
        self.assertIn("current_user_cloud_songs", [item["action"] for item in FEATURE_DEFS["netease"]])
        self.assertIn("current_user_dislike_create_songs_rd", [item["action"] for item in FEATURE_DEFS["qqmusic"]])
        self.assertIn("most_popular_videos", [item["action"] for item in FEATURE_DEFS["bilibili"]])
        self.assertIn("weekly_video_playlists", [item["action"] for item in FEATURE_DEFS["bilibili"]])

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

    def test_search_all_returns_supported_result_types(self):
        provider = _Provider()
        bridge = self.bridge(provider)

        payload = json.loads(bridge.search_all("keyword"))

        self.assertEqual(["Song"], [item["title"] for item in payload["tracks"]])
        self.assertEqual(["Mine"], [item["title"] for item in payload["playlists"]])
        self.assertEqual(["Artist"], [item["title"] for item in payload["artists"]])
        self.assertEqual(["Album"], [item["title"] for item in payload["albums"]])
        self.assertEqual(["MV"], [item["title"] for item in payload["videos"]])

    def test_search_all_reports_bilibili_login_gate_when_selected(self):
        provider = _Provider(logged_in=False)
        provider.identifier = "bilibili"
        bridge = self.bridge(provider)

        payload = json.loads(bridge.search_all("keyword", "bilibili"))

        self.assertEqual("登录后搜索哔哩哔哩", payload["error_message"])
        self.assertEqual([], payload["tracks"])

    def test_saved_login_restores_provider_user_before_login_required_feature(self):
        for provider_id in ("qqmusic", "ytmusic"):
            with self.subTest(provider_id=provider_id):
                provider = _SavedLoginProvider(provider_id)
                bridge = FuoMobileBridge.__new__(FuoMobileBridge)
                bridge._tracks = {}
                bridge._playlists = {}
                bridge._media_items = {}
                bridge._videos = {}
                bridge._dynamic_features = {}
                bridge.app = SimpleNamespace(library=_Library(provider))
                bridge._saved_login_provider_ids = {provider_id}
                bridge._authenticated_provider_ids = set()
                bridge._restore_saved_login = lambda _: setattr(
                    provider,
                    "user",
                    SimpleNamespace(name="saved-user"),
                )

                payload = json.loads(bridge.load_feature(f"{provider_id}_user_playlists"))

                self.assertFalse(payload["is_login_required"])
                self.assertEqual(1, len(provider.auth_calls))

    def test_track_related_operations(self):
        provider = _Provider()
        bridge = self.bridge(provider)
        bridge._tracks["netease:song1"] = provider.song

        similar = json.loads(bridge.similar_tracks("netease:song1"))
        comments = json.loads(bridge.hot_comments("netease:song1"))
        video = json.loads(bridge.track_video("netease:song1"))

        self.assertEqual(["Similar"], [item["title"] for item in similar["tracks"]])
        self.assertEqual("Listener", comments["comments"][0]["user_name"])
        self.assertEqual("MV", video["video"]["title"])

    def test_video_payload_returns_media_url(self):
        provider = _Provider()
        bridge = self.bridge(provider)
        bridge._videos["video:netease:video1"] = provider.video

        payload = json.loads(bridge.video_payload("video:netease:video1"))

        self.assertEqual("https://example.com/video.mp4", payload["url"])
        self.assertEqual("MV", payload["video"]["title"])


if __name__ == "__main__":
    unittest.main()
