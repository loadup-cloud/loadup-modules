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

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.GOTONE_NOT_FOUND;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_CHANNEL_RECIPIENTS;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_SERVICE_CODE;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_SUBMISSION;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_TEMPLATE;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.NO_CHANNEL_ROUTES;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.REQUEST_CONFLICT;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Delivery;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Request;
import io.github.loadup.modules.gotone.client.Gotone.Submit;
import io.github.loadup.modules.gotone.client.Gotone.Target;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneSubmissionService implements GotoneFacade {
    private final GotoneConfigurationStore configuration;
    private final GotoneDeliveryStore store;
    private final TaskTemplate tasks;
    private final Clock clock;
    private final JsonMapper json;
    private final GotoneTemplates templates;
    private final TransactionTemplate tx;
    private final GotoneDeliveryReconciliation reconciliation;

    public GotoneSubmissionService(
            GotoneConfigurationStore configuration,
            GotoneDeliveryStore store,
            TaskTemplate tasks,
            Clock clock,
            JsonMapper json,
            GotoneTemplates templates,
            TransactionTemplate tx,
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

    public static String taskType(Channel channel) {
        return "gotone.deliver."
                + (channel == Channel.IN_APP ? "inbox" : channel.name().toLowerCase(Locale.ROOT));
    }

    static String serviceCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) {
            throw new CommonException(INVALID_SERVICE_CODE);
        }
        return value;
    }

    static String required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf(0) >= 0) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        return value;
    }

    private static Receipt replay(GotoneDeliveryStore.StoredRequest stored, String digest) {
        if (!stored.digest().equals(digest)) {
            throw new CommonException(REQUEST_CONFLICT);
        }
        return stored.receipt();
    }

    static String id(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new CommonException(GOTONE_NOT_FOUND);
        }
        return value;
    }

    static Map<String, String> parameters(Map<String, String> input) {
        Map<String, String> result = new TreeMap<>();
        if (input == null) {
            return result;
        }
        if (input.size() > 50) {
            throw new CommonException(INVALID_TEMPLATE);
        }
        input.forEach((name, value) -> {
            if (name == null
                    || !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                    || value == null
                    || value.length() > 4000
                    || value.indexOf(0) >= 0) {
                throw new CommonException(INVALID_TEMPLATE);
            }
            result.put(name, value);
        });
        return result;
    }

    private static Map<Channel, List<Target>> recipients(Map<Channel, List<Target>> input) {
        var normalized = new EnumMap<Channel, List<Target>>(Channel.class);
        if (input == null) {
            return normalized;
        }
        input.forEach((channel, targets) -> {
            if (channel == null
                    || channel == Channel.WEBHOOK
                    || targets == null
                    || targets.isEmpty()
                    || targets.size() > 100) {
                throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
            }
            normalized.put(
                    channel,
                    targets.stream()
                            .map(target -> normalize(channel, target))
                            .distinct()
                            .sorted(Comparator.comparing((Target target) -> target.address())
                                    .thenComparing(target -> target.platform() == null ? "" : target.platform())
                                    .thenComparing(target -> target.appId() == null ? "" : target.appId())
                                    .thenComparing(target -> target.accountCode() == null ? "" : target.accountCode()))
                            .toList());
        });
        return normalized;
    }

    private static Target normalize(Channel channel, Target target) {
        if (target == null || target.address() == null) {
            throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        }
        String value = target.address().strip();
        if (value.isEmpty()
                || value.chars()
                        .anyMatch(
                                character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        }
        boolean valid = switch (channel) {
            case IN_APP -> value.length() <= 64;
            case EMAIL -> value.length() <= 254 && value.matches("[^@,;<>]+@[^@,;<>]+");
            case SMS -> value.matches("\\+[1-9][0-9]{2,14}");
            case PUSH ->
                value.length() <= 4096
                        && target.platform() != null
                        && Set.of("ANDROID", "IOS", "WEB").contains(target.platform())
                        && target.appId() != null
                        && target.appId().matches("[A-Za-z0-9_.:-]{1,128}")
                        && (target.accountCode() == null
                                || target.accountCode().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"));
            case WEBHOOK -> false;
        };
        if (!valid) {
            throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        }
        return channel == Channel.PUSH
                ? new Target(value, target.platform(), target.appId(), target.accountCode())
                : Target.address(value);
    }

    private static String digest(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Receipt submit(String source, String senderId, Submit command) {
        if (command == null) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        String code = serviceCode(command.serviceCode());
        String key = required(command.requestKey(), 128);
        String caller = required(source, 128);
        String sender = senderId == null ? null : required(senderId, 64);
        Map<String, String> parameters = parameters(command.parameters());
        var recipients = recipients(command.recipientsByChannel());
        var input = new Input(code, sender, parameters, recipients, command.expiresAt());
        String digest = digest(json.writeValueAsString(input));
        try {
            return tx.execute(status -> accept(caller, key, digest, input));
        } catch (DuplicateKeyException conflict) {
            // The unique-key winner is visible in a fresh transaction after our insert rolls back.
            return tx.execute(status -> replay(store.existing(caller, key).orElseThrow(() -> conflict), digest));
        }
    }

    private Receipt accept(String source, String key, String digest, Input input) {
        var existing = store.existing(source, key);
        if (existing.isPresent()) {
            return replay(existing.get(), digest);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expires = input.expiresAt() == null ? now.plus(Duration.ofDays(1)) : input.expiresAt();
        if (!now.isBefore(expires)) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var revision =
                configuration.active(input.serviceCode()).orElseThrow(() -> new CommonException(NO_CHANNEL_ROUTES));
        var planned = plan(revision, input);
        var receipt = store.insertRequest(
                source,
                key,
                digest,
                input.serviceCode(),
                revision.number(),
                input.senderId(),
                input.parameters(),
                now,
                expires,
                planned.size());
        for (Planned target : planned) {
            String id = store.insertDelivery(
                    receipt.requestId(),
                    target.mapId(),
                    target.channel(),
                    digest(json.writeValueAsString(target.snapshot().target())),
                    target.snapshot(),
                    input.senderId(),
                    expires);
            tasks.submit(TaskSubmission.immediate(taskType(target.channel()), id, id));
        }
        return receipt;
    }

    private List<Planned> plan(GotoneConfigurationStore.Revision revision, Input input) {
        if (!input.parameters().keySet().containsAll(revision.parameters())) {
            throw new CommonException(INVALID_TEMPLATE);
        }
        Set<Channel> expected = revision.templates().stream()
                .map(item -> item.template().channel())
                .filter(channel -> channel != Channel.WEBHOOK)
                .collect(Collectors.toSet());
        if (!expected.equals(input.recipients().keySet())) {
            throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        }
        List<Account> pushAccounts = expected.contains(Channel.PUSH)
                ? configuration.accounts().stream()
                        .filter(account -> account.channel() == Channel.PUSH)
                        .toList()
                : List.of();
        List<Planned> planned = new ArrayList<>();
        for (var item : revision.templates()) {
            Channel channel = item.template().channel();
            var fields = templates.render(item.template(), input.parameters());
            var targets = channel == Channel.WEBHOOK
                    ? GotoneTemplates.webhookEndpoints(fields).stream()
                            .map(Target::address)
                            .toList()
                    : input.recipients().get(channel);
            for (Target target : targets) {
                if (channel == Channel.PUSH
                        && fields.containsKey("platform")
                        && !fields.get("platform").equals(target.platform())) {
                    throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
                }
                Target destination = channel == Channel.PUSH ? pushTarget(target, pushAccounts) : target;
                var snapshot =
                        new Snapshot(destination, fields, channel == Channel.SMS ? input.parameters() : Map.of());
                planned.add(new Planned(item.id(), channel, snapshot));
            }
        }
        if (planned.isEmpty()) {
            throw new CommonException(NO_CHANNEL_ROUTES);
        }
        return planned.stream().distinct().toList();
    }

    private static Target pushTarget(Target target, List<Account> accounts) {
        var matching = accounts.stream()
                .filter(account -> PushDeliveryHandler.matchesTarget(target, account))
                .filter(account -> target.accountCode() != null || account.enabled())
                .toList();
        if (matching.size() > 1 || (target.accountCode() != null && matching.isEmpty())) {
            throw new CommonException(INVALID_CHANNEL_RECIPIENTS);
        }
        return matching.isEmpty()
                ? target
                : new Target(
                        target.address(),
                        target.platform(),
                        target.appId(),
                        matching.getFirst().accountCode());
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

    private record Input(
            String serviceCode,
            String senderId,
            Map<String, String> parameters,
            Map<Channel, List<Target>> recipients,
            Instant expiresAt) {}

    private record Planned(String mapId, Channel channel, Snapshot snapshot) {}
}
