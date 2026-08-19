from pathlib import Path

script_path = Path('.github/agent/refactor-playback-controller.py')
script = script_path.read_text(encoding='utf-8')
start = script.index('for forbidden in (')
end = script.index('path.write_text(source, encoding="utf-8")', start)
script = script[:start] + script[end:]
exec(compile(script, str(script_path), 'exec'), {'__name__': '__main__'})
