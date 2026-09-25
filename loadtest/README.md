# LinkForge 压测执行手册

> 目标：在本地 8C16G 单机环境，对核心接口做阶梯加压（100 / 500 / 1000 并发），
> 观察 QPS、RT（P95/P99）、错误率，以及内部 4 项指标：MySQL 查询次数、Redis 查询次数、锁竞争次数、响应时间。
> 用途：量化项目性能、暴露瓶颈、为简历提供"诚实可讲"的数据；验证多级缓存 / 布隆 / 分布式锁设计在高并发下的真实表现。

---

## 0. ⚠️ 先修两个坑（不修 → 压测数据严重失真）

### 坑 1：`getOriginUrl` 里大量 `System.out.println` 调试日志（最致命）
`ShortLinkService.getOriginUrl` 内有约 15 处 `System.out.println(...)`。高并发下 `System.out`
是**同步阻塞**写 stdout，会瞬间把吞吐压垮、并制造"应用很慢"的假象（实际是日志 IO 瓶颈）。
**压测前必须处理**：把这些 `System.out.println` 改成 `log.debug(...)` 或直接删除
（项目已引入 slf4j，`log` 字段已存在）。生产/压测日志级别设 INFO 即可屏蔽。

### 坑 2：Redis 密码不一致（应用可能连不上 Redis）
`docker-compose.yml` 里 Redis 用 `--requirepass 123456` 启动（有密码），但 `application.yml` 的
`spring.data.redis` 与 `redisson.address` **都没配密码**。后果：应用连 Redis 报 `NOAUTH`，
读链路全部穿透到 MySQL，布隆过滤器（Redisson 后端）也建不起来。
**修法（二选一）**：
- 应用侧补密码：`spring.data.redis.password: 123456` 且 `redisson.address: redis://127.0.0.1:6379` 改为带密码形式（Redisson 用 `redisson.yaml` 或 `Config` 设置 password）；
- 或 docker-compose 去掉 `--requirepass`（本地压测环境可接受，但别提交到简历演示用的"安全"版本）。

### 坑 3（认知校正）：actuator / micrometer 实际并未引入
`BACKLOG` 阶段 11 写着"项目已引入 micrometer-core"，但 **`pom.xml` 里既没有
`spring-boot-starter-actuator`，也没有 `micrometer-core`**。要观测下面 4 项内部指标，**必须先把
补丁加进去**（见第 5 节 / 本目录无需额外文件，补丁在文末）。目前不埋点时，只能靠 k6 看外部
QPS/RT/错误率，拿不到 MySQL/Redis/锁的细分次数。

---

## 1. 安装压测工具（任选其一，推荐 k6）

- **k6（推荐，脚本即代码、指标友好）**
  - Windows：`winget install k6` 或 `choco install k6`
  - 或 Docker（免装）：`docker run --rm -i grafana/k6 run - < loadtest/redirect.js`
    （注意：Docker 方式访问宿主机需用 `host.docker.internal` 替换 `localhost`）
- **wrk（超轻量，仅限无状态 GET，适合纯跳转极限吞吐快测）**
  - `wrk -t8 -c500 -d60s --latency "http://localhost:8081/{shortCode}"`
  - 缺点：不支持登录态、不支持变量，无法做"有效/无效混合"，只适合粗测峰值。
- **JMeter（Java，GUI 友好，面试官眼熟）**：适合团队，但单机 GUI 模式压不高，需命令行 `jmeter -n -t xxx.jmx`。

---

## 2. 启动环境与依赖

```bash
# 1) 起依赖（MySQL / Redis / RocketMQ）
cd linkforge-server
docker-compose up -d

# 2) 导入点击明细表（如尚未导入）
#    mysql -uroot -p111111 linkforge < ../sql/click_log.sql

# 3) 启动应用（已注入 ROCKETMQ_NAME_SERVER）
cd ..
start-app.bat        # 内部 mvn spring-boot:run，端口 8081
```

> 确认应用起来后，看启动日志是否有"布隆过滤器预热完成，共加载 N 条短码"。
> 压测前应在 DB 里先有一批短链数据（让布隆过滤器预热命中），或由脚本 setup 自动建（见下）。

---

## 3. 数据准备

