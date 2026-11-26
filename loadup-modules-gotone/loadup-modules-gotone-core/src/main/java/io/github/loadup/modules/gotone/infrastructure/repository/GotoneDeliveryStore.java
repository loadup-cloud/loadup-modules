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
package io.github.loadup.modules.gotone.infrastructure.repository;

import com.mybatisflex.core.query.QueryWrapper;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.Attempt;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Delivery;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Resolution;
import io.github.loadup.modules.gotone.client.Gotone.Retry;
import io.github.loadup.modules.gotone.client.Gotone.Target;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneDeliveryDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneRequestDO;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneDeliveryMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneRequestMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.GOTONE_NOT_FOUND;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.REQUEST_CONFLICT;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_DELIVERY;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_REQUEST;

public final class GotoneDeliveryStore {
    public record StoredRequest(Receipt receipt, String digest) {
    }

    public record Snapshot(Target target, Map<String, String> fields, Map<String, String> parameters) {
    }

    public record Work(UUID id, UUID mapId, Channel channel, String status, int attemptNo,
                       String senderId, Instant expiresAt, Snapshot snapshot) {
    }

    private final JdbcClient jdbc;
    private final GotoneRequestMapper requests;
    private final GotoneDeliveryMapper deliveries;
    private final JsonMapper json;

