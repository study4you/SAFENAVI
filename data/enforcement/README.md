# SafeNavi road-safety data

APK와 안전정보 데이터는 분리해서 관리합니다.

## 자동 갱신
- GitHub Actions의 `Refresh SafeNavi road-safety data`가 매일 실행됩니다.
- 공개 OpenStreetMap에서 도로 제한속도(`maxspeed`)와 교통신호 정보를 갱신합니다.
- 변경된 지역 JSON과 `manifest.json`만 커밋합니다.
- `data/**`만 바뀐 커밋은 Android APK를 다시 빌드하지 않습니다.

## 앱에서 갱신
사용자는 **단속정보 / 교통정보 업데이트** 화면에서 필요한 시·도를 체크하고
**선택 지역 업데이트**를 누르면 체크된 지역 파일만 내려받습니다.
기본 선택은 서울·경기·인천입니다.

## 데이터 성격
이 데이터는 안전운행을 위한 제한속도·교통신호 정보입니다.
공개 OSM 데이터 특성상 누락이나 지연이 있을 수 있으므로 실제 도로 표지와 현장 제한속도가 우선입니다.
