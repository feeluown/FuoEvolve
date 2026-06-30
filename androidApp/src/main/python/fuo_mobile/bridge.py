import json
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
from feeluown.media import Media
from feeluown.player.base_player import AbstractPlayer, State
from feeluown.utils.dispatch import Signal


def _patch_feeluown_models_for_pydantic_v1() -> None:
    import pydantic
    import feeluown.library.models as models

    if hasattr(pydantic, "field_validator"):
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


class ProviderRegistry:
    def __init__(self, app: MobileAppContext, enabled: List[str]):
        self.app = app
        self.enabled = enabled
        self.provider_ids: List[str] = []

    def load(self):
        for module_name in self.enabled:
            module = __import__(module_name)
            if not hasattr(module, "enable"):
                raise RuntimeError(f"provider module has no enable(app): {module_name}")
            if hasattr(module, "init_config"):
                module.init_config(Config(module_name))
            before = {provider.identifier for provider in self.app.library.list()}
            self._enable_without_auto_login(module)
            provider = getattr(module, "provider", None)
            provider_id = getattr(provider, "identifier", None)
            if provider_id and self.app.library.get(provider_id) is not None:
                self.provider_ids.append(provider_id)
            else:
                after = [provider.identifier for provider in self.app.library.list()]
                self.provider_ids.extend(provider_id for provider_id in after if provider_id not in before)

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
}


class FuoMobileBridge:
    def __init__(self, providers_json: str):
        providers = json.loads(providers_json or "{}").get("enabled") or ["fuo_netease"]
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

    def resolve(self, track_id: str) -> str:
        song = self._tracks.get(track_id)
        if song is None:
            song = self._song_from_track_id(track_id)
            self._tracks[track_id] = song
        payload = self._prepare_payload(song)
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
        if hasattr(provider, "get_user_from_cookies"):
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
                    "is_login_required": True,
                    "error_message": "",
                },
                ensure_ascii=False,
            )
        try:
            tracks, playlists, title = self._load_feature_content(provider, feature)
            if title:
                feature_payload["title"] = title
            payload = {
                "feature": feature_payload,
                "tracks": tracks,
                "playlists": playlists,
                "is_login_required": False,
                "error_message": "",
            }
        except Exception as exc:  # pylint: disable=broad-except
            payload = {
                "feature": feature_payload,
                "tracks": [],
                "playlists": [],
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
            return [self._remember_song(song) for song in songs], [], ""
        if action == "rec_list_daily_playlists":
            playlists = read_models(provider.rec_list_daily_playlists(), limit=30)
            return [], [self._remember_playlist(playlist) for playlist in playlists], ""
        if action == "current_user_list_radio_songs":
            songs = provider.current_user_list_radio_songs(20)
            return [self._remember_song(song) for song in songs], [], ""
        if action == "toplist_list":
            playlists = read_models(provider.toplist_list(), limit=50)
            return [], [self._remember_playlist(playlist) for playlist in playlists], ""
        if action == "rec_a_collection_of_songs":
            collection = provider.rec_a_collection_of_songs()
            songs = read_models(getattr(collection, "models", []) or [], limit=50)
            return [self._remember_song(song) for song in songs], [], getattr(collection, "name", "")
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

    def _save_login(self, provider_id: str, user, cookies: Dict[str, str]) -> None:
        if provider_id == "netease":
            import os
            from fuo_netease.login_controller import LoginController
            from fuo_netease.consts import USERS_INFO_FILE

            os.makedirs(os.path.dirname(USERS_INFO_FILE), exist_ok=True)
            LoginController.save(user)
        elif provider_id == "qqmusic":
            from fuo_qqmusic.login import write_cookies

            write_cookies(user, cookies)

    def _prepare_payload(self, song) -> Dict[str, Any]:
        try:
            media = self.app.library.song_prepare_media(
                song,
                self.app.config.AUDIO_SELECT_POLICY,
            )
        except MediaNotFound as exc:
            raise RuntimeError(f"media not found: {song}") from exc
        payload = media_to_payload(media, song_to_metadata(song, self.app.library))
        cover = self.app.library.model_get_cover(song)
        if cover:
            payload["cover_url"] = cover
        lyrics = self._get_lyrics(song)
        if lyrics:
            payload["lyrics"] = lyrics
        return payload

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
        "cover_url": display(song, "pic_url"),
    }


def song_to_metadata(song, library: Library) -> Dict[str, Any]:
    data = song_to_dict(song, library)
    return {
        "title": data["title"],
        "artists": data["artists"],
        "album": data["album"],
        "source": data["source"],
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
        "cover_url": display(playlist, "cover") or "",
        "description": display(playlist, "description") or display(playlist, "creator_name"),
        "play_count": play_count(playlist),
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
        "headers": dict(media.http_headers or {}),
        "cover_url": metadata.get("cover_url", ""),
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


def provider_name(provider) -> str:
    if provider is None:
        return ""
    return getattr(provider, "name", getattr(provider, "identifier", ""))


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
