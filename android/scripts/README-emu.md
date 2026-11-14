# Pleos 에뮬레이터 (WSL, 창 없이) — `emu-wsl.sh`

에이전트가 WSL 안에서 Pleos Connect 에뮬레이터를 띄워 앱을 깔고, 누르고, 찍고, 차 신호를 넣을 때 쓴다.
모든 명령은 `android/scripts/emu-wsl.sh <명령>`이다. 결과(시리얼, 파일 경로, XML, 값)만 stdout으로 나오고 진행 상황과 오류는 stderr로 나온다.

## 한눈에

| 항목 | 값 |
|---|---|
| AVD | `pleos210` = Pleos Connect v2.1.0 (API 34 Automotive x86_64, `user` 빌드, ko-KR) |
| 화면 | 2560×1440, density 160 (dp = px). `shot` 좌표가 곧 `tap` 좌표다 |
| 게스트 RAM | 4 GB (Pleos 가이드 값). 콜드 부팅만 한다(스냅샷 없음) |
| 부팅 | 22–45초 (`start` 기준. 디스크 캐시가 데워져 있으면 22–30초) |
| GPU | `-gpu host` = WSL의 Mesa **llvmpipe(CPU 렌더링)**. 아래 "GPU" 참고 |
| 호스트 메모리 | 부팅 직후 RSS 약 3.9 GB → 몇 분 쓰면 5.8–6.8 GB (게스트 4 GB + CPU 렌더링이 쓰는 그래픽 메모리) |
| 호스트 CPU | 정차 화면에서 5.5–7.5코어 (렌더 스레드 4개로 제한, nice 10). 제한을 풀면 8–10코어 |
| 시리얼 | `emulator-5580` (`EMU_PORT`로 바꿈) |
| 앱 사용자 | Android 사용자 **10** (AAOS: 사용자 0은 화면 없는 시스템 사용자). 앱 데이터도 `/data/user/10/` |

**RAM 15 GB를 다른 에이전트의 Gradle 빌드와 나눠 쓴다. 다 쓰면 `emu-wsl.sh stop`.** 에뮬레이터는 하나를 같이 쓴다: `start`는 이미 떠 있으면 그것을 쓰고, 동시에 불러도 하나만 띄운다(잠금). 다른 에이전트가 쓰는 중인지는 `status`로 본다.

## 같이 쓰기 (잠금)

에이전트끼리는 `/tmp/baro-emu.lock`을 쥔 동안만 에뮬레이터를 쓴다. **`flock -o`로 쥔다.** `-o`가 없으면 잠금을 쥔 파일 설명자가 명령에 물려 내려가고, `start`가 띄운 에뮬레이터(백그라운드로 계속 도는 프로세스)까지 그것을 물려받아 에뮬레이터가 꺼질 때까지 잠금이 풀리지 않는다.

```bash
flock -o /tmp/baro-emu.lock bash my-qa.sh                  # 차례가 올 때까지 기다렸다가 쓴다
flock -o -n /tmp/baro-emu.lock bash my-qa.sh || echo busy  # 쓰는 중이면 바로 포기
# 명령을 여러 번 나눠 부를 때: 잠금만 쥐는 프로세스를 띄워 두고, 다 쓰면 그것을 끝낸다
flock -o -n /tmp/baro-emu.lock sleep 3600 &  holder=$!
...; kill $holder
```

## 빠른 사용

처음 실행(ON-1)부터 1단계 한 바퀴(S1-B)를 돌려 '오늘 연습 돌아보기'(RV-1)까지. 시연 빌드(디버그 APK)이고 자료 출처는 처음 값 시뮬레이터다.

