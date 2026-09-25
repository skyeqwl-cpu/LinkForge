// LinkForge 压测脚本 —— 创建短链写场景（需登录态）
// 启动： k6 run loadtest/create.js
// 说明：
//   1) setup 注册压测用户拿 token，主流量每次 POST 一条新短链（走 DB 写入 + 布隆 add + Redis 预热）。
//   2) 写路径的 Redis 操作当前在 @Transactional 内（见 BACKLOG 阶段10），压测写场景前务必保证 Redis 可连。
//   3) 写场景会产生大量数据，压测后请清理（DELETE FROM short_link WHERE origin_url LIKE '%/write/%'）。
import http from 'k6/http';
import { check } from 'k6';

const BASE = 'http://localhost:8081';

export const options = {
  scenarios: {
    ramp_create: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 50 },
        { duration: '1m', target: 50 },
        { duration: '20s', target: 200 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  const username = `benchw_${Date.now()}`;
  const password = 'Bench@123456';
  const reg = http.post(
    `${BASE}/api/v1/auth/register`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const token = reg.json('data.token');
  return {
    auth: {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    },
  };
}

export default function (data) {
  const origin = `https://example.com/write/${Math.random().toString(36).slice(2)}`;
  const res = http.post(
    `${BASE}/api/v1/short-link`,
    JSON.stringify({ originUrl: origin, validDays: 7 }),
    data.auth
  );
  check(res, { 'create ok (200)': (r) => r.status === 200 });
}
