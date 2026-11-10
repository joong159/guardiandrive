# 가디언 드라이브 (Guardian Drive)
**사고 전에는 내 운전을 점검하고, 사고 직후엔 가장 당황한 순간을 단계별로 이끌어주는 안전 동승자**
현대자동차그룹 차량용 앱 개발 공모전 출품작 (안전 운전 분야)

## 한 줄 소개
평소엔 조용히 지켜보다가, 내 운전이 뒤차 흐름을 막거나 위험해지면 한 문장으로 알려주고,
사고가 나면 비상등 → 트렁크 → 대피 → 신고 순서로 음성 안내하고 직전 기록을 리포트로 남깁니다.

## 기능
| 기능 | 내용 | 사용 SDK | 상태 |
|---|---|---|---|
| 잦은 급제동 안내 | 5분 안에 급제동 2번 이상일 때만 안내 (한 번은 기록만) | Vehicle | ✅ 허용 SDK |
| 깜빡이 없는 차선 변경 | 핸들을 한쪽으로 1~3초 돌렸다 되돌리는 패턴으로 감지. 커브길은 제외 | Vehicle(조향각·방향지시등) | ✅ 허용 SDK |
| 잦은 차선 변경 | 2분 안에 3번 이상 | Vehicle | ✅ 허용 SDK |
| 사고 감지 | 1초에 7m/s² 이상 감속 후 정지, 또는 "사고 났어" 음성 | Vehicle, SpeechToText | ✅ 허용 SDK |
| 사고 직후 음성 안내 | 한국도로공사 '비트박스' 순서. 비상등은 차량 데이터로 자동 확인. 고속도로는 가드레일 대피·1588-2504 안내 | TextToSpeech, SpeechToText, Vehicle(비상등), NaviHelper | ✅ 허용 SDK |
| 무응답 시 비상 연락 | 20초간 대답이 없거나 "도와줘" → 가족에게 위치 전송 | FusedLocation (+외부 통신) | ⚠️ 외부 통신 허용 여부 문의 중 |
| 사고 정리 리포트 | 직전 30초 속도·브레이크·차선 변경 기록 | Vehicle, FusedLocation, NaviHelper | ✅ 허용 SDK |
| 주행 후 도로 매너 점수 | 100점에서 감점/가점 | — | ✅ |
| 방어 운전 레이더 | 앞 끼어들기, 갈지자 옆 차 경고 | ADAS | 🔜 향후 확장 (코드 완성, ADAS SDK가 공모전 목록에 없음) |
| 1차선 저속 주행 안내 | 1차선 + 앞은 비고 뒤차가 붙은 상태로 60초 | ADAS | 🔜 향후 확장 |

## 억울한 지적·오작동을 막는 장치
- 가다 서다 정체, 긴 커브길, 사고 직전 회피 브레이크는 감점하지 않음 (테스트로 확인)
- 사고가 아닌 급정거면 "아니야" 한마디로 취소
- "안 다쳤어"처럼 부정 표현을 먼저 해석 (도움 요청으로 오인 방지)
- 깜빡이·브레이크 중, 차선 변경 직후 5초는 일반 안내를 미룸. 같은 안내는 3분에 한 번

## 폴더 구조
```
core/   판단 엔진 (순수 Kotlin, PC에서 바로 테스트)
  GuardianEngine.kt     전체를 묶는 엔진 (앱은 이것만 사용)
  DrivingCheck.kt       내 운전 점검
  LaneChangeDetector.kt ADAS 없이 차선 변경 감지
  DefensiveRadar.kt     방어 운전 레이더 (ADAS)
  Accident.kt           사고 감지 · 음성 의도 분류 · 단계별 안내
  Recorder.kt           직전 30초 기록 · 사고 리포트
  sim/Scenarios.kt      시연 시나리오 11개
app/    차량 화면 · 음성 · SDK 연결
  MainActivity.kt       메인 화면 (가로: 왼쪽 레이더·오른쪽 안내 / 세로: 위아래)
  DrivingSource.kt      시연 모드 DemoSource (시나리오 재생, PT 시나리오는 비상등·트렁크를 실제 차량 값으로)
  PleosSource.kt        실제 주행 모드 (차량 값을 1초마다 묶어 엔진에 전달)
  VehicleReader.kt      차량 값 실시간 읽기 (Pleos Vehicle SDK + 안드로이드 표준 차량 API)
  VoiceInput.kt         운전자 음성 인식 (사고 대응 중 계속 듣기, 주행 중 🎤 버튼)
```

## 실행
1. Pleos Connect 에뮬레이터 설치 (Android Studio → SDK Manager → SDK Update Sites에 Pleos 이미지 주소 추가 → API 34 Pleos Connect 이미지 → Automotive AVD, RAM 4GB)
   - Apple Silicon Mac: `.../connect-avd/2.x/crp-arm64-sys-img.xml` / Intel Mac·Windows: `.../connect-avd/2.x/crp-sys-img.xml`
   - 빌드 없이 실행: `adb install -r apk/김현중_가디언드라이브_release.apk` → `adb shell am start -n ai.guardiandrive.app/.MainActivity`
2. Android Studio에서 이 폴더 열기 → 에뮬레이터에서 `app` 실행
3. 시나리오를 고르고 **시연 시작** (2배속 재생, 운전자 음성 포함)
4. 판단 로직 검증: `./gradlew :core:runScenarioCheck`

## 테스트 환경
- Android Studio 최신, Gradle 8.7, minSdk 28, targetSdk 34
- Pleos Connect Emulator 2.1.0 (API 34)

