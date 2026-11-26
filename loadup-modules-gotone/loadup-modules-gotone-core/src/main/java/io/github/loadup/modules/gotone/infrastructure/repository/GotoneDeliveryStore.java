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
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.*;

import com.mybatisflex.core.query.QueryWrapper;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.*;
import io.github.loadup.modules.gotone.infrastructure.dataobject.*;
import io.github.loadup.modules.gotone.infrastructure.mapper.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneDeliveryStore {
    public record StoredRequest(Receipt receipt, String digest) {}
    public record Snapshot(Target target, Map<String, String> fields, Map<String, String> parameters) {}
    public record Work(String id, String mapId, Channel channel, String status, int attemptNo,
                       String senderId, Instant expiresAt, Snapshot snapshot) {}

    private final GotoneRequestMapper requests;
    private final GotoneDeliveryMapper deliveries;
    private final GotoneDeliveryAttemptMapper attempts;
    private final GotoneDeliveryRetryMapper retries;
    private final GotoneDeliveryResolutionMapper resolutions;
    private final GotoneRows rows;
    private final JsonMapper json;

    public GotoneDeliveryStore(GotoneRequestMapper requests, GotoneDeliveryMapper deliveries,
            GotoneDeliveryAttemptMapper attempts, GotoneDeliveryRetryMapper retries,
            GotoneDeliveryResolutionMapper resolutions, GotoneRows rows, JsonMapper json) {
        this.requests = requests;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.retries = retries;
        this.resolutions = resolutions;
        this.rows = rows;
        this.json = json;
    }

    public Optional<StoredRequest> existing(String source, String key) {
        var query = requestSummary().where(GOTONE_REQUEST.SOURCE.eq(source)).and(GOTONE_REQUEST.REQUEST_KEY.eq(key));
        return Optional.ofNullable(requests.selectOneByQuery(query)).map(row -> new StoredRequest(receipt(row), row.getContentDigest()));
    }

    public Receipt insertRequest(String source, String key, String digest, String code, int revision, String sender,
            Map<String, String> parameters, Instant accepted, Instant expires, int targetCount) {
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
        requests.insert(row);
        return receipt(row);
    }

    public String insertDelivery(String requestId, String mapId, Channel channel, String targetKey, Snapshot snapshot,
            String sender, Instant expires) {
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
        deliveries.insert(row);
        return row.getId();
    }

    public Receipt receipt(String id, String source) {
        var query = requestSummary().where(GOTONE_REQUEST.ID.eq(id));
        if (source != null) query.and(GOTONE_REQUEST.SOURCE.eq(source));
        return Optional.ofNullable(requests.selectOneByQuery(query))
                .map(GotoneDeliveryStore::receipt).orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
    }

    public List<Receipt> recent() {
        return requests.selectListByQuery(requestSummary()
                        .orderBy(GOTONE_REQUEST.ACCEPTED_AT.desc(), GOTONE_REQUEST.ID.desc()).limit(100))
                .stream().map(GotoneDeliveryStore::receipt).toList();
    }

    public List<Delivery> deliveries(String request) {
        var targets = deliveries.selectListByQuery(QueryWrapper.create()
                .select(GOTONE_DELIVERY.ID, GOTONE_DELIVERY.CHANNEL, GOTONE_DELIVERY.MASKED_TARGET, GOTONE_DELIVERY.STATUS)
                .where(GOTONE_DELIVERY.REQUEST_ID.eq(request))
                .orderBy(GOTONE_DELIVERY.CHANNEL.asc(), GOTONE_DELIVERY.ID.asc()));
        if (targets.isEmpty()) return List.of();
        var history = attempts.selectListByQuery(QueryWrapper.create().where(GOTONE_DELIVERY_ATTEMPT.DELIVERY_ID
                        .in(targets.stream().map(GotoneDeliveryDO::getId).toList())).orderBy(GOTONE_DELIVERY_ATTEMPT.NUMBER.asc()))
                .stream().collect(Collectors.groupingBy(GotoneDeliveryAttemptDO::getDeliveryId,
                        Collectors.mapping(GotoneDeliveryStore::attempt, Collectors.toList())));
        return targets.stream().map(row -> new Delivery(row.getId(), Channel.valueOf(row.getChannel()), row.getMaskedTarget(),
                row.getStatus(), history.getOrDefault(row.getId(), List.of()))).toList();
    }

    public Map<String, Integer> triedAccounts(String delivery) {
        var entries = attempts.selectListByQuery(QueryWrapper.create().select(GOTONE_DELIVERY_ATTEMPT.ACCOUNT_CODE,
                        GOTONE_DELIVERY_ATTEMPT.NUMBER).where(GOTONE_DELIVERY_ATTEMPT.DELIVERY_ID.eq(delivery))
                .and(GOTONE_DELIVERY_ATTEMPT.ACCOUNT_CODE.isNotNull()));
        var tried = new HashMap<String, Integer>();
        for (var entry : entries) tried.merge(entry.getAccountCode(), entry.getNumber(), Math::max);
        return tried;
    }

    public Optional<Work> lock(String id) {
        return Optional.ofNullable(deliveries.selectOneByQuery(byId(id).forUpdate())).map(row -> new Work(id,
                row.getMapId(), Channel.valueOf(row.getChannel()), row.getStatus(), row.getAttemptNo(),
                row.getSenderId(), instant(row.getExpiresAt()), json.readValue(row.getSnapshot(), Snapshot.class)));
    }

    public int start(Work work, String account, Integer version) {
        int number = work.attemptNo() + 1;
        var patch = rows.touch(new GotoneDeliveryDO());
        patch.setAttemptNo(number);
        patch.setStatus("SENDING");
        deliveries.updateByQuery(patch, byId(work.id()));
        var attempt = rows.create(new GotoneDeliveryAttemptDO());
        attempt.setDeliveryId(work.id());
        attempt.setNumber(number);
        attempt.setAccountCode(account);
        attempt.setConfigVersion(version);
        attempt.setStatus("SENDING");
        attempt.setStartedAt(utc(rows.clock().instant()));
        attempts.insert(attempt);
        return number;
    }

    public void finish(String id, int attempt, String status, String reason, String remoteId) {
        var query = byId(id).and(GOTONE_DELIVERY.ATTEMPT_NO.eq(attempt)).and(GOTONE_DELIVERY.STATUS.eq("SENDING"));
        if (updateStatus(query, status) == 0) return;
        var patch = rows.touch(new GotoneDeliveryAttemptDO());
        patch.setStatus(status);
        patch.setReason(reason);
        patch.setRemoteId(remoteId);
        patch.setFinishedAt(utc(rows.clock().instant()));
        if (attempts.updateByQuery(patch, QueryWrapper.create().where(GOTONE_DELIVERY_ATTEMPT.DELIVERY_ID.eq(id))
                .and(GOTONE_DELIVERY_ATTEMPT.NUMBER.eq(attempt))) != 1) throw new CommonException(REQUEST_CONFLICT);
    }

    public void expire(String id) {
        updateStatus(byId(id).and(GOTONE_DELIVERY.STATUS.in(List.of("PENDING", "RETRY_WAIT"))), "EXPIRED");
    }

    public void retry(Work work, Retry retry, String actor) {
        var row = rows.create(new GotoneDeliveryRetryDO());
        row.setDeliveryId(work.id());
        row.setActor(actor);
        row.setReason(retry.reason());
        row.setRequestedAt(row.getCreatedAt());
        retries.insert(row);
        updateStatus(byId(work.id()), "PENDING");
    }

    public void resolve(Work work, Resolution resolution, String actor) {
        if (!work.status().equals("UNKNOWN")) throw new CommonException(REQUEST_CONFLICT);
        var row = rows.create(new GotoneDeliveryResolutionDO());
        row.setDeliveryId(work.id());
        row.setAttemptNo(work.attemptNo());
        row.setAccepted(resolution.accepted());
        row.setReason(resolution.reason());
        row.setActor(actor);
        row.setResolvedAt(row.getCreatedAt());
        resolutions.insert(row);
        updateStatus(byId(work.id()), resolution.accepted() ? "SENT" : "FAILED");
    }

    private int updateStatus(QueryWrapper query, String status) {
        var patch = rows.touch(new GotoneDeliveryDO());
        patch.setStatus(status);
        return deliveries.updateByQuery(patch, query);
    }

    private static QueryWrapper requestSummary() {
        return QueryWrapper.create().select(GOTONE_REQUEST.ID, GOTONE_REQUEST.SERVICE_CODE, GOTONE_REQUEST.REVISION,
                GOTONE_REQUEST.ACCEPTED_AT, GOTONE_REQUEST.TARGET_COUNT, GOTONE_REQUEST.CONTENT_DIGEST);
    }

    private static QueryWrapper byId(String id) {
        return QueryWrapper.create().where(GOTONE_DELIVERY.ID.eq(Objects.requireNonNull(id)));
    }

    private static Receipt receipt(GotoneRequestDO row) {
        return new Receipt(row.getId(), row.getServiceCode(), row.getRevision(), instant(row.getAcceptedAt()), row.getTargetCount());
    }

    private static Attempt attempt(GotoneDeliveryAttemptDO row) {
        return new Attempt(row.getNumber(), row.getAccountCode(), row.getConfigVersion(), row.getStatus(), row.getReason(),
                row.getRemoteId(), instant(row.getStartedAt()), instant(row.getFinishedAt()));
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
