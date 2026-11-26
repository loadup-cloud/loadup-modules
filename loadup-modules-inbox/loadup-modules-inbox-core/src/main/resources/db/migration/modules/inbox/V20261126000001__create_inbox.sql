CREATE TABLE IF NOT EXISTS inbox_message
(
    id           VARCHAR(64) PRIMARY KEY,
    recipient_id VARCHAR(64)   NOT NULL,
    sender_id    VARCHAR(64),
    category     VARCHAR(64)   NOT NULL,
    title        VARCHAR(200)  NOT NULL,
    body         VARCHAR(4000) NOT NULL,
    action_url   VARCHAR(500),
    request_key  VARCHAR(128)  NOT NULL,
    read_at      TIMESTAMP(6),
    archived_at  TIMESTAMP(6),
    created_at   TIMESTAMP(6)  NOT NULL,
    updated_at   TIMESTAMP(6)  NOT NULL,
    deleted      BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_inbox_message_request UNIQUE (recipient_id, request_key)
);
CREATE INDEX idx_inbox_message ON inbox_message (recipient_id, archived_at, created_at);
CREATE INDEX idx_inbox_message_unread ON inbox_message (recipient_id, archived_at, read_at);
