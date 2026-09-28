# SafeNavi v0.01

서울·경기·인천용 GPS 기반 안전운행/고정식 단속정보 안내 Android MVP.

현재 포함:
- Kotlin Android 앱
- Foreground GPS service
- Room SafetyPoint DB
- 전방/진행방향 필터
- 700m / 300m / 100m 단계 안내
- Android TTS
- 시작/종료 UI

중요:
현재 프로젝트에는 실제 공공데이터 레코드를 번들하지 않았습니다.
Room DB는 실행 시 생성되며, 공공데이터 정규화/초기 적재 단계가 다음 작업입니다.
실제 도로에서 신뢰하기 전에 충분한 검증이 필요합니다.
