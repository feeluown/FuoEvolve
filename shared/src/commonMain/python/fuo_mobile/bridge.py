import asyncio
import json
import os
import unicodedata
import sys
import types
from dataclasses import dataclass
from typing import Any, Dict, List, Optional


def _install_i18n_stub() -> None:
    """Avoid loading FeelUOwn's desktop localization stack on mobile."""
    if "feeluown.i18n" in sys.modules:
        return

    module = types.ModuleType("feeluown.i18n")

    def rfc1766_langcode() -> str:
        import locale

        lang, _ = locale.getlocale(locale.LC_CTYPE)
        return "en_US" if lang in ("C", None, "") else lang

    def translate(
        msg_id: str,
        locale: str = None,
        domain: str = None,
        **kwargs,
    ) -> str:
        return msg_id

    def human_readable_number(number: int, locale: str = None) -> str:
        language = locale or rfc1766_langcode()
        levels = (
            ((100_000_000, "亿"), (10_000, "万"))
            if language.startswith("zh")
            else ((1_000_000_000, "B"), (1_000_000, "M"), (1_000, "K"))
        )
        for value, unit in levels:
            if number > value:
                first = number // value
                second = (number % value) // (value // 10)
                return f"{first}.{second}{unit}"
        return str(number)

    module.t = translate
    module.rfc1766_langcode = rfc1766_langcode
    module.human_readable_number = human_readable_number
    sys.modules["feeluown.i18n"] = module


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


