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
        "login_url": "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
        "cookie_key_groups": [["SESSDATA", "bili_jct"]],
    },
    "ytmusic": {
        "login_url": "https://music.youtube.com",
        "cookie_key_groups": [["__Secure-3PAPISID"]],
    },
}

LOGIN_MODES = {
    "netease": ["WebView", "Cookie"],
    "qqmusic": ["WebView", "Cookie"],
    "bilibili": ["WebView", "Cookie"],
    "ytmusic": ["WebView", "Headers"],
}

BILIBILI_MEDIA_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)


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

    def provider_capabilities(self) -> str:
        providers = [self._get_provider(provider_id) for provider_id in self.provider_registry.provider_ids]
        return json.dumps(
            {"providers": [provider_capabilities(provider) for provider in providers]},
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

    def track_detail(self, track_id: str) -> str:
        song = self._tracks.get(track_id)
        if song is None:
            song = self._song_from_track_id(track_id)
            self._tracks[track_id] = song
        return json.dumps({"track": self._remember_song(song)}, ensure_ascii=False)

    def resolve(
        self,
        track_id: str,
        audio_select_policy: str = "",
        allow_standby: bool = True,
        standby_provider_ids_json: str = "",
        standby_min_score: float = 0.55,
        standby_use_origin_metadata: bool = False,
        standby_use_origin_lyrics: bool = False,
    ) -> str:
        song = self._tracks.get(track_id)
        if song is None:
            song = self._song_from_track_id(track_id)
            self._tracks[track_id] = song
        policy = audio_select_policy or self.app.config.AUDIO_SELECT_POLICY
        standby_provider_ids = parse_provider_ids(standby_provider_ids_json)
        min_score = normalize_standby_min_score(standby_min_score)
        payload = self._prepare_payload(
            song,
            policy,
            allow_standby,
            standby_provider_ids,
            min_score,
            standby_use_origin_metadata,
            standby_use_origin_lyrics,
        )
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
        if provider_id == "ytmusic":
            user = self._login_ytmusic_with_cookies(provider, cookies)
            return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)
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

    def _login_ytmusic_with_cookies(self, provider, cookies: Dict[str, str]):
        cookie_value = ytmusic_cookie_header(cookies)
        auth = ytmusic_authorization_from_cookies(cookies)
        from fuo_ytmusic.consts import HEADER_FILE
        from fuo_ytmusic.headerfile import write_headerfile

        write_headerfile(auth, cookie_value, HEADER_FILE)
        user = provider.try_get_user_with_headerfile()
        if user is None:
            raise RuntimeError("get user with ytmusic cookies failed")
        provider.auth(user)
        current_user_changed = getattr(provider, "current_user_changed", None)
        if current_user_changed is not None:
            current_user_changed.emit(user)
        return user

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
        playlist = self._playlist_for_tracks(playlist_id, provider, playlist)
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

    def playlist_detail(self, playlist_id: str) -> str:
        bridge_log(f"playlist_detail start playlist_id={playlist_id}")
        payload = self._playlist_detail_payload(playlist_id)
        bridge_log(
            f"playlist_detail done playlist_id={playlist_id} count={len(payload['tracks'])}"
        )
        return json.dumps(payload, ensure_ascii=False)

    def playlist_operation_targets(self, track_id: str) -> str:
        song = self._song_for_operation(track_id)
        provider_id = getattr(song, "source", "")
        provider = self._get_provider(provider_id)
        self._require_logged_in(provider)
        if not hasattr(provider, "playlist_add_song"):
            return json.dumps({"playlists": []}, ensure_ascii=False)
        playlists = read_models(provider.current_user_list_playlists(), limit=100)
        targets = [
            self._remember_playlist(playlist)
            for playlist in playlists
            if getattr(playlist, "source", "") == provider_id
        ]
        return json.dumps({"playlists": targets}, ensure_ascii=False)

    def playlist_add_song(self, playlist_id: str, track_id: str) -> str:
        playlist = self._playlist_from_id(playlist_id)
        song = self._song_for_operation(track_id)
        provider_id = getattr(playlist, "source", "")
        provider = self._get_provider(provider_id)
        self._require_logged_in(provider)
        if getattr(song, "source", "") != provider_id:
            return json.dumps(mutation_result(False, "只能添加同一音源的歌曲"), ensure_ascii=False)
        add_song = getattr(provider, "playlist_add_song", None)
        if add_song is None:
            return json.dumps(mutation_result(False, "当前音源不支持添加歌曲到歌单"), ensure_ascii=False)
        ok = bool(add_song(playlist, song))
        if ok:
            self._playlists.pop(playlist_id, None)
        title = display(song, "title")
        playlist_name = display(playlist, "name")
        message = f"已添加到：{playlist_name}" if ok else f"添加失败：{title}"
        return json.dumps(mutation_result(ok, message), ensure_ascii=False)

    def playlist_remove_song(self, playlist_id: str, track_id: str) -> str:
        playlist = self._playlist_from_id(playlist_id)
        song = self._song_for_operation(track_id)
        provider_id = getattr(playlist, "source", "")
        provider = self._get_provider(provider_id)
        self._require_logged_in(provider)
        if getattr(song, "source", "") != provider_id:
            return json.dumps(mutation_result(False, "只能移除同一音源的歌曲"), ensure_ascii=False)
        remove_song = getattr(provider, "playlist_remove_song", None)
        if remove_song is None:
            return json.dumps(mutation_result(False, "当前音源不支持从歌单移除歌曲"), ensure_ascii=False)
        ok = bool(remove_song(playlist, song))
        if ok:
            self._playlists.pop(playlist_id, None)
        title = display(song, "title")
        message = f"已从歌单移除：{title}" if ok else f"移除失败：{title}"
        return json.dumps(mutation_result(ok, message), ensure_ascii=False)

    def resource_state(self, resource_type: str, resource_id: str) -> str:
        provider_id = resource_id.split(":", 2)[1] if ":" in resource_id else ""
        return json.dumps(
            {
                "provider_id": provider_id,
                "resource_id": resource_id,
                "is_favorite": False,
                "can_favorite": False,
                "can_unfavorite": False,
            },
            ensure_ascii=False,
        )

    def set_resource_favorite(self, resource_type: str, resource_id: str, favorite: bool) -> str:
        return json.dumps(mutation_result(False, "当前音源不支持该收藏操作"), ensure_ascii=False)

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

    def media_item_detail(self, item_id: str) -> str:
        bridge_log(f"media_item_detail start item_id={item_id}")
        payload = self._media_item_detail_payload(item_id)
        bridge_log(
            f"media_item_detail done item_id={item_id} "
            f"tracks={len(payload['tracks'])} albums={len(payload['albums'])}"
        )
        return json.dumps(payload, ensure_ascii=False)

    def _get_provider(self, provider_id: str):
        provider = self.app.library.get(provider_id)
        if provider is None:
            raise RuntimeError(f"provider not found: {provider_id}")
        return provider

    def _require_logged_in(self, provider):
        if provider.get_current_user_or_none() is None:
            raise RuntimeError(f"{provider_name(provider)} 未登录")

    def _song_for_operation(self, track_id: str):
        return self._tracks.get(track_id) or self._song_from_track_id(track_id)

    def _playlist_for_tracks(self, playlist_id: str, provider, playlist):
        if getattr(playlist, "source", "") != "bilibili":
            return playlist
        if model_declares_field(playlist, "count"):
            return playlist
        playlist_get = getattr(provider, "playlist_get", None)
        if playlist_get is None:
            return playlist
        detail = playlist_get(getattr(playlist, "identifier", ""))
        if detail is None:
            return playlist
        self._playlists[playlist_id] = detail
        bridge_log(f"playlist_tracks hydrated playlist_id={playlist_id}")
        return detail

    def _playlist_detail_payload(self, playlist_id: str) -> Dict[str, Any]:
        playlist = self._playlist_from_id(playlist_id)
        detail_playlist = self._playlist_detail_from_id(playlist_id)
        provider = self._get_provider(getattr(playlist, "source", ""))
        track_playlist = self._playlist_for_tracks(playlist_id, provider, playlist)
        bridge_log(
            f"playlist_detail resolved playlist_id={playlist_id} source={getattr(track_playlist, 'source', '')}"
        )
        reader = provider.playlist_create_songs_rd(track_playlist)
        songs = read_models(reader, limit=300)
        tracks = [self._remember_song(song) for song in songs]
        return {
            "playlist": self._remember_playlist(detail_playlist),
            "tracks": tracks,
        }

    def _media_item_detail_payload(self, item_id: str) -> Dict[str, Any]:
        item_type, provider_id, _ = self._parse_media_item_id(item_id)
        item = self._media_item_from_id(item_id)
        detail_item = self._media_item_detail_from_id(item_id)
        provider = self._get_provider(provider_id)
        albums = []
        if item_type == "artist":
            reader = provider.artist_create_songs_rd(item)
            album_reader = getattr(provider, "artist_create_albums_rd", None)
            if album_reader is not None:
                albums = [
                    self._remember_media_item(album, "album")
                    for album in read_models(album_reader(item), limit=100)
                ]
        elif item_type == "album":
            reader = provider.album_create_songs_rd(item)
        else:
            raise RuntimeError(f"unsupported media item type: {item_type}")
        songs = read_models(reader, limit=300)
        tracks = [self._remember_song(song) for song in songs]
        return {
            "item": self._remember_media_item(detail_item, item_type),
            "tracks": tracks,
            "albums": albums,
        }

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

    def _playlist_detail_from_id(self, playlist_id: str):
        try:
            _, provider_id, identifier = playlist_id.split(":", 2)
        except ValueError as exc:
            raise RuntimeError(f"invalid playlist id: {playlist_id}") from exc
        provider = self._get_provider(provider_id)
        playlist_get = getattr(provider, "playlist_get", None)
        if playlist_get is not None:
            detail = playlist_get(identifier)
            if detail is not None:
                self._playlists[playlist_id] = detail
                return detail
        return self._playlist_from_id(playlist_id)

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

    def _media_item_detail_from_id(self, item_id: str):
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
        song = provider.song_get(identifier)
        if provider_id == "bilibili" and identifier.startswith("paged_"):
            for child in getattr(song, "children", None) or []:
                if getattr(child, "identifier", "") == identifier:
                    self._tracks[track_id] = child
                    return child
        return song

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

    def _prepare_payload(
        self,
        song,
        audio_select_policy: str,
        allow_standby: bool,
        standby_provider_ids: Optional[List[str]],
        standby_min_score: float,
        standby_use_origin_metadata: bool,
        standby_use_origin_lyrics: bool,
    ) -> Dict[str, Any]:
        try:
            playback_song, media, quality, parts, current_part_index = self._select_media(song, audio_select_policy)
        except MediaNotFound as exc:
            bridge_log(
                "media not found "
                f"track={song_log_label(song)} allow_standby={allow_standby} policy={audio_select_policy}"
            )
            if allow_standby:
                standby_payload = self._prepare_standby_payload(
                    song,
                    audio_select_policy,
                    standby_provider_ids,
                    standby_min_score,
                    standby_use_origin_metadata,
                    standby_use_origin_lyrics,
                )
                if standby_payload is not None:
                    return standby_payload
            raise RuntimeError(f"media not found: {song}") from exc
        payload = self._payload_from_media(playback_song, media, quality, parts, current_part_index)
        return payload

    def _prepare_standby_payload(
        self,
        song,
        audio_select_policy: str,
        standby_provider_ids: Optional[List[str]],
        standby_min_score: float,
        standby_use_origin_metadata: bool,
        standby_use_origin_lyrics: bool,
    ) -> Optional[Dict[str, Any]]:
        source = getattr(song, "source", "")
        provider_ids = standby_provider_ids or self.provider_registry.provider_ids
        loaded_provider_ids = set(self.provider_registry.provider_ids)
        source_in = [
            provider_id for provider_id in provider_ids
            if provider_id in loaded_provider_ids and provider_id != source
        ]
        if not source_in:
            bridge_log(f"standby skipped no source track={song_log_label(song)}")
            return None
        bridge_log(
            f"standby start track={song_log_label(song)} source_in={source_in} "
            f"policy={audio_select_policy} min_score={standby_min_score}"
        )
        try:
            standby_list = asyncio.run(
                self.app.library.a_list_song_standby_v2(
                    song,
                    audio_select_policy=audio_select_policy,
                    source_in=source_in,
                    limit=8,
                )
            )
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"standby failed track={song_log_label(song)} error={exc}")
            return self._prepare_search_standby_payload(
                song,
                audio_select_policy,
                source_in,
                standby_min_score,
                standby_use_origin_metadata,
                standby_use_origin_lyrics,
            )
        if not standby_list:
            bridge_log(f"standby empty track={song_log_label(song)}")
            return self._prepare_search_standby_payload(
                song,
                audio_select_policy,
                source_in,
                standby_min_score,
                standby_use_origin_metadata,
                standby_use_origin_lyrics,
            )
        candidates = []
        for standby, _ in standby_list:
            score = standby_score(song, standby)
            if score >= standby_min_score:
                candidates.append((score, standby))
        if not candidates:
            bridge_log(f"standby no scored candidates track={song_log_label(song)}")
            return self._prepare_search_standby_payload(
                song,
                audio_select_policy,
                source_in,
                standby_min_score,
                standby_use_origin_metadata,
                standby_use_origin_lyrics,
            )
        for score, standby in sorted(candidates, key=lambda item: item[0], reverse=True):
            self._tracks[f"{getattr(standby, 'source', '')}:{getattr(standby, 'identifier', '')}"] = standby
            bridge_log(
                f"standby candidate score={score:.2f} origin={song_log_label(song)} "
                f"replacement={song_log_label(standby)}"
            )
            try:
                playback_standby, media, quality, parts, current_part_index = self._select_media(
                    standby,
                    audio_select_policy,
                )
            except MediaNotFound:
                bridge_log(
                    f"standby candidate media not found score={score:.2f} replacement={song_log_label(standby)}"
                )
                continue
            payload = self._payload_from_media(playback_standby, media, quality, parts, current_part_index)
            return self._mark_standby_payload(
                payload,
                song,
                standby,
                "library",
                score,
                standby_use_origin_metadata,
                standby_use_origin_lyrics,
            )
        bridge_log(f"standby scored media empty track={song_log_label(song)} candidates={len(candidates)}")
        return self._prepare_search_standby_payload(
            song,
            audio_select_policy,
            source_in,
            standby_min_score,
            standby_use_origin_metadata,
            standby_use_origin_lyrics,
        )

    def _prepare_search_standby_payload(
        self,
        song,
        audio_select_policy: str,
        source_in: List[str],
        standby_min_score: float,
        standby_use_origin_metadata: bool,
        standby_use_origin_lyrics: bool,
    ) -> Optional[Dict[str, Any]]:
        candidates = []
        seen = set()
        for query, query_source_in in standby_searches(song, source_in):
            bridge_log(f"search standby query track={song_log_label(song)} query={query} source_in={query_source_in}")
            for result in self.app.library.search(query, type_in=[SearchType.so], source_in=query_source_in):
                for standby in read_models(getattr(result, "songs", []) or [], limit=10):
                    key = (getattr(standby, "source", ""), getattr(standby, "identifier", ""))
                    if key in seen or key == (getattr(song, "source", ""), getattr(song, "identifier", "")):
                        continue
                    seen.add(key)
                    score = standby_score(song, standby)
                    if score >= standby_min_score:
                        bridge_log(
                            f"search standby candidate score={score:.2f} replacement={song_log_label(standby)}"
                        )
                        candidates.append((score, standby))
        if not candidates:
            bridge_log(f"search standby empty track={song_log_label(song)}")
            return None
        for score, standby in sorted(candidates, key=lambda item: item[0], reverse=True)[:8]:
            try:
                playback_standby, media, quality, parts, current_part_index = self._select_media(
                    standby,
                    audio_select_policy,
                )
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
            payload = self._payload_from_media(playback_standby, media, quality, parts, current_part_index)
            return self._mark_standby_payload(
                payload,
                song,
                standby,
                "search",
                score,
                standby_use_origin_metadata,
                standby_use_origin_lyrics,
            )
        bridge_log(f"search standby media empty track={song_log_label(song)} candidates={len(candidates)}")
        return None

    def _select_media(self, song, audio_select_policy: str):
        provider = self.app.library.get(getattr(song, "source", ""))
        if provider is not None and isinstance(provider, SupportsSongMultiQuality):
            playback_song = song
            parts = []
            try:
                media, quality = provider.song_select_media(playback_song, audio_select_policy)
            except MediaNotFound as exc:
                parts = self._bilibili_parts_for_song(playback_song, provider)
                child_song = self._bilibili_first_child_for_media_retry(playback_song, provider, exc, parts)
                if child_song is not None:
                    playback_song = child_song
                    media, quality = provider.song_select_media(playback_song, audio_select_policy)
                else:
                    refreshed_song = self._refresh_song_for_media_retry(playback_song, provider)
                    if refreshed_song is None:
                        raise
                    playback_song = refreshed_song
                    media, quality = provider.song_select_media(playback_song, audio_select_policy)
            if str(getattr(playback_song, "identifier", "")).startswith("paged_"):
                parts = parts or self._bilibili_parts_for_song(playback_song, provider)
            self._tracks[
                f"{getattr(playback_song, 'source', '')}:{getattr(playback_song, 'identifier', '')}"
            ] = playback_song
            return (
                playback_song,
                media,
                getattr(quality, "value", str(quality)).upper(),
                parts,
                playback_part_index(playback_song, parts),
            )
        return song, self.app.library.song_prepare_media(song, audio_select_policy), "", [], -1

    def _bilibili_first_child_for_media_retry(self, song, provider, exc: MediaNotFound, parts: List[Any]):
        if (
            getattr(exc, "reason", None) is not MediaNotFound.Reason.check_children
            or getattr(song, "source", "") != "bilibili"
            or str(getattr(song, "identifier", "")).startswith("paged_")
        ):
            return None
        children = parts or self._bilibili_parts_for_song(song, provider)
        if not children:
            return None
        child_song = children[0]
        bridge_log(
            f"bilibili media retry first child parent={song_log_label(song)} "
            f"child={song_log_label(child_song)}"
        )
        return child_song

    def _bilibili_parts_for_song(self, song, provider) -> List[Any]:
        if getattr(song, "source", "") != "bilibili":
            return []
        detail_song = song
        parts = getattr(detail_song, "children", None) or []
        if not parts and hasattr(provider, "song_get"):
            try:
                detail_song = provider.song_get(getattr(song, "identifier", ""))
            except Exception as detail_exc:  # pylint: disable=broad-except
                bridge_log(f"bilibili parts lookup failed track={song_log_label(song)} error={detail_exc}")
                return []
            parts = getattr(detail_song, "children", None) or []
        if len(parts) <= 1:
            return []
        for part in parts:
            self._tracks[f"{getattr(part, 'source', '')}:{getattr(part, 'identifier', '')}"] = part
        return list(parts)

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

    def _payload_from_media(
        self,
        song,
        media: Media,
        quality: str,
        parts: Optional[List[Any]] = None,
        current_part_index: int = -1,
    ) -> Dict[str, Any]:
        payload = media_to_payload(media, song_to_metadata(song, self.app.library))
        parts = parts or []
        if getattr(song, "source", "") == "bilibili":
            headers = payload.setdefault("headers", {})
            headers.setdefault("Referer", "https://www.bilibili.com/")
            headers.setdefault("User-Agent", BILIBILI_MEDIA_USER_AGENT)
            if parts:
                payload["parts"] = [playback_part_to_dict(part) for part in parts]
                payload["current_part_index"] = current_part_index
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
        use_origin_metadata: bool = False,
        use_origin_lyrics: bool = False,
    ) -> Dict[str, Any]:
        origin_provider = provider_name(self.app.library.get(getattr(origin, "source", "")))
        standby_provider = provider_name(self.app.library.get(getattr(standby, "source", "")))
        origin_cover = self._get_cover(origin)
        standby_cover = payload.get("cover_url") or self._get_cover(standby)
        payload.update(
            {
                "smart_replacement": True,
                "standby_strategy": strategy,
                "original_id": f"{getattr(origin, 'source', '')}:{getattr(origin, 'identifier', '')}",
                "original_title": display(origin, "title"),
                "original_artists": display_artists(origin),
                "original_source": getattr(origin, "source", ""),
                "original_provider_name": origin_provider,
                "original_cover_url": origin_cover,
                "replacement_id": f"{getattr(standby, 'source', '')}:{getattr(standby, 'identifier', '')}",
                "replacement_title": display(standby, "title"),
                "replacement_artists": display_artists(standby),
                "replacement_source": getattr(standby, "source", ""),
                "replacement_provider_name": standby_provider,
                "replacement_cover_url": standby_cover,
            }
        )
        if score is not None:
            payload["standby_score"] = round(score, 2)
        if use_origin_metadata:
            apply_origin_metadata(payload, song_to_metadata(origin, self.app.library))
            if origin_cover:
                payload["cover_url"] = origin_cover
        if use_origin_lyrics:
            origin_lyrics = self._get_lyrics(origin)
            if origin_lyrics:
                payload["lyrics"] = origin_lyrics
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