```bash
E=android/scripts/emu-wsl.sh
android/scripts/gradlew-wsl.sh :app:assembleDebug
$E start                                   # 부팅(또는 재사용) → emulator-5580 출력. 새로 띄웠으면 1분쯤 기다린다(알려진 문제 10)
$E adb shell pm clear --user 10 com.barodrive.app                 # 처음 실행부터(앱 데이터는 사용자 10). 실행 중 권한도 지우므로 설치보다 먼저
$E install android/app/build/outputs/apk/debug/app-debug.apk -g   # -g: CAR_SPEED 같은 실행 중 권한을 미리 준다
$E launch                                  # com.barodrive.app/.MainActivity → ON-1
$E tap-text '시작하기' 60                   # ON-1. 글자가 뜰 때까지 기다렸다가 누른다
$E tap-text '연습 기록 저장'; $E tap-text '동의하고 계속'           # ON-2 필수 동의
$E tap-text '1년이 안 됐어요'; $E tap-text '안 했어요'; $E tap-text '다음'   # ON-3
$E tap-text '1단계로 시작'; $E tap-text '이 코치로 시작'          # ON-4 · ON-5 → HM-1
$E tap-text '이어서 연습하기'               # HM-1 → CR-1
$E tap-text 'B 코스로 연습하기'             # CR-1(추천 B = 시나리오 S1-B) → PR-1
$E tap-text '내비로 보내고 출발'            # 1초 뒤 S1-B가 저절로 재생된다
$E tap 2491 1196; $E tap 2160 708          # 시연 손잡이 → 패널 '시나리오' ×4 (주행 중에는 좌표로, 알려진 문제 7)
$E wait-text '오늘 연습 돌아보기' 240        # 도착·P → RV-1 (S1-B는 sim 약 7분: 처음 값 '실제'면 실제 7분, ×4면 2분쯤)
$E shot                                    # ~/work/qa-shots/shot-<시각>.png 경로 출력
$E logcat app -d                           # 앱 로그만
$E stop
```

- 빨리 보려면 온보딩 대신 패널 '데모 도구'의 미리 만든 상태(예: '1단계 1회차 마침') + [적용].
- 진짜 차 신호: 패널 자료 출처 [진짜 차] 뒤 `$E signal gear D; $E signal speed 30`(Virtual Console), `$E regulation on|off`(주행 규제).

## 명령

| 명령 | 하는 일 |
|---|---|
| `start` | 창 없이 콜드 부팅, `sys.boot_completed`까지 기다림, 차량 버스(eth0) 확인·복구, 8088 포워드, 시리얼 출력 |
| `stop` | `adb emu kill` → 30초 안에 안 꺼지면 SIGTERM → SIGKILL |
| `status` | 실행 여부, 부팅, eth0, 호스트 RSS·CPU, AAOS 주행 상태, UX 제한 |
| `install <apk> [플래그]` | `adb install -r -t [플래그] <apk>`. `-g`를 붙이면 런타임 권한(CAR_SPEED 등)을 미리 준다. 안 붙이면 진짜 차 자료 출처에서 PR-1이 속도 권한 창을 띄운다 |
| `launch [pkg/.Activity]` | `am start -W`. 첫 실행이 10초를 넘으면 `Status: timeout`이 나오지만 앱은 뜨는 중이다 → `wait-text` |
| `shot [파일.png]` | `screencap -p`. 기본 저장 위치 `~/work/qa-shots/` |
| `tap x y` · `swipe x1 y1 x2 y2 [ms]` · `key BACK` | `input` 명령. `tap`·`swipe`(그래서 `tap-text`도)는 먼저 Pleos 주행 화면을 멈춘다(알려진 문제 6) |
| `text <문자열>` | `input text`. **ASCII만** 된다(한글 입력 불가) |
| `keys <단계>…` | 하드웨어 키보드처럼 키를 **누르고 있기**(시연 키보드 운전 확인용). 셸 `uinput` 명령으로 가상 USB 키보드를 잠깐 만든다. 단계: `tap:키` · `down:키` · `up:키` · `hold:키:ms` · `wait:ms`, 키: `up down left right pageup pagedown w s p r n d q e c a x enter space esc back`. 예: `keys tap:d hold:up:3000`, `keys tap:pagedown tap:pagedown` (아래 '키보드') |
| `dump` | uiautomator XML을 stdout으로. 한 번에 약 4초 걸린다 |
| `texts [패키지]` | 글자가 있는 노드마다 `중심x 중심y 글자` 한 줄 |
| `wait-text <글자> [초]` | 그 글자가 화면에 나올 때까지 기다림(기본 30초), `x y` 출력 |
| `tap-text <글자> [초]` | 기다렸다가 그 노드 가운데를 누름 (같은 글자 우선, 없으면 포함하는 글자) |
| `signal <이름> <값>` | 차 신호. `signal help`, `signal list`(카드 64개), `signal state [키]` |
| `regulation on\|off` | 주행 규제 켜기·끄기 (아래 참고) |
| `logcat [app] [인자]` | `adb logcat -v time [인자]`. `app`이면 앱 PID만. 예: `logcat -d -s CoachVoice` |
| `vc` | Virtual Console 포워드 후 주소 출력 (`http://127.0.0.1:8088/`, Windows 브라우저에서도 열림) |
| `fix-net` | 차 신호가 안 먹을 때 eth0 복구 (아래 "알려진 문제" 1) |
| `serial` · `adb <인자>` | 시리얼 출력 · `adb -s emulator-5580 <인자>` |

