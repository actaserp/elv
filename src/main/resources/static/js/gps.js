// =============================================
// gps.js - GPS 및 카카오맵 주소 변환 모듈
//
// 좌표 확보와 주소 변환을 분리한다.
// 이전에는 Geocoder 가 준비되지 않으면 getCurrentPosition 조차 호출하지 않아
// 카카오 SDK 로딩이 늦거나 실패하면 좌표가 영영 null 로 남았다.
// (그 상태에서 출퇴근을 누르면 화면이 새로고침되고, 새로고침은 인앱 브라우저의
//  위치 권한을 초기화해 권한 요청창이 반복되는 원인이 됐다.)
// =============================================

var geocoder = null;
var latitude = null;
var longitude = null;

// 실제 변환에 성공한 주소. 안내 문구와 구분해야 하므로 별도로 들고 있는다.
// (gpsInfo 의 텍스트로 판단하면 "확인 중" 같은 문구를 주소로 오인한다)
var resolvedAddress = null;

// 마지막으로 주소를 변환한 좌표. 제자리에서 반복 변환하는 것을 막는다.
var lastGeoLat = null;
var lastGeoLon = null;
var GEO_MIN_MOVE_M = 20;      // 이 거리(m) 미만 이동이면 주소 변환 생략
var GEOCODER_RETRY_MS = 500;  // Geocoder 초기화 재시도 간격
var GEOCODER_RETRY_MAX = 20;  // 최대 20회 (약 10초)

// SDK 자체가 안 올라온 경우에도 스크립트가 죽지 않도록 방어한다
if (typeof kakao !== 'undefined' && kakao.maps) {
    kakao.maps.load(function () {
        geocoder = new kakao.maps.services.Geocoder();
        console.log('✅ Kakao Geocoder 초기화 완료');
    });
} else {
    console.warn('Kakao SDK 미로딩 — 좌표는 수집하되 주소 변환은 보류');
}

function setGpsInfo(text) {
    const el = document.getElementById('gpsInfo');
    if (el) el.innerText = text;
}

function getGpsInfoText() {
    const el = document.getElementById('gpsInfo');
    return el ? el.innerText.trim() : '';
}

// 두 좌표 사이 거리(m)
function distanceMeters(lat1, lon1, lat2, lon2) {
    if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return Infinity;
    const R = 6371000;
    const toRad = (d) => d * Math.PI / 180;
    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.sqrt(a));
}

// ─────────────────────────────────────────────────────────────
// 위치 추적은 watchPosition 을 한 번만 건다.
//
// 예전에는 setInterval 로 5초마다 getCurrentPosition 을 새로 불렀는데,
// 그 호출 하나하나가 별개의 권한 요청이라 네이버 인앱브라우저처럼
// 허용을 저장하지 않는(WebView 의 retain=false) 환경에서는
// 허용을 눌러도 5초 뒤 권한창이 다시 떴다. (분당 12회)
//
// watchPosition 은 구독을 한 번 열어두고 그 안에서 좌표가 갱신되므로
// 권한 요청이 화면당 1회로 끝난다. GPS 세션이 유지돼 정확도도 더 좋아진다.
// ─────────────────────────────────────────────────────────────
var gpsWatchId = null;
var gpsAccuracy = null;   // 마지막 측위의 오차 반경(m). 출퇴근 등록 시 참고용.

const handleGpsPosition = (position) => {
    // 1) 좌표부터 확보한다. Geocoder 상태와 무관하다.
    latitude    = position.coords.latitude;
    longitude   = position.coords.longitude;
    gpsAccuracy = position.coords.accuracy;
    console.log(`GPS Coordinates: Lat ${latitude}, Lon ${longitude}, 정확도 ${gpsAccuracy}m`);

    // 2) 주소를 이미 확보했고 거의 움직이지 않았으면 변환을 건너뛴다 (카카오 API 호출 절감)
    if (resolvedAddress
        && distanceMeters(lastGeoLat, lastGeoLon, latitude, longitude) < GEO_MIN_MOVE_M) {
        return;
    }

    // 3) 주소 변환
    resolveAddress(latitude, longitude, 0);
};

