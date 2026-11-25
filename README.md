# 公交网络灰度实验服务（R1/R2/R3 虚构样例）

用 **Java 21（运行/构建于 JDK 25）+ Spring Boot 3.5 + PostgreSQL** 实现的可复现灰度实验后端与评审控制台。
目标不是比较试点前后平均值，而是在预登记的 **四周、约 50% 合格服务接受新规则** 设计下，估计**乘客每次完整旅程**的端到端候车时间与拥挤暴露变化；旅程包含首乘与换乘，未完成刷卡或中途改线一律保留为 `UNKNOWN`。

系统覆盖题目要求的完整闭环：

1. **实验登记冻结**：合格线路/班次、处理规则、随机种子、分配概率、目标旅程人群、主要/安全指标、试点周期，一次登记并计算指纹。
2. **固定网络图**：连接关系只来自**实验前**刷卡换乘比例（R1↔R2=40%，R3 基本不换乘），用并查集得到冻结簇，不使用试点结果临时拆并。
3. **两套随机方案**：
   - 线路—具体班次（日期级，线路×日期×时段分层，**720 个单元，严格 360/360**）；
   - 高换乘线路成簇后的簇—周方案（2 簇 × 4 周 = **8 个单元，严格 4/4**，每周两簇一中一控）。
4. **暴露账逐段关联**：计划分配 → GPS/AVL 实际运行 → 短线折返/停运/延误偏离 → 每段与完整旅程路径暴露。
5. **共同支持器**：时段 × OD 区间 × 天气 × 服务可达性，检查两臂加权样本；不满足只报覆盖缺口，不外推。
6. **估计器**：乘客人数加权 Hajek **ITT**、按随机化单元聚类的 CR1 区间、CACE/Wald-IV、跨线暴露率 Wilson 区间、簇方案 2⁴ 随机化推断（RI）。
7. **独立护栏**：投诉率、满载班次占比、滞留/未完成旅程占比；任一硬护栏超限即 `STOP_EXPANSION`，扩围结论 `BLOCKED`，服务端同时拒绝人工改判 `GO`。
8. **试点净运营成本归集（本题新增）**：成本账把**司机加班、油电消耗、临时/备用车辆、维修、取消班次损失**五类凭证绑定到 **720 个可追溯车辆块/处理周期**；共享资源分摊器处理**一辆备用车跨线同时支援 R1 处理班与 R2 对照班**、以及**一次跨 R1/R2 且跨 AM/PM 班的维修**。结果层输出每个分配单元的五类成本分项、按随机分配臂计算的 **ITT 成本差（元/合格服务单元）**，并**保留未归属额**。给出两条归属路线：
   - **直接凭证归属（DIRECT）**：可追溯车辆块直接入账，真实度高；共同备用车与跨线/跨班维修不武断拆分，留下 **¥4,400 未归属余额**，并据此给出每合格单元净成本的保守归属区间；
   - **冻结驱动分摊（DRIVER_ALLOC）**：按登记时**冻结的车辆小时 / 车辆公里 / 维修工时**把共享成本分到支援范围，未归属为 0、账面完整；驱动份额是假设，故给出三种驱动 + 规范组合的 **ITT 敏感性区间**。
   - 收入/节省只有 `confirmed=true` 且带 `confirmation_ref` 才冲减净成本；**未确认的“增收/节省”估计（样例 ¥68,000）只作备忘披露，绝不用于把成本填平**。凭证号唯一 + 到分守恒保证**不重复计费**。
9. **外部事件按事件时间关联**：暴雨停运、降雨、施工、临时改线均带事件窗口；投诉同时保存 `complaint_time` 与 `service_event_time`，统计只用后者。
10. **界面**：随机分配、暴露路径、共同支持、客流结果、**试点成本归集**、护栏、离线审阅、扩围证据等 **11 个标签页**；另含 Swagger UI / OpenAPI 3。

---

## 一、最快启动：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- Compose 内 PostgreSQL 18：库/用户/密码均为 `ccdev`；为避免与宿主机已装 PostgreSQL 的 5432 冲突，宿主机映射为 **5433**（容器内仍是 5432）。
- 首次启动会自动执行 `schema.sql`（幂等建表）并写入确定性虚构样例。

> 说明：本交付环境内没有 `docker` 可执行程序，因此 Dockerfile/Compose 已按标准多阶段构建编写，但**镜像构建/Compose 启动未在本环境实际执行**；下列本地方式已被实际构建、运行与验证。

---

## 二、本地开发与启动（本容器已预装运行时）

默认 PATH 已指向预装版本；如需显式固定：