_install_i18n_stub()
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
            "id": "netease_cloud_songs",
            "title": "云盘歌曲",
            "category": "Mine",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_cloud_songs",
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
            "id": "qqmusic_disliked_songs",
            "title": "不喜欢的歌曲",
            "category": "Mine",
            "content_type": "Songs",
            "requires_login": True,
            "action": "current_user_dislike_create_songs_rd",
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
            "id": "bilibili_popular_videos",
            "title": "热门视频",
            "category": "Music",
            "content_type": "Songs",
            "requires_login": False,
            "action": "most_popular_videos",
        },
        {
            "id": "bilibili_weekly_video_playlists",
            "title": "每周必看",
            "category": "Music",
            "content_type": "Playlists",
            "requires_login": False,
            "action": "weekly_video_playlists",
        },
        {
            "id": "bilibili_recommended_videos",
            "title": "推荐视频",
            "category": "Recommend",
            "content_type": "Videos",
            "requires_login": True,
            "action": "rec_a_collection_of_videos",
        },
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
        self._videos: Dict[str, Any] = {}
        self._dynamic_features: Dict[str, Dict[str, Any]] = {}
        self._saved_login_provider_ids = set()
        self._authenticated_provider_ids = set()
        self._restore_saved_logins()

    def providers(self) -> str:
        providers = [self._raw_provider(provider_id) for provider_id in self.provider_registry.provider_ids]
        return json.dumps(
            {"providers": [provider_info(provider) for provider in providers]},
            ensure_ascii=False,
        )

    def provider_capabilities(self) -> str:
        providers = [self._raw_provider(provider_id) for provider_id in self.provider_registry.provider_ids]
        return json.dumps(
            {"providers": [provider_capabilities(provider) for provider in providers]},
            ensure_ascii=False,
        )

    def features(self) -> str:
        features = []
        self._dynamic_features = {}
        for provider_id in self.provider_registry.provider_ids:
            provider = self._raw_provider(provider_id)
            for feature in FEATURE_DEFS.get(provider_id, []):
                if hasattr(provider, feature["action"]):
                    features.append(feature_to_dict(feature, provider))
            if hasattr(provider, "rec_list_collections"):
                try:
                    for index, collection in enumerate(provider.rec_list_collections() or []):
                        content_type = collection_content_type(getattr(collection, "type_", None))
                        if content_type is None:
                            continue
                        feature = {
                            "id": f"{provider_id}_collection_{index}",
                            "provider_id": provider_id,
                            "title": getattr(collection, "name", "推荐"),
                            "category": "Recommend",
                            "content_type": content_type,
                            "requires_login": False,
                            "action": "cached_collection",
                            "collection": collection,
                        }
                        self._dynamic_features[feature["id"]] = feature
                        features.append(feature_to_dict(feature, provider))
                except Exception as exc:  # pylint: disable=broad-except
                    bridge_log(f"load recommendation collections failed provider_id={provider_id}: {exc}")
        return json.dumps({"features": features}, ensure_ascii=False)

    def search(self, keyword: str, provider_id: str = "") -> str:
        if provider_id == "bilibili":
            self._get_provider(provider_id)
        elif not provider_id and "bilibili" in getattr(getattr(self, "provider_registry", None), "provider_ids", []):
            self._get_provider("bilibili")
        tracks = []
        source_in = [provider_id] if provider_id else None
        for result in self.app.library.search(keyword, type_in=[SearchType.so], source_in=source_in):
            for song in getattr(result, "songs", []) or []:
                track = self._remember_song(song)
                tracks.append(track)
        return json.dumps({"tracks": tracks}, ensure_ascii=False)

    def search_all(self, keyword: str, provider_id: str = "") -> str:
        tracks = []
        playlists = []
        artists = []
        albums = []
        videos = []
        errors = []
        if provider_id == "bilibili":
            provider = self._get_provider(provider_id)
            if provider.get_current_user_or_none() is None:
                return json.dumps(
                    {
                        "tracks": [],
                        "playlists": [],
                        "artists": [],
                        "albums": [],
                        "videos": [],
                        "error_message": "登录后搜索哔哩哔哩",
                    },
                    ensure_ascii=False,
                )
        elif not provider_id and "bilibili" in getattr(getattr(self, "provider_registry", None), "provider_ids", []):
            self._get_provider("bilibili")
        source_in = [provider_id] if provider_id else None
        for search_type in (SearchType.so, SearchType.ar, SearchType.al, SearchType.pl, SearchType.vi):
            try:
                results = self.app.library.search(keyword, type_in=[search_type], source_in=source_in)
                for result in results:
                    err_msg = getattr(result, "err_msg", "")
                    if err_msg:
                        errors.append(err_msg)
                    for song in getattr(result, "songs", []) or []:
                        tracks.append(self._remember_song(song))
                    for playlist in getattr(result, "playlists", []) or []:
                        playlists.append(self._remember_playlist(playlist))
                    for artist in getattr(result, "artists", []) or []:
                        artists.append(self._remember_media_item(artist, "artist"))
                    for album in getattr(result, "albums", []) or []:
                        albums.append(self._remember_media_item(album, "album"))
                    for video in getattr(result, "videos", []) or []:
                        videos.append(self._remember_video(video))
            except Exception as exc:
                if is_unsupported_search_type(exc):
                    continue
                message = str(exc) or exc.__class__.__name__
                errors.append(message)
                bridge_log(f"search_all skipped type={search_type} provider_id={provider_id}: {message}")
        return json.dumps(
            {
                "tracks": tracks,
                "playlists": playlists,
                "artists": artists,
                "albums": albums,
                "videos": videos,
                "error_message": "\n".join(unique_texts(errors)),
            },
            ensure_ascii=False,
        )

    def track_detail(self, track_id: str) -> str:
        song = self._tracks.get(track_id)
        if song is None:
            song = self._song_from_track_id(track_id)
            self._tracks[track_id] = song
        return json.dumps({"track": self._remember_song(song)}, ensure_ascii=False)

    def similar_tracks(self, track_id: str) -> str:
        song = self._song_for_operation(track_id)
        provider_id = getattr(song, "source", "")
        provider = self._get_provider(provider_id)
        similar = getattr(provider, "song_list_similar", None)
        if similar is None:
            return json.dumps({"tracks": []}, ensure_ascii=False)
        try:
            songs = read_models(similar(song), limit=30)
        except Exception as exc:
            return json.dumps({"tracks": [], "error_message": str(exc) or exc.__class__.__name__}, ensure_ascii=False)
        return json.dumps({"tracks": [self._remember_song(item) for item in songs]}, ensure_ascii=False)

    def hot_comments(self, track_id: str) -> str:
        song = self._song_for_operation(track_id)
        provider_id = getattr(song, "source", "")
        provider = self._get_provider(provider_id)
        comments = getattr(provider, "song_list_hot_comments", None)
        if comments is None:
            return json.dumps({"comments": []}, ensure_ascii=False)
        try:
            items = read_models(comments(song), limit=20)
        except Exception as exc:
            return json.dumps({"comments": [], "error_message": str(exc) or exc.__class__.__name__}, ensure_ascii=False)
        return json.dumps({"comments": [comment_to_dict(item) for item in items]}, ensure_ascii=False)

    def track_video(self, track_id: str) -> str:
        song = self._song_for_operation(track_id)
        provider_id = getattr(song, "source", "")
        provider = self._get_provider(provider_id)
        get_video = getattr(provider, "song_get_mv", None)
        if get_video is None:
            return json.dumps({"video": None}, ensure_ascii=False)
        try:
            video = get_video(song)
        except Exception as exc:
            return json.dumps({"video": None, "error_message": str(exc) or exc.__class__.__name__}, ensure_ascii=False)
        if video is None:
            return json.dumps({"video": None}, ensure_ascii=False)
        return json.dumps({"video": self._remember_video(video)}, ensure_ascii=False)

    def video_payload(self, video_id: str, video_select_policy: str = "") -> str:
        video = self._video_from_id(video_id)
        provider = self._get_provider(getattr(video, "source", ""))
        media = None
        quality = None
        if hasattr(provider, "video_select_media"):
            media, quality = provider.video_select_media(video, video_select_policy or None)
        elif hasattr(provider, "video_get_media"):
            qualities = provider.video_list_quality(video) if hasattr(provider, "video_list_quality") else []
            selected_quality = qualities[0] if qualities else None
            media = provider.video_get_media(video, selected_quality)
            quality = selected_quality
        if media is None:
            raise RuntimeError("empty video media")
        return json.dumps(video_media_to_payload(video, media, quality, self.app.library), ensure_ascii=False)

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
        provider = self._raw_provider(provider_id)
        return json.dumps(
            provider_auth_state(provider, None, provider_id in self._saved_login_provider_ids),
            ensure_ascii=False,
        )

    def provider_auth_state_with_user(self, provider_id: str) -> str:
        provider = self._raw_provider(provider_id)
        if provider_id not in self._saved_login_provider_ids:
            return json.dumps(provider_auth_state(provider, None, False), ensure_ascii=False)
        self._restore_saved_login(provider_id)
        user = provider.get_current_user_or_none()
        return json.dumps(provider_auth_state(provider, user, user is not None), ensure_ascii=False)

    def provider_login_with_cookies(self, provider_id: str, cookies_json: str) -> str:
        provider = self._get_provider(provider_id)
        cookies = parse_cookies(cookies_json)
        if not isinstance(cookies, dict) or not cookies:
            raise RuntimeError("cookies must be a non-empty JSON object")
        if provider_id == "ytmusic":
            user = self._login_ytmusic_with_cookies(provider, cookies)
            self._saved_login_provider_ids.add(provider_id)
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
        self._saved_login_provider_ids.add(provider_id)
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def _login_ytmusic_with_cookies(self, provider, cookies: Dict[str, str]):
        cookie_value = ytmusic_cookie_header(cookies)
        auth = ytmusic_authorization_from_cookies(cookies)
        from fuo_ytmusic.consts import HEADER_FILE
        from fuo_ytmusic.headerfile import write_headerfile

        os.makedirs(os.fspath(HEADER_FILE.parent), exist_ok=True)
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

        os.makedirs(os.fspath(HEADER_FILE.parent), exist_ok=True)
        write_headerfile(auth, cookie_value, HEADER_FILE)
        user = provider.try_get_user_with_headerfile()
        if user is None:
            raise RuntimeError("get user with ytmusic headers failed")
        provider.auth(user)
        current_user_changed = getattr(provider, "current_user_changed", None)
        if current_user_changed is not None:
            current_user_changed.emit(user)
        self._saved_login_provider_ids.add(provider_id)
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def provider_login_with_ytmusic_headerfile(self, headerfile_json: str) -> str:
        try:
            headers = json.loads(headerfile_json)
        except (TypeError, json.JSONDecodeError) as exc:
            raise RuntimeError("ytmusic_header.json 不是有效的 JSON 文件") from exc
        if not isinstance(headers, dict):
            raise RuntimeError("ytmusic_header.json 顶层必须是 JSON 对象")
        authorization = headers.get("Authorization")
        cookie = headers.get("Cookie")
        if not isinstance(authorization, str) or not authorization.strip():
            raise RuntimeError("ytmusic_header.json 缺少 Authorization")
        if not isinstance(cookie, str) or not cookie.strip():
            raise RuntimeError("ytmusic_header.json 缺少 Cookie")

        provider = self._get_provider("ytmusic")
        from fuo_ytmusic.consts import HEADER_FILE
        from fuo_ytmusic.headerfile import YtdlpCookiefileManager

        with HEADER_FILE.open("w", encoding="utf-8") as handle:
            json.dump(headers, handle, indent=2, ensure_ascii=True)
        YtdlpCookiefileManager(HEADER_FILE).write(cookie)
        user = provider.try_get_user_with_headerfile()
        if user is None:
            raise RuntimeError("get user with ytmusic headerfile failed")
        provider.auth(user)
        current_user_changed = getattr(provider, "current_user_changed", None)
        if current_user_changed is not None:
            current_user_changed.emit(user)
        self._saved_login_provider_ids.add("ytmusic")
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def provider_logout(self, provider_id: str) -> str:
        provider = self._get_provider(provider_id)
        self._clear_provider_auth(provider_id, provider)
        self._delete_saved_login(provider_id)
        self._saved_login_provider_ids.discard(provider_id)
        return json.dumps(provider_auth_state(provider, None), ensure_ascii=False)

    def load_feature(self, feature_id: str, offset: int = 0, limit: int = 60) -> str:
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
                    "videos": [],
                    "is_login_required": True,
                    "error_message": "",
                    "next_offset": 0,
                    "has_more": False,
                },
                ensure_ascii=False,
            )
        try:
            tracks, playlists, media_items, videos, title, page = self._load_feature_content(provider, feature, offset, limit)
            if title:
                feature_payload["title"] = title
            payload = {
                "feature": feature_payload,
                "tracks": tracks,
                "playlists": playlists,
                "media_items": media_items,
                "videos": videos,
                "is_login_required": False,
                "error_message": "",
                "next_offset": page["next_offset"],
                "has_more": page["has_more"],
            }
        except Exception as exc:  # pylint: disable=broad-except
            payload = {
                "feature": feature_payload,
                "tracks": [],
                "playlists": [],
                "media_items": [],
                "videos": [],
                "is_login_required": False,
                "error_message": str(exc) or exc.__class__.__name__,
                "next_offset": offset,
                "has_more": False,
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

    def playlist_detail(self, playlist_id: str, offset: int = 0, limit: int = 60) -> str:
        bridge_log(f"playlist_detail start playlist_id={playlist_id}")
        payload = self._playlist_detail_payload(playlist_id, offset, limit)
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

    def playlist_create(self, provider_id: str, name: str) -> str:
        provider = self._get_provider(provider_id)
        self._require_logged_in(provider)
        create = getattr(provider, "playlist_create_by_name", None)
        if create is None:
            return json.dumps(mutation_result(False, "当前音源不支持新建歌单"), ensure_ascii=False)
        title = (name or "").strip()
        if not title:
            return json.dumps(mutation_result(False, "歌单名称不能为空"), ensure_ascii=False)
        playlist = create(title)
        if playlist is None:
            return json.dumps(mutation_result(False, "新建歌单失败"), ensure_ascii=False)
        return json.dumps({"result": mutation_result(True, f"已新建：{title}"), "playlist": self._remember_playlist(playlist)}, ensure_ascii=False)

    def playlist_delete(self, playlist_id: str) -> str:
        playlist = self._playlist_from_id(playlist_id)
        provider = self._get_provider(getattr(playlist, "source", ""))
        self._require_logged_in(provider)
        delete = getattr(provider, "playlist_delete", None)
        if delete is None:
            return json.dumps(mutation_result(False, "当前音源不支持删除歌单"), ensure_ascii=False)
        ok = bool(delete(getattr(playlist, "identifier", "")))
        if ok:
            self._playlists.pop(playlist_id, None)
        return json.dumps(mutation_result(ok, f"已删除：{display(playlist, 'name')}" if ok else "删除歌单失败"), ensure_ascii=False)

    def dislike_song(self, track_id: str, disliked: bool) -> str:
        song = self._song_for_operation(track_id)
        provider = self._get_provider(getattr(song, "source", ""))
        self._require_logged_in(provider)
        action_name = "current_user_dislike_add_song" if disliked else "current_user_dislike_remove_song"
        action = getattr(provider, action_name, None)
        if action is None:
            return json.dumps(mutation_result(False, "当前音源不支持不喜欢操作"), ensure_ascii=False)
        ok = bool(action(song))
        label = "已设为不喜欢" if disliked else "已取消不喜欢"
        return json.dumps(mutation_result(ok, label if ok else "操作失败"), ensure_ascii=False)

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

    def media_item_detail(
        self,
        item_id: str,
        tracks_offset: int = 0,
        albums_offset: int = 0,
        limit: int = 60,
    ) -> str:
        bridge_log(f"media_item_detail start item_id={item_id}")
        payload = self._media_item_detail_payload(item_id, tracks_offset, albums_offset, limit)
        bridge_log(
            f"media_item_detail done item_id={item_id} "
            f"tracks={len(payload['tracks'])} albums={len(payload['albums'])}"
        )
        return json.dumps(payload, ensure_ascii=False)

    def _get_provider(self, provider_id: str):
        provider = self._raw_provider(provider_id)
        if provider_id == "bilibili":
            self._ensure_bilibili_authenticated(provider)
        else:
            self._ensure_saved_login_authenticated(provider_id, provider)
        return provider

    def _raw_provider(self, provider_id: str):
        provider = self.app.library.get(provider_id)
        if provider is None:
            raise RuntimeError(f"provider not found: {provider_id}")
        return provider

    def _ensure_bilibili_authenticated(self, provider) -> None:
        authenticated_provider_ids = getattr(self, "_authenticated_provider_ids", set())
        if "bilibili" in authenticated_provider_ids:
            return
        if "bilibili" in getattr(self, "_saved_login_provider_ids", set()):
            self._restore_saved_login("bilibili")
            if "bilibili" in getattr(self, "_authenticated_provider_ids", set()):
                return
        current_user = getattr(provider, "get_current_user_or_none", None)
        if current_user is None:
            return
        user = current_user()
        if user is not None:
            provider.auth(user)
            authenticated_provider_ids.add("bilibili")
            self._authenticated_provider_ids = authenticated_provider_ids

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

    def _playlist_detail_payload(self, playlist_id: str, offset: int = 0, limit: int = 60) -> Dict[str, Any]:
        playlist = self._playlist_from_id(playlist_id)
        detail_playlist = self._playlist_detail_from_id(playlist_id)
        provider = self._get_provider(getattr(playlist, "source", ""))
        track_playlist = self._playlist_for_tracks(playlist_id, provider, playlist)
        bridge_log(
            f"playlist_detail resolved playlist_id={playlist_id} source={getattr(track_playlist, 'source', '')}"
        )
        reader = provider.playlist_create_songs_rd(track_playlist)
        page = read_model_page(reader, offset=offset, limit=limit)
        songs = page["items"]
        tracks = [self._remember_song(song) for song in songs]
        return {
            "playlist": self._remember_playlist(detail_playlist),
            "tracks": tracks,
            "tracks_next_offset": page["next_offset"],
            "tracks_has_more": page["has_more"],
        }

    def _media_item_detail_payload(
        self,
        item_id: str,
        tracks_offset: int = 0,
        albums_offset: int = 0,
        limit: int = 60,
    ) -> Dict[str, Any]:
        item_type, provider_id, _ = self._parse_media_item_id(item_id)
        item = self._media_item_from_id(item_id)
        detail_item = self._media_item_detail_from_id(item_id)
        provider = self._get_provider(provider_id)
        albums = []
        album_page = empty_page(albums_offset)
        if item_type == "artist":
            reader = provider.artist_create_songs_rd(item)
            album_reader = getattr(provider, "artist_create_albums_rd", None)
            if album_reader is not None:
                album_page = read_model_page(album_reader(item), offset=albums_offset, limit=limit)
                albums = [
                    self._remember_media_item(album, "album")
                    for album in album_page["items"]
                ]
        elif item_type == "album":
            reader = provider.album_create_songs_rd(item)
        else:
            raise RuntimeError(f"unsupported media item type: {item_type}")
        track_page = read_model_page(reader, offset=tracks_offset, limit=limit)
        songs = track_page["items"]
        tracks = [self._remember_song(song) for song in songs]
        return {
            "item": self._remember_media_item(detail_item, item_type),
            "tracks": tracks,
            "albums": albums,
            "tracks_next_offset": track_page["next_offset"],
            "tracks_has_more": track_page["has_more"],
            "albums_next_offset": album_page["next_offset"],
            "albums_has_more": album_page["has_more"],
        }

    def _feature_by_id(self, feature_id: str) -> Dict[str, Any]:
        dynamic = getattr(self, "_dynamic_features", {}).get(feature_id)
        if dynamic is not None:
            return dynamic
        for provider_id, features in FEATURE_DEFS.items():
            for feature in features:
                if feature["id"] == feature_id:
                    return {**feature, "provider_id": provider_id}
        raise RuntimeError(f"feature not found: {feature_id}")

    def _load_feature_content(self, provider, feature: Dict[str, Any], offset: int = 0, limit: int = 60):
        action = feature["action"]
        if action == "rec_list_daily_songs":
            page = read_model_page(provider.rec_list_daily_songs(), offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], "", page
        if action == "rec_list_daily_playlists":
            page = read_model_page(provider.rec_list_daily_playlists(), offset=offset, limit=limit)
            return [], [self._remember_playlist(playlist) for playlist in page["items"]], [], [], "", page
        if action == "current_user_list_radio_songs":
            songs = provider.current_user_list_radio_songs(max(1, min(int_limit(limit), 60)))
            page = {
                "items": songs,
                "next_offset": offset + len(songs),
                "has_more": bool(songs),
            }
            return [self._remember_song(song) for song in songs], [], [], [], "", page
        if action == "toplist_list":
            page = read_model_page(provider.toplist_list(), offset=offset, limit=limit)
            return [], [self._remember_playlist(playlist) for playlist in page["items"]], [], [], "", page
        if action == "rec_a_collection_of_songs":
            collection = provider.rec_a_collection_of_songs()
            page = read_model_page(getattr(collection, "models", []) or [], offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], getattr(collection, "name", ""), page
        if action == "rec_a_collection_of_videos":
            collection = provider.rec_a_collection_of_videos()
            page = read_model_page(getattr(collection, "models", []) or [], offset=offset, limit=limit)
            return [], [], [], [self._remember_video(video) for video in page["items"]], getattr(collection, "name", ""), page
        if action == "cached_collection":
            page = read_model_page(getattr(feature["collection"], "models", []) or [], offset=offset, limit=limit)
            content_type = feature["content_type"]
            if content_type == "Songs":
                return [self._remember_song(item) for item in page["items"]], [], [], [], "", page
            if content_type == "Playlists":
                return [], [self._remember_playlist(item) for item in page["items"]], [], [], "", page
            if content_type == "Videos":
                return [], [], [], [self._remember_video(item) for item in page["items"]], "", page
            item_type = "artist" if content_type == "Artists" else "album"
            return [], [], [self._remember_media_item(item, item_type) for item in page["items"]], [], "", page
        if action == "most_popular_videos":
            page = read_model_page(provider.most_popular_videos(), offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], "", page
        if action == "weekly_video_playlists":
            page = read_model_page(provider.weekly_video_playlists(), offset=offset, limit=limit)
            return [], [self._remember_playlist(playlist) for playlist in page["items"]], [], [], "", page
        if action == "current_user_fav_create_songs_rd":
            page = read_model_page(provider.current_user_fav_create_songs_rd(), offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], "", page
        if action == "current_user_cloud_songs":
            page = read_model_page(provider.current_user_cloud_songs(), offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], "", page
        if action == "current_user_dislike_create_songs_rd":
            page = read_model_page(provider.current_user_dislike_create_songs_rd(), offset=offset, limit=limit)
            return [self._remember_song(song) for song in page["items"]], [], [], [], "", page
        if action == "current_user_fav_create_playlists_rd":
            page = read_model_page(provider.current_user_fav_create_playlists_rd(), offset=offset, limit=limit)
            return [], [self._remember_playlist(playlist) for playlist in page["items"]], [], [], "", page
        if action == "current_user_list_playlists":
            page = read_model_page(provider.current_user_list_playlists(), offset=offset, limit=limit)
            return [], [self._remember_playlist(playlist) for playlist in page["items"]], [], [], "", page
        if action == "current_user_fav_create_artists_rd":
            page = read_model_page(provider.current_user_fav_create_artists_rd(), offset=offset, limit=limit)
            return [], [], [self._remember_media_item(artist, "artist") for artist in page["items"]], [], "", page
        if action == "current_user_fav_create_albums_rd":
            page = read_model_page(provider.current_user_fav_create_albums_rd(), offset=offset, limit=limit)
            return [], [], [self._remember_media_item(album, "album") for album in page["items"]], [], "", page
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

    def _video_from_id(self, video_id: str):
        video = self._videos.get(video_id)
        if video is not None:
            return video
        try:
            _, provider_id, identifier = video_id.split(":", 2)
        except ValueError as exc:
            raise RuntimeError(f"invalid video id: {video_id}") from exc
        provider = self._get_provider(provider_id)
        if not hasattr(provider, "video_get"):
            raise RuntimeError(f"provider does not support video_get: {provider_id}")
        video = provider.video_get(identifier)
        self._videos[video_id] = video
        return video

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

    def _remember_video(self, video) -> Dict[str, Any]:
        data = video_to_dict(video, self.app.library)
        self._videos[data["id"]] = video
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

    def _ensure_saved_login_authenticated(self, provider_id: str, provider) -> None:
        authenticated_provider_ids = getattr(self, "_authenticated_provider_ids", set())
        if provider_id in authenticated_provider_ids:
            return
        current_user = getattr(provider, "get_current_user_or_none", None)
        if not callable(current_user) or current_user() is not None:
            return
        if provider_id not in getattr(self, "_saved_login_provider_ids", set()):
            return
        try:
            self._restore_saved_login(provider_id)
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"restore login failed provider_id={provider_id}: {exc}")
            return
        user = current_user()
        if user is None:
            return
        try:
            provider.auth(user)
        except Exception as exc:  # pylint: disable=broad-except
            bridge_log(f"provider auth failed provider_id={provider_id}: {exc}")
            return
        authenticated_provider_ids.add(provider_id)
        self._authenticated_provider_ids = authenticated_provider_ids

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
                if self._restore_saved_cookies(provider_id):
                    self._saved_login_provider_ids.add(provider_id)
            except Exception as exc:  # pylint: disable=broad-except
                bridge_log(f"restore cookies failed provider_id={provider_id}: {exc}")

    def _restore_saved_cookies(self, provider_id: str) -> bool:
        provider = self._raw_provider(provider_id)
        if provider_id == "netease":
            from fuo_netease.login_controller import LoginController

            user = LoginController.load()
            if user is None:
                return False
            provider.auth(user)
            return True
        if provider_id == "qqmusic":
            from fuo_qqmusic.login import read_cookies

            cookies = read_cookies()
            if not cookies:
                return False
            provider.api.set_cookies(cookies)
            return True
        if provider_id == "bilibili":
            from fuo_bilibili.login import load_user_cookies

            cookies = load_user_cookies()
            if not cookies:
                return False
            provider._api.from_cookiedict(cookies)
            return True
        if provider_id == "ytmusic":
            from fuo_ytmusic.consts import HEADER_FILE

            if not HEADER_FILE.exists():
                return False
            provider.service.reinitialize_by_headerfile(HEADER_FILE)
            return True
        return False

    def _restore_saved_login(self, provider_id: str) -> None:
        provider = self._raw_provider(provider_id)
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
            if provider_id == "bilibili":
                self._authenticated_provider_ids.add(provider_id)
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
        if "bilibili" in source_in:
            self._get_provider("bilibili")
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
        "can_create_playlist": hasattr(provider, "playlist_create_by_name"),
        "can_delete_playlist": hasattr(provider, "playlist_delete"),
        "can_list_disliked_songs": hasattr(provider, "current_user_dislike_create_songs_rd"),
        "can_add_disliked_song": hasattr(provider, "current_user_dislike_add_song"),
        "can_remove_disliked_song": hasattr(provider, "current_user_dislike_remove_song"),
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


