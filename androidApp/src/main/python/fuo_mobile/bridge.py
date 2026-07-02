import asyncio
import json
import os
import unicodedata
import sys
import types
from dataclasses import dataclass
from typing import Any, Dict, List, Optional


def _install_mpv_stub() -> None:
    """Allow importing feeluown.player without loading desktop libmpv."""
    if "feeluown.mpv" in sys.modules:
        return

    module = types.ModuleType("feeluown.mpv")

    class _DummyMPV:
        def __init__(self, *args, **kwargs):
            raise RuntimeError("mpv is not available in FeelUOwn mobile")

    class _DummyEventID:
        END_FILE = 0

    class _DummyEndFile:
        pass

    class _DummyErrorCode:
        pass

    module.MPV = _DummyMPV
    module.MpvEventID = _DummyEventID
    module.MpvEventEndFile = _DummyEndFile
    module.ErrorCode = _DummyErrorCode
    module._mpv_set_property_string = lambda *args, **kwargs: None
    module._mpv_set_option_string = lambda *args, **kwargs: None
    module._mpv_client_api_version = lambda: (1, 30)
    sys.modules["feeluown.mpv"] = module


_install_mpv_stub()


def _patch_pydantic_v1() -> None:
    """Provide the small pydantic v2 surface used by current FeelUOwn models."""
    import pydantic

    if not hasattr(pydantic, "ConfigDict"):
        pydantic.ConfigDict = dict

    if not hasattr(pydantic, "model_validator"):
        from pydantic import root_validator

        def model_validator(*, mode="after"):
            return root_validator(pre=(mode == "before"), allow_reuse=True)

        pydantic.model_validator = model_validator

    if not hasattr(pydantic, "field_validator"):
        from pydantic import validator

        def field_validator(*fields, mode="after", **kwargs):
            return validator(*fields, pre=(mode == "before"), allow_reuse=True)

        pydantic.field_validator = field_validator

    if not hasattr(pydantic, "model_serializer"):
        def model_serializer(*args, **kwargs):
            def decorator(func):
                return func

            return decorator

        pydantic.model_serializer = model_serializer


_patch_pydantic_v1()

from feeluown.config import Config
from feeluown.library import Library
from feeluown.library.base import SearchType
from feeluown.library.excs import MediaNotFound
from feeluown.library.provider_protocol import SupportsSongMultiQuality
from feeluown.media import Media
from feeluown.player.base_player import AbstractPlayer, State
from feeluown.utils.dispatch import Signal


def _patch_feeluown_models_for_pydantic_v1() -> None:
    import pydantic
    import feeluown.library.models as models

    if hasattr(pydantic.BaseModel, "model_rebuild"):
        return

    refs = {
        name: value
        for name, value in vars(models).items()
        if name.endswith("Model") or name.startswith("T")
    }
    for model_cls in refs.values():
        update_forward_refs = getattr(model_cls, "update_forward_refs", None)
        if update_forward_refs is not None:
            update_forward_refs(**refs)


_patch_feeluown_models_for_pydantic_v1()


class MobileConfig:
    AUDIO_SELECT_POLICY = "hq<>"
    ENABLE_AI_STANDBY_MATCHER = False
    ENABLE_MV_AS_STANDBY = False
    PROVIDERS_STANDBY = None
    VIDEO_SELECT_POLICY = "hd<>"


class MobileTaskManager:
    def run_afn_preemptive(self, func, *args, **kwargs):
        return func(*args)


class MobileNativePlayer(AbstractPlayer):
    def __init__(self):
        super().__init__()
        self.last_payload = None

    def play(self, media, video=True, metadata=None):
        self._current_media = media
        self._current_metadata = metadata or {}
        self.last_payload = media_to_payload(media, metadata or {})
        self.state = State.playing
        self.media_changed.emit(media)

    def set_play_range(self, start=None, end=None):
        if start is not None:
            self._position = start
            self.seeked.emit(start)

    def set_infinite_loop(self, on: bool):
        self.infinite_loop = on

    def resume(self):
        self.state = State.playing

    def pause(self):
        self.state = State.paused

    def toggle(self):
        self.pause() if self.state == State.playing else self.resume()

    def stop(self):
        self.state = State.stopped

    def shutdown(self):
        self.stop()


@dataclass
class MobileAppContext:
    config: MobileConfig
    library: Library
    player: MobileNativePlayer
    task_mgr: MobileTaskManager
    has_gui: bool = False
    mode: int = 0
    GuiMode: int = 0x10

    def show_msg(self, msg, *args, **kwargs):
        self.last_message = str(msg)


PROVIDER_MODULES = {
    "netease": "fuo_netease",
    "qqmusic": "fuo_qqmusic",
    "bilibili": "fuo_bilibili",
    "ytmusic": "fuo_ytmusic",
}


