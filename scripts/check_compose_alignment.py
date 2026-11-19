"""Reject duplicate, incompatible Compose classes in an app/instrumentation APK pair."""
from pathlib import Path
import sys
import zipfile


def versions(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name).decode().strip() for name in archive.namelist()
                if name.startswith('META-INF/androidx.compose.') and name.endswith('.version')}


def main():
    app = versions(Path(sys.argv[1]))
    tests = versions(Path(sys.argv[2]))
    runtime = app.get('META-INF/androidx.compose.runtime_runtime.version')
    if not runtime:
        raise SystemExit('App Compose runtime metadata is missing')
    if not any(key.startswith('META-INF/androidx.compose.ui_ui-test') for key in tests):
        raise SystemExit('Instrumentation Compose test metadata is missing')
    mismatches = {key: (app[key], tests[key]) for key in app.keys() & tests.keys() if app[key] != tests[key]}
    for key, value in tests.items():
        if key.startswith('META-INF/androidx.compose.ui_ui-test') and value != runtime:
            mismatches[key] = (runtime, value)
    if mismatches:
        raise SystemExit('App/test Compose versions differ: ' + str(mismatches))
    print(f'Compose app/test alignment: PASS (runtime {runtime}, {len(app.keys() & tests.keys())} shared version markers)')


if __name__ == '__main__':
    main()
