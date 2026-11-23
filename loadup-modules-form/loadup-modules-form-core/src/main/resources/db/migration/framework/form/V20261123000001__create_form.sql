CREATE TABLE form_field (
    id VARCHAR(36) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    field_key VARCHAR(64) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    multiple BOOLEAN NOT NULL,
    value_set_code VARCHAR(255),
    unit VARCHAR(64),
    system_owned BOOLEAN NOT NULL,
    UNIQUE (namespace, field_key)
);

CREATE TABLE form_field_version (
    id VARCHAR(36) PRIMARY KEY,
    field_id VARCHAR(36) NOT NULL REFERENCES form_field(id),
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    definition TEXT NOT NULL,
    UNIQUE (field_id, version_no)
);

CREATE TABLE form_definition_version (
    id VARCHAR(36) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('GROUP', 'TEMPLATE')),
    code VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    definition TEXT NOT NULL,
    UNIQUE (namespace, kind, code, version_no)
);

CREATE TABLE form_release (
    id VARCHAR(36) PRIMARY KEY,
    template_version_id VARCHAR(36) NOT NULL UNIQUE REFERENCES form_definition_version(id),
    snapshot TEXT NOT NULL,
    schema_hash CHAR(64) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE form_instance (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL REFERENCES form_release(id),
    namespace VARCHAR(64) NOT NULL,
    actor VARCHAR(256) NOT NULL,
    start_key VARCHAR(128) NOT NULL,
    submit_key VARCHAR(128),
    row_version BIGINT NOT NULL CHECK (row_version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (namespace, actor, start_key),
    CHECK ((submit_key IS NULL) = (submitted_at IS NULL))
);

CREATE TABLE form_instance_item (
    instance_id VARCHAR(36) NOT NULL REFERENCES form_instance(id),
    item_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (instance_id, item_key)
);

CREATE TABLE form_instance_value (
    instance_id VARCHAR(36) NOT NULL,
    item_key VARCHAR(128) NOT NULL,
    field_version_id VARCHAR(36) NOT NULL REFERENCES form_field_version(id),
    value_json TEXT NOT NULL,
    value_origin VARCHAR(24) NOT NULL,
    PRIMARY KEY (instance_id, item_key, field_version_id),
    FOREIGN KEY (instance_id, item_key) REFERENCES form_instance_item(instance_id, item_key)
);