class ProviderRegistry:
    def __init__(self, app: MobileAppContext, enabled: List[str]):
        self.app = app
        self.enabled = enabled
        self.provider_ids: List[str] = []

    def load(self):
        for provider_or_module in self.enabled:
            module_name = PROVIDER_MODULES.get(provider_or_module, provider_or_module)
            module = __import__(module_name)
            if not hasattr(module, "enable"):
                raise RuntimeError(f"provider module has no enable(app): {module_name}")
            if hasattr(module, "init_config"):
                config_name = self._config_name(provider_or_module, module_name, module)
                provider_config = Config(config_name)
                module.init_config(provider_config)
                setattr(self.app.config, config_name, provider_config)
            before = {provider.identifier for provider in self.app.library.list()}
            self._enable_without_auto_login(module)
            provider = getattr(module, "provider", None)
            provider_id = getattr(provider, "identifier", None)
            if provider_id and self.app.library.get(provider_id) is not None:
                self.provider_ids.append(provider_id)
            else:
                after = [provider.identifier for provider in self.app.library.list()]
                self.provider_ids.extend(provider_id for provider_id in after if provider_id not in before)

    def _config_name(self, provider_or_module: str, module_name: str, module) -> str:
        if provider_or_module in PROVIDER_MODULES:
            return provider_or_module
        provider_id = getattr(module, "__identifier__", "")
        if provider_id:
            return provider_id
        if module_name.startswith("fuo_"):
            return module_name.removeprefix("fuo_")
        return module_name

    def _enable_without_auto_login(self, module):
        import feeluown.library.library as library_module

        original_run_fn = library_module.run_fn
        library_module.run_fn = lambda *args, **kwargs: None
        try:
            module.enable(self.app)
        finally:
            library_module.run_fn = original_run_fn


FEATURE_DEFS = {
    "netease": [
        {
            "id": "netease_daily_songs",
            "title": "每日推荐歌曲",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": True,
            "action": "rec_list_daily_songs",
        },
        {
            "id": "netease_daily_playlists",
            "title": "推荐歌单",
            "category": "Recommend",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "rec_list_daily_playlists",
        },
        {
            "id": "netease_radio",
            "title": "私人 FM",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_list_radio_songs",
        },
        {
            "id": "netease_toplists",
            "title": "排行榜",
            "category": "Music",
            "content_type": "Playlists",
            "requires_login": False,
            "action": "toplist_list",
        },
        {
            "id": "netease_user_playlists",
            "title": "我的歌单",
            "category": "MinePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_list_playlists",
        },
        {
            "id": "netease_favorite_songs",
            "title": "收藏歌曲",
            "category": "Mine",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_fav_create_songs_rd",
        },
        {
            "id": "netease_favorite_playlists",
            "title": "收藏歌单",
            "category": "MineFavoritePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_fav_create_playlists_rd",
        },
        {
            "id": "netease_favorite_artists",
            "title": "收藏歌手",
            "category": "Mine",
            "content_type": "Artists",
            "requires_login": True,
            "action": "current_user_fav_create_artists_rd",
        },
        {
            "id": "netease_favorite_albums",
            "title": "收藏专辑",
            "category": "Mine",
            "content_type": "Albums",
            "requires_login": True,
            "action": "current_user_fav_create_albums_rd",
        },
    ],
    "qqmusic": [
        {
            "id": "qqmusic_daily_songs",
            "title": "每日推荐歌曲",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": True,
            "action": "rec_list_daily_songs",
        },
        {
            "id": "qqmusic_daily_playlists",
            "title": "推荐歌单",
            "category": "Recommend",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "rec_list_daily_playlists",
        },
        {
            "id": "qqmusic_collection",
            "title": "为你推荐歌曲",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": True,
            "action": "rec_a_collection_of_songs",
        },
        {
            "id": "qqmusic_radio",
            "title": "私人 FM",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_list_radio_songs",
        },
        {
            "id": "qqmusic_user_playlists",
            "title": "我的歌单",
            "category": "MinePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_list_playlists",
        },
        {
            "id": "qqmusic_favorite_songs",
            "title": "收藏歌曲",
            "category": "Mine",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_fav_create_songs_rd",
        },
        {
            "id": "qqmusic_favorite_playlists",
            "title": "收藏歌单",
            "category": "MineFavoritePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_fav_create_playlists_rd",
        },
        {
            "id": "qqmusic_favorite_artists",
            "title": "收藏歌手",
            "category": "Mine",
            "content_type": "Artists",
            "requires_login": True,
            "action": "current_user_fav_create_artists_rd",
        },
        {
            "id": "qqmusic_favorite_albums",
            "title": "收藏专辑",
            "category": "Mine",
            "content_type": "Albums",
            "requires_login": True,
            "action": "current_user_fav_create_albums_rd",
        },
    ],
    "bilibili": [
        {
            "id": "bilibili_user_playlists",
            "title": "我的歌单",
            "category": "MinePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_list_playlists",
        },
        {
            "id": "bilibili_favorite_playlists",
            "title": "收藏歌单",
            "category": "MineFavoritePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_fav_create_playlists_rd",
        },
    ],
    "ytmusic": [
        {
            "id": "ytmusic_daily_songs",
            "title": "每日推荐歌曲",
            "category": "Recommend",
            "content_type": "Songs",
            "requires_login": False,
            "action": "rec_list_daily_songs",
        },
        {
            "id": "ytmusic_daily_playlists",
            "title": "推荐歌单",
            "category": "Recommend",
            "content_type": "Playlists",
            "requires_login": False,
            "action": "rec_list_daily_playlists",
        },
        {
            "id": "ytmusic_toplists",
            "title": "排行榜",
            "category": "Music",
            "content_type": "Playlists",
            "requires_login": False,
            "action": "toplist_list",
        },
        {
            "id": "ytmusic_user_playlists",
            "title": "我的歌单",
            "category": "MinePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_list_playlists",
        },
        {
            "id": "ytmusic_favorite_songs",
            "title": "收藏歌曲",
            "category": "Mine",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_fav_create_songs_rd",
        },
        {
            "id": "ytmusic_favorite_playlists",
            "title": "收藏歌单",
            "category": "MineFavoritePlaylists",
            "content_type": "Playlists",
            "requires_login": True,
            "action": "current_user_fav_create_playlists_rd",
        },
        {
            "id": "ytmusic_favorite_artists",
            "title": "收藏歌手",
            "category": "Mine",
            "content_type": "Artists",
            "requires_login": True,
            "action": "current_user_fav_create_artists_rd",
        },
        {
            "id": "ytmusic_favorite_albums",
            "title": "收藏专辑",
            "category": "Mine",
            "content_type": "Albums",
            "requires_login": True,
            "action": "current_user_fav_create_albums_rd",
        },
    ],
}

