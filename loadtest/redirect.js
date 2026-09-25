// LinkForge 压测脚本 —— 核心 302 跳转读场景（已适配「仅创建者」访问权限）
// 启动： k6 run loadtest/redirect.js
//
// 背景：项目新增短链访问限制（accessType：0=公开 / 1=仅创建者）。
//   resolveRedirect(shortCode, authHeader) 对 PRIVATE 链做鉴权：
//     无 token / 失效        → 401
//     有 token 但非 owner     → 403
//     owner / 管理员          → 302
//   PUBLIC 链不鉴权，无 token 直接 302。
//
// 本脚本覆盖 5 种流量，真实压到权限代码：
//   50%  PRIVATE + owner token        → 302（真实「用户访问自己链接」主场景）
//   30%  PUBLIC  + 无 token            → 302（基线，无鉴权开销）
//   10%  PRIVATE + 无 token            → 401（鉴权闸门）
//   5%   PRIVATE + 其他用户 token      → 403（越权拦截）
//   5%   随机无效码                    → 404（布隆/穿透防护）
//
// 注意：
//   1) redirects:0 不跟随外网 302，只测跳转接口本身延迟。
//   2) setup 自动注册 owner/intruder 两个用户，批量建 150 公开 + 150 私有链，
//      并预热 meta + 缓存，开压即稳态命中。
//   3) 新读路径每条请求多一次 Redis `short_link:meta:{code}` 查询（resolveMeta），
//      埋点统计 Redis 次数时必须计入。
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = 'http://localhost:8081';
const ORIGIN = 'https://example.com';

export const options = {
  // 去掉 url 维度，避免 300 个唯一短码 + 随机无效码产生海量时间序列（高基数警告）
  systemTags: ['status', 'method', 'name', 'group', 'scenario', 'check'],
  scenarios: {
    ramp_redirect: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 500 },
        { duration: '1m', target: 500 },
        { duration: '30s', target: 1000 },
        { duration: '1m', target: 1000 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 注意：本场景 401/403/404 均为预期正常结果（占约 20% 流量），
    // 而 k6 的 http_req_failed 会把状态>=400 也计为失败，故不在此设失败率阈值，
    // 避免误判。真实失败（5xx / 连接错误）请通过下方 check 输出与 http_req_duration 观察。
    // 延迟阈值按「本地 8C16G 单机（MySQL/Redis/App 同机）」校准，非生产 SLA：
    // 1000 并发下 P95<600ms / P99<1200ms 视为健康（尾延迟稳定、无长尾优于绝对数值）。
    http_req_duration: ['p(95)<600', 'p(99)<1200'],
  },
};

// 业务计数器（k6 输出 / handleSummary 可见）
const cntPrivateOwner302 = new Counter('private_owner_302'); // 私有链 owner 命中
const cntPublic302 = new Counter('public_302');             // 公开链无 token 命中
const cntUnauth401 = new Counter('private_anon_401');       // 私有链未登录 → 401
const cntForbidden403 = new Counter('private_other_403');   // 私有链非 owner → 403
const cntPenetration404 = new Counter('penetration_404');   // 无效码 → 404

export function setup() {
  const owner = regUser('bench_owner');
  const intruder = regUser('bench_intr');

  const publicCodes = [];
  const privateCodes = [];
  for (let i = 0; i < 150; i++) {
    const code = createLink(owner.token, 0); // 公开
    if (code) publicCodes.push(code);
  }
  for (let i = 0; i < 150; i++) {
    const code = createLink(owner.token, 1); // 仅创建者
    if (code) privateCodes.push(code);
  }

  // 预热：每个码先打一次，填充 L1 Caffeine + Redis 缓存 + meta 缓存，开压即稳态
  for (const code of publicCodes) http.get(`${BASE}/${code}`, { redirects: 0 });
  for (const code of privateCodes) {
    http.get(`${BASE}/${code}`, { redirects: 0, headers: { Authorization: `Bearer ${owner.token}` } });
  }

  return {
    publicCodes,
    privateCodes,
    ownerAuth: { headers: { Authorization: `Bearer ${owner.token}` } },
    intruderAuth: { headers: { Authorization: `Bearer ${intruder.token}` } },
  };
}

export default function (data) {
  const r = Math.random();

  // 50% 私有链 + owner token（主场景：用户访问自己链接）
  if (r < 0.5 && data.privateCodes.length) {
    const code = pick(data.privateCodes);
    const res = http.get(`${BASE}/${code}`, Object.assign({ redirects: 0 }, data.ownerAuth));
    if (res.status === 302) cntPrivateOwner302.add(1);
    else check(res, { 'private owner expect 302': (x) => x.status === 302 });

  // 30% 公开链 + 无 token（基线）
  } else if (r < 0.8 && data.publicCodes.length) {
    const code = pick(data.publicCodes);
    const res = http.get(`${BASE}/${code}`, { redirects: 0 });
    if (res.status === 302) cntPublic302.add(1);
    else check(res, { 'public expect 302': (x) => x.status === 302 });

  // 10% 私有链 + 无 token（应 401）
  } else if (r < 0.9 && data.privateCodes.length) {
    const code = pick(data.privateCodes);
    const res = http.get(`${BASE}/${code}`, { redirects: 0 });
    if (res.status === 401) cntUnauth401.add(1);
    else check(res, { 'private anon expect 401': (x) => x.status === 401 });

  // 5% 私有链 + 其他用户 token（应 403）
  } else if (r < 0.95 && data.privateCodes.length) {
    const code = pick(data.privateCodes);
    const res = http.get(`${BASE}/${code}`, Object.assign({ redirects: 0 }, data.intruderAuth));
    if (res.status === 403) cntForbidden403.add(1);
    else check(res, { 'private other expect 403': (x) => x.status === 403 });

  // 5% 随机无效码（应 404）
  } else {
    const fake = Math.random().toString(36).slice(2, 8);
    const res = http.get(`${BASE}/${fake}`, { redirects: 0 });
    if (res.status === 404) cntPenetration404.add(1);
  }
}

function regUser(prefix) {
  const username = `${prefix}_${Date.now()}_${Math.floor(Math.random() * 1e6)}`;
  const password = 'Bench@123456';
  const res = http.post(
    `${BASE}/api/v1/auth/register`,
    JSON.stringify({ username, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  return { token: res.json('data.token') };
}

function createLink(token, accessType) {
  const origin = `${ORIGIN}/bench/${accessType}/${Math.random().toString(36).slice(2)}`;
  const res = http.post(
    `${BASE}/api/v1/short-link`,
    JSON.stringify({ originUrl: origin, validDays: 30, accessType }),
    { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } }
  );
  return res.json('data.shortCode');
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
