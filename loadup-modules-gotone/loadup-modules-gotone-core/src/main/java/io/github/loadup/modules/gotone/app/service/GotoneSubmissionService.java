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
package io.github.loadup.modules.gotone.app.service;

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.*;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.gotone.client.Gotone.*;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Snapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneSubmissionService implements GotoneFacade {
    private record Input(String serviceCode, String senderId, Map<String, String> parameters,
                         Map<Channel, List<Target>> recipients, Instant expiresAt) {}
    private record Planned(UUID mapId, Channel channel, Snapshot snapshot) {}

    private final GotoneConfigurationStore configuration;
    private final GotoneDeliveryStore store;
    private final TaskTemplate tasks;
    private final Clock clock;
    private final JsonMapper json;
    private final GotoneTemplates templates;
    private final TransactionTemplate tx;
    private final GotoneDeliveryReconciliation reconciliation;

    public GotoneSubmissionService(GotoneConfigurationStore configuration, GotoneDeliveryStore store, TaskTemplate tasks,
                                   Clock clock, JsonMapper json, GotoneTemplates templates, TransactionTemplate tx,
                                   GotoneDeliveryReconciliation reconciliation) {
        this.configuration = configuration;
        this.store = store;
        this.tasks = tasks;
        this.clock = clock;
        this.json = json;
        this.templates = templates;
        this.tx = tx;
        this.reconciliation = reconciliation;
    }

    @Override
    public Receipt submit(String source, String senderId, Submit command) {
        if (command == null) throw new CommonException(INVALID_SUBMISSION);
        String code = serviceCode(command.serviceCode());
        String key = required(command.requestKey(), 128);
        String caller = required(source, 128);
        String sender = senderId == null ? null : required(senderId, 64);
        Map<String, String> parameters = parameters(command.parameters());
        var recipients = recipients(command.recipientsByChannel());
        String digest = digest(json.writeValueAsString(new Input(code, sender, parameters, recipients, command.expiresAt())));
        return tx.execute(transaction -> {
            store.lockRequest(caller, key);
            var existing = store.existing(caller, key);
            if (existing.isPresent()) {
                if (!existing.get().digest().equals(digest)) throw new CommonException(REQUEST_CONFLICT);
                return existing.get().receipt();
            }
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant expires = command.expiresAt() == null ? now.plus(Duration.ofDays(1)) : command.expiresAt();
            if (!now.isBefore(expires)) throw new CommonException(INVALID_SUBMISSION);
            var revision = configuration.active(code).orElseThrow(() -> new CommonException(NO_CHANNEL_ROUTES));
            if (!parameters.keySet().containsAll(revision.parameters())) throw new CommonException(INVALID_TEMPLATE);
            Set<Channel> expected = revision.templates().stream().map(item -> item.template().channel())
                    .filter(channel -> channel != Channel.WEBHOOK).collect(Collectors.toSet());
            if (!expected.equals(recipients.keySet())) throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
            List<Planned> planned = new ArrayList<>();
            for (var item : revision.templates()) {
                Channel channel = item.template().channel();
                var fields = templates.render(item.template(), parameters);
                var targets = channel == Channel.WEBHOOK
                        ? List.of(Target.address(fields.get("endpointCode"))) : recipients.get(channel);
                for (Target target : targets) {
                    if (channel == Channel.PUSH && fields.containsKey("platform")
                            && !fields.get("platform").equals(target.platform())) throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
                    var snapshot = new Snapshot(target, fields, channel == Channel.SMS ? parameters : Map.of());
                    planned.add(new Planned(item.id(), channel, snapshot));
                }
            }
            if (planned.isEmpty()) throw new CommonException(NO_CHANNEL_ROUTES);
            var receipt = new Receipt(UUID.randomUUID().toString(), code, revision.number(), now, planned.size());
            store.insertRequest(receipt, caller, key, digest, sender, expires, parameters);
            for (Planned plan : planned) {
                UUID id = UUID.randomUUID();
                store.insertDelivery(id, receipt.requestId(), plan.mapId(), plan.channel(),
                        digest(json.writeValueAsString(plan.snapshot().target())), plan.snapshot());
                tasks.submit(TaskSubmission.immediate(taskType(plan.channel()), id.toString(), id.toString()));
            }
            return receipt;
        });
    }

    @Override
    public Request status(String source, String requestId) {
        return read(store.receipt(id(requestId), required(source, 128)));
    }

    public Request request(String requestId) {
        return read(store.receipt(id(requestId), null));
    }

    private Request read(Receipt receipt) {
        var deliveries = store.deliveries(id(receipt.requestId()));
        boolean changed = false;
        for (Delivery delivery : deliveries) {
            changed |= reconciliation.sync(delivery);
        }
        return new Request(receipt, changed ? store.deliveries(id(receipt.requestId())) : deliveries);
    }

    public static String taskType(Channel channel) {
        return "gotone.deliver." + (channel == Channel.IN_APP ? "inbox" : channel.name().toLowerCase(Locale.ROOT));
    }

    static String serviceCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw new CommonException(INVALID_SERVICE_CODE);
        return value;
    }

    static String required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf(0) >= 0) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        return value;
    }

    static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CommonException(GOTONE_NOT_FOUND);
        }
    }

    static Map<String, String> parameters(Map<String, String> input) {
        Map<String, String> result = new TreeMap<>();
        if (input == null) return result;
        if (input.size() > 50) throw new CommonException(INVALID_TEMPLATE);
        input.forEach((name, value) -> {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || value == null
                    || value.length() > 4000 || value.indexOf(0) >= 0) throw new CommonException(INVALID_TEMPLATE);
            result.put(name, value);
        });
        return result;
    }

    private static Map<Channel, List<Target>> recipients(Map<Channel, List<Target>> input) {
        var normalized = new EnumMap<Channel, List<Target>>(Channel.class);
        if (input == null) return normalized;
        input.forEach((channel, targets) -> {
            if (channel == null || channel == Channel.WEBHOOK || targets == null || targets.isEmpty() || targets.size() > 100) {
                throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
            }
            normalized.put(channel, targets.stream().map(target -> normalize(channel, target)).distinct()
                    .sorted(Comparator.comparing(Target::address)
                            .thenComparing(target -> target.platform() == null ? "" : target.platform())
                            .thenComparing(target -> target.appId() == null ? "" : target.appId())).toList());
        });
        return normalized;
    }

    private static Target normalize(Channel channel, Target target) {
        if (target == null || target.address() == null) throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        String value = target.address().strip();
        if (value.isEmpty() || value.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        boolean valid = switch (channel) {
            case IN_APP -> value.length() <= 64;
            case EMAIL -> value.length() <= 254 && value.matches("[^@,;<>]+@[^@,;<>]+");
            case SMS -> value.matches("\\+[1-9][0-9]{2,14}");
            case PUSH -> value.length() <= 4096 && target.platform() != null
                    && Set.of("ANDROID", "IOS", "WEB").contains(target.platform())
                    && target.appId() != null && target.appId().matches("[A-Za-z0-9_.:-]{1,128}");
            case WEBHOOK -> false;
        };
        if (!valid) throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        return channel == Channel.PUSH ? new Target(value, target.platform(), target.appId()) : Target.address(value);
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