    public GotoneDeliveryStore(JdbcClient jdbc, GotoneRequestMapper requests, GotoneDeliveryMapper deliveries, JsonMapper json) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.deliveries = deliveries;
        this.json = json;
    }

    public void lockRequest(String source, String key) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(source.length() + ":" + source + key).query(Integer.class).single();
    }

    public Optional<StoredRequest> existing(String source, String key) {
        var query = requestSummary().where(GOTONE_REQUEST.SOURCE.eq(source)).and(GOTONE_REQUEST.REQUEST_KEY.eq(key));
        return Optional.ofNullable(requests.selectOneByQuery(query))
                .map(row -> new StoredRequest(receipt(row), row.getContentDigest()));
    }

    public void insertRequest(Receipt receipt, String source, String key, String digest, String sender,
                              Instant expires, Map<String, String> parameters) {
        jdbc.sql("""
                INSERT INTO gotone_request
                    (id, source, request_key, content_digest, service_code, revision, sender_id,
                     parameters, accepted_at, expires_at, target_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """).params(UUID.fromString(receipt.requestId()), source, key, digest, receipt.serviceCode(), receipt.revision(),
                sender, json.writeValueAsString(parameters), receipt.acceptedAt().atOffset(ZoneOffset.UTC),
                expires.atOffset(ZoneOffset.UTC), receipt.targetCount()).update();
    }

    public void insertDelivery(UUID id, String requestId, UUID mapId, Channel channel, String targetKey, Snapshot snapshot) {
        String address = snapshot.target().address();
        String masked = "••••" + address.substring(Math.max(0, address.length() - 4));
        jdbc.sql("""
                INSERT INTO gotone_delivery
                    (id, request_id, map_id, channel, target_key, masked_target, snapshot, status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING')
                """).params(id, UUID.fromString(requestId), mapId, channel.name(), targetKey, masked,
                json.writeValueAsString(snapshot)).update();
    }

    public Receipt receipt(UUID id, String source) {
        var query = requestSummary().where(GOTONE_REQUEST.ID.eq(id));
        if (source != null) {
            query.and(GOTONE_REQUEST.SOURCE.eq(source));
        }
        return Optional.ofNullable(requests.selectOneByQuery(query))
                .map(GotoneDeliveryStore::receipt).orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
    }

    public List<Receipt> recent() {
        return requests.selectListByQuery(requestSummary()
                        .orderBy(GOTONE_REQUEST.ACCEPTED_AT.desc(), GOTONE_REQUEST.ID.desc()).limit(100))
                .stream().map(GotoneDeliveryStore::receipt).toList();
    }

    public List<Delivery> deliveries(UUID request) {
        var rows = deliveries.selectListByQuery(QueryWrapper.create()
                .select(GOTONE_DELIVERY.ID, GOTONE_DELIVERY.CHANNEL, GOTONE_DELIVERY.MASKED_TARGET, GOTONE_DELIVERY.STATUS)
                .where(GOTONE_DELIVERY.REQUEST_ID.eq(request))
                .orderBy(GOTONE_DELIVERY.CHANNEL.asc(), GOTONE_DELIVERY.ID.asc()));
        Map<String, List<Attempt>> attempts = jdbc.sql("""
                SELECT a.* FROM gotone_delivery_attempt a
                JOIN gotone_delivery d ON d.id = a.delivery_id WHERE d.request_id = ?
                ORDER BY a.delivery_id, a.number
                """).param(request).query(rs -> {
            Map<String, List<Attempt>> result = new HashMap<>();
            while (rs.next()) {
                var attempt = new Attempt(rs.getInt("number"), rs.getString("account_code"),
                        rs.getObject("config_version", Integer.class), rs.getString("status"), rs.getString("reason"),
                        rs.getString("remote_id"), instant(rs, "started_at"), instant(rs, "finished_at"));
                result.computeIfAbsent(rs.getString("delivery_id"), ignored -> new ArrayList<>()).add(attempt);
            }
            return result;
        });
        return rows.stream().map(row -> new Delivery(row.getId(), Channel.valueOf(row.getChannel()), row.getMaskedTarget(),
                row.getStatus(), attempts.getOrDefault(row.getId(), List.of()))).toList();
    }

    public Map<String, Integer> triedAccounts(UUID delivery) {
        return jdbc.sql("""
                SELECT account_code, max(number) AS last_attempt FROM gotone_delivery_attempt
                WHERE delivery_id = ? AND account_code IS NOT NULL GROUP BY account_code
                """).param(delivery).query(rs -> {
            Map<String, Integer> accounts = new HashMap<>();
            while (rs.next()) {
                accounts.put(rs.getString("account_code"), rs.getInt("last_attempt"));
            }
            return accounts;
        });
    }

    public Optional<Work> lock(UUID id) {
        return jdbc.sql("""
                SELECT d.*, r.sender_id, r.expires_at FROM gotone_delivery d
                JOIN gotone_request r ON r.id = d.request_id WHERE d.id = ? FOR UPDATE OF d
                """).param(id).query((rs, row) -> new Work(id, rs.getObject("map_id", UUID.class),
                Channel.valueOf(rs.getString("channel")), rs.getString("status"), rs.getInt("attempt_no"),
                rs.getString("sender_id"), instant(rs, "expires_at"),
                json.readValue(rs.getString("snapshot"), Snapshot.class))).optional();
    }

    public int start(Work work, String account, Integer version) {
        int number = work.attemptNo() + 1;
        var row = new GotoneDeliveryDO();
        row.setAttemptNo(number);
        row.setStatus("SENDING");
        deliveries.updateByQuery(row, byId(work.id()));
        jdbc.sql("""
                INSERT INTO gotone_delivery_attempt (delivery_id, number, account_code, config_version, status)
                VALUES (?, ?, ?, ?, 'SENDING')
                """).params(work.id(), number, account, version).update();
        return number;
    }

    public void finish(UUID id, int attempt, String status, String reason, String remoteId) {
        var query = byId(id).and(GOTONE_DELIVERY.ATTEMPT_NO.eq(attempt)).and(GOTONE_DELIVERY.STATUS.eq("SENDING"));
        if (updateStatus(query, status) != 0) {
            jdbc.sql("""
                    UPDATE gotone_delivery_attempt SET status = ?, reason = ?, remote_id = ?, finished_at = now()
                    WHERE delivery_id = ? AND number = ?
                    """).params(status, reason, remoteId, id, attempt).update();
        }
    }

    public void expire(UUID id) {
        updateStatus(byId(id).and(GOTONE_DELIVERY.STATUS.in(List.of("PENDING", "RETRY_WAIT"))), "EXPIRED");
    }

    public void retry(Work work, Retry retry, String actor) {
        jdbc.sql("INSERT INTO gotone_delivery_retry (id, delivery_id, actor, reason) VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID(), work.id(), actor, retry.reason()).update();
        updateStatus(byId(work.id()), "PENDING");
    }

    public void resolve(Work work, Resolution resolution, String actor) {
        if (!work.status().equals("UNKNOWN")) {
            throw new CommonException(REQUEST_CONFLICT);
        }
        jdbc.sql("""
                INSERT INTO gotone_delivery_resolution (delivery_id, attempt_no, accepted, reason, actor)
                VALUES (?, ?, ?, ?, ?)
                """).params(work.id(), work.attemptNo(), resolution.accepted(), resolution.reason(), actor).update();
        updateStatus(byId(work.id()), resolution.accepted() ? "SENT" : "FAILED");
    }

    private int updateStatus(QueryWrapper query, String status) {
        var row = new GotoneDeliveryDO();
        row.setStatus(status);
        return deliveries.updateByQuery(row, query);
    }

    private static QueryWrapper requestSummary() {
        return QueryWrapper.create().select(GOTONE_REQUEST.ID, GOTONE_REQUEST.SERVICE_CODE, GOTONE_REQUEST.REVISION,
                GOTONE_REQUEST.ACCEPTED_AT, GOTONE_REQUEST.TARGET_COUNT, GOTONE_REQUEST.CONTENT_DIGEST);
    }

    private static QueryWrapper byId(UUID id) {
        return QueryWrapper.create().where(GOTONE_DELIVERY.ID.eq(Objects.requireNonNull(id)));
    }

    private static Receipt receipt(GotoneRequestDO row) {
        return new Receipt(row.getId(), row.getServiceCode(), row.getRevision(), row.getAcceptedAt().toInstant(),
                row.getTargetCount());
    }

    private static Instant instant(ResultSet result, String name) throws SQLException {
        OffsetDateTime timestamp = result.getObject(name, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
