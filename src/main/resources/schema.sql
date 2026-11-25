-- 公交灰度实验服务 PostgreSQL schema（幂等，可重复启动）

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT NOT NULL CHECK (role IN ('VIEWER','ANALYST','APPROVER'))
);

CREATE TABLE IF NOT EXISTS experiment (
    id                  BIGSERIAL PRIMARY KEY,
    code                TEXT UNIQUE NOT NULL,
    name                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/REGISTERED/RUNNING/PAUSED/STOPPED/COMPLETED
    active_design       TEXT NOT NULL DEFAULT 'TRIP_RANDOMIZED',
    seed                TEXT NOT NULL,
    assignment_prob     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    saturation          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    pilot_weeks         INT NOT NULL DEFAULT 4,
    start_date          DATE,
    end_date            DATE,
    target_population   TEXT,
    treatment_rules     TEXT,   -- JSON：处理规则（缩短间隔/短线折返）
    primary_metrics     TEXT,   -- JSON 数组
    safety_metrics      TEXT,   -- JSON 数组
    network_config      TEXT,   -- JSON：换乘边阈值等
    analysis_config     TEXT,   -- JSON：共同支持最小样本等
    registration_hash   TEXT,
    registered_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS route (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS stop_ (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'NORMAL', -- NORMAL/HUB
    UNIQUE (experiment_id, code)
);

-- 实验前刷卡换乘比例（冻结，不允许用试点结果重算）
CREATE TABLE IF NOT EXISTS pre_transfer_edge (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    from_route         TEXT NOT NULL,
    to_route           TEXT NOT NULL,
    transfer_journeys  INTEGER NOT NULL,
    total_journeys     INTEGER NOT NULL,
    ratio              DOUBLE PRECISION NOT NULL,
    frozen             BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, from_route, to_route)
);

-- 依据实验前网络一次性冻结的线路簇
CREATE TABLE IF NOT EXISTS route_cluster (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    cluster_code  TEXT NOT NULL,
    route_codes   TEXT NOT NULL, -- 逗号分隔，排序
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, cluster_code)
);

-- 随机化单元与分配结果（同一实验保存两套设计的分配）
CREATE TABLE IF NOT EXISTS assignment_unit (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type       TEXT NOT NULL, -- TRIP_RANDOMIZED / CLUSTER_WEEK
    unit_key          TEXT NOT NULL,
    stratum           TEXT NOT NULL,
    label             TEXT,
    route_codes       TEXT NOT NULL,
    cluster_code      TEXT,
    week_index        INTEGER,
    hash_value        TEXT NOT NULL,
    arm               TEXT NOT NULL, -- TREATMENT / CONTROL
    inclusion_prob    DOUBLE PRECISION NOT NULL,
    UNIQUE (experiment_id, design_type, unit_key)
);
CREATE INDEX IF NOT EXISTS idx_assignment_exp ON assignment_unit(experiment_id, design_type);

-- 计划班次（日期级实例，保留计划分配）
CREATE TABLE IF NOT EXISTS service_trip (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code         TEXT NOT NULL,
    template_key      TEXT NOT NULL, -- TRIP:R1:T02
    cluster_week_key  TEXT NOT NULL, -- CW:C12:W1
    route_code        TEXT NOT NULL,
    service_date      DATE NOT NULL,
    week_index        INTEGER NOT NULL,
    period_band       TEXT NOT NULL,
    seq_in_band       INTEGER NOT NULL DEFAULT 1,
    planned_departure TIMESTAMPTZ NOT NULL,
    od_band           TEXT,
    capacity          INTEGER NOT NULL DEFAULT 80,
    eligible          BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, trip_code)
);
CREATE INDEX IF NOT EXISTS idx_trip_lookup ON service_trip(experiment_id, route_code, service_date, period_band);