```bash
export JAVA_HOME=/opt/jdk-25
export PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/postgresql-18/bin:$PATH
```

### 1. 准备 PostgreSQL（已运行可跳过）

本容器自带 PostgreSQL 18，且已存在可连账号：

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1;'
```

若在全新机器上：用 PostgreSQL 18 建库建用户即可，或直接使用上面的 Docker Compose。连接参数可用环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` |
| `SERVER_PORT` | `8080` |

> `ccdev/ccdev` 仅为隔离开发库，不是生产配置。

### 2. 开发模式启动

```bash
mvn spring-boot:run
```

或打包后运行：

```bash
mvn -DskipTests package
java -jar target/transit-pilot.jar
```

启动后访问 <http://127.0.0.1:8080>，会自动建表并播种样例（幂等：检测到 `PILOT-R123-202606` 已存在则跳过）。

### 3. 完全重置样例

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# 然后重启应用；Docker 方式可用 docker compose down -v && docker compose up --build
```

---

## 三、测试账号

表单登录页 <http://127.0.0.1:8080/login.html>，也可直接对 API 使用 HTTP Basic：

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 全部 GET / 离线审阅 |
| `analyst` | `analyst123` | ANALYST | 额外可：登记实验、重算流水线、评估护栏 |
| `approver` | `approver123` | APPROVER | 额外可：形成/签署扩围结论（GO/HOLD/BLOCKED） |

例：

```bash
curl -u analyst:analyst123 http://127.0.0.1:8080/api/experiments
```

---

## 四、本题验证方式（含我实际执行过的结论）

### 1. 单元测试（实际执行 ✅）

```bash
mvn test
```

结果（最后一次实际运行）：

```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：50% 平衡分配与确定性哈希、R1/R2 聚类/R3 独立、全部命名路径分类
（分流/同车普通对照/对照转乘处理/全程 T/全程 C/不执行/两类 UNKNOWN）、
聚类 ITT 公式、Wilson 区间与 RI 枚举；以及 6 个 `CostEngineTest`：
最大余额法**到分分摊**、**不重复计费/总额守恒**、直接法保留共享**未归属**而驱动分摊清零、
ITT 符号与未归属保守边界、每合格单元净成本区间有序、**未确认估计不冲减成本**、
孤儿直接凭证在两种方法下都保留为未归属、跨臂备用车/跨线维修证据识别。

### 2. 端到端不变量脚本（实际执行 ✅）

先启动应用，再运行：

```bash
./verify.sh                 # 默认 http://127.0.0.1:8080
```

脚本仅依赖 `bash/curl/python3`（标准库），实际结果：

```
通过 62 项，失败 0 项
ALL_VERIFY_CHECKS_PASSED
```

> 注：仓库里的 `verify.sh` 为 LF 行尾（若在 Windows 上被改成 CRLF，先 `sed -i 's/\r$//' verify.sh`）。

核验内容包括：

- 两套分配总数与严格 50%（720=360/360；8=4/4）；
- 9 个 `J-DEMO-*` 命名旅程的完整路径、分配臂、cross/diverted 标记；
- `FULL_CONTROL` 中不存在任何 `cross_line=true`（交叉暴露不混入未暴露对照）；
- 两设计 ITT/CACE/RI/跨线率区间存在，且簇方案区间更宽；
- 共同支持缺口存在；投诉、滞留两条护栏超限；
- 投诉确有滞后提交且按 `service_event_time` 关联；
- analyst 不能调用扩围评估（403）；approver 得到 `BLOCKED`；护栏超限时人工 `GO` 被拒（409）；
- **试点成本归集（28 项）**：720 单元 360/360；恰好两笔加班；备用车凭证 `COST-TEMP-SPARE-001`（¥1,800，SHARED）的支援链接同时含 R1/TREATMENT 与 R2/CONTROL；一次跨线跨班维修 `COST-MAINT-CROSS-001`（¥2,600，SHARED）；凭证数与对账一致、OVERTIME 恰 2 张；直接法“已归属+未归属=总额”、分摊差异=0；直接法未归属 ¥4,400 > 0 而分摊法为 0、两方法余额不同；两方法净总额都等于“凭证总额−已确认抵扣”；两条每合格单元净成本区间有序；ITT 成本差为正且分摊 ITT 点估计被驱动情景区间包住；4 个驱动情景；线路“触及敞口”标注不可加且其和大于未归属额（跨线重复列示）、R3 敞口为 0；¥68,000 未确认估计被排除；`/costs/unit-costs?method=...` 两方法各 720 行；以及抵扣纪律的可重复生命周期——未确认 ¥888,888 不改变净成本、`confirmed=true` 缺 `confirmation_ref` 返回 400、补凭证后 ¥1,337 精确冲减、还原未确认后回到基线；
- `/v3/api-docs`、`/swagger-ui/index.html`、前端页面与 `app.js` 均可访问。

