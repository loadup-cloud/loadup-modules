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
package io.github.loadup.modules.approval.infrastructure;

import static io.github.loadup.modules.approval.client.Approval.Action;
import static io.github.loadup.modules.approval.client.Approval.AssignmentChange;
import static io.github.loadup.modules.approval.client.Approval.Business;
import static io.github.loadup.modules.approval.client.Approval.Definition;
import static io.github.loadup.modules.approval.client.Approval.Instance;
import static io.github.loadup.modules.approval.client.Approval.Rule;
import static io.github.loadup.modules.approval.client.Approval.Spec;
import static io.github.loadup.modules.approval.client.Approval.Status;
import static io.github.loadup.modules.approval.client.Approval.Step;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class ApprovalStore {
    private static final String INSTANCES = """
            SELECT i.*, d.version FROM approval_instance i JOIN approval_definition d ON d.id = i.definition_id
            """;
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApprovalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public JdbcClient.StatementSpec sql(String statement, Object... parameters) {
        return jdbc.sql(statement).params(Arrays.asList(parameters));
    }

    public String json(Object value) {
        return json.writeValueAsString(value);
    }

    public <T> T read(String value, Class<T> type) {
        return json.readValue(value, type);
    }

    public String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void lockFlow(String app, String flow) {
        sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", "approval/" + app + "/" + flow)
                .query(Integer.class)
                .single();
    }

    private Definition definition(ResultSet rs, int row) throws SQLException {
        return new Definition(
                rs.getString("id"),
                rs.getString("app_code"),
                rs.getString("flow_key"),
                rs.getInt("version"),
                rs.getLong("revision"),
                Status.valueOf(rs.getString("status")),
                read(rs.getString("spec_jsonb"), Spec.class));
    }

    public List<Definition> definitions(String app, int offset, int limit) {
        return sql("""
                SELECT * FROM approval_definition WHERE app_code = ?
                ORDER BY flow_key, version DESC LIMIT ? OFFSET ?
                """, app, limit, offset).query(this::definition).list();
    }

    public Optional<Definition> definition(String app, String id) {
        return sql("SELECT * FROM approval_definition WHERE app_code = ? AND id = ?", app, id)
                .query(this::definition)
                .optional();
    }

    public Optional<Definition> active(String app, String flow) {
        return sql(
                        "SELECT * FROM approval_definition WHERE app_code = ? AND flow_key = ? AND status = 'ACTIVE'",
                        app,
                        flow)
                .query(this::definition)
                .optional();
    }

    public int nextDefinitionVersion(String app, String flow) {
        return sql(
                        "SELECT COALESCE(MAX(version), 0) + 1 FROM approval_definition WHERE app_code = ? AND flow_key = ?",
                        app,
                        flow)
                .query(Integer.class)
                .single();
    }

    private Instant time(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getObject(column, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Record instance(ResultSet rs, int row) throws SQLException {
        var business = new Business(
                rs.getString("business_type"),
                rs.getString("business_id"),
                rs.getString("business_version"),
                rs.getString("content_hash"));
        var instance = new Instance(
                rs.getString("id"),
                rs.getString("app_code"),
                rs.getString("flow_key"),
                rs.getInt("version"),
                business,
                rs.getString("title"),
                rs.getString("applicant_id"),
                Status.valueOf(rs.getString("status")),
                rs.getLong("revision"),
                time(rs, "created_at"),
                time(rs, "finished_at"));
        return new Record(
                instance,
                rs.getString("definition_id"),
                rs.getString("request_hash"),
                rs.getString("delivery_task_id"),
                time(rs, "delivered_at"));
    }

    public Optional<Record> instance(String app, String id, boolean lock) {
        return sql(INSTANCES + " WHERE i.app_code = ? AND i.id = ?" + (lock ? " FOR UPDATE OF i" : ""), app, id)
                .query(this::instance)
                .optional();
    }

    public Optional<Record> byStartKey(String app, String key) {
        return sql(INSTANCES + " WHERE i.app_code = ? AND i.start_key = ?", app, key)
                .query(this::instance)
                .optional();
    }

    public List<Instance> instances(String app, String subject, boolean readAll, int offset, int limit) {
        return sql(INSTANCES + """
                 WHERE i.app_code = ? AND (? OR i.applicant_id = ?)
                 ORDER BY i.created_at DESC, i.id LIMIT ? OFFSET ?
                """, app, readAll, subject, limit, offset).query(this::instance).list().stream()
                .map(Record::instance)
                .toList();
    }

    public List<String> pendingIds(String app, String userId, List<String> roles, int offset, int limit) {
        return jdbc.sql("""
                        SELECT i.id FROM approval_instance i JOIN approval_instance_step s ON s.instance_id = i.id
                        WHERE i.app_code = :app AND s.status = 'PENDING'
                          AND (s.assignee_user_id = :user OR s.assignee_role_id IN (:roles))
                        ORDER BY s.activated_at, s.id LIMIT :limit OFFSET :offset
                        """)
                .param("app", app)
                .param("user", userId)
                .param("roles", roles.isEmpty() ? List.of("") : roles)
                .param("limit", limit)
                .param("offset", offset)
                .query(String.class)
                .list();
    }

    private Step step(ResultSet rs, int row) throws SQLException {
        return new Step(
                rs.getString("id"),
                read(rs.getString("rule_snapshot"), Rule.class),
                rs.getString("assignee_user_id"),
                rs.getString("assignee_role_id"),
                Status.valueOf(rs.getString("status")),
                rs.getString("completed_by"),
                time(rs, "activated_at"),
                time(rs, "completed_at"));
    }

    public List<Step> steps(String id) {
        return sql("SELECT * FROM approval_instance_step WHERE instance_id = ? ORDER BY step_order", id)
                .query(this::step)
                .list();
    }

    public Optional<String> stepInstance(String app, String stepId) {
        return sql("""
                SELECT i.id FROM approval_instance i JOIN approval_instance_step s ON i.id = s.instance_id
                WHERE i.app_code = ? AND s.id = ?
                """, app, stepId).query(String.class).optional();
    }

    public Optional<Receipt> receipt(String id, String key) {
        return sql("SELECT * FROM approval_action WHERE instance_id = ? AND command_key = ?", id, key)
                .query((rs, row) -> new Receipt(
                        rs.getString("actor_id"),
                        rs.getString("command_hash"),
                        read(rs.getString("result_jsonb"), Instance.class)))
                .optional();
    }

    public List<Action> actions(String id) {
        return sql("SELECT * FROM approval_action WHERE instance_id = ? ORDER BY result_revision", id)
                .query((rs, row) -> new Action(
                        rs.getString("action"),
                        rs.getString("step_id"),
                        rs.getString("actor_id"),
                        rs.getString("matched_principal"),
                        rs.getString("reason"),
                        json.readValue(rs.getString("changes_jsonb"), new TypeReference<List<AssignmentChange>>() {}),
                        rs.getLong("result_revision"),
                        time(rs, "occurred_at")))
                .list();
    }

    public record Record(
            Instance instance, String definitionId, String requestHash, String deliveryTaskId, Instant deliveredAt) {}

    public record Receipt(String actor, String hash, Instance result) {}
}
