/*
 * Copyright (C) 2026 LoadUp Cloud
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
package io.github.loadup.modules.notification.app.service;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.components.gotone.config.ChannelConfigProvider;
import io.github.loadup.components.gotone.config.ChannelConfigProvider.ChannelConfig;
import io.github.loadup.components.gotone.config.NotificationRouting;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.notification.app.converter.NotificationSubmissionConverter;
import io.github.loadup.modules.notification.client.command.NotificationDispatchCommand;
import io.github.loadup.modules.notification.client.command.NotificationSubmitCommand;
import io.github.loadup.modules.notification.client.dto.NotificationAcceptedDTO;
import io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum;
import io.github.loadup.modules.notification.client.facade.NotificationFacade;
import io.github.loadup.modules.notification.domain.gateway.NotificationSubmissionGateway;
import io.github.loadup.modules.notification.domain.model.RoutedNotificationContent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

public class NotificationSubmissionService implements NotificationFacade {
    public static final String DELIVERY_EVENT = "notification.delivery";
    public static final String LEGACY_DELIVERY_EVENT = "notification.in-app.delivery";
    private final NotificationSubmissionGateway gateway;
    private final TaskTemplate tasks;
    private final IdGenerator ids;
    private final JsonMapper json;
    private final NotificationSubmissionConverter converter;
    private final Clock clock;
    private final Optional<ChannelConfigProvider> channelConfig;

    public NotificationSubmissionService(
            NotificationSubmissionGateway gateway,
            TaskTemplate tasks,
            IdGenerator ids,
            JsonMapper json,
            NotificationSubmissionConverter converter,
            Clock clock,
            Optional<ChannelConfigProvider> channelConfig) {
        this.gateway = gateway;
        this.tasks = tasks;
        this.ids = ids;
        this.json = json;
        this.converter = converter;
        this.clock = clock;
        this.channelConfig = channelConfig;
    }

    @Override
    @Transactional
    public NotificationAcceptedDTO submit(String source, NotificationSubmitCommand command) {
        validateSubmission(source, command);
        var content = converter.toContent(command);
        return accept(source, command.requestKey(), json.writeValueAsString(content), content.expiresAt(),
                (id, at) -> content.recipients().forEach(recipient -> enqueue(id, "IN_APP", recipient, null, at)));
    }

    @Override
    @Transactional
    public NotificationAcceptedDTO dispatch(String source, String senderId, NotificationDispatchCommand command) {
        validateSubmission(source, command);
        var content = new RoutedNotificationContent(
                command.serviceCode(), senderId, command.title(), command.body(),
                command.actionUrl(), command.expiresAt(), command.recipientsByChannel());
        return accept(source, command.requestKey(), json.writeValueAsString(content), content.expiresAt(),
                (id, at) -> enqueueRoutes(id, content, at));
    }

    private NotificationAcceptedDTO accept(
            String source, String key, String payload, LocalDateTime expiresAt,
            BiConsumer<String, LocalDateTime> enqueue) {
        String digest = digest(payload);
        var at = LocalDateTime.ofInstant(clock.instant(), clock.getZone()).truncatedTo(ChronoUnit.MICROS);
        String id = ids.generate();
        boolean inserted = gateway.insert(id, source, key, digest, payload, at);
        var receipt = gateway.find(source, key);
        if (!digest.equals(receipt.contentDigest())) {
            throw new CommonException(NotificationResultCodeEnum.REQUEST_CONFLICT);
        }
        if (!inserted) {
            return converter.toAccepted(receipt, true);
        }
        if (expiresAt != null && !expiresAt.isAfter(at)) {
            throw new CommonException(NotificationResultCodeEnum.NOTIFICATION_EXPIRED);
        }
        enqueue.accept(receipt.notificationId(), at);
        return converter.toAccepted(receipt, false);
    }

    private void enqueueRoutes(String notificationId, RoutedNotificationContent content, LocalDateTime at) {
        var routes = new LinkedHashMap<String, ChannelConfig>();
        channelConfig.ifPresent(config -> config.findEnabledByServiceCode(content.serviceCode()).forEach(route -> {
            if (NotificationRouting.MANAGED_CHANNELS.contains(route.channel())) {
                routes.putIfAbsent(route.channel(), route);
            }
        }));
        if (routes.isEmpty()) {
            throw new CommonException(NotificationResultCodeEnum.NO_CHANNEL_ROUTES);
        }
        if (!routes.keySet().equals(content.recipientsByChannel().keySet())) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_CHANNEL_RECIPIENTS);
        }
        routes.forEach((channel, route) -> {
            String snapshot = json.writeValueAsString(route);
            content.recipientsByChannel().get(channel)
                    .forEach(recipient -> enqueue(notificationId, channel, recipient, snapshot, at));
        });
    }

    private void enqueue(String notificationId, String channel, String recipient, String route, LocalDateTime at) {
        String deliveryId = ids.generate();
        String eventId = tasks.submit(TaskSubmission.immediate(DELIVERY_EVENT, deliveryId, deliveryId));
        gateway.addDelivery(deliveryId, notificationId, recipient, channel, eventId, route, at);
    }

    private static void validateSubmission(String source, Object command) {
        if (source == null || source.isBlank() || source.length() > 128 || command == null) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_SUBMISSION);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