### 3. 前端渲染冒烟（实际执行 ✅）

本环境没有浏览器，我用临时目录中的 `jsdom` 对**真实运行的后端**加载 `index.html + app.js`：
**11 个标签页全部成功渲染**（含新增“💰 试点成本归集”页：样例证据卡、两方法区间、对账、驱动情景、线路敞口、
抵扣纪律交互与 200 行单元分项），命名旅程弹窗输出逐段判定追踪、`CROSS_MIXED` 过滤可交互、成本臂筛选可点击，
结果 `UI_SMOKE_OK`，无运行时错误。该冒烟脚本在 `/tmp/uismoke/smoke.mjs`（临时目录，不属于交付物）。

### 4. 我没有执行的验证

- 没有实际运行 `docker compose up --build`（该容器内无 `docker` 命令）；
  Docker 路径仅完成了文件编写与 YAML 自检，未声称已实际构建镜像。

---

## 五、虚构样例剧本与复现数字

固定种子 `TSP-PILOT-2026-06-01`，数据由 `SeedService` 用固定 `Random(20260601)` 与
`SHA-256(seed::unitKey)` 确定性生成；任何人重置库后重跑应得到一致数字。

- 网络：R1/R2 共享 `HUB-CENTRAL`，实验前边 R1→R2=0.40、R2→R1=0.38；R3 边比例 ≤0.02。
  冻结簇：`C-R1R2`、`C-R3`。
- 规模：**2477** 条旅程（含 9 个命名样例、22 条批量分流、46 条暴雨、约 2400 条普通合成）、
  **720** 个日期级计划班次及 GPS 实际运行、**45** 条投诉、**4** 个外部事件。
- 关键事件：
  - `2026-06-02`：一个 R1 处理 AM 班次实际**短线折返**，22+1 条分流旅程改乘 R2 对照 AM1 班次（LF=1.17）；
    同车普通 R2 乘客 `J-DEMO-PURER2-01` 仍是纯净对照。
  - R1/PM3 的处理实例上司机多次不执行新规则（ITT 保留其处理臂，实际路径落为 `FULL_CONTROL`）。
  - `2026-06-15` 暴雨红色预警，R1/R2 早高峰停运：18 未完成刷卡 + 12 中途改线 + 16 可达性受损完成旅程。
  - 另有 `06-08` 降雨、`06-11` R3 施工、`06-19` R2 临时改线。投诉提交时间刻意滞后服务 1–6 天。

最近一次确定性流水线的主要结果（共同支持域，候车秒，负=改善）：

| 设计 | 估计量 | 估计 | 95% 区间 / p | 旅程数/单元 |
| --- | --- | --- | --- | --- |
| 班次随机 | ITT 候车 | **−66.7** | [−90.7, −42.8] | 2255 / 584 |
| 班次随机 | CACE/Wald | −86.1 | [−114.1, −58.1] | 2255 |
| 班次随机 | ITT 拥挤暴露 | −0.1 | [−1.4, 1.2]（不显著） | 2255 / 584 |
| 簇—周随机 | ITT 候车 | **−194.3** | [−235.1, −153.5]（CR1, df=7，偏乐观） | 2067 / 8 |
| 簇—周随机 | RI 置换 p | — | **p=0.177**（2⁴=16 置换；双侧最小可达 (1+2)/17≈0.176） | 2067 |
| 跨线暴露 | Wilson 率 | **18.3%** | [16.8%, 19.9%] | 2391 |

解读：簇内统一处理内部化了 R1/R2 换乘溢出，点估计更大；但只有 2 个簇、8 个簇—周单元，
渐近 CR1 区间偏窄且在少簇下反保守性不足，**随机化推断 p=0.177** 才是更诚实的不确定性证据——
这正是题目要求的“簇方案污染更小但可用随机化单元少、置信/分辨更弱”。

护栏最新值（独立于候车改善）：

| 护栏 | 观测 | 冻结阈值 | 结论 |
| --- | --- | --- | --- |
| 投诉率（/万乘客人次，按服务时间） | ≈145.9 | 45 | **BREACHED** |
| 滞留/未完成或候车 >1200s 占比 | 4.12% | 3% | **BREACHED** |
| LF≥1.0 满载班次占比 | 3.23% | 5% | OK（分流 R2 班次进入 Top 满载明细） |

因此扩围证据页与全局横幅均为 **BLOCKED**：即使平均候车显著改善，投诉与安全护栏超限仍独立阻止扩围。