LOGIN_CONFIGS = {
    "netease": {
        "login_url": "https://music.163.com",
        "cookie_key_groups": [["MUSIC_U"]],
    },
    "qqmusic": {
        "login_url": "https://y.qq.com",
        "cookie_key_groups": [
            ["qqmusic_key", "wxuin", "qm_keyst"],
            ["qqmusic_key", "uin", "qm_keyst"],
        ],
    },
    "bilibili": {
        "login_url": "https://www.bilibili.com",
        "cookie_key_groups": [["SESSDATA", "bili_jct"]],
    },
}

LOGIN_MODES = {
    "netease": ["WebView", "Cookie"],
    "qqmusic": ["WebView", "Cookie"],
    "bilibili": ["WebView", "Cookie"],
    "ytmusic": ["Headers"],
}


class FuoMobileBridge:
    def __init__(self, providers_json: str):
        providers = json.loads(providers_json or "{}").get("enabled") or ["netease"]
        library = Library(
            MobileConfig.PROVIDERS_STANDBY,
            MobileConfig.ENABLE_AI_STANDBY_MATCHER,
        )
        self.app = MobileAppContext(
            config=MobileConfig(),
            library=library,
            player=MobileNativePlayer(),
            task_mgr=MobileTaskManager(),
        )
        self.provider_registry = ProviderRegistry(self.app, providers)
        self.provider_registry.load()
        self._tracks: Dict[str, Any] = {}
        self._playlists: Dict[str, Any] = {}
        self._media_items: Dict[str, Any] = {}
        self._restore_saved_logins()

    def providers(self) -> str:
        providers = [self._get_provider(provider_id) for provider_id in self.provider_registry.provider_ids]
        return json.dumps(
            {"providers": [provider_info(provider) for provider in providers]},
            ensure_ascii=False,
        )

    def features(self) -> str:
        features = []
        for provider_id in self.provider_registry.provider_ids:
            provider = self._get_provider(provider_id)
            for feature in FEATURE_DEFS.get(provider_id, []):
                if hasattr(provider, feature["action"]):
                    features.append(feature_to_dict(feature, provider))
        return json.dumps({"features": features}, ensure_ascii=False)

    def search(self, keyword: str, provider_id: str = "") -> str:
        tracks = []
        source_in = [provider_id] if provider_id else None
        for result in self.app.library.search(keyword, type_in=[SearchType.so], source_in=source_in):
            for song in getattr(result, "songs", []) or []:
                track = self._remember_song(song)
                tracks.append(track)
        return json.dumps({"tracks": tracks}, ensure_ascii=False)

    def resolve(self, track_id: str, audio_select_policy: str = "", allow_standby: bool = True) -> str:
        song = self._tracks.get(track_id)
        if song is None:
            song = self._song_from_track_id(track_id)
            self._tracks[track_id] = song
        policy = audio_select_policy or self.app.config.AUDIO_SELECT_POLICY
        payload = self._prepare_payload(song, policy, allow_standby)
        return json.dumps(payload, ensure_ascii=False)

    def play(self, track_id: str) -> str:
        return self.resolve(track_id)

    def provider_auth_state(self, provider_id: str) -> str:
        provider = self._get_provider(provider_id)
        user = provider.get_current_user_or_none()
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def provider_login_with_cookies(self, provider_id: str, cookies_json: str) -> str:
        provider = self._get_provider(provider_id)
        cookies = parse_cookies(cookies_json)
        if not isinstance(cookies, dict) or not cookies:
            raise RuntimeError("cookies must be a non-empty JSON object")
        if provider_id == "bilibili":
            provider._api.from_cookiedict(cookies)
            user = provider.user_info()
        elif hasattr(provider, "get_user_from_cookies"):
            user = provider.get_user_from_cookies(cookies)
        elif hasattr(provider, "try_get_user_from_cookies"):
            user, err = provider.try_get_user_from_cookies(cookies)
            if user is None:
                raise RuntimeError(err or f"get user with cookies failed: {provider_id}")
        else:
            raise RuntimeError(f"provider does not support cookies login: {provider_id}")
        provider.auth(user)
        self._save_login(provider_id, user, cookies)
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def provider_login_with_headers(self, provider_id: str, authorization: str, cookie: str) -> str:
        if provider_id != "ytmusic":
            raise RuntimeError(f"provider does not support header login: {provider_id}")
        auth = (authorization or "").strip()
        cookie_value = (cookie or "").strip()
        if not auth or not cookie_value:
            raise RuntimeError("authorization and cookie must be non-empty")
        provider = self._get_provider(provider_id)
        from fuo_ytmusic.consts import HEADER_FILE
        from fuo_ytmusic.headerfile import write_headerfile

        write_headerfile(auth, cookie_value, HEADER_FILE)
        user = provider.try_get_user_with_headerfile()
        if user is None:
            raise RuntimeError("get user with ytmusic headers failed")
        provider.auth(user)
        current_user_changed = getattr(provider, "current_user_changed", None)
        if current_user_changed is not None:
            current_user_changed.emit(user)
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def provider_logout(self, provider_id: str) -> str:
        provider = self._get_provider(provider_id)
        self._clear_provider_auth(provider_id, provider)
        self._delete_saved_login(provider_id)
        return json.dumps(provider_auth_state(provider, None), ensure_ascii=False)

    def load_feature(self, feature_id: str) -> str:
        feature = self._feature_by_id(feature_id)
        provider = self._get_provider(feature["provider_id"])
        feature_payload = feature_to_dict(feature, provider)
        if feature.get("requires_login") and provider.get_current_user_or_none() is None:
            return json.dumps(
                {
                    "feature": feature_payload,
                    "tracks": [],
                    "playlists": [],
                    "media_items": [],
                    "is_login_required": True,
                    "error_message": "",
                },
                ensure_ascii=False,
            )
        try:
            tracks, playlists, media_items, title = self._load_feature_content(provider, feature)
            if title:
                feature_payload["title"] = title
            payload = {
                "feature": feature_payload,
                "tracks": tracks,
                "playlists": playlists,
                "media_items": media_items,
                "is_login_required": False,
                "error_message": "",
            }
        except Exception as exc:  # pylint: disable=broad-except
            payload = {
                "feature": feature_payload,
                "tracks": [],
                "playlists": [],
                "media_items": [],
                "is_login_required": False,
                "error_message": str(exc) or exc.__class__.__name__,
            }
        return json.dumps(payload, ensure_ascii=False)

    def playlist_tracks(self, playlist_id: str) -> str:
        bridge_log(f"playlist_tracks start playlist_id={playlist_id}")
        playlist = self._playlist_from_id(playlist_id)
        provider = self._get_provider(getattr(playlist, "source", ""))
        bridge_log(
            f"playlist_tracks resolved playlist_id={playlist_id} source={getattr(playlist, 'source', '')}"
        )
        reader = provider.playlist_create_songs_rd(playlist)
        bridge_log(f"playlist_tracks reader created playlist_id={playlist_id}")
        songs = read_models(reader, limit=300)
        bridge_log(f"playlist_tracks songs read playlist_id={playlist_id} count={len(songs)}")
        tracks = [self._remember_song(song) for song in songs]
        bridge_log(f"playlist_tracks done playlist_id={playlist_id} count={len(tracks)}")
        return json.dumps({"tracks": tracks}, ensure_ascii=False)

    def media_item_tracks(self, item_id: str) -> str:
        bridge_log(f"media_item_tracks start item_id={item_id}")
        item_type, provider_id, _ = self._parse_media_item_id(item_id)
        item = self._media_item_from_id(item_id)
        provider = self._get_provider(provider_id)
        if item_type == "artist":
            reader = provider.artist_create_songs_rd(item)
        elif item_type == "album":
            reader = provider.album_create_songs_rd(item)
        else:
            raise RuntimeError(f"unsupported media item type: {item_type}")
        songs = read_models(reader, limit=300)
        tracks = [self._remember_song(song) for song in songs]
        bridge_log(f"media_item_tracks done item_id={item_id} count={len(tracks)}")
        return json.dumps({"tracks": tracks}, ensure_ascii=False)

    def _get_provider(self, provider_id: str):
        provider = self.app.library.get(provider_id)
        if provider is None:
            raise RuntimeError(f"provider not found: {provider_id}")
        return provider

    def _feature_by_id(self, feature_id: str) -> Dict[str, Any]:
        for provider_id, features in FEATURE_DEFS.items():
            for feature in features:
                if feature["id"] == feature_id:
                    return {**feature, "provider_id": provider_id}
        raise RuntimeError(f"feature not found: {feature_id}")

    def _load_feature_content(self, provider, feature: Dict[str, Any]):
        action = feature["action"]
        if action == "rec_list_daily_songs":
            songs = read_models(provider.rec_list_daily_songs(), limit=50)
            return [self._remember_song(song) for song in songs], [], [], ""
        if action == "rec_list_daily_playlists":
            playlists = read_models(provider.rec_list_daily_playlists(), limit=30)
            return [], [self._remember_playlist(playlist) for playlist in playlists], [], ""
        if action == "current_user_list_radio_songs":
            songs = provider.current_user_list_radio_songs(20)
            return [self._remember_song(song) for song in songs], [], [], ""
        if action == "toplist_list":
            playlists = read_models(provider.toplist_list(), limit=50)
            return [], [self._remember_playlist(playlist) for playlist in playlists], [], ""
        if action == "rec_a_collection_of_songs":
            collection = provider.rec_a_collection_of_songs()
            songs = read_models(getattr(collection, "models", []) or [], limit=50)
            return [self._remember_song(song) for song in songs], [], [], getattr(collection, "name", "")
        if action == "current_user_fav_create_songs_rd":
            songs = read_models(provider.current_user_fav_create_songs_rd(), limit=100)
            return [self._remember_song(song) for song in songs], [], [], ""
        if action == "current_user_fav_create_playlists_rd":
            playlists = read_models(provider.current_user_fav_create_playlists_rd(), limit=60)
            return [], [self._remember_playlist(playlist) for playlist in playlists], [], ""
        if action == "current_user_list_playlists":
            playlists = read_models(provider.current_user_list_playlists(), limit=60)
            return [], [self._remember_playlist(playlist) for playlist in playlists], [], ""
        if action == "current_user_fav_create_artists_rd":
            artists = read_models(provider.current_user_fav_create_artists_rd(), limit=60)
            return [], [], [self._remember_media_item(artist, "artist") for artist in artists], ""
        if action == "current_user_fav_create_albums_rd":
            albums = read_models(provider.current_user_fav_create_albums_rd(), limit=60)
            return [], [], [self._remember_media_item(album, "album") for album in albums], ""
        raise RuntimeError(f"unsupported feature action: {action}")

    def _playlist_from_id(self, playlist_id: str):
        playlist = self._playlists.get(playlist_id)
        if playlist is not None:
            return playlist
        try:
            _, provider_id, identifier = playlist_id.split(":", 2)
        except ValueError as exc:
            raise RuntimeError(f"invalid playlist id: {playlist_id}") from exc
        provider = self._get_provider(provider_id)
        playlist = provider.playlist_get(identifier)
        self._playlists[playlist_id] = playlist
        return playlist

    def _media_item_from_id(self, item_id: str):
        item = self._media_items.get(item_id)
        if item is not None:
            return item
        item_type, provider_id, identifier = self._parse_media_item_id(item_id)
        provider = self._get_provider(provider_id)
        if item_type == "artist":
            item = provider.artist_get(identifier)
        elif item_type == "album":
            item = provider.album_get(identifier)
        else:
            raise RuntimeError(f"unsupported media item type: {item_type}")
        self._media_items[item_id] = item
        return item

    def _parse_media_item_id(self, item_id: str):
        try:
            item_type, provider_id, identifier = item_id.split(":", 2)
        except ValueError as exc:
            raise RuntimeError(f"invalid media item id: {item_id}") from exc
        return item_type, provider_id, identifier

    def _song_from_track_id(self, track_id: str):
        try:
            provider_id, identifier = track_id.split(":", 1)
        except ValueError as exc:
            raise RuntimeError(f"invalid track id: {track_id}") from exc
        provider = self._get_provider(provider_id)
        if not hasattr(provider, "song_get"):
            raise RuntimeError(f"provider does not support song_get: {provider_id}")
        return provider.song_get(identifier)

    def _remember_song(self, song) -> Dict[str, Any]:
        track = song_to_dict(song, self.app.library)
        self._tracks[track["id"]] = song
        return track

    def _remember_playlist(self, playlist) -> Dict[str, Any]:
        data = playlist_to_dict(playlist, self.app.library)
        self._playlists[data["id"]] = playlist
        return data

    def _remember_media_item(self, item, item_type: str) -> Dict[str, Any]:
        data = media_item_to_dict(item, item_type, self.app.library)
        self._media_items[data["id"]] = item
        return data

    def _save_login(self, provider_id: str, user, cookies: Dict[str, str]) -> None:
        if provider_id == "netease":
            from fuo_netease.login_controller import LoginController
            from fuo_netease.consts import USERS_INFO_FILE

            os.makedirs(os.path.dirname(USERS_INFO_FILE), exist_ok=True)
            LoginController.save(user)
        elif provider_id == "qqmusic":
            from fuo_qqmusic.login import write_cookies

            write_cookies(user, cookies)
        elif provider_id == "bilibili":
            from fuo_bilibili.login import dump_user_cookies

            dump_user_cookies(cookies)

    def _delete_saved_login(self, provider_id: str) -> None:
        if provider_id == "netease":
            from fuo_netease.consts import USERS_INFO_FILE

            if os.path.exists(USERS_INFO_FILE):
                os.remove(USERS_INFO_FILE)
        elif provider_id == "qqmusic":
            from fuo_qqmusic.login import USER_INFO_FILE

            if os.path.exists(USER_INFO_FILE):
                os.remove(USER_INFO_FILE)
        elif provider_id == "bilibili":
            from fuo_bilibili.const import PLUGIN_API_COOKIEJAR_FILE, PLUGIN_API_COOKIEDICT_FILE

            for path in (PLUGIN_API_COOKIEJAR_FILE, PLUGIN_API_COOKIEDICT_FILE):
                if os.path.exists(path):
                    os.remove(path)
        elif provider_id == "ytmusic":
            from fuo_ytmusic.consts import HEADER_FILE
            from fuo_ytmusic.headerfile import YtdlpCookiefileManager

            cookiefile = YtdlpCookiefileManager(HEADER_FILE).cookiefile_path
            for path in (HEADER_FILE, cookiefile):
                if path is not None and os.path.exists(path):
                    os.remove(path)

    def _clear_provider_auth(self, provider_id: str, provider) -> None:
        try:
            provider.auth(None)
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"provider auth(None) failed provider_id={provider_id}: {exc}")
            setattr(provider, "_user", None)
        if provider_id == "netease":
            from fuo_netease.api import API
            from fuo_netease.login_controller import LoginController

            provider.api = API()
            LoginController._api = provider.api
        elif provider_id == "qqmusic":
            provider.api.set_cookies(None)
        elif provider_id == "bilibili":
            from fuo_bilibili.api import BilibiliApi

            provider._api = BilibiliApi()
        elif provider_id == "ytmusic":
            provider._user = None
        current_user_changed = getattr(provider, "current_user_changed", None)
        if current_user_changed is not None:
            current_user_changed.emit(None)

    def _restore_saved_logins(self) -> None:
        for provider_id in self.provider_registry.provider_ids:
            try:
                self._restore_saved_login(provider_id)
            except Exception as exc:  # pylint: disable=broad-except
                bridge_log(f"restore login failed provider_id={provider_id}: {exc}")

    def _restore_saved_login(self, provider_id: str) -> None:
        provider = self._get_provider(provider_id)
        user = None
        if provider_id == "netease":
            from fuo_netease.login_controller import LoginController

            user = LoginController.load()
        elif provider_id == "qqmusic":
            from fuo_qqmusic.login import read_cookies

            cookies = read_cookies()
            if hasattr(provider, "try_get_user_from_cookies"):
                user, err = provider.try_get_user_from_cookies(cookies)
                if user is None:
                    bridge_log(f"restore login skipped provider_id={provider_id}: {err}")
        elif provider_id == "bilibili":
            from fuo_bilibili.login import load_user_cookies

            cookies = load_user_cookies()
            if cookies:
                provider._api.from_cookiedict(cookies)
                user = provider.user_info()
        elif provider_id == "ytmusic":
            user = provider.try_get_user_with_headerfile()
        if user is not None:
            provider.auth(user)
            current_user_changed = getattr(provider, "current_user_changed", None)
            if current_user_changed is not None:
                current_user_changed.emit(user)
            bridge_log(f"restore login ok provider_id={provider_id} user={getattr(user, 'name', '')}")

    def _prepare_payload(self, song, audio_select_policy: str, allow_standby: bool) -> Dict[str, Any]:
        try:
            media, quality = self._select_media(song, audio_select_policy)
        except MediaNotFound as exc:
            bridge_log(
                "media not found "
                f"track={song_log_label(song)} allow_standby={allow_standby} policy={audio_select_policy}"
            )
            if allow_standby:
                standby_payload = self._prepare_standby_payload(song, audio_select_policy)
                if standby_payload is not None:
                    return standby_payload
            raise RuntimeError(f"media not found: {song}") from exc
        payload = self._payload_from_media(song, media, quality)
        return payload

    def _prepare_standby_payload(self, song, audio_select_policy: str) -> Optional[Dict[str, Any]]:
        source = getattr(song, "source", "")
        source_in = [provider_id for provider_id in self.provider_registry.provider_ids if provider_id != source]
        if not source_in:
            bridge_log(f"standby skipped no source track={song_log_label(song)}")
            return None
        bridge_log(
            f"standby start track={song_log_label(song)} source_in={source_in} policy={audio_select_policy}"
        )
        try:
            standby_list = asyncio.run(
                self.app.library.a_list_song_standby_v2(
                    song,
                    audio_select_policy=audio_select_policy,
                    source_in=source_in,
                    limit=1,
                )
            )
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"standby failed track={song_log_label(song)} error={exc}")
            return self._prepare_search_standby_payload(song, audio_select_policy, source_in)
        if not standby_list:
            bridge_log(f"standby empty track={song_log_label(song)}")
            return self._prepare_search_standby_payload(song, audio_select_policy, source_in)
        standby, _ = standby_list[0]
        self._tracks[f"{getattr(standby, 'source', '')}:{getattr(standby, 'identifier', '')}"] = standby
        bridge_log(
            f"standby selected origin={song_log_label(song)} replacement={song_log_label(standby)}"
        )
        try:
            media, quality = self._select_media(standby, audio_select_policy)
        except MediaNotFound:
            bridge_log(f"standby selected media not found replacement={song_log_label(standby)}")
            return self._prepare_search_standby_payload(song, audio_select_policy, source_in)
        payload = self._payload_from_media(standby, media, quality)
        return self._mark_standby_payload(payload, song, standby, "library", None)

    def _prepare_search_standby_payload(
        self,
        song,
        audio_select_policy: str,
        source_in: List[str],
    ) -> Optional[Dict[str, Any]]:
        candidates = []
        seen = set()
        for query in standby_queries(song):
            bridge_log(f"search standby query track={song_log_label(song)} query={query} source_in={source_in}")
            for result in self.app.library.search(query, type_in=[SearchType.so], source_in=source_in):
                for standby in read_models(getattr(result, "songs", []) or [], limit=10):
                    key = (getattr(standby, "source", ""), getattr(standby, "identifier", ""))
                    if key in seen or key == (getattr(song, "source", ""), getattr(song, "identifier", "")):
                        continue
                    seen.add(key)
                    score = standby_score(song, standby)
                    if score >= 0.55:
                        bridge_log(
                            f"search standby candidate score={score:.2f} replacement={song_log_label(standby)}"
                        )
                        candidates.append((score, standby))
        if not candidates:
            bridge_log(f"search standby empty track={song_log_label(song)}")
            return None
        for score, standby in sorted(candidates, key=lambda item: item[0], reverse=True)[:8]:
            try:
                media, quality = self._select_media(standby, audio_select_policy)
            except MediaNotFound:
                bridge_log(
                    f"search standby candidate media not found score={score:.2f} replacement={song_log_label(standby)}"
                )
                continue
            self._tracks[f"{getattr(standby, 'source', '')}:{getattr(standby, 'identifier', '')}"] = standby
            bridge_log(
                f"search standby selected score={score:.2f} "
                f"origin={song_log_label(song)} replacement={song_log_label(standby)}"
            )
            payload = self._payload_from_media(standby, media, quality)
            return self._mark_standby_payload(payload, song, standby, "search", score)
        bridge_log(f"search standby media empty track={song_log_label(song)} candidates={len(candidates)}")
        return None

    def _select_media(self, song, audio_select_policy: str):
        provider = self.app.library.get(getattr(song, "source", ""))
        if provider is not None and isinstance(provider, SupportsSongMultiQuality):
            try:
                media, quality = provider.song_select_media(song, audio_select_policy)
            except MediaNotFound:
                refreshed_song = self._refresh_song_for_media_retry(song, provider)
                if refreshed_song is None:
                    raise
                media, quality = provider.song_select_media(refreshed_song, audio_select_policy)
                song = refreshed_song
            self._tracks[f"{getattr(song, 'source', '')}:{getattr(song, 'identifier', '')}"] = song
            return media, getattr(quality, "value", str(quality)).upper()
        return self.app.library.song_prepare_media(song, audio_select_policy), ""

    def _refresh_song_for_media_retry(self, song, provider):
        source = getattr(song, "source", "")
        identifier = getattr(song, "identifier", "")
        if source != "netease" or not identifier:
            return None
        try:
            song.cache_set("q_media_mapping", None, ttl=-1)
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"netease media cache clear failed track={song_log_label(song)} error={exc}")
        refreshed_song = None
        try:
            refreshed_song = provider.song_get(identifier)
            refreshed_song.cache_set("q_media_mapping", None, ttl=-1)
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"netease song refresh failed track={song_log_label(song)} error={exc}")
        retry_song = refreshed_song or song
        bridge_log(f"netease media retry track={song_log_label(retry_song)}")
        return retry_song

    def _payload_from_media(self, song, media: Media, quality: str) -> Dict[str, Any]:
        payload = media_to_payload(media, song_to_metadata(song, self.app.library))
        if quality:
            payload["audio_quality"] = quality
        cover = self._get_cover(song)
        if cover:
            payload["cover_url"] = cover
        lyrics = self._get_lyrics(song)
        if lyrics:
            payload["lyrics"] = lyrics
        return payload

    def _mark_standby_payload(
        self,
        payload: Dict[str, Any],
        origin,
        standby,
        strategy: str,
        score: Optional[float],
    ) -> Dict[str, Any]:
        origin_provider = provider_name(self.app.library.get(getattr(origin, "source", "")))
        standby_provider = provider_name(self.app.library.get(getattr(standby, "source", "")))
        payload.update(
            {
                "smart_replacement": True,
                "standby_strategy": strategy,
                "original_id": f"{getattr(origin, 'source', '')}:{getattr(origin, 'identifier', '')}",
                "original_title": display(origin, "title"),
                "original_artists": display_artists(origin),
                "original_source": getattr(origin, "source", ""),
                "original_provider_name": origin_provider,
                "replacement_id": f"{getattr(standby, 'source', '')}:{getattr(standby, 'identifier', '')}",
                "replacement_title": display(standby, "title"),
                "replacement_artists": display_artists(standby),
                "replacement_source": getattr(standby, "source", ""),
                "replacement_provider_name": standby_provider,
            }
        )
        if score is not None:
            payload["standby_score"] = round(score, 2)
        bridge_log(
            f"standby payload ready strategy={strategy} "
            f"origin={song_log_label(origin)} replacement={song_log_label(standby)} "
            f"quality={payload.get('audio_quality', '')}"
        )
        return payload

    def _get_cover(self, song) -> str:
        try:
            return normalize_image_url(self.app.library.model_get_cover(song))
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"cover lookup failed: {exc}")
            return ""

    def _get_lyrics(self, song) -> str:
        try:
            lyric = self.app.library.song_get_lyric(song)
        except Exception:
            return ""
        if lyric is None:
            return ""
        content = getattr(lyric, "content", "") or ""
        trans_content = getattr(lyric, "trans_content", "") or ""
        if content and trans_content:
            return f"{content}\n{trans_content}"
        return content or trans_content


