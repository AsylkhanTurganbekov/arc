CREATE TABLE IF NOT EXISTS project_metrics_daily (
    metric_date DATE NOT NULL,
    project_key VARCHAR(32) NOT NULL,
    health_score INTEGER NOT NULL,
    blocked INTEGER NOT NULL,
    stuck INTEGER NOT NULL,
    testing INTEGER NOT NULL,
    released INTEGER NOT NULL,
    total INTEGER NOT NULL,
    release_readiness INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (metric_date, project_key)
);

CREATE TABLE IF NOT EXISTS employee_metrics_daily (
    metric_date DATE NOT NULL,
    employee VARCHAR(255) NOT NULL,
    load_score INTEGER NOT NULL,
    active_tasks INTEGER NOT NULL,
    blocked_tasks INTEGER NOT NULL,
    project_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (metric_date, employee)
);

CREATE TABLE IF NOT EXISTS sprint_metrics_daily (
    metric_date DATE NOT NULL,
    project_key VARCHAR(32) NOT NULL,
    sprint_id VARCHAR(64) NOT NULL,
    sprint_name VARCHAR(255) NOT NULL,
    health_score INTEGER NOT NULL,
    total INTEGER NOT NULL,
    completed INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (metric_date, project_key, sprint_id)
);

CREATE TABLE IF NOT EXISTS employee_directory (
    employee VARCHAR(255) PRIMARY KEY,
    position VARCHAR(80) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_metrics_lookup
    ON project_metrics_daily(project_key, metric_date DESC);

CREATE INDEX IF NOT EXISTS idx_employee_metrics_lookup
    ON employee_metrics_daily(employee, metric_date DESC);