환경 변수: `EMU_GPU`(host), `EMU_RAM_MB`(4096), `EMU_RENDER_THREADS`(4, llvmpipe 스레드 수), `EMU_NICE`(10), `EMU_PORT`(5580), `EMU_APP`, `EMU_SHOTS`, `EMU_BOOT_TIMEOUT`(420), `EMU_WAIT_S`(30), `EMU_STOP_DRIVINGVIEW`(1, 0이면 누르기·덤프 전에 Pleos 주행 화면을 멈추지 않는다). 에뮬레이터 로그: `~/.cache/baro-emu/emulator.log` (libunwind 경고 수천 줄은 무시).

## 화면 구성과 앱 창 크기 (실측)

```
x 0–853: Driving view(클러스터: 기어·속도·3D 차)   y 0–90: 상태 표시줄   y 1300–1440: GNB(공조·앱 아이콘)
앱 영역: Rect(853, 90 – 2535, 1300) = 1682×1210 px = 1682×1210 dp
```

- 부팅하면 내비가 앱 영역 전체(Landscape)에 떠 있다. 우리 앱을 띄우면 **내비를 덮고 같은 Landscape 창 1682×1210**으로 뜬다 (`dumpsys activity`: `mBounds=Rect(853, 90 - 2535, 1300)`, `w1682dp h1210dp land`). Pleos UX 가이드 4.2와 같다.
- 내비 옆 Portrait 2분할은 adb로 만들지 못했다(오른쪽 끝 `⋮` 창 막대를 탭·드래그해도 창이 그대로).
- 우리 앱의 `시연` 손잡이(시연 빌드)는 앱 창 오른쪽 아래 구석의 세로 탭이다: 가운데 **(2491, 1196)**, 접근성 이름 '시연 패널 열기'(`tap-text '시연'`으로 눌린다). 펼친 패널(폭 620)은 손잡이 왼쪽 x 1843–2463, y 196–1276(높이는 내용만큼, 최대 1080)을 덮는다. 패널 '시나리오'의 배속 [실제 · ×1 · ×2 · ×4](처음 값 실제)는 기본 펼침 상태에서 y 705~708 줄이다: 서 있을 때(▶ 재생) x 1979·2031·2078·2126, 재생 중(⏸ 일시정지가 넓어짐) x 2013·2065·2112·2160.

## GPU

| 모드 | 결과 |
|---|---|
| `-gpu host` (기본) | 된다. WSL Mesa가 llvmpipe(CPU)를 고른다: `Graphics Adapter … (llvmpipe (LLVM 20.1.2))`. 주행 화면(3D)에서 60초 넘게 안정 |
| `-gpu host` + `GALLIUM_DRIVER=d3d12` (RTX 3060) | 렌더러 초기화 중 바로 segfault. 못 쓴다 |
| `-gpu swiftshader_indirect` | 부팅·앱 화면은 되지만, Virtual Console로 D·30 km/h를 넣어 Pleos 주행 화면이 움직이자 RenderThread SIGSEGV(SwiftShader JIT 코드)로 에뮬레이터가 죽었다. 쓰지 않는다 |