def provider_capabilities(provider) -> Dict[str, Any]:
    provider_id = getattr(provider, "identifier", "")
    return {
        "provider_id": provider_id,
        "provider_name": provider_name(provider),
        "can_add_song_to_playlist": hasattr(provider, "playlist_add_song"),
        "can_remove_song_from_playlist": (
            hasattr(provider, "playlist_remove_song") and provider_id != "ytmusic"
        ),
        "can_favorite_playlist": False,
        "can_unfavorite_playlist": False,
        "can_favorite_artist": False,
        "can_unfavorite_artist": False,
        "can_favorite_album": False,
        "can_unfavorite_album": False,
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


def mutation_result(success: bool, message: str) -> Dict[str, Any]:
    return {
        "success": success,
        "message": message,
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


def ytmusic_cookie_header(cookies: Dict[str, str]) -> str:
    return "; ".join(
        f"{key}={value}"
        for key, value in cookies.items()
        if key and value
    )


def ytmusic_authorization_from_cookies(cookies: Dict[str, str]) -> str:
    sapisid = (cookies.get("__Secure-3PAPISID") or "").strip()
    if not sapisid:
        raise RuntimeError("ytmusic cookies must include __Secure-3PAPISID")
    from ytmusicapi.helpers import get_authorization

    return get_authorization(f"{sapisid} https://music.youtube.com")


def parse_provider_ids(raw: str) -> Optional[List[str]]:
    text = (raw or "").strip()
    if not text:
        return None
    values = json.loads(text)
    if not isinstance(values, list):
        return None
    result = []
    for value in values:
        provider_id = str(value or "").strip()
        if provider_id and provider_id not in result:
            result.append(provider_id)
    return result or None


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


def model_declares_field(model, field: str) -> bool:
    model_fields = getattr(model.__class__, "model_fields", None)
    if model_fields is not None:
        return field in model_fields
    fields = getattr(model.__class__, "__fields__", None)
    return field in (fields or {})


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
        "artist_item_id": first_artist_item_id(song),
        "album_item_id": album_item_id(song),
        "provider_url": song_provider_url(song, library),
    }


def playback_part_to_dict(song) -> Dict[str, Any]:
    source = getattr(song, "source", "")
    identifier = getattr(song, "identifier", "")
    return {
        "id": f"{source}:{identifier}",
        "title": display(song, "title"),
        "duration_ms": duration_ms(song),
    }


def playback_part_index(song, parts: List[Any]) -> int:
    if not parts:
        return -1
    identifier = getattr(song, "identifier", "")
    for index, part in enumerate(parts):
        if getattr(part, "identifier", "") == identifier:
            return index
    if getattr(song, "source", "") == "bilibili" and not str(identifier).startswith("paged_"):
        return 0
    return -1


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


def apply_origin_metadata(payload: Dict[str, Any], metadata: Dict[str, Any]) -> None:
    for key in ("title", "artists", "album", "source", "provider_name", "cover_url"):
        value = metadata.get(key)
        if value:
            payload[key] = value
    duration = metadata.get("duration_ms", 0)
    if duration:
        payload["duration_ms"] = duration


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
        "provider_url": provider_web_url(source, "playlist", identifier),
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
        "provider_url": provider_web_url(source, item_type, identifier),
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


def song_provider_url(song, library: Library) -> str:
    try:
        url = library.song_get_web_url(song)
        if url:
            return str(url)
    except Exception:  # pylint: disable=broad-except
        pass
    return provider_web_url(getattr(song, "source", ""), "song", getattr(song, "identifier", ""))


def provider_web_url(source: str, resource_type: str, identifier: str) -> str:
    if not source or not identifier:
        return ""
    if source == "netease":
        path = {
            "song": "song",
            "playlist": "playlist",
            "artist": "artist",
            "album": "album",
        }.get(resource_type)
        return f"https://y.music.163.com/m/{path}?id={identifier}" if path else ""
    if source == "qqmusic":
        path = {
            "song": "songDetail",
            "artist": "singer",
            "album": "albumDetail",
        }.get(resource_type)
        return f"https://y.qq.com/n/ryqq/{path}/{identifier}" if path else ""
    if source == "ytmusic":
        if resource_type == "song":
            return f"https://music.youtube.com/watch?v={identifier}"
        if resource_type == "playlist":
            return f"https://music.youtube.com/playlist?list={identifier}"
    if source == "bilibili" and resource_type == "song":
        return f"https://www.bilibili.com/video/{identifier}"
    return ""


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


def first_artist_item_id(song) -> str:
    source = getattr(song, "source", "")
    artists = getattr(song, "artists", []) or []
    artist = artists[0] if artists else None
    identifier = getattr(artist, "identifier", "") if artist is not None else ""
    if source and identifier:
        return f"artist:{source}:{identifier}"
    return ""


def album_item_id(song) -> str:
    source = getattr(song, "source", "")
    album = getattr(song, "album", None)
    identifier = getattr(album, "identifier", "") if album is not None else ""
    if source and identifier:
        return f"album:{source}:{identifier}"
    return ""


def standby_queries(song) -> List[str]:
    title = display(song, "title").strip()
    artists = display_artists(song).strip()
    queries = []
    if title and artists:
        queries.append(f"{title} {artists}")
    if title:
        queries.append(title)
    return unique_texts(queries)


def bilibili_standby_queries(song) -> List[str]:
    title = display(song, "title").strip()
    artists = display_artists(song).strip()
    if title and artists:
        return [f"{title} {artists}"]
    if title:
        return [title]
    return []


def standby_searches(song, source_in: List[str]) -> List[tuple]:
    searches = []
    other_source_in = [source for source in source_in if source != "bilibili"]
    if other_source_in:
        searches.extend((query, other_source_in) for query in standby_queries(song))
    if "bilibili" in source_in:
        searches.extend((query, ["bilibili"]) for query in bilibili_standby_queries(song))
    return searches


def standby_score(origin, standby) -> float:
    if getattr(standby, "source", "") == "bilibili":
        return bilibili_standby_score(origin, standby)

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

    return apply_duration_penalty(score, origin, standby)


def bilibili_standby_score(origin, standby) -> float:
    origin_title = normalize_match_text(display(origin, "title"))
    standby_title = normalize_match_text(display(standby, "title"))
    if not origin_title or not standby_title:
        return 0.0

    score = 0.0
    if origin_title in standby_title:
        score += 0.40
    if any(artist in standby_title for artist in artist_match_texts(origin)):
        score += 0.20
    if any(keyword in standby_title for keyword in BILIBILI_STANDBY_BONUS_KEYWORDS):
        score += 0.10
    if (
        not any(keyword in origin_title for keyword in BILIBILI_STANDBY_PENALTY_KEYWORDS)
        and any(keyword in standby_title for keyword in BILIBILI_STANDBY_PENALTY_KEYWORDS)
    ):
        score -= 0.20
    return apply_duration_penalty(score, origin, standby)


def apply_duration_penalty(score: float, origin, standby) -> float:
    origin_duration = duration_ms(origin)
    standby_duration = duration_ms(standby)
    if origin_duration and standby_duration:
        diff_ratio = abs(origin_duration - standby_duration) / max(origin_duration, 1)
        score -= min(diff_ratio * MAX_DURATION_PENALTY, MAX_DURATION_PENALTY)
    return max(score, 0.0)


def normalize_standby_min_score(value) -> float:
    try:
        score = float(value)
    except (TypeError, ValueError):
        return 0.55
    return min(max(score, 0.0), 1.0)


def normalize_match_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value or "").casefold()
    return "".join(char for char in normalized if char.isalnum())


