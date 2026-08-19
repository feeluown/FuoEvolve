from pathlib import Path
import re

# Execute the mechanical extraction without the staging script's broad
# substring assertion. Then close the one orchestration-specific mutation
# that differs from the common next/recovery cleanup shape.
script_path = Path('.github/agent/refactor-playback-controller.py')
script = script_path.read_text(encoding='utf-8')
start = script.index('for forbidden in (')
end = script.index('path.write_text(source, encoding="utf-8")', start)
script = script[:start] + script[end:]
exec(compile(script, str(script_path), 'exec'), {'__name__': '__main__'})

controller_path = Path('shared/src/commonMain/kotlin/org/feeluown/mobile/FuoPlayerController.kt')
controller = controller_path.read_text(encoding='utf-8')
old = '''        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
            playMainIndex(mainQueueIndex.coerceAtLeast(0))
            return
        }
'''
new = '''        if (currentIsUpNext) {
            playbackQueueController.clearCurrentUpNext()
            persistPlaybackQueue()
            playMainIndex(mainQueueIndex.coerceAtLeast(0))
            return
        }
'''
if controller.count(old) != 1:
    raise SystemExit(f'previous Up Next cleanup: expected one match, found {controller.count(old)}')
controller = controller.replace(old, new, 1)
controller_path.write_text(controller, encoding='utf-8')

# Enforce the architectural boundary: FuoPlayerController may read queue
# state through facade getters but may not directly assign any queue-owned
# state. Match a single assignment operator only, not == comparisons.
owned = (
    'currentUpNextTrack',
    'currentIsUpNext',
    'mainQueue',
    'originalMainQueue',
    'upNextQueue',
    'mainQueueIndex',
    'queueFeature',
    'queuePlaylistId',
    'shuffleEnabled',
    '_repeatMode',
    'isFmQueue',
    'shuffleBeforeFm',
)
assignment = re.compile(r'^\s*(' + '|'.join(map(re.escape, owned)) + r')\s*=\s*(?!=)')
violations = [
    (index + 1, line)
    for index, line in enumerate(controller.splitlines())
    if assignment.search(line)
]
if violations:
    details = '\n'.join(f'{line_no}: {line}' for line_no, line in violations)
    raise SystemExit(f'direct queue-state assignments remain in FuoPlayerController:\n{details}')