-- 实际 GPS/AVL 运行与偏离（实际执行状态）
CREATE TABLE IF NOT EXISTS actual_service_run (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL UNIQUE REFERENCES service_trip(id) ON DELETE CASCADE,
    gps_run_code        TEXT NOT NULL,
    actual_departure    TIMESTAMPTZ,
    actual_arrival      TIMESTAMPTZ,
    headway_seconds     INTEGER,
    rule_executed       BOOLEAN NOT NULL DEFAULT TRUE,
    deviation           TEXT NOT NULL DEFAULT 'NONE', -- NONE/SHORT_TURN/SUSPENDED/OVERTIME/DELAY
    short_turn_at_stop  TEXT,
    diverted_to_route   TEXT,
    delay_seconds       INTEGER NOT NULL DEFAULT 0,
    observed_lf         DOUBLE PRECISION,
    source              TEXT NOT NULL DEFAULT 'GPS_AVL'
);

-- 天气/施工/临时改线事件，按事件发生时间关联服务
CREATE TABLE IF NOT EXISTS external_event (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    event_type     TEXT NOT NULL, -- WEATHER_RAINSTORM/WEATHER_RAIN/CONSTRUCTION/ROUTE_CHANGE
    severity       TEXT,
    title          TEXT NOT NULL,
    start_ts       TIMESTAMPTZ NOT NULL,
    end_ts         TIMESTAMPTZ NOT NULL,
    affected_routes TEXT NOT NULL DEFAULT '*',
    suspension     BOOLEAN NOT NULL DEFAULT FALSE,
    source         TEXT
);

-- 刷卡完整旅程（未完成刷卡/中途改线保留为非 COMPLETE 状态）
CREATE TABLE IF NOT EXISTS card_journey (
    id                        BIGSERIAL PRIMARY KEY,
    experiment_id             BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code              TEXT NOT NULL UNIQUE,
    week_index                INTEGER NOT NULL,
    service_date              DATE NOT NULL,
    service_ts                TIMESTAMPTZ NOT NULL,
    period_band               TEXT NOT NULL,
    origin_stop               TEXT,
    hub_stop                  TEXT,
    dest_stop                 TEXT,
    od_band                   TEXT NOT NULL,
    route_pattern             TEXT NOT NULL, -- R1ONLY/R2ONLY/R1R2/R3ONLY/...
    weather_class             TEXT NOT NULL DEFAULT 'NORMAL',
    accessibility             BOOLEAN NOT NULL DEFAULT TRUE,
    status                    TEXT NOT NULL, -- COMPLETE/INCOMPLETE_TAP/MID_ROUTE_CHANGE
    passenger_count           INTEGER NOT NULL DEFAULT 1,
    anchor_unit_key           TEXT,
    anchor_arm_trip           TEXT,
    cluster_week_key          TEXT,
    cluster_code              TEXT,
    anchor_arm_cluster        TEXT,
    wait_seconds              DOUBLE PRECISION,
    first_wait_seconds        DOUBLE PRECISION,
    transfer_wait_seconds     DOUBLE PRECISION NOT NULL DEFAULT 0,
    crowd_exposure            DOUBLE PRECISION,
    severe_crowd_minutes      DOUBLE PRECISION,
    max_load_factor           DOUBLE PRECISION,
    -- 同一目标总体在“簇—周”方案下按同一 DGP 构造的可复现实现结果
    wait_cluster_seconds      DOUBLE PRECISION,
    crowd_cluster_exposure    DOUBLE PRECISION,
    severe_crowd_cluster_min  DOUBLE PRECISION,
    complaint_count           INTEGER NOT NULL DEFAULT 0,
    is_demo                   BOOLEAN NOT NULL DEFAULT FALSE,
    note                      TEXT
);
CREATE INDEX IF NOT EXISTS idx_journey_exp ON card_journey(experiment_id);
CREATE INDEX IF NOT EXISTS idx_journey_stratum ON card_journey(experiment_id, period_band, od_band, weather_class, accessibility);