### 试点成本归集样例数字（同一四周随机试点，人民币元，确定性可复现）

成本播种器 `CostDataSeeder` 为 **720 个合格分配单元各建一个车辆块**（360 处理 / 360 对照），
冻结其车辆小时/公里/维修工时，并生成 **736 张唯一凭证**：720 张按公里计量的油电凭证、
**恰好 2 笔加班**（`COST-OT-001/002`，均在处理臂，合计 ¥1,600）、
**1 张跨臂备用车共享凭证 ¥1,800**、**1 张跨线跨班维修共享凭证 ¥2,600**、
4 张 T/C 同额配对的常规维修、以及暴雨停运的 8 张取消班次损失。凭证总额 **¥52,553.36**。

| 口径 | 直接凭证归属 DIRECT | 冻结驱动分摊 DRIVER_ALLOC |
| --- | ---: | ---: |
| 已归属/分摊后 gross | ¥48,153.36 | ¥52,553.36 |
| 未归属余额 | **¥4,400.00**（备用车+跨线维修） | **¥0.00**（账面完整） |
| 已确认抵扣（保险追偿，有凭证号） | −¥400.00 | −¥400.00 |
| 净运营成本总额 | **¥52,153.36** | **¥52,153.36** |
| 每合格服务单元净成本区间 | **¥66.32 ~ ¥72.44**（点值 ¥66.32，低端不含未归属） | **¥72.44**（未归属 0，总体为点） |
| ITT 成本差（处理−对照，元/单元） | **+3.28**，保守归属边界 [−8.94, +15.51] | **+3.35**，驱动假设区间 **[3.19, 3.66]** |

- **直接法 ITT 边界**：把整笔 ¥4,400 未归属余额极端全归对照（下界）或全归处理（上界），
  因 N_T=N_C=360，半宽 = 4400/360 ≈ 12.22；这是**归属不确定性边界，不是抽样置信区间**。
- **分摊法驱动敏感性**：规范组合 ITT +3.35；强制全部按车辆小时 +3.58、全部按公里 +3.66、
  全部按维修工时 +3.19——总体每单元成本不变，驱动只改变在两臂/线路间的分布。
- **不重复计费**：`cost_voucher.voucher_code` 唯一；共享凭证只入一次账，分摊是“把同一笔钱搬入单元”，
  不是在其上追加。`CostEngine` 内部按“分”用最大余额法分摊，分摊合计与共享凭证额差异严格为 0；
  `cost_unit_cost` 两方法各 720 行，gross 合计分别精确等于 ¥48,153.36 / ¥52,553.36。
- **线路未归属敞口（备忘，不可跨线相加）**：备用车与跨线维修都触及 R1、R2，故 R1、R2 各自列示
  ¥4,400 全额敞口、R3 为 ¥0；两线敞口之和 ¥8,800 > ¥4,400，正是跨线凭证在各线重复列示所致，
  相加会重复计费。可加的线路成本请看分摊后的 `net_cost_alloc`。
- **不得用估计填平**：样例另有一笔 ¥68,000 的“准点提升带来票收增收+用工节省”**未确认估计**
  （`OFF-EST-UNCONF-001`，无 `confirmation_ref`），若错误计入会把净成本填成净节省；
  系统将其放在 `excluded_unconfirmed_estimate_total`，**净成本 ¥52,153.36 不因它改变**。
  `POST /api/experiments/{id}/costs/offsets` 对 `confirmed=true` 但无凭证号的请求直接返回 400。

---

## 六、主要 HTTP API

所有 `/api/**` 需登录（表单 Cookie 或 Basic）。完整交互文档：`/swagger-ui.html`。