def artist_match_texts(song) -> List[str]:
    values = []
    for artist in getattr(song, "artists", []) or []:
        values.append(display(artist, "name"))
    values.extend(split_artist_names(display_artists(song)))
    return unique_texts(
        normalized
        for normalized in (normalize_match_text(value) for value in values)
        if normalized
    )


def split_artist_names(value: str) -> List[str]:
    parts = [value or ""]
    for separator in (" / ", "/", "、", ",", "，", ";", "；", "&", "+", "＋"):
        next_parts = []
        for part in parts:
            next_parts.extend(part.split(separator))
        parts = next_parts
    return [part.strip() for part in parts if part.strip()]


def unique_texts(values: List[str]) -> List[str]:
    result = []
    seen = set()
    for value in values:
        if value and value not in seen:
            seen.add(value)
            result.append(value)
    return result


BILIBILI_STANDBY_BONUS_KEYWORDS = ("mv", "hires")
BILIBILI_STANDBY_PENALTY_KEYWORDS = ("cover", "翻唱", "remix")
MAX_DURATION_PENALTY = 0.30


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
    duration = parse_duration_ms(getattr(song, "duration", None))
    if duration:
        return duration
    return parse_duration_ms(getattr(song, "duration_ms", None))


def parse_duration_ms(duration) -> int:
    if not duration:
        return 0
    if isinstance(duration, str):
        duration = duration.strip()
        if not duration:
            return 0
        if ":" in duration:
            return parse_duration_text_ms(duration)
    try:
        value = float(duration)
    except (TypeError, ValueError):
        return 0
    if value <= 0:
        return 0
    if value < 10_000:
        return int(value * 1000)
    return int(value)


def parse_duration_text_ms(duration: str) -> int:
    parts = duration.split(":")
    if len(parts) not in (2, 3):
        return 0
    try:
        seconds = float(parts[-1])
        minutes = int(parts[-2])
        hours = int(parts[0]) if len(parts) == 3 else 0
    except (TypeError, ValueError):
        return 0
    if hours < 0 or minutes < 0 or seconds < 0:
        return 0
    return int((hours * 3600 + minutes * 60 + seconds) * 1000)


def play_count(model) -> int:
    value = getattr(model, "play_count", None)
    if value is None:
        return 0
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0