def collection_content_type(type_) -> Optional[str]:
    name = getattr(type_, "name", "")
    return {
        "only_songs": "Songs",
        "only_playlists": "Playlists",
        "only_artists": "Artists",
        "only_albums": "Albums",
        "only_videos": "Videos",
    }.get(name)


def mutation_result(success: bool, message: str) -> Dict[str, Any]:
    return {
        "success": success,
        "message": message,
    }


def provider_auth_state(provider, user, is_logged_in: Optional[bool] = None) -> Dict[str, Any]:
    return {
        "provider_id": getattr(provider, "identifier", ""),
        "provider_name": provider_name(provider),
        "is_logged_in": user is not None if is_logged_in is None else is_logged_in,
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


def read_model_page(value, offset: int = 0, limit: int = 60) -> Dict[str, Any]:
    safe_offset = max(0, int_offset(offset))
    safe_limit = max(1, int_limit(limit))
    read_limit = safe_limit + 1
    if value is None:
        return empty_page(safe_offset)
    if isinstance(value, list):
        items = value[safe_offset:safe_offset + read_limit]
        return page_result(items, safe_offset, safe_limit)
    if hasattr(value, "read_range"):
        items = value.read_range(safe_offset, safe_offset + read_limit)
        return page_result(items, safe_offset, safe_limit)
    if hasattr(value, "readall"):
        items = value.readall()[safe_offset:safe_offset + read_limit]
        return page_result(items, safe_offset, safe_limit)
    try:
        items = []
        for index, model in enumerate(value):
            if index < safe_offset:
                continue
            items.append(model)
            if len(items) >= read_limit:
                break
        return page_result(items, safe_offset, safe_limit)
    except TypeError:
        return empty_page(safe_offset)


def page_result(items, offset: int, limit: int) -> Dict[str, Any]:
    values = list(items or [])
    page_items = values[:limit]
    return {
        "items": page_items,
        "next_offset": offset + len(page_items),
        "has_more": len(values) > limit,
    }


def empty_page(offset: int = 0) -> Dict[str, Any]:
    return {
        "items": [],
        "next_offset": max(0, int_offset(offset)),
        "has_more": False,
    }


def int_offset(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def int_limit(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 60


def is_unsupported_search_type(exc: Exception) -> bool:
    return isinstance(exc, (KeyError, ValueError))


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
        "artist_items": artist_items(song, library),
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
        "cover_url": image_url(playlist),
        "description": display(playlist, "description") or display(playlist, "creator_name"),
        "play_count": play_count(playlist),
        "provider_url": provider_web_url(source, "playlist", identifier),
        "track_count": model_count(playlist, "count", "song_count", "songs_count", "track_count", "tracks_count"),
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
        "cover_url": image_url(item),
        "description": display(item, "description") or display(item, "artists_name"),
        "provider_url": provider_web_url(source, item_type, identifier),
        "track_count": model_count(item, "song_count", "songs_count", "track_count", "tracks_count"),
        "album_count": model_count(item, "album_count", "albums_count"),
    }


def video_to_dict(video, library: Library) -> Dict[str, Any]:
    source = getattr(video, "source", "")
    identifier = getattr(video, "identifier", "")
    return {
        "id": f"video:{source}:{identifier}",
        "title": display(video, "title"),
        "artists": display(video, "artists_name") or display_artists(video),
        "provider_id": source,
        "provider_name": provider_name(library.get(source)),
        "cover_url": normalize_image_url(display(video, "cover") or display(video, "pic_url")),
        "duration_ms": duration_ms(video),
        "provider_url": provider_web_url(source, "video", identifier),
    }


def comment_to_dict(comment) -> Dict[str, Any]:
    user = getattr(comment, "user", None)
    return {
        "id": display(comment, "identifier"),
        "user_name": display(user, "name") or display(comment, "user_name"),
        "content": display(comment, "content"),
        "liked_count": int(getattr(comment, "liked_count", 0) or 0),
        "time": int(getattr(comment, "time", 0) or 0),
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


def write_m4a_tags(
    path: str,
    title: str,
    artists: str,
    album: str,
    cover_mime_type: str = "",
    cover_base64: str = "",
) -> bool:
    from base64 import b64decode

    from mutagen.mp4 import MP4, MP4Cover

    audio = MP4(path)
    if audio.tags is None:
        audio.add_tags()
    if title:
        audio.tags["\xa9nam"] = [title]
    if artists:
        audio.tags["\xa9ART"] = [artists]
    if album:
        audio.tags["\xa9alb"] = [album]
    if cover_base64:
        image_format = MP4Cover.FORMAT_PNG if cover_mime_type == "image/png" else MP4Cover.FORMAT_JPEG
        audio.tags["covr"] = [MP4Cover(b64decode(cover_base64), imageformat=image_format)]
    audio.save()
    return True


def video_media_to_payload(video, media: Media, quality, library: Library) -> Dict[str, Any]:
    video_data = video_to_dict(video, library)
    manifest = getattr(media, "manifest", None)
    return {
        "video": video_data,
        "url": getattr(media, "url", "") or "",
        "video_url": getattr(manifest, "video_url", "") if manifest is not None else "",
        "audio_url": getattr(manifest, "audio_url", "") if manifest is not None else "",
        "headers": dict(getattr(media, "http_headers", {}) or {}),
        "quality": str(quality or ""),
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


def image_url(model) -> str:
    for field in (
        "cover",
        "cover_url",
        "coverUrl",
        "pic_url",
        "picUrl",
        "picture_url",
        "pictureUrl",
        "image_url",
        "imageUrl",
        "thumbnail",
        "avatar",
        "face",
    ):
        value = display(model, field)
        if value:
            return normalize_image_url(value)
    return ""


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
            "video": "mv",
        }.get(resource_type)
        return f"https://y.music.163.com/m/{path}?id={identifier}" if path else ""
    if source == "qqmusic":
        if resource_type == "video":
            return f"https://y.qq.com/n/ryqq/mv/{identifier}"
        path = {
            "song": "songDetail",
            "artist": "singer",
            "album": "albumDetail",
        }.get(resource_type)
        return f"https://y.qq.com/n/ryqq/{path}/{identifier}" if path else ""
    if source == "ytmusic":
        if resource_type in ("song", "video"):
            return f"https://music.youtube.com/watch?v={identifier}"
        if resource_type == "playlist":
            return f"https://music.youtube.com/playlist?list={identifier}"
    if source == "bilibili" and resource_type in ("song", "video"):
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


def artist_items(song, library: Library) -> List[Dict[str, Any]]:
    song_source = getattr(song, "source", "")
    items = []
    for artist in getattr(song, "artists", []) or []:
        identifier = getattr(artist, "identifier", "")
        if not identifier:
            continue
        item = media_item_to_dict(artist, "artist", library)
        if not item["provider_id"] and song_source:
            item["id"] = f"artist:{song_source}:{identifier}"
            item["provider_id"] = song_source
            item["provider_name"] = provider_name(library.get(song_source))
            item["provider_url"] = provider_web_url(song_source, "artist", identifier)
        if item["provider_id"]:
            items.append(item)
    return items


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


def model_count(model, *fields: str) -> Optional[int]:
    for field in fields:
        value = getattr(model, field, None)
        if value is None:
            continue
        try:
            count = int(value)
        except (TypeError, ValueError):
            continue
        if count >= 0:
            return count
    return None