| 方法/路径 | 作用 |
| --- | --- |
| `GET /api/me` | 当前账号与角色 |
| `GET/POST /api/experiments`、`GET /api/experiments/{id}` | 列表/登记/详情 |
| `POST /api/experiments/{id}/register` | 冻结簇并生成两套分配与指纹 |
| `GET /api/experiments/{id}/network` | 实验前边、冻结簇、两设计有效单元与选择依据 |
| `GET /api/experiments/{id}/assignments?design=...` | `TRIP_RANDOMIZED` / `CLUSTER_WEEK` 分配清单 |
| `POST /api/experiments/{id}/pipeline/run` | 用冻结数据重算暴露账→支持层→估计→护栏 |
| `GET /api/experiments/{id}/exposure` | 暴露台账（可按 pathLabel/q 过滤） |
| `GET /api/experiments/{id}/journeys/{code}` | 单旅程：卡片 + 逐段 GPS/臂/偏离 + 判定追踪 |
| `GET /api/experiments/{id}/support` | 共同支持分层与覆盖缺口 |
| `GET /api/experiments/{id}/analysis` | 全部估计结果 |
| `GET/POST /api/experiments/{id}/guardrails[/evaluate]` | 护栏规则/最新评估/重新评估 |
| `GET /api/experiments/{id}/expansion`、`POST .../expansion/evaluate|decide` | 扩围证据与结论（APPROVER） |
| `GET /api/experiments/{id}/review-bundle` | 离线审阅包（冻结配置+分配哈希+追踪+结果+`costReport`） |
| `GET /api/experiments/{id}/costs` | **成本归集完整报告**：两方法、对账、未归属池、线路敞口、ITT 成本差与每单元净成本区间、驱动情景、样例证据、720 单元分项、凭证账与抵扣行（另有别名 `/cost`、`/cost-report`、`/cost-allocation`） |
| `GET /api/experiments/{id}/costs/vouchers?category=OVERTIME` | 成本凭证账，可按五类过滤（别名 `/cost-ledger`） |
| `GET /api/experiments/{id}/costs/unit-costs?method=DIRECT\|DRIVER_ALLOC` | 结果层：每分配单元 × 方法的五类成本分项（`cost_unit_cost`，各 720 行） |
| `POST /api/experiments/{id}/costs/recompute` | 用冻结凭证重算分摊与单元结果（ANALYST+；`/pipeline/run` 也会自动调用） |
| `POST /api/experiments/{id}/costs/offsets` | 登记/更新收入或节省抵扣；`confirmed=true` 必须给 `confirmationRef`，否则 400（ANALYST+，幂等 upsert） |
| `GET /api/experiments/{id}/events`、`/complaints` | 外部事件时间线、投诉时间关联核验 |

---

## 七、目录与实现说明

```
src/main/java/com/ccdev/transit/
  engine/            # 纯逻辑、无 Spring：确定性哈希、平衡分配、网络聚类、路径分类、统计/RI、CostEngine 成本归集
  service/           # 登记冻结、确定性播种器、分析流水线、扩围决策、CostService、CostDataSeeder 成本样例
  web/               # REST 控制器（含 CostController）与 DTO
  config/ security/  # Spring Security 三角色、OpenAPI、jsonb 响应处理
src/main/resources/
  schema.sql         # 幂等 PostgreSQL 建表（含 vehicle_block/cost_voucher/cost_offset/cost_unit_cost）
  static/            # 原生 JS 单页控制台（login/index/app/styles，11 个标签页）
src/test/java/...    # 22 个纯 JUnit 测试（不依赖数据库/Docker，含 6 个 CostEngineTest）
verify.sh            # 62 项端到端不变量（含 28 项成本归集）
Dockerfile docker-compose.yml docker-settings.xml
```

关键口径：

- **分配臂 vs 实际暴露是两条轴**：ITT 永远按随机分配臂；司机不执行/短线折返不改变分配臂，只改变实际路径标签。
- **CROSS_MIXED 单独成类**：枢纽异臂搭乘、短线折返分流都进该类，绝不并入 `FULL_CONTROL`。
- **不把执行者子集叫随机效应**：实际路径/执行者均值以 `AS_TREATED_ASSOCIATION` 明确标注为观察性关联；因果口径只有 ITT 与带假设的 CACE。
- **未知保留**：`INCOMPLETE_TAP/MID_ROUTE_CHANGE` 不插补、不归臂、不进效应估计，只计数并进入安全护栏。
- **共同支持**：无支持层旅程不产生外推结论，仅在支持页与 `gaps` 中报告覆盖缺口。
- **时间基准**：天气/施工/改线与 `service_ts` 按窗口重叠关联；护栏投诉率按 `complaint.service_event_time` 归集。
- **成本 ITT 按随机分配臂**：成本随车辆块/单元按 `assignment_unit.arm` 归臂，司机不执行规则不改变其分配臂；成本 ITT 是“元/合格服务单元”的随机组均值差，不是乘客加权，也不是统计抽样 CI。
- **未归属额始终保留**：直接法下共同备用车/跨线维修全额留在 `unallocated_amount`，绝不为了账面好看而静默清零；其每单元区间是归属边界而非置信区间。
- **分摊只是搬运同一笔钱**：驱动分摊前后凭证总额不变，分摊合计与共享凭证额逐分相等；跨线“触及敞口”仅作追溯备忘、不可跨线相加。
- **估计不得填平成本**：只有已确认且有凭证号的收入/节省可冲减净成本；投诉不货币化、加班按实际记录而非平均工资率估算。
