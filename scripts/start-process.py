import json
import subprocess
import sys
from pathlib import Path

root, name, executable, directory, *arguments = sys.argv[1:]
with (Path(root) / 'work' / f'{name}.log').open('wb') as output, \
        (Path(root) / 'work' / f'{name}-error.log').open('wb') as errors:
    # Close inherited Gradle pipes so the before-run task can finish on Windows.
    process = subprocess.Popen(
        [executable, *arguments], cwd=directory, stdin=subprocess.DEVNULL,
        stdout=output, stderr=errors, close_fds=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
print(json.dumps({'Name': name, 'Id': process.pid}))
