import json
import os
from pathlib import Path
import shutil
import sys
import traceback


LOGIN_STATE_RELATIVE_PATHS = (
    "nem_users_info.json",
    "qqmusic_user_info.json",
    "ytmusic_header.json",
    "ytmusic_header.cookies.txt",
    "fuo_bilibili/bilibili_api.cookie",
    "fuo_bilibili/bilibili_api_cookie.json",
)


def _write_response(request_id, ok, result=None, error=None):
    payload = {
        "id": request_id,
        "ok": ok,
        "result": result,
        "error": error,
    }
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def _prepare_feeluown_storage():
    try:
        from feeluown import consts
    except Exception:
        return

    for name in ("HOME_DIR", "DATA_DIR", "STATE_DIR", "CACHE_DIR"):
        path = getattr(consts, name, None)
        if path:
            Path(path).mkdir(parents=True, exist_ok=True)

    data_dir = Path(consts.DATA_DIR)
    home = Path(os.path.expanduser("~"))
    candidate_dirs = [
        home / ".local" / "share" / "fuo-evolve" / "feeluown",
        home / ".config" / "fuo-evolve" / "feeluown",
        home / ".FeelUOwn",
    ]
    xdg_data_home = os.environ.get("XDG_DATA_HOME")
    if xdg_data_home:
        candidate_dirs.append(Path(xdg_data_home) / "fuo-evolve" / "feeluown")

    for source_dir in candidate_dirs:
        if source_dir.resolve() == data_dir.resolve() or not source_dir.exists():
            continue
        for relative_path in LOGIN_STATE_RELATIVE_PATHS:
            source = source_dir / relative_path
            target = data_dir / relative_path
            if source.is_file() and not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)


def main():
    android_python_dir = os.environ.get("FUO_ANDROID_PYTHON_DIR")
    if android_python_dir:
        sys.path.insert(0, android_python_dir)

    from fuo_mobile.bridge import create_bridge

    _prepare_feeluown_storage()

    bridge = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            method = request.get("method")
            args = request.get("args") or []
            if method == "create_bridge":
                bridge = create_bridge(args[0] if args else "")
                _write_response(request_id, True, "")
                continue
            if bridge is None:
                raise RuntimeError("bridge is not initialized")
            result = getattr(bridge, method)(*args)
            _write_response(request_id, True, result)
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            _write_response(request_id, False, error=str(exc))


if __name__ == "__main__":
    main()
