import json
import os
import sys
import traceback


def _write_response(request_id, ok, result=None, error=None):
    payload = {
        "id": request_id,
        "ok": ok,
        "result": result,
        "error": error,
    }
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def main():
    android_python_dir = os.environ.get("FUO_ANDROID_PYTHON_DIR")
    if android_python_dir:
        sys.path.insert(0, android_python_dir)

    from fuo_mobile.bridge import create_bridge

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