def create_bridge(providers_json: str) -> FuoMobileBridge:
    return FuoMobileBridge(providers_json)


def bridge_log(message: str) -> None:
    try:
        from java import jclass

        jclass("android.util.Log").d("FuoPythonBridge", message)
    except Exception:
        print(f"FuoPythonBridge D {message}", flush=True)


def provider_info(provider) -> Dict[str, Any]:
    provider_id = getattr(provider, "identifier", "")
    return {
        "provider_id": provider_id,
        "provider_name": provider_name(provider),
        "login_config": LOGIN_CONFIGS.get(provider_id),
        "login_modes": LOGIN_MODES.get(provider_id, ["WebView", "Cookie"]),
    }


def feature_to_dict(feature: Dict[str, Any], provider) -> Dict[str, Any]:
    return {
        "id": feature["id"],
        "provider_id": getattr(provider, "identifier", ""),
        "provider_name": provider_name(provider),
        "title": feature["title"],
        "category": feature["category"],
        "content_type": feature["content_type"],
        "requires_login": bool(feature.get("requires_login")),
    }


def provider_auth_state(provider, user) -> Dict[str, Any]:
    return {
        "provider_id": getattr(provider, "identifier", ""),
        "provider_name": provider_name(provider),
        "is_logged_in": user is not None,
        "user_name": getattr(user, "name", "") if user is not None else "",
    }


