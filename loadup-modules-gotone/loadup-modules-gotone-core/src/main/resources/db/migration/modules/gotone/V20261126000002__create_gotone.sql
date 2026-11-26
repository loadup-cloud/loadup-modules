CREATE TABLE gotone_service (
    service_code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    active_revision INTEGER
);

CREATE TABLE gotone_service_draft (
    service_code VARCHAR(64) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    config JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (service_code, actor)
);

CREATE TABLE gotone_service_revision (
    service_code VARCHAR(64) NOT NULL REFERENCES gotone_service,
    revision INTEGER NOT NULL,
    required_parameters JSONB NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (service_code, revision)
);

ALTER TABLE gotone_service ADD CONSTRAINT gotone_active_revision_fk
    FOREIGN KEY (service_code, active_revision) REFERENCES gotone_service_revision;

CREATE TABLE gotone_service_channel_map (
    id UUID PRIMARY KEY,
    service_code VARCHAR(64) NOT NULL,
    revision INTEGER NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('SMS','EMAIL','PUSH','IN_APP','WEBHOOK')),
    FOREIGN KEY (service_code, revision) REFERENCES gotone_service_revision,
    UNIQUE (service_code, revision, channel),
    UNIQUE (id, channel)
);

CREATE TABLE gotone_provider_account (
    account_code VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('SMS','EMAIL','PUSH','WEBHOOK')),
    adapter VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    secret_ref VARCHAR(132),
    connect_timeout_ms INTEGER NOT NULL,
    request_timeout_ms INTEGER NOT NULL,
    version INTEGER NOT NULL,
    UNIQUE (account_code, channel)
);

CREATE TABLE gotone_webhook_endpoint (
    account_code VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('WEBHOOK') STORED,
    settings JSONB NOT NULL,
    FOREIGN KEY (account_code, channel) REFERENCES gotone_provider_account(account_code, channel)
);

CREATE TABLE gotone_sms_template (
    map_id UUID PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('SMS') STORED,
    fields JSONB NOT NULL,
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_email_template (
    map_id UUID PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('EMAIL') STORED,
    fields JSONB NOT NULL,
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_push_config (
    map_id UUID PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('PUSH') STORED,
    fields JSONB NOT NULL,
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_inbox_config (
    map_id UUID PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('IN_APP') STORED,
    fields JSONB NOT NULL,
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_webhook_config (
    map_id UUID PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('WEBHOOK') STORED,
    endpoint_code VARCHAR(64) NOT NULL REFERENCES gotone_webhook_endpoint,
    fields JSONB NOT NULL,
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_sms_account (
    account_code VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('SMS') STORED,
    settings JSONB NOT NULL,
    FOREIGN KEY (account_code, channel) REFERENCES gotone_provider_account(account_code, channel)
);

CREATE TABLE gotone_email_account (
    account_code VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('EMAIL') STORED,
    settings JSONB NOT NULL,
    FOREIGN KEY (account_code, channel) REFERENCES gotone_provider_account(account_code, channel)
);

CREATE TABLE gotone_push_account (
    account_code VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(16) GENERATED ALWAYS AS ('PUSH') STORED,
    settings JSONB NOT NULL,
    FOREIGN KEY (account_code, channel) REFERENCES gotone_provider_account(account_code, channel)
);

CREATE TABLE gotone_sms_template_binding (
    map_id UUID REFERENCES gotone_sms_template,
    account_code VARCHAR(64) REFERENCES gotone_sms_account,
    remote_template_id VARCHAR(200) NOT NULL,
    remote_signature VARCHAR(200),
    variable_mapping JSONB NOT NULL,
    PRIMARY KEY (map_id, account_code)
);

CREATE TABLE gotone_request (
    id UUID PRIMARY KEY,
    source VARCHAR(128) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    service_code VARCHAR(64) NOT NULL,
    revision INTEGER NOT NULL,
    sender_id VARCHAR(64),
    parameters JSONB NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    target_count INTEGER NOT NULL,
    UNIQUE (source, request_key),
    FOREIGN KEY (service_code, revision) REFERENCES gotone_service_revision
);

CREATE INDEX gotone_request_recency ON gotone_request (accepted_at DESC);

CREATE TABLE gotone_delivery (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES gotone_request,
    map_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    target_key VARCHAR(64) NOT NULL,
    masked_target VARCHAR(40) NOT NULL,
    snapshot JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RETRY_WAIT','SENDING','SENT','UNKNOWN','FAILED','EXPIRED')),
    attempt_no INTEGER NOT NULL DEFAULT 0,
    UNIQUE (request_id, channel, target_key),
    FOREIGN KEY (map_id, channel) REFERENCES gotone_service_channel_map(id, channel)
);

CREATE TABLE gotone_delivery_attempt (
    delivery_id UUID NOT NULL REFERENCES gotone_delivery,
    number INTEGER NOT NULL,
    account_code VARCHAR(64),
    config_version INTEGER,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(64),
    remote_id VARCHAR(256),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (delivery_id, number)
);

CREATE TABLE gotone_delivery_resolution (
    delivery_id UUID NOT NULL REFERENCES gotone_delivery,
    attempt_no INTEGER NOT NULL,
    accepted BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (delivery_id, attempt_no),
    FOREIGN KEY (delivery_id, attempt_no) REFERENCES gotone_delivery_attempt(delivery_id, number)
);

CREATE TABLE gotone_delivery_retry (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES gotone_delivery,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO gotone_service (service_code, name, enabled, version) VALUES ('approval.pending', '审批待办', true, 1);
INSERT INTO gotone_service_revision (service_code, revision, required_parameters, published_by)
VALUES ('approval.pending', 1, '["actionUrl"]', 'system');
INSERT INTO gotone_service_channel_map (id, service_code, revision, channel)
VALUES ('591ef6d7-0851-455c-9d10-a927867f6cc2', 'approval.pending', 1, 'IN_APP');
INSERT INTO gotone_inbox_config (map_id, fields)
VALUES ('591ef6d7-0851-455c-9d10-a927867f6cc2',
        '{"category":"IN_APP","title":"有新的审批待办","body":"请在审批管理中查看待办并办理。","actionPath":"${actionUrl}"}');
UPDATE gotone_service SET active_revision = 1 WHERE service_code = 'approval.pending';