CPU 렌더링이라 느리다: 앱 프레임 약 200 ms(5 fps 안팎, `Davey!` 로그), 설치·부팅 직후 첫 실행 10–12초, 그 뒤 콜드 실행(`force-stop` 후) 약 2초. 성능·애니메이션 판단은 실차/실기기로 한다.

## 키보드 (시연 키보드 운전)

시연 빌드는 PC 키보드로 시뮬레이터 차를 몬다(↑ 가속 · ↓ 브레이크 · ← → 핸들 · PgUp PgDn 기어 한 칸(P R N D 키도) · Q E 깜빡이 · W 비상등 · C 핸들 가운데, 아키텍처-세팅 11장).
이 AVD는 `hw.keyboard=yes`라 커널 키보드 `qwerty2`가 있다(Android Studio에서 만든 Pleos AVD는 처음 값이 꺼짐 — 'Enable keyboard input'을 켠다).

- **키를 누르고 있기는 `keys`로 한다.** 셸 사용자가 `uhid` 묶음이라 `/system/bin/uinput`(JSON)으로 가상 USB 키보드를 만들 수 있다. 등록(`"type":100` = UI_SET_EVBIT, `101` = UI_SET_KEYBIT, 이 빌드는 이름 대신 숫자) → 0.6초 기다림 → 누름·뗌. 명령이 끝나면 장치도 사라진다(그때 누르고 있던 키는 뗌으로 온다).
- 에뮬레이터 콘솔 `adb emu event send EV_KEY:KEY_UP:1`은 `OK`라고 답하지만 이 에뮬레이터(37.2.13)에서는 게스트 어느 장치에도 이벤트가 오지 않는다(`getevent`로 확인). `adb emu event text abc`는 `qwerty2`로 온다(누르고 바로 뗌만). `input keyevent`도 톡 누름만, `input keycombination -t ms`는 키가 둘 이상이어야 한다. 셸 `sendevent`는 SELinux로 막힌다.
- 키 이벤트는 포커스를 가진 창으로 간다. Pleos 주행 화면(알려진 문제 6)이 다시 뜰 때마다 포커스를 가져가서, 그 순간 누르고 있던 페달은 앱이 뗀다(창 포커스를 잃으면 떼게 했다). 키를 오래 시험할 때는 잠깐 꺼 두고 끝나면 꼭 켠다: `$E adb shell pm disable-user --user 10 ai.umos.drivingview` → 시험 → `$E adb shell pm enable --user 10 ai.umos.drivingview`.
- **PgUp·PgDn(리눅스 `KEY_PAGEUP` 104·`KEY_PAGEDOWN` 109)은 키보드 장치의 키 배치에 따라 다르게 온다.** `keys`의 가상 USB 키보드는 `Generic.kl`이라 `KEYCODE_PAGE_UP`·`KEYCODE_PAGE_DOWN`이다. 그런데 에뮬레이터가 PC 키를 넘기는 키보드 `qwerty2`는 `qwerty2.idc` → `qwerty.kl`을 쓰고, 이 이미지의 `qwerty.kl`(vendor·system 모두)에는 104·109 줄이 없다(대신 102 Home → HOME, 107 End → ENDCALL). 그래서 PC의 PgUp·PgDn은 `KEYCODE_UNKNOWN`(스캔 코드 104·109)으로 올 것이라 앱이 스캔 코드로도 알아본다(`DriveKeys.keyCodeOf`). 그 길을 흉내 내려면 장치 이름을 바꾼다: `EMU_KEYS_NAME=qwerty2 $E keys tap:pagedown`(그 이름이면 안드로이드가 `qwerty2.idc`·`qwerty.kl`을 준다). 어느 장치가 어느 키 배치를 쓰는지는 `$E adb shell dumpsys input`의 `KeyLayoutFile`로 본다.
- 화면을 누른 직후의 첫 화살표 누름은 안드로이드가 '터치 모드 벗어나기'에 써 버린다(앱에 안 옴). 시연 빌드는 내용 틀이 포커스를 쥐고 있어서 이 일이 없다 — 확인: 화면을 누른 뒤 `keys hold:up:1500` → 7 km/h쯤.

