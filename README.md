# BTC 신호 (안드로이드 앱)

업비트 KRW-BTC 차트(이평선 5·20·50·100·200, 스토캐스틱 슬로우 5-3-3 / 10-3-3 / 20-6-6)와
5분봉 매수·매도 신호 백그라운드 알림 앱입니다.

## APK 만들기 (GitHub, 코딩 불필요)
1. github.com 에 로그인 → 오른쪽 위 + → New repository → 이름 `btc-signal` → Private → Create repository
2. 새 저장소 화면에서 "uploading an existing file" 클릭 → 이 폴더 안의 내용 전체(.github 폴더 포함)를 끌어다 놓기 → Commit changes
   - `.github` 폴더가 숨김 폴더라 안 보이면, 압축을 푼 폴더에서 Ctrl+A 로 전체 선택 후 끌어다 놓기
3. 저장소 위쪽 Actions 탭 → "Build APK" 실행 확인 (3~6분) → 초록 체크가 뜨면 클릭
4. 아래쪽 Artifacts 의 `BTC-Signal-APK` 다운로드 → 압축 풀면 `app-debug.apk`

## 갤럭시 S25 설치
1. `app-debug.apk` 를 폰으로 옮기기 (카카오톡 나에게 보내기, 구글 드라이브, USB 등)
2. 내 파일에서 APK 열기 → "출처를 알 수 없는 앱 설치" 허용 → 설치
3. Play 프로텍트 경고가 뜨면 "자세히 → 무시하고 설치"
4. 앱 실행 → 알림 허용 → "백그라운드 알림 켜기" → "배터리 최적화 해제" → 허용
5. 설정 → 애플리케이션 → BTC 신호 → 배터리 → **제한 없음** (삼성 절전 기능에 앱이 꺼지지 않게)