def parse_cookies(raw: str) -> Dict[str, str]:
    text = (raw or "").strip()
    if not text:
        return {}
    if text.startswith("{"):
        return json.loads(text)
    cookies = {}
    for part in text.split(";"):
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        key = key.strip()
        if key:
            cookies[key] = value.strip()
    return cookies


def read_models(value, limit: int = 50) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value[:limit]
    if hasattr(value, "read_range"):
        return value.read_range(0, limit)
    if hasattr(value, "readall"):
        return value.readall()[:limit]
    try:
        result = []
        for model in value:
            result.append(model)
            if len(result) >= limit:
                break
        return result
    except TypeError:
        return []


def song_to_dict(song, library: Library) -> Dict[str, Any]:
    source = getattr(song, "source", "")
    identifier = getattr(song, "identifier", "")
    return {
        "id": f"{source}:{identifier}",
        "title": display(song, "title"),
        "artists": display_artists(song),
        "album": display_album(song),
        "source": source,
        "provider_name": provider_name(library.get(source)),
        "duration_ms": duration_ms(song),
        "cover_url": normalize_image_url(display(song, "pic_url")),
    }


def song_to_metadata(song, library: Library) -> Dict[str, Any]:
    data = song_to_dict(song, library)
    return {
        "title": data["title"],
        "artists": data["artists"],
        "album": data["album"],
        "source": data["source"],
        "provider_name": data["provider_name"],
        "duration_ms": data["duration_ms"],
        "cover_url": data["cover_url"],
    }


