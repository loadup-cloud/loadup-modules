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
package io.github.loadup.modules.audit.infrastructure.repository;

import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.modules.audit.domain.gateway.AuditGateway;
import io.github.loadup.modules.audit.domain.model.AuditEvent;
import io.github.loadup.modules.audit.domain.model.AuditQuery;
import io.github.loadup.modules.audit.infrastructure.converter.AuditStorageConverter;
import io.github.loadup.modules.audit.infrastructure.dataobject.AuditEventDO;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class AuditGatewayImpl implements AuditGateway {
    private final NamedParameterJdbcTemplate jdbc;
    private final IdGenerator ids;
    private final AuditStorageConverter converter;
    private final Clock clock;

    public AuditGatewayImpl(
            NamedParameterJdbcTemplate jdbc, IdGenerator ids, AuditStorageConverter converter, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public void insert(AuditEvent event) {
        var row = converter.toDO(event);
        if (row.getId() == null || row.getId().isBlank()) {
            row.setId(ids.generate());
        }
        var now = LocalDateTime.now(clock);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        jdbc.update("""
            INSERT INTO audit_event (
                id, actor_id, action, http_method, request_path, outcome, trace_id, occurred_at,
                created_at, updated_at, deleted)
            VALUES (
                :id, :actorId, :action, :method, :path, :outcome, :traceId, :occurredAt,
                :createdAt, :updatedAt, FALSE)
            """, new org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource(row));
    }

    @Override
    public PageResult<AuditEvent> search(AuditQuery query) {
        var args = new MapSqlParameterSource()
                .addValue("limit", query.size())
                .addValue("offset", (long) (query.page() - 1) * query.size());
        var where = new StringBuilder("deleted = FALSE");
        if (query.actorId() != null && !query.actorId().isBlank()) {
            where.append(" AND actor_id = :actor");
            args.addValue("actor", query.actorId());
        }
        if (query.action() != null && !query.action().isBlank()) {
            where.append(" AND action = :action");
            args.addValue("action", query.action());
        }
        if (query.outcome() != null && !query.outcome().isBlank()) {
            where.append(" AND outcome = :outcome");
            args.addValue("outcome", query.outcome());
        }
        if (query.from() != null) {
            where.append(" AND occurred_at >= :fromTime");
            args.addValue("fromTime", query.from());
        }
        if (query.to() != null) {
            where.append(" AND occurred_at <= :toTime");
            args.addValue("toTime", query.to());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE " + where, args, Long.class);
        var rows = jdbc.query(
                "SELECT id, actor_id, action, http_method AS method, request_path AS path, outcome, trace_id,"
                        + " occurred_at FROM audit_event WHERE "
                        + where
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
                args,
                BeanPropertyRowMapper.newInstance(AuditEventDO.class));
        return new PageResult<>(rows.stream().map(converter::toDomain).toList(), total, query.page(), query.size());
    }
}
