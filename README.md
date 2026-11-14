# 바로운전 (baro-drive)

## 1. 앱 이름 및 한 줄 소개

**바로운전** (팀 fluffyalpaca)

차량의 방향지시등·브레이크·핸들 신호와 내비의 회전 안내를 함께 읽어, 가족 말투의 코치가 회전 전에 미리 알려 주고 정차 후에는 '다음 과제 하나'를 주는 초보·장롱면허 운전자용 4단계 셀프 연습 코치예요.

## 2. 실행 방법

1. Android Studio에서 Pleos Connect 에뮬레이터를 만들어요(버전·설정은 3·4장).
   - SDK Manager → SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` 추가
   - API 34의 **Pleos Connect System Image** 설치 → Device Manager → Automotive → **Pleos Connect v2.1.0**
2. 에뮬레이터를 켜고 APK를 설치해요.
   ```bash
   adb install -r -g "apk/복슬복슬 알파카_바로운전_release.apk"
   ```
3. 앱 목록에서 **바로운전**을 열어요. 처음 실행이면 [시작하기] → 기록 저장 동의 → 질문 2개 → 시작 단계 추천 → 코치 고르기 뒤 홈이 나와요.
4. 에뮬레이터는 차를 움직일 수 없어서 앱 안에 **시연 패널**을 넣었어요. 화면 오른쪽 아래 **'시연'** 탭을 누르면 열려요.
   - 홈 [이어서 연습하기] → 코스 고르기 → '연습 시작 전' → **[내비로 보내고 출발]**을 누르면 녹화된 주행이 저절로 재생돼요. 배속은 처음 값이 '실제'(한 바퀴 약 7분)이고, 시연 패널 '시나리오'에서 ×4로 바꿀 수 있어요.
   - 시연 패널 '차량'에서 기어·깜빡이·브레이크·핸들을 손으로 바꾸거나, '데모 도구'에서 미리 만든 상태(예: 2단계 3회차)를 적용할 수 있어요. [처음 실행으로 되돌리기]로 처음 상태로 돌아가요.
   - PC 키보드로도 운전해요(자료 출처가 시뮬레이터일 때): ↑ 가속 · ↓ 브레이크 · ← → 핸들 · PgUp/PgDn 기어(P R N D 키도) · Q E 깜빡이 · W 비상등 · C 핸들 가운데.

## 3. 테스트 환경 및 필요한 설정

| | |
|---|---|
| 확인한 호스트 | Windows + WSL2(x86_64 시스템 이미지), macOS Apple silicon(arm64-v8a 시스템 이미지) |
| AVD | RAM 4 GB, Cold boot, 가로 화면 |
| 앱 | `com.barodrive.app` 0.1.0 · minSdk 28 · targetSdk 35 · 시연 도구를 넣은 release 빌드(팀 release 키 서명) |

필요한 설정

- **차 속도 권한**: 설치할 때 `-g`를 붙이면 `CAR_SPEED` 권한이 미리 허용돼요. 빼면 '연습 시작 전' 화면에 처음 들어갈 때 물어요.
- **키보드 운전**: 에뮬레이터의 하드웨어 키보드 입력을 켜야 해요. Device Manager → 그 AVD 편집(연필) → Show Advanced Settings → Keyboard의 **Enable keyboard input** 체크 → 에뮬레이터 다시 켜기. 키를 누르기 전에 에뮬레이터 화면을 한 번 눌러 포커스를 줘요.
- **설치 실패**: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나오면 다른 키로 서명된 바로운전이 이미 깔려 있는 거예요. `adb uninstall com.barodrive.app` 뒤 다시 설치해요(앱 기록도 지워져요).
- **소리**: 에뮬레이터 기본 TTS에는 한국어 음성이 없어 Pleos TTS SDK로 말해요. 소리가 나지 않으면 화면 말풍선과 시연 패널 '말한 내용'으로 볼 수 있어요.
- **터치가 막힐 때**: 에뮬레이터 환경에 따라 Pleos의 Driving view가 다시 시작되며 잠깐 터치를 막을 수 있어요. 몇 초 기다리거나 `adb shell am force-stop ai.umos.drivingview`를 실행해요.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| | |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (Android 14 · API 34 Automotive, 시스템 이미지 `release_connect_v2.1.0`) |
| 화면 규격 | **2560 × 1440** 가로, 밀도 **160 dpi**(1 dp = 1 px) |
| 앱 창 | 가운데 **1682 × 1210**(왼쪽 Pleos 주행 정보 · 위 상태 줄 · 아래 독을 뺀 영역) |

차 화면에 맞춘 크기(기본 글자 26sp, 버튼 80dp 이상)로 그려서, 휴대폰·태블릿에서는 글과 버튼이 잘려 보여요. Pleos 에뮬레이터에서 봐 주세요.

## 5. APK 파일 위치

[`apk/복슬복슬 알파카_바로운전_release.apk`](apk/복슬복슬%20알파카_바로운전_release.apk)