CREATE TABLE IF NOT EXISTS journey_leg (
    id             BIGSERIAL PRIMARY KEY,
    journey_id     BIGINT NOT NULL REFERENCES card_journey(id) ON DELETE CASCADE,
    leg_index      INTEGER NOT NULL,
    route_code     TEXT NOT NULL,
    trip_id        BIGINT REFERENCES service_trip(id),
    actual_run_id  BIGINT REFERENCES actual_service_run(id),
    board_stop     TEXT,
    alight_stop    TEXT,
    is_transfer    BOOLEAN NOT NULL DEFAULT FALSE,
    unit_key       TEXT,
    planned_arm    TEXT,
    rule_effective BOOLEAN,
    deviation      TEXT,
    leg_exposure   TEXT NOT NULL, -- TREATMENT/CONTROL/CROSS/UNKNOWN
    wait_seconds   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ride_minutes   DOUBLE PRECISION NOT NULL DEFAULT 0,
    load_factor    DOUBLE PRECISION,
    note           TEXT
);
CREATE INDEX IF NOT EXISTS idx_leg_journey ON journey_leg(journey_id);

-- 暴露账：每旅程完整路径一行
CREATE TABLE IF NOT EXISTS exposure_ledger (
    journey_id            BIGINT PRIMARY KEY REFERENCES card_journey(id) ON DELETE CASCADE,
    experiment_id         BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code          TEXT NOT NULL,
    status                TEXT NOT NULL,
    path_label            TEXT NOT NULL, -- FULL_TREATMENT/FULL_CONTROL/CROSS_MIXED/UNKNOWN
    cluster_path_label    TEXT NOT NULL, -- CLUSTER_TREATMENT/CLUSTER_CONTROL/CLUSTER_INTERNAL_MIX/UNKNOWN
    treatment_legs        INTEGER NOT NULL DEFAULT 0,
    control_legs          INTEGER NOT NULL DEFAULT 0,
    cross_legs            INTEGER NOT NULL DEFAULT 0,
    unknown_legs          INTEGER NOT NULL DEFAULT 0,
    assigned_arm_trip     TEXT,
    assigned_arm_cluster  TEXT,
    effective_treatment   BOOLEAN NOT NULL DEFAULT FALSE,
    cross_line            BOOLEAN NOT NULL DEFAULT FALSE,
    diverted              BOOLEAN NOT NULL DEFAULT FALSE,
    path_probability      DOUBLE PRECISION,
    path_detail           TEXT,
    rebuilt_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ledger_path ON exposure_ledger(experiment_id, path_label);

CREATE TABLE IF NOT EXISTS support_stratum (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    stratum_key      TEXT NOT NULL,
    period_band      TEXT,
    od_band          TEXT,
    weather_class    TEXT,
    accessibility    BOOLEAN,
    n_total          INTEGER,
    weighted_total   DOUBLE PRECISION,
    n_t_trip         INTEGER,
    n_c_trip         INTEGER,
    w_t_trip         DOUBLE PRECISION,
    w_c_trip         DOUBLE PRECISION,
    n_t_cluster      INTEGER,
    n_c_cluster      INTEGER,
    w_t_cluster      DOUBLE PRECISION,
    w_c_cluster      DOUBLE PRECISION,
    supported_trip   BOOLEAN,
    supported_cluster BOOLEAN,
    gap_reason       TEXT,
    UNIQUE (experiment_id, stratum_key)
);

CREATE TABLE IF NOT EXISTS analysis_result (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type   TEXT NOT NULL,
    estimator_code TEXT NOT NULL, -- ITT / CACE_WALD / PATH_MEAN / CROSS_RATE / RI ...
    metric_code   TEXT NOT NULL,  -- wait_seconds / crowd_exposure ...
    scope         TEXT NOT NULL,  -- COMMON_SUPPORT / ALL_ELIGIBLE / PATH_*
    estimate      DOUBLE PRECISION,
    se            DOUBLE PRECISION,
    ci_low        DOUBLE PRECISION,
    ci_high       DOUBLE PRECISION,
    p_value       DOUBLE PRECISION,
    n_journeys    INTEGER,
    n_units       INTEGER,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_result_exp ON analysis_result(experiment_id);

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    metric_source TEXT NOT NULL,
    comparator    TEXT NOT NULL DEFAULT 'GT', -- GT/LT
    threshold     DOUBLE PRECISION NOT NULL,
    min_sample    INTEGER NOT NULL DEFAULT 30,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS guardrail_evaluation (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    rule_code     TEXT NOT NULL,
    observed      DOUBLE PRECISION,
    threshold     DOUBLE PRECISION,
    sample_n      INTEGER,
    breached      BOOLEAN NOT NULL,
    action        TEXT NOT NULL, -- OK / WARN / STOP_EXPANSION / INSUFFICIENT_EVIDENCE
    detail        TEXT,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ge_exp ON guardrail_evaluation(experiment_id);

CREATE TABLE IF NOT EXISTS expansion_decision (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    decision      TEXT NOT NULL, -- GO / HOLD / BLOCKED
    reason        TEXT,
    evidence      TEXT,
    decided_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS complaint (
    id                   BIGSERIAL PRIMARY KEY,
    experiment_id        BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    complaint_code       TEXT,
    journey_id           BIGINT REFERENCES card_journey(id) ON DELETE SET NULL,
    route_code           TEXT,
    category             TEXT,
    severity             TEXT,
    complaint_time       TIMESTAMPTZ NOT NULL,  -- 投诉提交时间（可滞后服务数日）
    service_event_time   TIMESTAMPTZ NOT NULL,  -- 实际服务发生时间（统计关联基准）
    channel              TEXT
);
CREATE INDEX IF NOT EXISTS idx_complaint_service_time ON complaint(experiment_id, service_event_time);

CREATE TABLE IF NOT EXISTS load_count (
    id             BIGSERIAL PRIMARY KEY,
    actual_run_id BIGINT REFERENCES actual_service_run(id) ON DELETE CASCADE,
    trip_id        BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at    TIMESTAMPTZ,
    passengers     INTEGER,
    capacity       INTEGER,
    load_factor    DOUBLE PRECISION
);

-- =====================================================================
-- 试点成本归集（净运营成本，而非投诉货币化或用平均工资率估算）
-- 成本账：加班 / 油电 / 临时车辆 / 维修 / 取消班次损失，绑定车辆块/处理周期
-- =====================================================================

-- 可追溯车辆块：一个合格随机化班次单元对应一个常规车辆块；另有跨线路备用块
CREATE TABLE IF NOT EXISTS vehicle_block (
    id                       BIGSERIAL PRIMARY KEY,
    experiment_id            BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    block_code               TEXT NOT NULL,
    unit_key                 TEXT,                  -- 对应 TRIP_RANDOMIZED 分配单元
    trip_id                  BIGINT REFERENCES service_trip(id) ON DELETE SET NULL,
    route_code               TEXT NOT NULL,         -- 主属线路；跨线共用资源为 MULTI
    service_date             DATE,
    week_index               INTEGER,
    period_band              TEXT,
    arm                      TEXT,                  -- 按随机分配（非实际执行）的臂
    block_kind               TEXT NOT NULL DEFAULT 'REGULAR', -- REGULAR/SPARE/TEMPORARY
    eligible                 BOOLEAN NOT NULL DEFAULT TRUE,   -- 是否合格服务单元
    frozen_vehicle_hours     DOUBLE PRECISION NOT NULL DEFAULT 0, -- 冻结车辆小时（分摊驱动）
    frozen_vehicle_km        DOUBLE PRECISION NOT NULL DEFAULT 0, -- 冻结车辆公里（分摊驱动）
    frozen_maintenance_hours DOUBLE PRECISION NOT NULL DEFAULT 0, -- 冻结维修工时（分摊驱动）
    UNIQUE (experiment_id, block_code)
);
CREATE INDEX IF NOT EXISTS idx_vblock_exp ON vehicle_block(experiment_id);
CREATE INDEX IF NOT EXISTS idx_vblock_unit ON vehicle_block(experiment_id, unit_key);

-- 成本凭证账：每张实际凭证只入账一次（voucher_code 唯一），从源头防止重复计费
CREATE TABLE IF NOT EXISTS cost_voucher (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    voucher_code     TEXT NOT NULL,
    cost_category    TEXT NOT NULL, -- OVERTIME/ENERGY/TEMP_VEHICLE/MAINTENANCE/CANCEL_LOSS
    attribution      TEXT NOT NULL DEFAULT 'DIRECT', -- DIRECT 直接归属 / SHARED 共享待分摊
    driver_basis     TEXT,          -- HOURS/KM/MAINT_HOURS：该共享凭证的首选分摊驱动
    shared_pool      TEXT,          -- SPARE_VEHICLE/CROSS_LINE_MAINT ...
    block_id         BIGINT REFERENCES vehicle_block(id) ON DELETE SET NULL,
    block_code       TEXT,
    unit_key         TEXT,
    route_code       TEXT,
    service_date     DATE,
    week_index       INTEGER,
    amount           NUMERIC(14,2) NOT NULL,
    currency         TEXT NOT NULL DEFAULT 'CNY',
    source_ref       TEXT,          -- 实际凭证号/工单号/发票号
    description      TEXT,
    cross_line       BOOLEAN NOT NULL DEFAULT FALSE, -- 跨线（服务多条线路）
    cross_shift      BOOLEAN NOT NULL DEFAULT FALSE, -- 跨班（跨越多个班次/时段）
    support_links    TEXT,          -- JSON：共享资源实际支援的块/线路/臂（可追溯，不直接拆分）
    posted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, voucher_code)
);
CREATE INDEX IF NOT EXISTS idx_costv_exp ON cost_voucher(experiment_id);
CREATE INDEX IF NOT EXISTS idx_costv_unit ON cost_voucher(experiment_id, unit_key);
CREATE INDEX IF NOT EXISTS idx_costv_cat ON cost_voucher(experiment_id, cost_category);

-- 收入/节省/追偿：只有 confirmed=TRUE 且有确认凭证号的才能冲减净成本；
-- 未确认的估计（estimated/unconfirmed）一律排除在净成本之外，仅作备忘披露
CREATE TABLE IF NOT EXISTS cost_offset (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    offset_code      TEXT NOT NULL,
    kind             TEXT NOT NULL, -- REVENUE/SAVING/RECOVERY/CLAIM
    description      TEXT,
    amount           NUMERIC(14,2) NOT NULL,
    confirmed        BOOLEAN NOT NULL DEFAULT FALSE,
    confirmation_ref TEXT,
    unit_key         TEXT,
    arm              TEXT,
    route_code       TEXT,
    service_date     DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, offset_code)
);
CREATE INDEX IF NOT EXISTS idx_costoffset_exp ON cost_offset(experiment_id);

-- 结果层：每个分配单元 × 归属方法的成本分项（重算时按 experiment+method 幂等重建）
CREATE TABLE IF NOT EXISTS cost_unit_cost (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    method             TEXT NOT NULL, -- DIRECT 直接凭证归属 / DRIVER_ALLOC 冻结驱动分摊
    unit_key           TEXT NOT NULL,
    block_code         TEXT,
    route_code         TEXT,
    arm                TEXT,
    week_index         INTEGER,
    overtime           NUMERIC(14,2) NOT NULL DEFAULT 0,
    energy             NUMERIC(14,2) NOT NULL DEFAULT 0,
    temp_vehicle       NUMERIC(14,2) NOT NULL DEFAULT 0,
    maintenance        NUMERIC(14,2) NOT NULL DEFAULT 0,
    cancel_loss        NUMERIC(14,2) NOT NULL DEFAULT 0,
    direct_amount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    allocated_shared   NUMERIC(14,2) NOT NULL DEFAULT 0,
    gross_cost         NUMERIC(14,2) NOT NULL DEFAULT 0,
    confirmed_offset   NUMERIC(14,2) NOT NULL DEFAULT 0,
    net_cost           NUMERIC(14,2) NOT NULL DEFAULT 0,
    UNIQUE (experiment_id, method, unit_key)
);
CREATE INDEX IF NOT EXISTS idx_cuc_exp ON cost_unit_cost(experiment_id, method);
