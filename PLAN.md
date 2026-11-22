# autosync 설계 메모

목적: 메인폰 사진/영상 → 구글드라이브 → 픽셀1 → 구글포토(원본 무제한) 업로드 후 모든 곳에서 정리.
개인용(플레이스토어 배포 안 함), 루팅 안 함.

## 결정사항
- 한 앱, 모드 2개: 메인폰 / 픽셀1
- 빌드: GitHub Actions로 APK, 서명 키는 GitHub Secrets (OAuth SHA-1 고정용)
- 최소 SDK 29 (픽셀1 = 안드로이드 10)
- 드라이브 권한: 전체 drive 권한 + 테스트 사용자 등록 (drive.file은 다른 앱이 올린 파일을 못 봄)

## 메인폰 모드
- 자동 업로드: `DCIM/Camera`만
- 수동 업로드: 파일 선택 / 폴더 선택
- 업로드 → MD5 일치 확인 → 로컬 삭제
- **토글: "업로드 후 삭제"** (자동/수동 모두 적용, 기본 켜짐)
- 구글포토 지원 형식만, 크기 제한 초과 시 알림
- 삭제는 MANAGE_EXTERNAL_STORAGE로 확인창 없이

## 픽셀1 모드 (128GB)
- 드라이브 PixelSync 폴더 → `DCIM/Camera`로 다운로드 → MD5 확인 → 드라이브 삭제
- 여유 공간 10GB 미만이면 다운로드 중지 (설정 가능)
- 구글포토 "백업 중" 알림 감시 (NotificationListener)
- 알림 사라지면 접근성 서비스로 구글포토 "기기 공간 확보" 실행 → 백업된 것만 삭제됨
- N시간 지나도 남은 파일 = 삭제하지 않고 알림
- 운영 조건: 충전 중 화면 켜짐, 잠금 없음, 구글포토 자동 업데이트 끔, 언어 한국어 고정

## 파일 구조
```
.github/workflows/build.yml
settings.gradle.kts, build.gradle.kts
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility.xml
app/src/main/java/com/hee/autosync/
  MainActivity.kt     한 화면: 모드, 로그인, 폴더, 수동 업로드, 토글, 상태/로그
  Settings.kt
  Drive.kt            로그인 + 목록/업로드/다운로드/삭제
  Db.kt               파일 기록(경로, 크기, MD5, 상태, 시간)
  UploadWorker.kt     메인폰
  DownloadWorker.kt   픽셀1 + 남은 파일 감시
  PhotosWatcher.kt    구글포토 알림 감시
  FreeUpService.kt    화면 읽기로 공간 확보
```

## 사용자가 할 일
1. Google Cloud: Drive API 켜기, OAuth 동의 화면 테스트 사용자 추가, 안드로이드 OAuth 클라이언트(패키지명 + SHA-1)
2. 픽셀1 화면 구조 기록(디버그 기능) 보내주기 → FreeUpService 맞춤

## 미확인
- 구글포토 지원 형식/크기 제한, 중복 파일도 공간 확보로 지워지는지
- 픽셀1 구글포토 버전의 "기기 공간 확보" 메뉴 위치