## 차 신호 — Virtual Console (포트 8088)

서버는 vendor APEX `ai.pleos.hardware.vehicle.emulator.service`의 `vehicle_api_dds_emulator`(게스트 8088)다. 차량 신호를 DDS로 발행하고, Pleos VHAL이 그것을 CarProperty로 바꾼다.

**스크립트에서 쓰는 법** (`signal`이 다 해 준다):

1. 쓰기는 "브라우저 세션"이 살아 있을 때만 받는다. `POST /browser/session?client=<아무 id>`를 보내면(3초 지나면 만료) 잠시 뒤 `GET /vehicle/state`의 `controls.emulator_active`와 `gear.bridge_ready`가 `true`가 된다. 그 전에 쓰면 `HTTP 503 {"error":"emulator_inactive"}`.
2. 쓰기는 전부 `PUT <경로>?<쿼리>`다. 세션이 끝나도 넣은 값은 그대로 남는다.

| 신호 | `signal` | HTTP | 확인한 것 |
|---|---|---|---|
| 기어 | `gear P\|R\|N\|D` | `PUT /vehicle/gear?gear=Park\|Reverse\|Neutral\|Drive` | 클러스터 D, 앱(진짜 차) "D 주행 중" |
| 속도 | `speed 30` | `PUT /vehicle/controls?speed=30&accelerator=…&brake=…&steering=…` | 클러스터 30 km/h, 앱 30 km/h, AAOS MOVING |
| 브레이크·가속 | `brake 100` · `accel 20` | 위와 같은 `/vehicle/controls` (네 값을 같이 보낸다) | |
| 조향각 | `steering 15` | 위와 같음 (-470…500°) | 앱 Pleos SDK에 `PERF_STEERING_ANGLE = -15.0`으로 온다 — **부호가 반대** |
| 방향지시등 | `turn left\|right\|off\|flash-left\|flash-right` | `PUT /vehicle/turn-signal?mode=BlinkLeft\|BlinkRight\|Off\|…` | 클러스터 초록 화살표, 앱에 `TURN_SIGNAL_STATE` 콜백 (로그) |
| 비상등 | `hazard on` | `PUT /vehicle/lights?feature=hazard&value=true` | |
| 착석 | `seat occupied [driver]` | `PUT /vehicle/seat?kind=occupied&value=true&area=driver` | |
| 문 | `door front_left open` | `PUT /vehicle/body?door_fl=true` | |
| 잠금 | `lock on` | `PUT /vehicle/body?doors_locked=true` | |
| ODO | `odometer 1234` | `PUT /vehicle/odometer?traveled_distance_km=1234` | |
| 텔테일 | `telltale SeatBelt On` | `PUT /vehicle/telltales/simulator?color=Red&state=On&type=SeatBelt` | |
| 그 밖 | `raw /vehicle/hvac fan_speed=3` | 아무 쓰기 경로 | 전체는 `signal list` |

- 화면 카드 목록(사용 가이드)에는 속도가 없지만 `/vehicle/controls`의 `speed`로 바뀐다 (0–240 km/h).
- 안전벨트 "체결" 신호는 없다. 텔테일(경고등)만 있다.
- 처음 활성화될 때 에뮬레이터 기본값이 한 번 나간다: 공조 켜짐 22.0°, 주행 가능 거리 500 → 0 km.
- `GET /vehicle/catalog`: 카드 64개의 쓰기 경로·필드·값 범위·CarProperty 이름 (JSON).

## 주행 규제

