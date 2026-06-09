# 이미지 매크로 (ImageMacro)

안드로이드용 **이미지 감지 기반 자동화 매크로** 앱입니다. 시중 플레이스토어 매크로 앱들의
동작 방식(이미지 감지, 조건 분기, 반복, 알고리즘화, 오버레이 제어)을 참고해 구현했습니다.

## 주요 기능

- **이미지 감지**: `MediaProjection`으로 화면을 캡처하고, 등록한 템플릿 이미지를
  그레이스케일 ZNCC(정규화 상관계수) 매칭으로 화면에서 찾습니다.
- **알고리즘(스텝) 구성**: 단계 목록으로 매크로를 만듭니다.
  - 탭(좌표), 스와이프, 대기, 메시지, 뒤로가기, 홈
  - **이미지 찾아 탭** — 화면에서 이미지를 찾으면 그 위치를 탭
  - **이미지가 보이면 (조건)** — `IF` 분기: 보일 때(then) / 안 보일 때(else) 각각 하위 단계 실행
  - **반복(LOOP)** — N회 또는 무한 반복, 내부에 하위 단계 중첩
- **전체 반복 / 단계간 지연** 설정
- **오버레이 컨트롤 패널**: 다른 앱 위에 떠 있는 드래그 가능한 패널에서
  **▶ 시작 / ■ 정지 / ✎ 수정 / ✕ 닫기**
- **실제 동작 수행**: 접근성 서비스(`AccessibilityService`)의 제스처 디스패치로 탭·스와이프 실행

## 권한

| 권한 | 용도 |
|------|------|
| 다른 앱 위에 표시 (SYSTEM_ALERT_WINDOW) | 오버레이 컨트롤 패널 |
| 접근성 서비스 | 탭/스와이프/뒤로·홈 동작 수행 |
| 화면 캡처 (MediaProjection) | 이미지 감지를 위한 화면 캡처 (실행 시마다 동의) |
| 알림 (POST_NOTIFICATIONS) | 포그라운드 서비스 알림 |

## 설치법

### 방법 A — 휴대폰에 APK 직접 설치 (가장 간단)

1. 저장소의 **[`ImageMacro.apk`](ImageMacro.apk)** 파일을 휴대폰으로 옮깁니다.
   (GitHub에서 파일을 연 뒤 **Download**, 또는 USB/클라우드로 전송)
2. 파일 관리자에서 `ImageMacro.apk` 를 탭합니다.
3. "출처를 알 수 없는 앱 설치" 안내가 뜨면 **설정 → 이 출처 허용**으로 켭니다.
   (Android 8+ 는 앱별 허용: 파일 관리자/브라우저에 권한을 줍니다.)
4. **설치** → 완료 후 **이미지 매크로** 아이콘으로 실행합니다.

> 디버그 서명 APK라 일부 기기에선 "Play 프로텍트" 경고가 나올 수 있습니다. **무시하고 설치**를 선택하면 됩니다.

### 방법 B — adb 로 설치 (PC 연결)

```bash
adb install -r ImageMacro.apk
```

### 방법 C — 직접 빌드

```bash
export JAVA_HOME=<JDK 17~21 경로>
export ANDROID_HOME=<Android SDK 경로>
./gradlew :app:assembleDebug
```

산출물: `app/build/outputs/apk/debug/app-debug.apk`

## 사용 순서

1. 앱 실행 → **① 다른 앱 위에 표시 권한 허용**, **② 접근성 서비스 켜기**
2. **+** 로 새 매크로 생성 → 이름/반복횟수 설정
3. **＋ 단계 추가** 로 동작을 쌓습니다.
   - "이미지 찾아 탭" 추가 → **🖼 이미지 선택/캡처** 로 대상 게임/앱 스크린샷을 골라
     찾을 부분을 드래그해 템플릿으로 저장 → 임계값(%) 조절
   - "반복"/"이미지가 보이면" 단계는 저장 후 **탭**하면 하위 단계로 들어가 중첩 구성
4. **💾 저장**
5. 목록에서 **▶ 실행** → 화면 캡처 동의 → 오버레이 패널의 **▶ 시작**

> 템플릿 이미지는 기기 해상도와 같은 스크린샷을 사용하면 매칭 정확도가 가장 높습니다.

## 구조

```
app/src/main/java/com/imagemacro/
├─ model/        Macro·Step 데이터 모델, JSON 저장소(MacroStore)
├─ engine/       TemplateMatcher(이미지 매칭), MacroEngine(스텝 실행)
├─ capture/      ScreenCaptureManager(MediaProjection), ProjectionRequestActivity
├─ service/      MacroService(오버레이+포그라운드), MacroAccessibilityService(제스처)
├─ ui/           MainActivity, MacroEditorActivity, 좌표/크롭 액티비티, 어댑터
└─ util/         PermissionUtil
```

## 주의

개인적으로 만든 자동화/접근성 도구입니다. 각 서비스(특히 게임)의 약관에 따라 자동화가
금지될 수 있으니 사용 책임은 사용자에게 있습니다.
