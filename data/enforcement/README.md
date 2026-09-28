# SafeNavi Enforcement Data

앱 APK와 단속정보 데이터를 분리해서 관리합니다.

- `manifest.json`: 지역별 데이터 버전과 파일 경로
- `seoul.json`: 서울
- `gyeonggi.json`: 경기
- `incheon.json`: 인천

데이터만 수정할 때는 APK를 다시 빌드하지 않습니다.
각 지역 JSON의 `version`과 `manifest.json`의 해당 지역 `version`을 함께 올리면
앱이 다음 데이터 확인 시 변경된 지역 파일만 다운로드합니다.

지원 타입:
- `SPEED`
- `SIGNAL_SPEED`
- `SECTION`

앱은 다운로드한 데이터를 Room DB에 저장하므로 네트워크가 끊겨도 마지막으로 받은 데이터로 계속 동작합니다.