- **最简单**：直接跑 `redirect.js`，其 `setup` 会自动注册用户并批量建 200 条短链，
  新建短码会被 add 进布隆过滤器，开压即有真实命中数据。
- **想造"存量大数据"**（测本地缓存命中率 / 布隆预热规模）：在**应用启动前**往 `short_link`
  表批量 INSERT（自增 id 经 `Base62Utils.encode(id)` 得到 `short_code`，字符集
  `0-9a-zA-Z`），启动后 `BloomFilterWarmupRunner` 会全量预热。注意 `short_code` 务必与
  `Base62(id)` 一致，否则布隆会误判。
- **造数 SQL 模板**（假设 id 从 1 起，validDays=30，expire_time=now+30d）：
  ```sql
  INSERT INTO short_link (id, origin_url, user_id, short_code, expire_time, create_time)
  SELECT n,
         CONCAT('https://example.com/seed/', n),
         1,
         -- 这里 short_code 由应用层 Base62(id) 决定，直插时需用程序算；
         -- 否则建议用 create 接口批量建，避免算错。
         '',
         DATE_ADD(NOW(), INTERVAL 30 DAY),
         NOW()
  FROM (SELECT @n:=@n+1 n FROM information_schema.columns, (SELECT @n:=0) t LIMIT 10000) s;
  ```
  > 注意：直插 SQL 的 `short_code` 必须和程序 Base62 一致；稳妥起见**优先用 create 接口批量建**。

---

## 4. 执行压测

```bash
# 核心读场景（302 跳转，已适配「仅创建者」访问权限，阶梯 100/500/1000）
k6 run loadtest/redirect.js

# 写场景（创建短链，阶梯 50/200）
k6 run loadtest/create.js

# 想要图文报告（JSON 汇总）：
k6 run --out json=result.json loadtest/redirect.js
```

> **权限适配说明（项目已加 accessType：0=公开 / 1=仅创建者）**：
> `redirect.js` 已升级为覆盖 5 种流量——私有链+owner(302) / 公开链无token(302) /
> 私有链无token(401) / 私有链非owner(403) / 无效码(404)，真实压到新权限代码。
> 创建短链不传 accessType 默认 PUBLIC，故原脚本也能跑出 302 基线；新脚本额外覆盖权限路径。

每档稳定 1 分钟，观察 k6 实时输出：
- `http_reqs`：实际 QPS
- `http_req_duration`：平均 / P95 / P99 延迟
- `http_req_failed`：错误率（仅网络错误；401/403/404 不算失败）
- 业务计数器：`private_owner_302` / `public_302` / `private_anon_401` / `private_other_403` / `penetration_404`

---

## 5. 观测 4 项内部指标（需先打埋点补丁）

当前代码无埋点，需引入 `actuator + micrometer` 并在 `getOriginUrl` 关键分支埋 4 个计数器
（见文末补丁）。打完后：

- 方式 A（轻量）：压测时用另一个终端周期采样
  ```bash
  # 实时看 JVM / HTTP 指标
  curl -s localhost:8081/actuator/prometheus | grep linkforge
  ```
- 方式 B（推荐，面试展示用）：接 Prometheus + Grafana，建看板实时看
  MySQL 次数 / Redis 次数 / 锁竞争 / RT 曲线，压测时投屏讲。

**埋点位置（对应读链路节点）**：
- 布隆过滤器 `contains==false` → `linkforge.bloom.rejected +1`
- `stringRedisTemplate.get` 执行 → `linkforge.redis.queries +1`
- `shortLinkMapper.selectOne` 执行 → `linkforge.mysql.queries +1`
- `lock.tryLock` 失败 或 进入锁临界区 → `linkforge.lock.contention +1`
- 整个 `getOriginUrl` 用 `Timer` 记录 `linkforge.request.latency`

---

## 6. 各档位看什么（分析思路）