- Pleos ADB 가이드의 `cmd hmg_car_service --service drivingstate driving_regulation on|off`는 **이 이미지에 없다**: `cmd: Can't find service: hmg_car_service`.
- 그래서 `regulation`은 먼저 그 명령을 해 보고, 없으면 차를 움직인다.
  - `on` = 기어 D + 30 km/h → `CarDrivingState` MOVING(2), UX 제한 `DO: true UxR: 80`(0x50 = NO_VIDEO | NO_SETUP, 한국 설정)
  - `off` = 0 km/h + 브레이크 + 기어 P → PARKED(0), `DO: false UxR: 0`
- 시뮬레이터 자료 출처일 때 우리 앱은 주행 규제를 보지 않는다(설계대로). 규제 화면을 보려면 시연 패널에서 `진짜 차`로 바꾼다.

## TTS (한국어 음성)

- 코치 음성은 **Pleos TTS SDK**(`ai.pleos.playground.tts.TextToSpeech`, ON_DEVICE 한국어)로 낸다. `AutoCoachVoice`가 처음 말할 때 Pleos TTS(`data/voice/PleosTtsCoachVoice`) → 안드로이드 TTS → 소리 없음(말풍선·자막만) 차례로 켜 보고, 고른 엔진을 로그에 남긴다. 이 에뮬레이터에서는 Pleos TTS가 잡힌다: `I/CoachVoice: 코치 음성: Pleos TTS(ON_DEVICE)` (`$E logcat app -d -s CoachVoice:I`).
- 안드로이드 기본 `TextToSpeech`에는 쓸 엔진이 없다(설정 > TTS 출력의 "기본 엔진"이 비어 있음, CarSettings 로그 `EngineInfo is null`). 그래서 안드로이드 TTS는 Pleos TTS를 못 쓸 때의 대신일 뿐이다. 내비도 Pleos 음성 서비스(caas `SpeechService`)를 쓴다.
- `cmd tts` 같은 셸 명령은 없다. 스크립트는 `-no-audio`로 띄우므로 소리 자체는 확인하지 않았다(엔진 선택과 말한 내용 기록만 본다).

## 알려진 문제

1. **차량 버스(eth0)가 안 올라오는 부팅.** DDS 차량 버스는 eth0(10.0.2.15)를 쓰는데, 이 이미지에서 eth0는 가짜 LTE 모뎀의 데이터 연결이다. 부팅 때 Wi-Fi가 먼저 붙으면 안드로이드가 LTE 데이터를 바로 끊고(`Tear down the network since there is no live network request`) eth0가 DOWN으로 남는다. 그러면 VHAL이 `network eth0 is not ready yet`를 0.2초마다 찍고, 차 신호가 안 들어오고, Virtual Console은 `emulator_inactive`만 돌려준다.
   - `start`가 자동으로 고친다: Wi-Fi 끄기 + 비행기 모드 켰다 끄기 → 약 20초 뒤 eth0 UP. Wi-Fi는 꺼진 채로 저장되어 다음 부팅부터는 처음부터 eth0가 올라온다. 인터넷은 LTE(eth0)로 된다.
   - 나중에 차 신호가 다시 안 먹으면 `emu-wsl.sh fix-net`.