## 처음 열 때
1. `local.properties.example`을 복사해 `local.properties`로 이름을 바꾸고 클라이언트 ID·시크릿 입력 (Git에 올라가지 않음)
2. Gradle Sync. Pleos 저장소와 `ai.pleos.playground:Vehicle:2.3.2`는 이미 들어 있음
   - Sync가 Vehicle SDK에서 실패하면 `app/build.gradle.kts`의 그 줄만 잠시 주석 처리하면 시연 모드는 그대로 동작

## Pleos SDK 상태 (2026-11-03 기준)
| SDK | 상태 | 지금 대응 |
|---|---|---|
| Vehicle State, NaviHelper | 사용 가능 | 그대로 사용 |
| TextToSpeech, SpeechToText, FusedLocation | 준비 중 | Android 기본 TTS·위치 + 사고 안내 화면 버튼 |
| Vehicle Control | 준비 중 | 사용 안 함 (상태 조회만 함) |

Pleos Connect에 기본 TTS 엔진이 없으면 음성 없이 화면 안내와 버튼(괜찮아/다음/도와줘/사고 아님)으로 진행됩니다.

## 차량 데이터 연결 (완료)
| 값 | 읽는 곳 |
|---|---|
| 브레이크 · 방향지시등 · 핸들 각도 · 트렁크 | Pleos Vehicle SDK 2.3.2 (Brake / TurnSignal / SteeringWheel / Door REAR 위치) |
| 속도 | 안드로이드 표준 차량 API `CarPropertyManager` (`PERF_VEHICLE_SPEED`) — Vehicle SDK 2.3.2 공개 API에 없음 |
| 비상등 | ① Pleos Safety API 계기판 경고등: 왼쪽·오른쪽 방향지시 표시등이 동시에 켜지면 비상등 (1초마다 확인) ② 표준 `HAZARD_LIGHTS_STATE` (시스템 권한이라 일반 앱은 못 읽을 수 있음). 둘 중 하나라도 켜지면 인정 |
| 위치 | 안드로이드 기본 위치 (FusedLocation SDK 준비 중) |
| 도로 종류 | NaviHelper 연동 전까지 최근 2분 최고 속도 80km/h 이상이면 고속도로로 추정 (TODO) |

- 화면 오른쪽 위 표시에 실제로 값이 들어온 항목이 나와요 (예: `● PT 시연 · 비상등·트렁크·속도`)
- **조향각 부호 확인**: 엔진은 왼쪽 = + 기준. 실제 차에서 반대면 `VehicleReader`의 `steeringDeg`에 `-`를 붙이세요

## 12월 PT 시연 순서
1. Android Studio에서 기기를 **PleosConnect 2.1**로 고르고 `app` 실행
2. 시나리오 **[PT] 고속도로 사고 (비상등·트렁크 직접)** → **시연 시작** (실제 시간 재생)
3. 15초 뒤 충돌 → "괜찮으세요?" → **괜찮아** 버튼(또는 말하기)
   - 대답 전에 비상등부터 눌러도 응답으로 보고 넘어가요 (20초 안에 아무 반응이 없으면 비상 연락)
4. 심사위원이 **버추얼 콘솔에서 비상등** → "비상등 켜진 것 확인했어요"
5. **버추얼 콘솔에서 트렁크** 열기 → "트렁크 열린 것 확인했어요"
6. 대피 → 신고 → 사진 단계는 **다음** 버튼(또는 "했어", "완료")
7. 사고 정리 리포트가 화면에 표시
- 충돌 후 15분 동안 정차 상태가 유지돼서 설명이 길어져도 시나리오가 끝나지 않아요
- 시작 전 비상등·트렁크는 꺼 두세요 (켜져 있으면 앱이 알려줌). 켜진 채면 껐다 다시 켜면 인정돼요
- "괜찮으세요?" 후 20초 동안 아무 반응이 없으면 비상 연락으로 넘어가요 → 질문이 나오면 바로 버튼/비상등
- Pleos Connect 에뮬레이터에는 TTS 엔진이 없어 음성 대신 화면 글자로 안내돼요
- 매니페스트는 반드시 가로 고정(`landscape`)이어야 Pleos 메인 화면에 앱이 떠요 (휴대폰에서만 코드로 세로 허용)

## APK 제출 (공모전 규정)
- release APK: [`apk/김현중_가디언드라이브_release.apk`](apk/김현중_가디언드라이브_release.apk) (서명된 release 빌드, Pleos Connect Emulator 2.1.0에서 실행 확인)
- 직접 빌드: `local.properties`에 `release.storeFile` 등 서명 정보를 넣고 `./gradlew :app:assembleRelease`

## 백그라운드 실행 · 낮/밤 화면
- `GuardianService.kt`: 판단 엔진·차량 연결·음성 안내를 **전경 서비스**로 실행 → 내비 등 다른 앱을 써도 운전 점검·사고 감지 유지
  - 주행 중 상단에 "가디언 드라이브 지켜보는 중" 알림
  - 사고 감지 시 앱 화면을 앞으로 불러오고(차량 시스템이 허용하면), 막혀 있어도 긴급 알림을 눌러 바로 열 수 있음
  - 음성 인식(마이크)은 앱 화면이 보일 때만 동작 → 사고 시 화면을 앞으로 불러온 뒤 듣기 시작
- 화면 색: 차의 주간·야간 모드를 따라 **낮=밝은 배경, 밤=어두운 배경** 자동 전환 (전환돼도 진행 중인 안내는 서비스에 있어 유지)

## 변경 이력
- 2026-11-06: 실제 차량 연결(VehicleReader·PleosSource), PT 전용 시나리오, 음성 인식, 세로 화면 대응,
  비상 연락 후 '사고 아님' 정정 안내, 대답 전 비상등을 응답으로 인정, 리포트에 30초 전 기록 추가,
  사고 대응 진행 단계 표시(진행 n/5). 이전 버전 백업: `../_backup/guardiandrive_20261106_1717`
