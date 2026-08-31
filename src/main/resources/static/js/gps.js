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

// GPS 좌표 조회 → 주소 변환
const getGPSLocation = () => {
    if (!navigator.geolocation) {
        console.error('Geolocation 지원 안됨');
        setGpsInfo('GPS를 지원하지 않는 기기입니다.');
        return;
    }

    navigator.geolocation.getCurrentPosition(
        (position) => {
            // 1) 좌표부터 확보한다. Geocoder 상태와 무관하다.
            latitude  = position.coords.latitude;
            longitude = position.coords.longitude;
            console.log(`GPS Coordinates: Lat ${latitude}, Lon ${longitude}, 정확도 ${position.coords.accuracy}m`);

            // 2) 거의 움직이지 않았고 주소가 이미 있으면 변환을 건너뛴다 (API 호출 절감)
            if (getGpsInfoText()
                && distanceMeters(lastGeoLat, lastGeoLon, latitude, longitude) < GEO_MIN_MOVE_M) {
                return;
            }

            // 3) 주소 변환
            resolveAddress(latitude, longitude, 0);
        },
        (error) => {
            console.warn('GPS 접근 실패', error.message);
            setGpsInfo('위치정보를 조회할 수 없습니다.');
        },
        {
            enableHighAccuracy: true,   // GPS 우선 사용 (WiFi/네트워크 측위 대신)
            timeout: 15000,             // 최대 15초까지 GPS 측위 대기
            maximumAge: 0               // 캐시된 이전 위치 사용 안 함
        }
    );
};

// Geocoder 가 준비될 때까지 기다렸다가 변환한다. 좌표는 이미 확보된 상태다.
function resolveAddress(lat, lon, attempt) {
    if (!geocoder) {
        if (attempt >= GEOCODER_RETRY_MAX) {
            console.warn('Kakao Geocoder 초기화 실패 — 좌표만 확보된 상태');
            if (!getGpsInfoText()) setGpsInfo('주소를 확인하지 못했습니다.');
            return;
        }
        if (!getGpsInfoText()) setGpsInfo('주소를 확인하는 중입니다...');
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

            // 변환에 성공한 좌표를 기록해 다음 호출에서 재변환 여부를 판단한다
            lastGeoLat = lat;
            lastGeoLon = lon;
        } else {
            console.error('주소 변환 실패');
            if (!getGpsInfoText()) setGpsInfo('주소를 찾을 수 없습니다.');
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
