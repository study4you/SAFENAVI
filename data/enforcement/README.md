# SafeNavi safety data

SafeNavi의 APK와 안전정보 데이터는 분리되어 있습니다.

## 자동 갱신
- GitHub Actions의 `Refresh SafeNavi safety data`가 매일 실행됩니다.
- 공개 OpenStreetMap의 speed camera / maxspeed enforcement 태그를 수집합니다.
- 변경된 지역 JSON과 `manifest.json`만 커밋합니다.
- `data/**` 커밋은 Android APK 빌드를 실행하지 않습니다.

## 앱 업데이트
사용자는 앱의 **단속정보 / 교통정보 업데이트** 화면에서 필요한 시·도를 체크한 뒤
**선택 지역 업데이트**를 누르면 해당 지역 파일만 내려받습니다.
기본 선택은 서울·경기·인천입니다.

## 주의
이 자료는 공개 OSM 데이터 기반이라 누락·지연·오류가 있을 수 있습니다.
실제 도로 표지판과 현장 제한속도를 우선해야 합니다.

지원 타입:
- `SPEED`
- `SIGNAL_SPEED`
- `SECTION`
