# ImageMacro 프로젝트 규칙

안드로이드 이미지 감지 자동화 매크로 앱. 앱에 **인앱 자동 업데이트** 기능이 있어서
실행 시 이 저장소(`Hwanje/image_macro`)의 GitHub **최신 릴리스**를 확인하고,
태그 버전이 현재 `versionName`보다 높으면 APK 에셋을 내려받아 설치를 안내한다.

## 빌드 / 서명

- 이 환경의 기본 JDK(25)로는 Gradle이 실패한다. 반드시 JDK 21로 **릴리스** 빌드:
  ```bash
  JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms ./gradlew :app:assembleRelease
  ```
- 산출물: `app/build/outputs/apk/release/app-release.apk`
- **서명**: 저장소에 포함된 고정 릴리스 키스토어 `app/imagemacro-release.keystore`
  (alias `imagemacro`, store/key 비밀번호 `imagemacro2024`)로 서명한다. 설정은
  `app/build.gradle.kts`의 `signingConfigs`에 있다. 디버그 키로 서명하면 Play Protect가
  "개발자 미확인" 악성앱 경고를 띄우므로 **반드시 이 릴리스 키로 배포**한다.
- 업데이트(덮어쓰기) 설치는 서명이 같아야 하므로 **항상 이 키스토어로 빌드**할 것.
  서명이 달라지면 사용자는 앱을 지우고 재설치해야 한다.
  (v1.2 이하 디버그 서명 → v1.3 릴리스 서명으로 키가 바뀌었으므로, v1.2 이하 설치
  사용자는 한 번은 기존 앱 삭제 후 재설치해야 v1.3을 받을 수 있다.)

## ⚠️ 커밋·푸시 시 필수 절차 (인앱 자동 업데이트 배포)

**코드를 커밋·푸시할 때마다 아래 릴리스 절차를 반드시 함께 수행한다.**
릴리스를 빼먹으면 사용자 기기의 앱이 새 버전을 받지 못한다.

1. **버전 올리기** — `app/build.gradle.kts`의 `versionCode` +1,
   `versionName`을 새 버전으로 (예: `1.2` → `1.3`)
2. **APK 빌드** (위 JDK 21 릴리스 명령) 후 저장소 루트의 `ImageMacro.apk`도 갱신:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk ImageMacro.apk
   ```
3. **커밋 & 푸시** (버전 변경 + `ImageMacro.apk` 포함)
4. **GitHub 릴리스 게시** — 태그는 `v` + versionName, APK 에셋 첨부 필수:
   ```bash
   gh release create v<versionName> ImageMacro.apk \
     --title "v<versionName>" --notes "<변경 사항 요약 (한국어)>"
   ```
   릴리스 본문(`--notes`)은 앱의 업데이트 대화상자에 "변경 사항"으로 그대로 표시된다.

- 태그의 버전(`v1.3`)과 `versionName`(`1.3`)이 **반드시 일치**해야 업데이트가 감지된다.
- 에셋 파일명은 `.apk`로 끝나기만 하면 된다 (앱은 첫 번째 `.apk` 에셋을 받는다).
- 문서만 고치는 등 앱 동작이 안 바뀌는 커밋은 릴리스를 생략해도 되지만,
  애매하면 릴리스까지 진행한다.

## 구조 메모

- 업데이트 로직: `app/src/main/java/com/imagemacro/update/UpdateManager.kt`
  (릴리스 조회 → 버전 비교 → APK 다운로드 → FileProvider로 설치 인텐트)
- 업데이트 확인 진입점: `MainActivity.checkForUpdate()` (onCreate에서 호출)
