CREATE TABLE approval_definition (
    id varchar(36) PRIMARY KEY,
    app_code varchar(64) NOT NULL,
    flow_key varchar(64) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    name varchar(200) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    spec_jsonb jsonb NOT NULL,
    spec_hash char(64) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_by varchar(200) NOT NULL,
    updated_by varchar(200) NOT NULL,
    published_by varchar(200),
    published_at timestamptz,
    retired_by varchar(200),
    retired_at timestamptz,
    UNIQUE (app_code, flow_key, version),
    UNIQUE (app_code, flow_key, id)
);

CREATE UNIQUE INDEX approval_definition_draft_idx ON approval_definition (app_code, flow_key) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX approval_definition_active_idx ON approval_definition (app_code, flow_key) WHERE status = 'ACTIVE';

CREATE TABLE approval_instance (
    id varchar(36) PRIMARY KEY,
    definition_id varchar(36) NOT NULL,
    app_code varchar(64) NOT NULL,
    flow_key varchar(64) NOT NULL,
    business_type varchar(64) NOT NULL,
    business_id varchar(128) NOT NULL,
    business_version varchar(64) NOT NULL,
    content_hash varchar(128) NOT NULL,
    title varchar(200) NOT NULL,
    applicant_id varchar(200) NOT NULL,
    start_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    delivery_task_id varchar(64),
    delivered_at timestamptz,
    FOREIGN KEY (app_code, flow_key, definition_id) REFERENCES approval_definition(app_code, flow_key, id),
    UNIQUE (app_code, start_key),
    CHECK ((status = 'PENDING') = (finished_at IS NULL))
);

CREATE UNIQUE INDEX approval_instance_live_idx ON approval_instance
    (app_code, flow_key, business_type, business_id, business_version) WHERE status = 'PENDING';
CREATE INDEX approval_instance_business_idx ON approval_instance
    (app_code, flow_key, business_type, business_id, business_version);
CREATE INDEX approval_instance_applicant_idx ON approval_instance (app_code, applicant_id, created_at DESC);

CREATE TABLE approval_instance_step (
    id varchar(36) PRIMARY KEY,
    instance_id varchar(36) NOT NULL REFERENCES approval_instance(id),
    step_key varchar(64) NOT NULL,
    step_order integer NOT NULL CHECK (step_order > 0),
    step_name varchar(200) NOT NULL,
    rule_snapshot jsonb NOT NULL,
    assignee_user_id varchar(200),
    assignee_role_id varchar(200),
    status varchar(16) NOT NULL CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    activated_at timestamptz,
    completed_at timestamptz,
    completed_by varchar(200),
    UNIQUE (instance_id, id),
    UNIQUE (instance_id, step_key),
    UNIQUE (instance_id, step_order),
    CHECK ((assignee_user_id IS NULL) <> (assignee_role_id IS NULL))
);

CREATE UNIQUE INDEX approval_step_live_idx ON approval_instance_step (instance_id) WHERE status = 'PENDING';
CREATE INDEX approval_step_user_idx ON approval_instance_step (assignee_user_id) WHERE status = 'PENDING';
CREATE INDEX approval_step_role_idx ON approval_instance_step (assignee_role_id) WHERE status = 'PENDING';

CREATE TABLE approval_action (
    id varchar(36) PRIMARY KEY,
    instance_id varchar(36) NOT NULL REFERENCES approval_instance(id),
    step_id varchar(36),
    action varchar(24) NOT NULL CHECK (action IN ('SUBMITTED', 'ASSIGNEE_CHANGED', 'APPROVED_STEP', 'REJECTED_STEP', 'WITHDRAWN')),
    actor_id varchar(200) NOT NULL,
    matched_principal varchar(205),
    reason varchar(2000),
    changes_jsonb jsonb NOT NULL DEFAULT '[]'::jsonb,
    command_key varchar(128) NOT NULL,
    command_hash char(64) NOT NULL,
    result_revision bigint NOT NULL,
    result_jsonb jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (instance_id, step_id) REFERENCES approval_instance_step(instance_id, id),
    UNIQUE (instance_id, command_key),
    UNIQUE (instance_id, result_revision)
);
