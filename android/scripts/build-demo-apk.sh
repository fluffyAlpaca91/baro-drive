#!/usr/bin/env bash
# 공모전 제출용 시연 APK를 만들어 저장소 맨 위 apk/ 폴더에 정해진 이름으로 복사한다.
#   - 시연 도구(시연 패널·시뮬레이터)를 넣은 release 빌드다. 에뮬레이터는 차를 움직일 수 없어서 시연 도구가 꼭 필요하다.
#   - 서명은 팀 release 키다(~/.gradle/gradle.properties의 baroRelease*). 키가 없으면 디버그 키로 서명되니 서명 DN을 확인한다.
# 사용: android/scripts/build-demo-apk.sh   (WSL. Windows에서는 Android Studio에서 Build → Generate APK 뒤 같은 이름으로 복사)
set -euo pipefail

TEAM="복슬복슬 알파카"
APP="바로운전"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$here/.." && pwd)"
repo_dir="$(cd "$android_dir/.." && pwd)"

"$here/gradlew-wsl.sh" :app:assembleRelease -PdemoTools=true

src="$android_dir/app/build/outputs/apk/release/app-release.apk"
[ -f "$src" ] || { echo "APK가 없어요: $src" >&2; exit 1; }

mkdir -p "$repo_dir/apk"
dst="$repo_dir/apk/${TEAM}_${APP}_release.apk"
cp "$src" "$dst"

source "$HOME/opt/baro-env.sh"
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
"$apksigner" verify --print-certs "$dst" | grep "certificate DN" && echo "서명 확인: 통과"
echo "만들었어요: $dst ($(du -h "$dst" | cut -f1))"