const handleGpsError = (error) => {
    // 권한 거부(1) / 측위 실패(2) / 타임아웃(3)
    console.warn('GPS 접근 실패 code=' + error.code, error.message);
    // gpsInfo 는 건드리지 않는다. 이전에 확보한 주소가 있으면 그대로 유지된다.

    // 권한 거부가 아닌 일시적 실패(2,3)면 구독을 유지한다. 곧 다시 콜백이 온다.
    // 권한 거부(1)는 구독을 정리한다 — 열어둬도 콜백이 오지 않는다.
    if (error.code === 1 && gpsWatchId !== null) {
        navigator.geolocation.clearWatch(gpsWatchId);
        gpsWatchId = null;
    }
};

// gpsInfo 에는 "실제 주소"만 넣는다. 출퇴근 등록 검증이 이 값의 유무로
// 진행 여부를 판단하기 때문에, 에러/안내 문구를 넣으면 주소가 확보된 것으로 오인된다.
//
// 여러 번 불려도 구독은 하나만 유지한다 (화면의 재시도 버튼에서도 호출된다).
const getGPSLocation = () => {
    if (!navigator.geolocation) {
        console.error('Geolocation 지원 안됨');
        return;
    }
    if (gpsWatchId !== null) {
        // 이미 추적 중이다. 새로 요청하면 권한창이 다시 뜰 수 있으므로 아무것도 하지 않는다.
        return;
    }

    gpsWatchId = navigator.geolocation.watchPosition(
        handleGpsPosition,
        handleGpsError,
        {
            enableHighAccuracy: true,   // GPS 우선 사용 (WiFi/네트워크 측위 대신)
            timeout: 15000,             // 최대 15초까지 측위 대기
            maximumAge: 5000            // 5초 이내 측위값은 재사용 (불필요한 재측위 방지)
        }
    );
};

// 화면을 떠날 때 구독 정리
window.addEventListener('pagehide', function () {
    if (gpsWatchId !== null) {
        navigator.geolocation.clearWatch(gpsWatchId);
        gpsWatchId = null;
    }
});

// Geocoder 가 준비될 때까지 기다렸다가 변환한다. 좌표는 이미 확보된 상태다.
function resolveAddress(lat, lon, attempt) {
    if (!geocoder) {
        if (attempt >= GEOCODER_RETRY_MAX) {
            console.warn('Kakao Geocoder 초기화 실패 — 좌표만 확보된 상태');
            return;
        }
        setTimeout(function () { resolveAddress(lat, lon, attempt + 1); }, GEOCODER_RETRY_MS);
        return;
    }
    getAddressFromKakao(lat, lon);
}

// 카카오맵 좌표 → 주소 변환
function getAddressFromKakao(lat, lon) {
    const coord = new kakao.maps.LatLng(lat, lon);

    geocoder.coord2Address(coord.getLng(), coord.getLat(), function (result, status) {
        if (status === kakao.maps.services.Status.OK && result && result[0]) {
            const address = result[0].road_address
                ? result[0].road_address.address_name
                : result[0].address.address_name;

            console.log('주소:', address);
            setGpsInfo(address);

            // 변환에 성공한 값만 기록한다. 다음 호출의 재변환 여부 판단 기준.
            resolvedAddress = address;
            lastGeoLat = lat;
            lastGeoLon = lon;
        } else {
            console.error('주소 변환 실패');
            // gpsInfo 는 비워둔다 — 출퇴근 등록 검증이 이 값의 유무로 진행 여부를 판단하므로
            // 안내 문구를 넣으면 주소가 확보된 것으로 오인된다.
        }
    });
}

// ── 아래 VWorld 경로는 현재 호출되는 곳이 없다(미검증). 참고용으로 남겨둔다. ──

const getGPSLocationSub = () => {
    console.log('GPS(VWorld) 메서드 진입');

    if (!navigator.geolocation) {
        console.error('Geolocation 지원 안됨');
        return;
    }

    navigator.geolocation.getCurrentPosition(
        (position) => {
            latitude = position.coords.latitude;
            longitude = position.coords.longitude;
            getAddressFromVWorld(latitude, longitude);
        },
        (error) => {
            console.warn('GPS 접근 실패', error.message);
            setGpsInfo('위치정보를 조회할 수 없습니다.');
        },
        {
            enableHighAccuracy: true,
            timeout: 15000,
            maximumAge: 0
        }
    );
};

function getAddressFromVWorld(lat, lon) {
    AjaxUtil.postAsyncData(
        '/api/mobile_main/switchAddress',
        { lat, lon },
        function (result) {
            console.log('주소 :', result.data);
            setGpsInfo(result.data);
        }
    );
}