| 并发 | 关注点 | 健康信号 | 异常信号 → 说明 |
|---|---|---|---|
| 100 | 基线，缓存是否生效 | P99 < 50ms，QPS 平稳 | RT 抖动大 → 日志 IO（坑1）或 GC |
| 500 | 本地缓存 / Redis 扛压能力 | Redis/本地命中，MySQL 次数趋近 0 | MySQL 查询次数随并发线性涨 → 缓存没挡住，查 getOriginUrl 回填逻辑 |
| 1000 | 锁竞争与连接池瓶颈 | 锁竞争次数可控，错误率 < 1% | 大量 429（获取锁失败）→ 热点 key 单 flight 被放大，需评估锁粒度 / 本地缓存 TTL；或 Tomcat/Redis 连接池耗尽 |

---

## 7. 常见瓶颈与调优手段

1. **吞吐上不去、CPU 不高**：几乎一定是坑 1 的 `System.out` 日志，先删。
2. **RT 随并发陡增、MySQL 次数暴涨**：本地缓存未命中或 TTL 太短；确认 Caffeine 已生效、
   热点 key 回填逻辑正确；可适当放宽 L1 TTL。
3. **出现 429（锁获取失败）**：热点 key 在失效瞬间大量请求进 single-flight；确认 Redisson
   看门狗续期；极端热点可考虑"逻辑过期 + 异步重建"替代互斥锁。
4. **Redis 连接池耗尽**：调大 `spring.data.redis.lettuce.pool` 或 `jedis` 连接数；
   确认 `recordAccess` 的 `INCR` 与 MQ 发送不阻塞主流程（当前是异步，OK）。
5. **MySQL 连接池耗尽**：调大 Hikari 最大连接数；确认慢查询（短码有索引）。
6. **GC 导致毛刺**：`-Xmx` 给到 2~4G，G1 收集器；压测时 `jstat -gc` 看回收频率。

---

## 8. 简历量化话术（诚实版，不要吹绝对峰值）

- ✅ "在本地 8C16G 单机（Spring Boot + MySQL + Redis 同机）环境下，对 302 跳转接口做阶梯压测，
  1000 并发时稳定支撑约 X QPS，P99 延迟 < Y ms，错误率 < 1%；多级缓存使 MySQL 查询次数
  在热点场景下趋近于 0。"
- ✅ "通过 Micrometer 埋点观测到：热点 key 命中 L1 Caffeine 后 Redis 查询次数下降 Z%，
  验证了本地缓存对 Redis 的减压效果。"
- ❌ 不要写"支撑千万级日活 / 生产级高并发"——这是本地练手项目，无集群、无上线数据。
- ✅ 主动讲"压测中发现的待优化项"（KEYS→SCAN、Redis 操作移出事务、System.out 日志），
  最能体现工程素养。

---

## 9. 埋点补丁（待并入源码，需你确认后我帮你加）

**pom.xml 增加：**
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

**application.yml 增加：**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: linkforge-server
```

**ShortLinkService.java 增加：**
```java
@Autowired
private io.micrometer.core.instrument.MeterRegistry meterRegistry;

private final io.micrometer.core.instrument.Counter bloomRejected;
private final io.micrometer.core.instrument.Counter redisQueries;
private final io.micrometer.core.instrument.Counter mysqlQueries;
private final io.micrometer.core.instrument.Counter lockContention;
private final io.micrometer.core.instrument.Timer requestLatency;

// 在类体内（@PostConstruct 或字段初始化块）初始化：
//  this.bloomRejected = meterRegistry.counter("linkforge.bloom.rejected");
//  this.redisQueries  = meterRegistry.counter("linkforge.redis.queries");
//  this.mysqlQueries  = meterRegistry.counter("linkforge.mysql.queries");
//  this.lockContention= meterRegistry.counter("linkforge.lock.contention");
//  this.requestLatency= meterRegistry.timer("linkforge.request.latency");

// getOriginUrl 各分支埋点（示意）：
//   布隆 contains==false        -> bloomRejected.increment();
//   执行 redisTemplate.get 前    -> redisQueries.increment();
//   执行 mapper.selectOne 前      -> mysqlQueries.increment();
//   tryLock 失败 / 进入临界区     -> lockContention.increment();
//   方法首尾用 Timer.Sample 包裹  -> requestLatency.record(...)
```

> 以上补丁涉及改源码，按你的"先确认再行动"原则，我不会自动并入。
> **要我直接把这套埋点 + actuator 配置写进项目吗？** 确认后我立刻改。
