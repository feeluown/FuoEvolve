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

    def load(self):
        for module_name in self.enabled:
            module = __import__(module_name)
            if not hasattr(module, "enable"):
                raise RuntimeError(f"provider module has no enable(app): {module_name}")
            if hasattr(module, "init_config"):
                module.init_config(Config(module_name))
            self._enable_without_auto_login(module)

    def _enable_without_auto_login(self, module):
        import feeluown.library.library as library_module

        original_run_fn = library_module.run_fn
        library_module.run_fn = lambda *args, **kwargs: None
        try:
            module.enable(self.app)
        finally:
            library_module.run_fn = original_run_fn


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

    def search(self, keyword: str) -> str:
        tracks = []
        self._tracks.clear()
        for result in self.app.library.search(keyword, type_in=[SearchType.so]):
            for song in getattr(result, "songs", []) or []:
                track = song_to_dict(song)
                self._tracks[track["id"]] = song
                tracks.append(track)
        return json.dumps({"tracks": tracks}, ensure_ascii=False)

    def resolve(self, track_id: str) -> str:
        if track_id not in self._tracks:
            raise RuntimeError(f"unknown track id: {track_id}")
        payload = self._prepare_payload(self._tracks[track_id])
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
        if not hasattr(provider, "get_user_from_cookies"):
            raise RuntimeError(f"provider does not support cookies login: {provider_id}")
        user = provider.get_user_from_cookies(cookies)
        provider.auth(user)
        if provider_id == "netease":
            import os
            from fuo_netease.login_controller import LoginController
            from fuo_netease.consts import USERS_INFO_FILE
            os.makedirs(os.path.dirname(USERS_INFO_FILE), exist_ok=True)
            LoginController.save(user)
        return json.dumps(provider_auth_state(provider, user), ensure_ascii=False)

    def _get_provider(self, provider_id: str):
        provider = self.app.library.get(provider_id)
        if provider is None:
            raise RuntimeError(f"provider not found: {provider_id}")
        return provider

    def _prepare_payload(self, song) -> Dict[str, Any]:
        try:
            media = self.app.library.song_prepare_media(
                song,
                self.app.config.AUDIO_SELECT_POLICY,
            )
        except MediaNotFound as exc:
            raise RuntimeError(f"media not found: {song}") from exc
        payload = media_to_payload(media, song_to_metadata(song))
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


def provider_auth_state(provider, user) -> Dict[str, Any]:
    return {
        "provider_id": getattr(provider, "identifier", ""),
        "provider_name": getattr(provider, "name", getattr(provider, "identifier", "")),
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


def song_to_dict(song) -> Dict[str, str]:
    source = getattr(song, "source", "")
    identifier = getattr(song, "identifier", "")
    return {
        "id": f"{source}:{identifier}",
        "title": display(song, "title"),
        "artists": display_artists(song),
        "album": display_album(song),
        "source": source,
        "duration_ms": duration_ms(song),
        "cover_url": "",
    }


def song_to_metadata(song) -> Dict[str, str]:
    data = song_to_dict(song)
    return {
        "title": data["title"],
        "artists": data["artists"],
        "album": data["album"],
        "source": data["source"],
        "duration_ms": data["duration_ms"],
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