def playlist_to_dict(playlist, library: Library) -> Dict[str, Any]:
    source = getattr(playlist, "source", "")
    identifier = getattr(playlist, "identifier", "")
    return {
        "id": f"playlist:{source}:{identifier}",
        "title": display(playlist, "name"),
        "provider_id": source,
        "provider_name": provider_name(library.get(source)),
        "cover_url": normalize_image_url(display(playlist, "cover")),
        "description": display(playlist, "description") or display(playlist, "creator_name"),
        "play_count": play_count(playlist),
    }


def media_item_to_dict(item, item_type: str, library: Library) -> Dict[str, Any]:
    source = getattr(item, "source", "")
    identifier = getattr(item, "identifier", "")
    return {
        "id": f"{item_type}:{source}:{identifier}",
        "title": display(item, "name") or display(item, "title"),
        "provider_id": source,
        "provider_name": provider_name(library.get(source)),
        "type": "Artist" if item_type == "artist" else "Album",
        "cover_url": normalize_image_url(display(item, "pic_url") or display(item, "cover") or display(item, "cover_url")),
        "description": display(item, "description") or display(item, "artists_name"),
    }


def media_to_payload(media: Media, metadata: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    metadata = metadata or {}
    if media is None:
        raise RuntimeError("empty media")
    return {
        "url": media.url,
        "title": metadata.get("title", ""),
        "artists": metadata.get("artists", ""),
        "album": metadata.get("album", ""),
        "source": metadata.get("source", ""),
        "provider_name": metadata.get("provider_name", ""),
        "headers": dict(media.http_headers or {}),
        "cover_url": normalize_image_url(metadata.get("cover_url", "")),
        "duration_ms": metadata.get("duration_ms", 0),
        "lyrics": metadata.get("lyrics", ""),
    }


def display(model, field: str) -> str:
    display_field = f"{field}_display"
    value = getattr(model, display_field, None)
    if value:
        return str(value)
    value = getattr(model, field, "")
    if isinstance(value, (list, tuple)):
        return " / ".join(str(each) for each in value)
    return str(value or "")


def normalize_image_url(url: Optional[str]) -> str:
    value = str(url or "")
    if value.startswith("http://qpic.y.qq.com/") or value.startswith("http://y.gtimg.cn/"):
        return "https://" + value[len("http://"):]
    return value


def display_artists(song) -> str:
    artists_name = display(song, "artists_name")
    if artists_name:
        return artists_name
    artists = getattr(song, "artists", []) or []
    names = [display(artist, "name") for artist in artists]
    return " / ".join(name for name in names if name)


def display_album(song) -> str:
    album_name = display(song, "album_name")
    if album_name:
        return album_name
    album = getattr(song, "album", None)
    if album is None:
        return ""
    return display(album, "name")


def standby_queries(song) -> List[str]:
    title = display(song, "title").strip()
    artists = display_artists(song).strip()
    queries = []
    if title and artists:
        queries.append(f"{title} {artists}")
    if title:
        queries.append(title)
    return unique_texts(queries)


def standby_score(origin, standby) -> float:
    origin_title = normalize_match_text(display(origin, "title"))
    standby_title = normalize_match_text(display(standby, "title"))
    if not origin_title or not standby_title:
        return 0.0
    if origin_title == standby_title:
        score = 0.55
    elif (
        len(origin_title) >= 4
        and len(standby_title) >= 4
        and (origin_title in standby_title or standby_title in origin_title)
    ):
        score = 0.35
    else:
        return 0.0

    origin_artists = normalize_match_text(display_artists(origin))
    standby_artists = normalize_match_text(display_artists(standby))
    if origin_artists and standby_artists:
        if origin_artists == standby_artists:
            score += 0.25
        elif origin_artists in standby_artists or standby_artists in origin_artists:
            score += 0.15

    origin_album = normalize_match_text(display_album(origin))
    standby_album = normalize_match_text(display_album(standby))
    if origin_album and standby_album and origin_album == standby_album:
        score += 0.10

    origin_duration = duration_ms(origin)
    standby_duration = duration_ms(standby)
    if origin_duration and standby_duration:
        if abs(origin_duration - standby_duration) / max(origin_duration, 1) < 0.10:
            score += 0.10
    return score


def normalize_match_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value or "").casefold()
    return "".join(char for char in normalized if char.isalnum())


def unique_texts(values: List[str]) -> List[str]:
    result = []
    seen = set()
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


def provider_name(provider) -> str:
    if provider is None:
        return ""
    return getattr(provider, "name", getattr(provider, "identifier", ""))


def song_log_label(song) -> str:
    source = getattr(song, "source", "")
    identifier = getattr(song, "identifier", "")
    title = display(song, "title")
    artists = display_artists(song)
    return f"{source}:{identifier} title={title} artists={artists}"


def duration_ms(song) -> int:
    duration = getattr(song, "duration", None) or getattr(song, "duration_ms", None)
    if not duration:
        return 0
    try:
        value = int(duration)
    except (TypeError, ValueError):
        return 0
    if value < 10_000:
        return value * 1000
    return value


def play_count(model) -> int:
    value = getattr(model, "play_count", None)
    if value is None:
        return 0
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0
