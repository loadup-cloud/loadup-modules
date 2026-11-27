CREATE TABLE gotone_service
(
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    service_code    VARCHAR(64)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    enabled         BOOLEAN      NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 0,
    active_revision INTEGER,
    UNIQUE (service_code)
);

CREATE TABLE gotone_service_draft
(
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    service_code VARCHAR(64)  NOT NULL,
    actor        VARCHAR(128) NOT NULL,
    config       TEXT,
    UNIQUE (service_code, actor)
);

CREATE TABLE gotone_service_revision
(
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    service_code        VARCHAR(64)  NOT NULL,
    revision            INTEGER      NOT NULL,
    required_parameters TEXT         NOT NULL,
    published_by        VARCHAR(128) NOT NULL,
    published_at        TIMESTAMP(6) NOT NULL,
    UNIQUE (service_code, revision)
);

CREATE TABLE gotone_service_channel_map
(
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    service_code VARCHAR(64)  NOT NULL,
    revision     INTEGER      NOT NULL,
    channel      VARCHAR(16)  NOT NULL CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'IN_APP', 'WEBHOOK')),
    fields       TEXT         NOT NULL,
    UNIQUE (service_code, revision, channel)
);

CREATE TABLE gotone_provider_account
(
    id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6) NOT NULL,
    account_code       VARCHAR(64)  NOT NULL,
    channel            VARCHAR(16)  NOT NULL CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'WEBHOOK')),
    adapter            VARCHAR(64)  NOT NULL,
    enabled            BOOLEAN      NOT NULL,
    priority           INTEGER      NOT NULL,
    secret_ref         VARCHAR(132),
    connect_timeout_ms INTEGER      NOT NULL,
    request_timeout_ms INTEGER      NOT NULL,
    version            INTEGER      NOT NULL,
    settings           TEXT         NOT NULL,
    UNIQUE (account_code)
);

CREATE TABLE gotone_sms_template_binding
(
    id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at         TIMESTAMP(6) NOT NULL,
    updated_at         TIMESTAMP(6) NOT NULL,
    map_id             VARCHAR(64)  NOT NULL,
    account_code       VARCHAR(64)  NOT NULL,
    remote_template_id VARCHAR(200) NOT NULL,
    remote_signature   VARCHAR(200),
    variable_mapping   TEXT         NOT NULL,
    service_code       VARCHAR(64)  NOT NULL,
    revision           INTEGER      NOT NULL,
    UNIQUE (map_id, account_code)
);

CREATE TABLE gotone_request
(
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at     TIMESTAMP(6) NOT NULL,
    updated_at     TIMESTAMP(6) NOT NULL,
    source         VARCHAR(128) NOT NULL,
    request_key    VARCHAR(128) NOT NULL,
    content_digest VARCHAR(64)  NOT NULL,
    service_code   VARCHAR(64)  NOT NULL,
    revision       INTEGER      NOT NULL,
    sender_id      VARCHAR(64),
    parameters     TEXT         NOT NULL,
    accepted_at    TIMESTAMP(6) NOT NULL,
    expires_at     TIMESTAMP(6) NOT NULL,
    target_count   INTEGER      NOT NULL,
    UNIQUE (source, request_key)
);

CREATE TABLE gotone_delivery
(
    id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,
    map_id        VARCHAR(64)  NOT NULL,
    channel       VARCHAR(16)  NOT NULL,
    target_key    VARCHAR(64)  NOT NULL,
    masked_target VARCHAR(40)  NOT NULL,
    snapshot      TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL CHECK (status IN
                                               ('PENDING', 'RETRY_WAIT', 'SENDING', 'SENT', 'UNKNOWN', 'FAILED',
                                                'EXPIRED')),
    attempt_no    INTEGER      NOT NULL DEFAULT 0,
    sender_id     VARCHAR(64),
    expires_at    TIMESTAMP(6) NOT NULL,
    UNIQUE (request_id, channel, target_key)
);

CREATE TABLE gotone_delivery_attempt
(
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at     TIMESTAMP(6) NOT NULL,
    updated_at     TIMESTAMP(6) NOT NULL,
    delivery_id    VARCHAR(64)  NOT NULL,
    number         INTEGER      NOT NULL,
    account_code   VARCHAR(64),
    config_version INTEGER,
    status         VARCHAR(16)  NOT NULL,
    reason         VARCHAR(64),
    remote_id      VARCHAR(256),
    started_at     TIMESTAMP(6) NOT NULL,
    finished_at    TIMESTAMP(6),
    UNIQUE (delivery_id, number)
);

CREATE TABLE gotone_delivery_resolution
(
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL,
    delivery_id VARCHAR(64)  NOT NULL,
    attempt_no  INTEGER      NOT NULL,
    accepted    BOOLEAN      NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    actor       VARCHAR(128) NOT NULL,
    resolved_at TIMESTAMP(6) NOT NULL,
    UNIQUE (delivery_id, attempt_no)
);

CREATE TABLE gotone_delivery_retry
(
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    delivery_id  VARCHAR(64)  NOT NULL,
    actor        VARCHAR(128) NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX gotone_request_recency ON gotone_request (accepted_at);