2. 느리다 (GPU 참고). `am start -W`가 시간 초과를 낼 수 있다 → `wait-text`로 기다린다.
3. `text`는 ASCII만 된다.
4. 내비 지도 타일이 하얗게 비어 있고 현재 위치는 성남 수정구 시흥동이다 (가이드 6.3과 같음).
5. `user` 빌드라 `adb root`가 안 된다. `run-as`는 디버그 앱에만 되고, 앱 파일은 사용자 10에 있으니 `--user 10`을 붙인다: `$E adb shell run-as com.barodrive.app --user 10 ls databases`.
6. **Pleos 주행 화면(`ai.umos.drivingview`, 왼쪽 클러스터)이 누름을 삼킨다.** CPU 렌더링에서는 시작을 끝내지 못해 약 20초마다 ANR(`failed to complete startup`)로 죽고 홈이 다시 띄우는데, 떠 있는 동안 우리 앱 창의 누름이 먹지 않고, 다시 뜰 때마다 포커스를 가져가서 uiautomator가 그 빈 창만 덤프한다. `adb shell am force-stop ai.umos.drivingview` 바로 뒤에는 눌리고 포커스도 돌아온다(다시 저절로 뜬다). 스크립트가 해 준다: `tap`·`swipe`(그래서 `tap-text`도)는 떠 있으면 멈추고 1초 뒤 누르고, `dump`·`texts`·`wait-text`는 그것이 포커스를 가졌을 때만 멈춘다(`EMU_STOP_DRIVINGVIEW=0`이면 안 함). 몇 초마다 멈추면 Pleos 홈이 `Listener for taskId=… already exists`로 죽는 일이 있었다 — 그러면 우리 앱이 내비 지도 뒤로 숨으니 `launch`를 다시 한다. `adb shell input`을 직접 쓸 때는 손으로 멈춘다. 그래서 왼쪽 클러스터는 대개 하얗게 비어 있다.
7. 주행 화면처럼 쉬지 않고 바뀌는 화면에서는 `uiautomator dump`가 `ERROR: could not get idle state`를 내고 느려지거나 실패한다(`texts`·`wait-text`·`tap-text`도). 주행 중에는 좌표로 `tap`한다.
8. 앱 데이터는 사용자 10에 있다. 처음 실행으로 되돌리기: `$E adb shell pm clear --user 10 com.barodrive.app` (`--user`가 없으면 사용자 0 것을 지워서 아무 일도 없다). **`pm clear`는 실행 중 권한(CAR_SPEED)도 지운다** — 그 뒤 `install … -g`를 다시 하거나 `$E adb shell pm grant --user 10 com.barodrive.app android.car.permission.CAR_SPEED`. 권한 창을 다시 보려면 `pm revoke --user 10 …` 뒤 `pm clear-permission-flags --user 10 com.barodrive.app android.car.permission.CAR_SPEED user-set user-fixed`(거절이 두 번이면 안드로이드가 더는 묻지 않는다). AAOS에서 CAR_SPEED는 위치 묶음이라 창에는 '위치 정보에 액세스'로 나온다.
9. 오래 쓰거나 부하가 크면 차 서비스(`com.android.car`)가 ANR로 죽을 때가 있다(`Killing …:com.android.car/1000 (adj -800): bg anr`). 그러면 차 서비스를 쓰는 시스템 앱이 줄줄이 죽고 SystemUI가 다시 뜨면서 화면이 검게 남는다. `stop` 뒤 `start`로 다시 띄운다.
10. **새로 띄운 직후 1분쯤은 누르지 않는다.** 부팅 뒤 1~1.5분 동안 시스템이 앱을 띄우느라 바쁘다(logcat `Slow operation`이 계속 쌓임). 그때 누르면 SystemUI 아래 바(`BottomCarSystemBar`)가 입력 ANR → SystemUI가 죽어 화면이 검게 남을 수 있다.
11. **호스트 메모리가 모자라면 에뮬레이터가 죽는다.** 스왑이 거의 찬 때 qemu가 gfxstream 오류(`glGenTextures … error null ctx`) 뒤 SIGSEGV로 죽거나, 게스트의 `system_server`가 죽었다(`DeadSystemException`). 띄우기 전에 `free -g`로 사용 가능이 6 GB쯤 되는지 보고, 모자라면 쉬는 Gradle 데몬을 끈다(`android/scripts/gradlew-wsl.sh --stop` — 다음 빌드가 새로 띄운다).
12. 앱이 멈추면(ANR) 그 때의 스택: `$E adb shell dumpsys dropbox --print data_app_anr` (`----- pid <앱 pid>` 아래 `"main"`). user 빌드라 `/data/anr`는 못 읽는다.
13. 에뮬레이터 내비의 즐겨찾기 둘(집·회사 자리)은 비어 있다(이름·주소 없음, 좌표 0,0). 진짜 차 자료 출처의 CR-2는 이런 칸을 빼서 '저장된 장소가 없어요'가 나온다 — 좌표 있는 장소로 [내비로 보내고 출발](`requestRoute`)을 끝까지 보려면 실차나 장소가 든 내비가 필요하다.
14. **`start`가 "timed out waiting for another start/stop"까지 기다리던 것(11/11 밤 고침).** `start` 안에서 adb 서버가 새로 뜨면 시작 잠금(`~/.cache/baro-emu/lock`, fd 9)을 물려받아, 그 adb 서버가 살아 있는 동안 다음 `start`·`stop`이 멈췄다. 이제 스크립트의 adb 호출은 fd 9를 닫고 부른다. 옛 스크립트로 이미 걸렸으면 `adb kill-server` 뒤 다시 한다.

