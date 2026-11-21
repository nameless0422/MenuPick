// 주변 조회 시나리오: 사용자 500명(각 식당 20개, 합계 10,000개)이 동시에 반경 거리 픽을 한다.
// VUS명이 각자 한 번 조회하고 1~3초 쉰다(사람처럼). 사용자 = VU 하나로 고정한다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { authParams } from './jwt.js';

const BASE_URL = __ENV.BASE_URL;
const JWT_SECRET = __ENV.JWT_SECRET;
const [FROM, TO] = __ENV.USER_IDS.split('-').map(Number);
const VUS = Number(__ENV.VUS || 500);
const THINK = __ENV.THINK !== '0';
const noCandidates = new Counter('pick_no_candidates');

export const options = {
  scenarios: {
    nearby: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '3m' },
  },
  thresholds: { 'http_req_duration{name:nearbyPick}': ['p(95)<500'] },
};

export default function () {
  const userId = FROM + ((__VU - 1) % (TO - FROM + 1));
  // 서울시청 주변 ±약 1km 안에서 매번 위치를 바꾼다(같은 좌표만 쏘면 같은 결과만 잰다).
  const body = {
    latitude: 37.5665 + (Math.random() - 0.5) * 0.018,
    longitude: 126.978 + (Math.random() - 0.5) * 0.0227,
    maxDistance: 1000,
  };
  const res = http.post(`${BASE_URL}/api/v1/pick`, JSON.stringify(body),
    authParams(userId, JWT_SECRET, { name: 'nearbyPick' }));
  if (res.status === 404) noCandidates.add(1);
  check(res, { 'nearby 200 or 404': (r) => r.status === 200 || r.status === 404 });
  if (THINK) sleep(1 + Math.random() * 2);
}
