from pathlib import Path

# Execute the queue refactor without the staging script's broad substring
# assertion, then report any direct queue-state assignments left in the
# generated FuoPlayerController so they can be explicitly delegated.
script_path = Path('.github/agent/refactor-playback-controller.py')
script = script_path.read_text(encoding='utf-8')
start = script.index('for forbidden in (')
end = script.index('path.write_text(source, encoding="utf-8")', start)
script = script[:start] + script[end:]
exec(compile(script, str(script_path), 'exec'), {'__name__': '__main__'})

controller_path = Path('shared/src/commonMain/kotlin/org/feeluown/mobile/FuoPlayerController.kt')
lines = controller_path.read_text(encoding='utf-8').splitlines()
needles = (
    'currentUpNextTrack =',
    'currentIsUpNext =',
    'mainQueue =',
    'upNextQueue =',
    'mainQueueIndex =',
    'queueFeature =',
    'queuePlaylistId =',
    'shuffleEnabled =',
    '_repeatMode =',
    'isFmQueue =',
    'shuffleBeforeFm =',
    'originalMainQueue =',
)
for index, line in enumerate(lines):
    if any(needle in line for needle in needles):
        start_line = max(0, index - 4)
        end_line = min(len(lines), index + 5)
        print(f'--- residual queue mutation near line {index + 1} ---')
        for context_index in range(start_line, end_line):
            print(f'{context_index + 1:5}: {lines[context_index]}')
