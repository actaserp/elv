// =============================================
// gps.js - GPS 및 카카오맵 주소 변환 모듈
// =============================================

var geocoder = null;
var latitude = null;
var longitude = null;

kakao.maps.load(function() {
    geocoder = new kakao.maps.services.Geocoder();
    console.log('✅ Kakao Geocoder 초기화 완료');
});

// GPS 좌표 조회 + 주소 변환 (카카오맵)
const getGPSLocation = () => {
    console.log('GPS 메서드 진입');
    console.log('✅ 1-1. geocoder 상태:', geocoder); // geocoder 초기화 확인
    if (!geocoder) {
        console.warn('Geocoder 아직 초기화되지 않음, 5초 후 재시도');
        return;
    }

    if (!navigator.geolocation) {
        console.error('Geolocation 지원 안됨');
        document.getElementById('gpsInfo').innerText = 'GPS를 지원하지 않는 기기입니다.';
        return;
    }

    navigator.geolocation.getCurrentPosition(
        (position) => {
            latitude = position.coords.latitude;
            longitude = position.coords.longitude;
            console.log(`GPS Coordinates: Lat ${latitude}, Lon ${longitude}`);
            getAddressFromKakao(latitude, longitude);
        },
        (error) => {
            console.warn('GPS 접근 실패', error.message);
            document.getElementById('gpsInfo').innerText = '위치정보를 조회할 수 없습니다.';
        }
    );
};

// 카카오맵 좌표 → 주소 변환
function getAddressFromKakao(lat, lon) {
    const coord = new kakao.maps.LatLng(lat, lon);

    geocoder.coord2Address(coord.getLng(), coord.getLat(), function (result, status) {
        console.log('✅ 4. 카카오 응답 status:', status);
        console.log('✅ 4-1. 카카오 응답 result 전체:', result); // 응답 데이터 전체 확인

        if (status === kakao.maps.services.Status.OK) {
            const address = result[0].road_address
                ? result[0].road_address.address_name
                : result[0].address.address_name;

            console.log('주소:', address);
            document.getElementById('gpsInfo').innerText = address;
        } else {
            console.error('주소 변환 실패');
            document.getElementById('gpsInfo').innerText = '주소를 찾을 수 없습니다.';
        }
    });
}

// VWorld 좌표 → 주소 변환 (백업용)
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
            console.log(`GPS Coordinates: Lat ${latitude}, Lon ${longitude}`);
            getAddressFromVWorld(latitude, longitude);
        },
        (error) => {
            console.warn('GPS 접근 실패', error.message);
            document.getElementById('gpsInfo').innerText = '위치정보를 조회할 수 없습니다.';
        }
    );
};

function getAddressFromVWorld(lat, lon) {
    AjaxUtil.postAsyncData(
        '/api/mobile_main/switchAddress',
        { lat, lon },
        function (result) {
            console.log('주소 :', result.data);
            document.getElementById('gpsInfo').innerText = result.data;
        }
    );
}