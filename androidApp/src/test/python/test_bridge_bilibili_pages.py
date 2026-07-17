import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT_DIR = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT_DIR / "shared" / "src" / "commonMain" / "python"))

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


class _LoggedOutBilibiliProvider:
    identifier = "bilibili"
    name = "哔哩哔哩"

    def __init__(self):
        self.user = None
        self.auth_calls = []

    def get_current_user_or_none(self):
        return self.user

    def auth(self, user):
        self.auth_calls.append(user)
        self.user = user

    def current_user_list_playlists(self):
        return []


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

    def test_auth_state_uses_saved_bilibili_cookie_without_user_lookup(self):
        provider = _LoggedOutBilibiliProvider()
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._saved_login_provider_ids = {"bilibili"}
        bridge._restore_saved_login = lambda provider_id: self.fail("不应在普通状态查询时读取用户信息")

        state = json.loads(bridge.provider_auth_state("bilibili"))

        self.assertTrue(state["is_logged_in"])
        self.assertEqual("", state["user_name"])

    def test_auth_state_with_user_refreshes_bilibili_profile(self):
        provider = _LoggedOutBilibiliProvider()
        restored_user = SimpleNamespace(name="bilibili-user")
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._saved_login_provider_ids = {"bilibili"}
        bridge._restore_saved_login = lambda provider_id: setattr(provider, "user", restored_user)

        state = json.loads(bridge.provider_auth_state_with_user("bilibili"))

        self.assertTrue(state["is_logged_in"])
        self.assertEqual("bilibili-user", state["user_name"])

    def test_login_required_feature_restores_saved_bilibili_login(self):
        provider = _LoggedOutBilibiliProvider()
        bridge = FuoMobileBridge.__new__(FuoMobileBridge)
        bridge.app = SimpleNamespace(library=_Library(provider))
        bridge._saved_login_provider_ids = {"bilibili"}
        bridge._authenticated_provider_ids = set()
        bridge._tracks = {}
        bridge._playlists = {}
        bridge._media_items = {}
        bridge._restore_saved_login = lambda provider_id: setattr(provider, "user", SimpleNamespace(name="bilibili-user"))

        payload = json.loads(bridge.load_feature("bilibili_user_playlists"))
        bridge._get_provider("bilibili")

        self.assertFalse(payload["is_login_required"])
        self.assertEqual(1, len(provider.auth_calls))


if __name__ == "__main__":
    unittest.main()
