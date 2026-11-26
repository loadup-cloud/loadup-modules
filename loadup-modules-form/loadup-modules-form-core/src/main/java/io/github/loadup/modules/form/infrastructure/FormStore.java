/*
 * Copyright (C) 2025-2026 LoadUp Cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loadup.modules.form.infrastructure;

import static io.github.loadup.modules.form.client.FormDefinitions.Field;
import static io.github.loadup.modules.form.client.FormDefinitions.FieldVersion;
import static io.github.loadup.modules.form.client.FormDefinitions.Kind;
import static io.github.loadup.modules.form.client.FormDefinitions.Origin;
import static io.github.loadup.modules.form.client.FormDefinitions.Release;
import static io.github.loadup.modules.form.client.FormDefinitions.Status;
import static io.github.loadup.modules.form.client.FormDefinitions.ValueType;
import static io.github.loadup.modules.form.client.FormDefinitions.Version;

import io.github.loadup.modules.form.client.FormInstances.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class FormStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public FormStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.json = JsonMapper.builder().build();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Identity fieldIdentity(String id, String namespace, Field field, boolean systemOwned) {
        jdbc.sql("""
                INSERT INTO form_field (id, namespace, field_key, value_type, multiple, value_set_code, unit, system_owned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (namespace, field_key) DO NOTHING
                """)
                .params(
                        id,
                        namespace,
                        field.key(),
                        field.type().name(),
                        field.multiple(),
                        field.valueSetCode(),
                        field.unit(),
                        systemOwned)
                .update();
        return jdbc.sql("SELECT * FROM form_field WHERE namespace = ? AND field_key = ? FOR UPDATE")
                .params(namespace, field.key())
                .query((rs, _) -> new Identity(
                        rs.getString("id"),
                        ValueType.valueOf(rs.getString("value_type")),
                        rs.getBoolean("multiple"),
                        rs.getString("value_set_code"),
                        rs.getString("unit"),
                        rs.getBoolean("system_owned")))
                .single();
    }

    public void insertField(String id, String fieldId, int version, Field field) {
        jdbc.sql("""
                INSERT INTO form_field_version (id, field_id, version_no, definition)
                VALUES (?, ?, ?, ?) ON CONFLICT (field_id, version_no) DO NOTHING
                """)
                .params(id, fieldId, version, json.writeValueAsString(field))
                .update();
    }

    public FieldVersion fieldVersion(String fieldId, int version) {
        return jdbc.sql("""
                SELECT v.*, f.namespace, f.system_owned FROM form_field_version v
                JOIN form_field f ON v.field_id = f.id WHERE f.id = ? AND v.version_no = ?
                """).params(fieldId, version).query(this::field).single();
    }

    public Optional<FieldVersion> field(String namespace, String id) {
        return jdbc.sql("""
                SELECT v.*, f.namespace, f.system_owned FROM form_field_version v
                JOIN form_field f ON v.field_id = f.id WHERE f.namespace = ? AND v.id = ?
                """).params(namespace, id).query(this::field).optional();
    }

    public List<FieldVersion> fields(String namespace, String prefix, int offset, int limit) {
        return jdbc.sql("""
                SELECT v.*, f.namespace, f.system_owned FROM form_field_version v
                JOIN form_field f ON v.field_id = f.id
                WHERE f.namespace = ? AND starts_with(f.field_key, ?)
                ORDER BY f.field_key, v.version_no DESC LIMIT ? OFFSET ?
                """)
                .params(namespace, prefix, limit, offset)
                .query(this::field)
                .list();
    }

    private FieldVersion field(ResultSet rs, int row) throws SQLException {
        return new FieldVersion(
                rs.getString("id"),
                rs.getString("namespace"),
                rs.getInt("version_no"),
                rs.getBoolean("system_owned"),
                json.readValue(rs.getString("definition"), Field.class));
    }

    public boolean insertDefinition(String namespace, Kind kind, Version<?> version) {
        return jdbc.sql("""
                INSERT INTO form_definition_version (id, namespace, kind, code, version_no, status, row_version, definition)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, ?)
                ON CONFLICT (namespace, kind, code, version_no) DO NOTHING
                """)
                        .params(
                                version.id(),
                                namespace,
                                kind.name(),
                                version.code(),
                                version.version(),
                                json.writeValueAsString(version.definition()))
                        .update()
                == 1;
    }

    public <T> Optional<Version<T>> definition(String namespace, Kind kind, String id, Class<T> type, boolean lock) {
        String sql = "SELECT * FROM form_definition_version WHERE namespace = ? AND kind = ? AND id = ?";
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .params(namespace, kind.name(), id)
                .query((rs, _) -> definition(rs, type))
                .optional();
    }

    public <T> Optional<Version<T>> definition(
            String namespace, Kind kind, String code, int version, Class<T> type, boolean lock) {
        String sql = """
                SELECT * FROM form_definition_version WHERE namespace = ? AND kind = ? AND code = ? AND version_no = ?
                """;
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .params(namespace, kind.name(), code, version)
                .query((rs, _) -> definition(rs, type))
                .optional();
    }

    public <T> List<Version<T>> definitions(String namespace, Kind kind, Class<T> type, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM form_definition_version WHERE namespace = ? AND kind = ?
                ORDER BY code, version_no DESC LIMIT ? OFFSET ?
                """)
                .params(namespace, kind.name(), limit, offset)
                .query((rs, _) -> definition(rs, type))
                .list();
    }

    private <T> Version<T> definition(ResultSet rs, Class<T> type) throws SQLException {
        return new Version<>(
                rs.getString("id"),
                rs.getString("code"),
                rs.getInt("version_no"),
                rs.getLong("row_version"),
                Status.valueOf(rs.getString("status")),
                json.readValue(rs.getString("definition"), type));
    }

    public void saveDefinition(String id, Object definition) {
        jdbc.sql("UPDATE form_definition_version SET definition = ?, row_version = row_version + 1 WHERE id = ?")
                .params(json.writeValueAsString(definition), id)
                .update();
    }

    public void setStatus(String id, Status status) {
        jdbc.sql("UPDATE form_definition_version SET status = ?, row_version = row_version + 1 WHERE id = ?")
                .params(status.name(), id)
                .update();
    }

    public void insertRelease(Release release) {
        String snapshot = json.writeValueAsString(release);
        jdbc.sql("INSERT INTO form_release (id, template_version_id, snapshot, schema_hash) VALUES (?, ?, ?, ?)")
                .params(release.id(), release.templateVersionId(), snapshot, hash(snapshot))
                .update();
    }

    public Optional<Release> releaseForTemplate(String templateVersionId) {
        return jdbc.sql("SELECT snapshot, schema_hash FROM form_release WHERE template_version_id = ?")
                .param(templateVersionId)
                .query(this::release)
                .optional();
    }

    public Optional<Release> release(String namespace, String id, boolean publishedOnly) {
        String sql = """
                SELECT r.snapshot, r.schema_hash FROM form_release r JOIN form_definition_version t
                ON t.id = r.template_version_id WHERE t.namespace = ? AND r.id = ?
                """;
        return jdbc.sql(publishedOnly ? sql + " AND t.status = 'PUBLISHED' FOR SHARE OF t" : sql)
                .params(namespace, id)
                .query(this::release)
                .optional();
    }

    private Release release(ResultSet rs, int row) throws SQLException {
        String snapshot = rs.getString("snapshot");
        if (!hash(snapshot).equals(rs.getString("schema_hash"))) {
            throw new IllegalStateException("Form release snapshot does not match its recorded hash");
        }
        return json.readValue(snapshot, Release.class);
    }

    public boolean insertInstance(String namespace, String actor, String id, String releaseId, String requestKey) {
        return jdbc.sql("""
                INSERT INTO form_instance (id, namespace, actor, release_id, start_key, row_version)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (namespace, actor, start_key) DO NOTHING
                """).params(id, namespace, actor, releaseId, requestKey).update() == 1;
    }

    public Optional<Instance> byStartKey(String namespace, String actor, String key) {
        return jdbc.sql("SELECT * FROM form_instance WHERE namespace = ? AND actor = ? AND start_key = ? FOR SHARE")
                .params(namespace, actor, key)
                .query(this::instance)
                .optional();
    }

    public Optional<Instance> instance(String namespace, String actor, String id, boolean lock) {
        String sql = "SELECT * FROM form_instance WHERE namespace = ? AND actor = ? AND id = ?";
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql)
                .params(namespace, actor, id)
                .query(this::instance)
                .optional();
    }

    private Instance instance(ResultSet rs, int row) throws SQLException {
        return new Instance(
                rs.getString("id"), rs.getString("release_id"), rs.getLong("row_version"), rs.getString("submit_key"));
    }

    public Set<String> selectedItems(String instanceId) {
        return Set.copyOf(jdbc.sql("SELECT item_key FROM form_instance_item WHERE instance_id = ?")
                .param(instanceId)
                .query(String.class)
                .list());
    }

    public Map<String, Map<String, Value>> values(String instanceId) {
        Map<String, Map<String, Value>> values = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT v.item_key, f.field_key, v.value_json, v.value_origin FROM form_instance_value v
                JOIN form_field_version fv ON fv.id = v.field_version_id
                JOIN form_field f ON f.id = fv.field_id
                WHERE v.instance_id = ? ORDER BY v.item_key, f.field_key
                """)
                .param(instanceId)
                .query((rs, _) -> {
                    values.computeIfAbsent(rs.getString("item_key"), ignored -> new LinkedHashMap<>())
                            .put(
                                    rs.getString("field_key"),
                                    new Value(
                                            json.readTree(rs.getString("value_json")),
                                            Origin.valueOf(rs.getString("value_origin"))));
                    return Boolean.TRUE;
                })
                .list();
        values.replaceAll((_, fields) -> Map.copyOf(fields));
        return Map.copyOf(values);
    }

    public void replaceSelection(String id, Set<String> selected) {
        jdbc.sql("DELETE FROM form_instance_value WHERE instance_id = ?")
                .param(id)
                .update();
        jdbc.sql("DELETE FROM form_instance_item WHERE instance_id = ?")
                .param(id)
                .update();
        for (String key : selected) {
            jdbc.sql("INSERT INTO form_instance_item (instance_id, item_key) VALUES (?, ?)")
                    .params(id, key)
                    .update();
        }
    }

    public void insertValue(String id, String item, String fieldVersionId, JsonNode value, Origin origin) {
        jdbc.sql("""
                INSERT INTO form_instance_value (instance_id, item_key, field_version_id, value_json, value_origin)
                VALUES (?, ?, ?, ?, ?)
                """)
                .params(id, item, fieldVersionId, json.writeValueAsString(value), origin.name())
                .update();
    }

    public void advanceInstance(String id, String submitKey) {
        if (submitKey == null) {
            jdbc.sql("UPDATE form_instance SET row_version = row_version + 1 WHERE id = ?")
                    .param(id)
                    .update();
        } else {
            jdbc.sql("""
                    UPDATE form_instance SET row_version = row_version + 1, submit_key = ?,
                        submitted_at = CURRENT_TIMESTAMP WHERE id = ?
                    """).params(submitKey, id).update();
        }
    }

    public record Identity(
            String id, ValueType type, boolean multiple, String valueSetCode, String unit, boolean systemOwned) {}

    public record Instance(String id, String releaseId, long rowVersion, String submitKey) {}
}
