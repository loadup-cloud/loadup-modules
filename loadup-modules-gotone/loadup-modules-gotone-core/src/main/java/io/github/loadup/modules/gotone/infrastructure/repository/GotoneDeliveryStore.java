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

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.GOTONE_NOT_FOUND;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.REQUEST_CONFLICT;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.Attempt;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Delivery;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Resolution;
import io.github.loadup.modules.gotone.client.Gotone.Retry;
import io.github.loadup.modules.gotone.client.Gotone.Target;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneDeliveryAttemptDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneDeliveryDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneDeliveryResolutionDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneDeliveryRetryDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneRequestDO;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneDeliveryStore {
    private final JdbcClient jdbc;
    private final JdbcAggregateTemplate aggregates;
    private final GotoneRows rows;
    private final JsonMapper json;

    public GotoneDeliveryStore(JdbcClient jdbc, JdbcAggregateTemplate aggregates, GotoneRows rows, JsonMapper json) {
        this.jdbc = jdbc;
        this.aggregates = aggregates;
        this.rows = rows;
        this.json = json;
    }

    private static final String REQUEST_SUMMARY =
            "SELECT id, service_code, revision, accepted_at, target_count, content_digest FROM gotone_request";

    private static Receipt receipt(GotoneRequestDO row) {
        return new Receipt(
                row.getId(),
                row.getServiceCode(),
                row.getRevision(),
                instant(row.getAcceptedAt()),
                row.getTargetCount());
    }

    private static Attempt attempt(GotoneDeliveryAttemptDO row) {
        return new Attempt(
                row.getNumber(),
                row.getAccountCode(),
                row.getConfigVersion(),
                row.getStatus(),
                row.getReason(),
                row.getRemoteId(),
                instant(row.getStartedAt()),
                instant(row.getFinishedAt()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public Optional<StoredRequest> existing(String source, String key) {
        return jdbc.sql(REQUEST_SUMMARY + " WHERE source = :source AND request_key = :key")
                .param("source", source)
                .param("key", key)
                .query(GotoneRequestDO.class)
                .optional()
                .map(row -> new StoredRequest(receipt(row), row.getContentDigest()));
    }

    public Receipt insertRequest(
            String source,
            String key,
            String digest,
            String code,
            int revision,
            String sender,
            Map<String, String> parameters,
            Instant accepted,
            Instant expires,
            int targetCount) {
        var row = rows.create(new GotoneRequestDO());
        row.setSource(source);
        row.setRequestKey(key);
        row.setContentDigest(digest);
        row.setServiceCode(code);
        row.setRevision(revision);
        row.setSenderId(sender);
        row.setParameters(json.writeValueAsString(parameters));
        row.setAcceptedAt(utc(accepted));
        row.setExpiresAt(utc(expires));
        row.setTargetCount(targetCount);
        aggregates.insert(row);
        return receipt(row);
    }

    public String insertDelivery(
            String requestId,
            String mapId,
            Channel channel,
            String targetKey,
            Snapshot snapshot,
            String sender,
            Instant expires) {
        var row = rows.create(new GotoneDeliveryDO());
        row.setRequestId(requestId);
        row.setMapId(mapId);
        row.setChannel(channel.name());
        row.setTargetKey(targetKey);
        String address = snapshot.target().address();
        row.setMaskedTarget("••••" + address.substring(Math.max(0, address.length() - 4)));
        row.setSnapshot(json.writeValueAsString(snapshot));
        row.setStatus("PENDING");
        row.setAttemptNo(0);
        row.setSenderId(sender);
        row.setExpiresAt(utc(expires));
        aggregates.insert(row);
        return row.getId();
    }

    public Receipt receipt(String id, String source) {
        return jdbc.sql(REQUEST_SUMMARY + " WHERE id = :id AND (:source IS NULL OR source = :source)")
                .param("id", id)
                .param("source", source, Types.VARCHAR)
                .query(GotoneRequestDO.class)
                .optional()
                .map(GotoneDeliveryStore::receipt)
                .orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
    }

    public List<Receipt> recent() {
        return jdbc
                .sql(REQUEST_SUMMARY + " ORDER BY accepted_at DESC, id DESC LIMIT 100")
                .query(GotoneRequestDO.class)
                .list()
                .stream()
                .map(GotoneDeliveryStore::receipt)
                .toList();
    }

    public List<Delivery> deliveries(String request) {
        var targets = jdbc.sql(
                        "SELECT id, channel, masked_target, status FROM gotone_delivery WHERE request_id = :id ORDER BY channel, id")
                .param("id", request)
                .query(GotoneDeliveryDO.class)
                .list();
        if (targets.isEmpty()) {
            return List.of();
        }
        var history = jdbc
                .sql("SELECT * FROM gotone_delivery_attempt WHERE delivery_id IN (:ids) ORDER BY number")
                .param("ids", targets.stream().map(GotoneDeliveryDO::getId).toList())
                .query(GotoneDeliveryAttemptDO.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        GotoneDeliveryAttemptDO::getDeliveryId,
                        Collectors.mapping(GotoneDeliveryStore::attempt, Collectors.toList())));
        return targets.stream()
                .map(row -> new Delivery(
                        row.getId(),
                        Channel.valueOf(row.getChannel()),
                        row.getMaskedTarget(),
                        row.getStatus(),
                        history.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    public Map<String, Integer> triedAccounts(String delivery) {
        var entries = jdbc.sql(
                        "SELECT account_code, number FROM gotone_delivery_attempt WHERE delivery_id = :id AND account_code IS NOT NULL")
                .param("id", delivery)
                .query(GotoneDeliveryAttemptDO.class)
                .list();
        var tried = new HashMap<String, Integer>();
        for (var entry : entries) {
            tried.merge(entry.getAccountCode(), entry.getNumber(), Math::max);
        }
        return tried;
    }

    public Optional<Work> lock(String id) {
        return jdbc.sql("SELECT * FROM gotone_delivery WHERE id = :id FOR UPDATE")
                .param("id", Objects.requireNonNull(id))
                .query(GotoneDeliveryDO.class)
                .optional()
                .map(row -> new Work(
                        id,
                        row.getMapId(),
                        Channel.valueOf(row.getChannel()),
                        row.getStatus(),
                        row.getAttemptNo(),
                        row.getSenderId(),
                        instant(row.getExpiresAt()),
                        json.readValue(row.getSnapshot(), Snapshot.class)));
    }

    public int start(Work work, String account, Integer version) {
        int number = work.attemptNo() + 1;
        jdbc.sql("""
                    UPDATE gotone_delivery SET attempt_no = :number, status = 'SENDING', updated_at = :now
                    WHERE id = :id
                    """)
                .param("number", number)
                .param("now", now())
                .param("id", work.id())
                .update();
        var attempt = rows.create(new GotoneDeliveryAttemptDO());
        attempt.setDeliveryId(work.id());
        attempt.setNumber(number);
        attempt.setAccountCode(account);
        attempt.setConfigVersion(version);
        attempt.setStatus("SENDING");
        attempt.setStartedAt(utc(rows.clock().instant()));
        aggregates.insert(attempt);
        return number;
    }

    public void finish(String id, int attempt, String status, String reason, String remoteId) {
        finish(id, attempt, status, status, reason, remoteId);
    }

    public void finish(
            String id, int attempt, String deliveryStatus, String attemptStatus, String reason, String remoteId) {
        if (updateAttemptStatus(id, attempt, "SENDING", deliveryStatus) == 0) {
            return;
        }
        var now = now();
        int changed = jdbc.sql("""
                    UPDATE gotone_delivery_attempt SET status = :status, reason = COALESCE(:reason, reason),
                        remote_id = COALESCE(:remoteId, remote_id), finished_at = :now, updated_at = :now
                    WHERE delivery_id = :id AND number = :number
                    """)
                .param("status", attemptStatus)
                .param("reason", reason)
                .param("remoteId", remoteId)
                .param("now", now)
                .param("id", id)
                .param("number", attempt)
                .update();
        if (changed != 1) {
            throw new CommonException(REQUEST_CONFLICT);
        }
    }

    public void failWaiting(String id, int attempt) {
        updateAttemptStatus(id, attempt, "RETRY_WAIT", "FAILED");
    }

    public void expire(String id) {
        jdbc.sql("""
                    UPDATE gotone_delivery SET status = 'EXPIRED', updated_at = :now
                    WHERE id = :id AND status IN ('PENDING', 'RETRY_WAIT')
                    """).param("now", now()).param("id", id).update();
    }

    public void retry(Work work, Retry retry, String actor) {
        var row = rows.create(new GotoneDeliveryRetryDO());
        row.setDeliveryId(work.id());
        row.setActor(actor);
        row.setReason(retry.reason());
        row.setRequestedAt(row.getCreatedAt());
        aggregates.insert(row);
        updateStatus(work.id(), "PENDING");
    }

    public void resolve(Work work, Resolution resolution, String actor) {
        if (!work.status().equals("UNKNOWN")) {
            throw new CommonException(REQUEST_CONFLICT);
        }
        var row = rows.create(new GotoneDeliveryResolutionDO());
        row.setDeliveryId(work.id());
        row.setAttemptNo(work.attemptNo());
        row.setAccepted(resolution.accepted());
        row.setReason(resolution.reason());
        row.setActor(actor);
        row.setResolvedAt(row.getCreatedAt());
        aggregates.insert(row);
        updateStatus(work.id(), resolution.accepted() ? "SENT" : "FAILED");
    }

    private void updateStatus(String id, String status) {
        jdbc.sql("UPDATE gotone_delivery SET status = :status, updated_at = :now WHERE id = :id")
                .param("status", status)
                .param("now", now())
                .param("id", id)
                .update();
    }

    private int updateAttemptStatus(String id, int attempt, String expected, String status) {
        return jdbc.sql("""
                    UPDATE gotone_delivery SET status = :status, updated_at = :now
                    WHERE id = :id AND attempt_no = :attempt AND status = :expected
                    """)
                .param("status", status)
                .param("now", now())
                .param("id", id)
                .param("attempt", attempt)
                .param("expected", expected)
                .update();
    }

    private LocalDateTime now() {
        return utc(rows.clock().instant());
    }

    public record StoredRequest(Receipt receipt, String digest) {}

    public record Snapshot(Target target, Map<String, String> fields, Map<String, String> parameters) {}

    public record Work(
            String id,
            String mapId,
            Channel channel,
            String status,
            int attemptNo,
            String senderId,
            Instant expiresAt,
            Snapshot snapshot) {}
}
