package io.github.loadup.modules.notification.app.service;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.gotone.NotificationService;
import io.github.loadup.components.gotone.config.ChannelConfigProvider.ChannelConfig;
import io.github.loadup.components.gotone.model.NotificationRequest;
import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum;
import io.github.loadup.modules.notification.domain.gateway.NotificationSubmissionGateway;
import io.github.loadup.modules.notification.domain.model.NotificationContent;
import io.github.loadup.modules.notification.domain.model.NotificationDelivery;
import io.github.loadup.modules.notification.domain.model.RoutedNotificationContent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public class NotificationDeliveryHandler implements TaskHandler {
    private final String taskType;
    private final NotificationSubmissionGateway gateway;
    private final NotificationService gotone;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public NotificationDeliveryHandler(
            String taskType, NotificationSubmissionGateway gateway, NotificationService gotone,
            JsonMapper json, Clock clock, TransactionTemplate transactions) {
        this.taskType = taskType;
        this.gateway = gateway;
        this.gotone = gotone;
        this.json = json;
        this.clock = clock;
        this.transactions = transactions;
    }

    @Override
    public String taskType() {
        return taskType;
    }

    @Override
    public void handle(TaskExecution event) {
        if (event == null || !Objects.equals(taskType, event.taskType())
                || !Objects.equals(event.bizId(), event.payload())) {
            throw invalidEvent();
        }
        var delivery = load(event, false);
        if ("IN_APP".equals(delivery.channel())) {
            transactions.executeWithoutResult(status -> deliver(load(event, true)));
        } else {
            deliver(delivery);
        }
    }

    private NotificationDelivery load(TaskExecution event, boolean lock) {
        var delivery = gateway.findDelivery(event.bizId(), lock)
                .orElseThrow(NotificationDeliveryHandler::invalidEvent);
        if (!Objects.equals(event.id(), delivery.eventId())) {
            throw invalidEvent();
        }
        return delivery;
    }

    private void deliver(NotificationDelivery delivery) {
        if (!"PENDING".equals(delivery.status())) {
            return;
        }

        ChannelConfig route;
        String serviceCode;
        LocalDateTime expiresAt;
        var params = new HashMap<String, Object>();
        params.put("requestKey", "delivery:" + delivery.id());
        if (delivery.routeJson() == null) {
            var content = json.readValue(delivery.contentJson(), NotificationContent.class);
            if (!"IN_APP".equals(delivery.channel()) || !delivery.channel().equals(content.channel())
                    || !content.recipients().contains(delivery.recipientId())) {
                throw invalidEvent();
            }
            route = new ChannelConfig(delivery.channel(), null, List.of(), null, Map.of());
            serviceCode = content.sceneCode();
            expiresAt = content.expiresAt();
            putContent(params, content.title(), content.body(), content.actionUrl());
        } else {
            var content = json.readValue(delivery.contentJson(), RoutedNotificationContent.class);
            route = json.readValue(delivery.routeJson(), ChannelConfig.class);
            if (!delivery.channel().equals(route.channel())
                    || !content.recipientsByChannel().getOrDefault(delivery.channel(), List.of())
                            .contains(delivery.recipientId())) {
                throw invalidEvent();
            }
            serviceCode = content.serviceCode();
            expiresAt = content.expiresAt();
            putContent(params, content.title(), content.body(), content.actionUrl());
            if (content.senderId() != null) {
                params.put("senderId", content.senderId());
            }
        }
        params.put("category", serviceCode);

        LocalDateTime now = LocalDateTime.now(clock);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            gateway.finish(delivery.id(), "EXPIRED", now);
            return;
        }
        var request = NotificationRequest.builder()
                .serviceCode(serviceCode)
                .requestId(delivery.id())
                .receivers(List.of(delivery.recipientId()))
                .templateParams(params)
                .build();
        if (!gotone.sendChannel(request, route).success()) {
            throw new CommonException(NotificationResultCodeEnum.CHANNEL_DELIVERY_FAILED);
        }
        gateway.finish(delivery.id(), "DELIVERED", LocalDateTime.now(clock));
    }

    private static void putContent(Map<String, Object> params, String title, String body, String actionUrl) {
        params.put("title", title);
        params.put("body", body);
        if (actionUrl != null) {
            params.put("actionUrl", actionUrl);
        }
    }

    private static CommonException invalidEvent() {
        return new CommonException(NotificationResultCodeEnum.INVALID_DELIVERY_EVENT);
    }
}
