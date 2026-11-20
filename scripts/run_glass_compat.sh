#!/usr/bin/env bash
set -euo pipefail
mkdir -p android-glass-results
adb install -r app-artifacts/qingwei-debug.apk
adb install -r app-artifacts/qingwei-android-test.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am instrument -w -r -e class com.kernelai.app.GlassCompatibilityTest,com.kernelai.app.NavigationUiTest \
  com.kernelai.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee android-glass-results/instrumentation.log
adb logcat -d -t 2000 > android-glass-results/logcat.txt
python3 - <<'PY'
from pathlib import Path
import re
text = Path('android-glass-results/instrumentation.log').read_text(errors='replace')
match = re.search(r'OK \((\d+) tests?\)', text)
if re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=', text) or not match or int(match[1]) < 5:
    raise SystemExit('Glass compatibility checks failed or did not finish')
PY
