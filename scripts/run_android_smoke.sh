#!/usr/bin/env bash
set -euo pipefail
mkdir -p android-smoke-results
adb install -r app-artifacts/qingwei-debug.apk
adb install -r app-artifacts/qingwei-android-test.apk
adb shell am instrument -w -r com.kernelai.app.debug.test/androidx.test.runner.AndroidJUnitRunner \
  | tee android-smoke-results/instrumentation.log
adb logcat -d -t 3000 > android-smoke-results/logcat.txt
adb pull /sdcard/Android/data/com.kernelai.app.debug/files/test-screenshots android-smoke-results/ || true
python3 - <<'PY'
from pathlib import Path
import re
text = Path('android-smoke-results/instrumentation.log').read_text(errors='replace')
if re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', text):
    raise SystemExit('Android instrumentation failed; see uploaded report')
result = re.search(r'OK \((\d+) tests?\)', text)
if not result or int(result[1]) < 26:
    raise SystemExit('Android smoke suite did not complete all required tests')
PY