## 한 번만 하는 설치 (이 PC는 끝남)

다른 PC에서 다시 할 때의 순서다. `source ~/opt/baro-env.sh` 뒤에 한다.

1. 에뮬레이터: `android sdk install emulator` (37.2.13).
2. 시스템 이미지: cmdline-tools 23의 `sdkmanager`/`android sdk`는 사용자 업데이트 사이트를 받지 않는다. 그래서 직접 깐다.
   - `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`이 가리키는 `crp_car_emulator.zip`(4.09 GB, sha1 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`)을 받는다.
   - zip 안 `crp_avd_out_x86_64/`의 내용을 `~/opt/android-sdk/system-images/android-34/pleos_car_emulator/x86_64/`에 푼다 (`unzip`이 없어 Python `zipfile`로 풀었다. `system.img`는 18 GB지만 0 블록을 건너뛰어 디스크는 5.9 GB).
   - 같은 폴더에 `package.xml`이 있어야 한다: `path="system-images;android-34;pleos_car_emulator;x86_64"`, tag `android-automotive`, vendor `pleos`, revision 2, sys-img2/05 형식. 없거나 SDK가 못 읽으면("Found corrupted package.xml") SDK가 `source.properties`로 `…;android-automotive;x86_64`라고 다시 써 버려 `avdmanager`가 이미지를 못 찾는다. 새 `android sdk list`에는 직접 깐 패키지가 안 보인다(정상).
3. AVD: `echo no | avdmanager create avd -n pleos210 -k "system-images;android-34;pleos_car_emulator;x86_64" -d Pleos_Connect_v2_0` 뒤 `~/.android/avd/pleos210.avd/config.ini`에서 `hw.ramSize=4096`, `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`, `firstboot.bootFromLocalSnapshot=no`, `firstboot.saveToLocalSnapshot=no`, `firstboot.bootFromDownloadableSnapshot=no`, `hw.gpu.enabled=yes`, `hw.gpu.mode=host`, `hw.initialOrientation=landscape`, `hw.keyboard=yes`, `showDeviceFrame=no`.
4. `libpulse.so.0`: Ubuntu WSL에 없다. `sudo apt install libpulse0`가 제일 간단하다. sudo 없이 하려면 `apt-get download libpulse0 libasyncns0 libsndfile1 libflac12t64 libmp3lame0 libmpg123-0t64 libogg0 libopus0 libvorbis0a libvorbisenc2` 뒤 각 `.deb`를 `dpkg-deb -x <deb> ~/opt/emu-libs/root`로 푼다 (스크립트가 `LD_LIBRARY_PATH`에 넣는다).
5. `~/.android/adbkey.pub`: `user` 빌드는 adb 키 승인이 필요하다. 없으면 스크립트가 `adb pubkey`로 만든다.

## 사용자가 할 일

- `/dev/kvm` 권한은 WSL을 다시 켜면 돌아간다. 한 번만 하면 되는 방법:
  `sudo usermod -aG kvm $USER` 그다음 Windows에서 `wsl --shutdown` 후 다시 연다.
  (임시로는 `sudo chmod 666 /dev/kvm`. WSL 재시작마다 다시 해야 한다.)
- (선택) `sudo apt install libpulse0` — 하면 `~/opt/emu-libs`가 필요 없다.
