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
package io.github.loadup.modules.notification.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.gotone.NotificationChannelProvider;
import io.github.loadup.components.gotone.config.ChannelConfigProvider;
import io.github.loadup.components.gotone.config.ChannelConfigProvider.ChannelConfig;
import io.github.loadup.components.gotone.model.ChannelSendRequest;
import io.github.loadup.components.gotone.model.ChannelSendResponse;
import io.github.loadup.components.task.TaskDispatcher;
import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.testcontainers.annotation.ContainerType;
import io.github.loadup.components.testcontainers.annotation.EnableTestContainers;
import io.github.loadup.modules.notification.app.service.NotificationDeliveryHandler;
import io.github.loadup.modules.notification.client.command.NotificationDispatchCommand;
import io.github.loadup.modules.notification.client.command.NotificationSubmitCommand;
import io.github.loadup.modules.notification.client.facade.NotificationFacade;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = NotificationSubmissionIT.Application.class,
        properties = {"loadup.task.worker-enabled=false", "spring.flyway.enabled=true"})
@EnableTestContainers(ContainerType.POSTGRESQL)
@Sql(
        scripts = {"classpath:db/schema/sequence_allocation.sql", "classpath:db/task/pg/schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class NotificationSubmissionIT {
    private static final String SOURCE = "notification-submission-it";

    private final NotificationFacade notifications;
    private final JdbcTemplate jdbc;
    private final TaskDispatcher dispatcher;
    private final NotificationDeliveryHandler deliveryHandler;
    private final PlatformTransactionManager transactions;
    private final RoutingFixture fixture;

    @Autowired
    NotificationSubmissionIT(
            NotificationFacade notifications,
            JdbcTemplate jdbc,
            TaskDispatcher dispatcher,
            @Qualifier("notificationDeliveryHandler") NotificationDeliveryHandler deliveryHandler,
            PlatformTransactionManager transactions,
            RoutingFixture fixture) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.deliveryHandler = deliveryHandler;
        this.transactions = transactions;
        this.fixture = fixture;
    }

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM notification_inbox");
        jdbc.update("DELETE FROM notification_delivery");
        jdbc.update("DELETE FROM notification_request");
        jdbc.update("DELETE FROM loadup_task");
        fixture.routes().set(List.of());
        fixture.delivered().clear();
    }

    @Test
    void submitPersistsInboxOnlyWhenDispatcherDelivers() {
        var accepted = notifications.submit(SOURCE, command("submit-dispatch", "user-submit", "Hello"));

        assertThat(count("notification_request")).isEqualTo(1);
        assertThat(count("notification_delivery")).isEqualTo(1);
        assertThat(count("loadup_task")).isEqualTo(1);
        assertThat(inboxCount("user-submit")).isZero();
        assertThat(deliveryStatus(accepted.notificationId())).isEqualTo("PENDING");

        dispatcher.execute();

        assertThat(deliveryStatus(accepted.notificationId())).isEqualTo("DELIVERED");
        assertThat(inboxCount("user-submit")).isEqualTo(1);
    }

    @Test
    void dispatchFreezesFourConfiguredChannelsAndUsesTheCorrectRecipientForEach() {
        fixture.routes().set(List.of("IN_APP", "EMAIL", "SMS", "PUSH").stream()
                .map(channel -> new ChannelConfig(channel, null, List.of(), null, Map.of()))
                .toList());
        var command = new NotificationDispatchCommand(
                "four-channels", "servicecodeA", "Title", "Body", null, null,
                Map.of("IN_APP", List.of("user-1"), "EMAIL", List.of("user@example.test"),
                        "SMS", List.of("+8613800000000"), "PUSH", List.of("test-device-token")));
        var accepted = notifications.dispatch(SOURCE, "operator-1", command);
        assertThat(jdbc.queryForList(
                "SELECT channel FROM notification_delivery WHERE notification_id = ?", String.class,
                accepted.notificationId())).containsExactlyInAnyOrder("IN_APP", "EMAIL", "SMS", "PUSH");
        assertThat(count("loadup_task")).isEqualTo(4);

        fixture.routes().set(List.of());
        assertThat(notifications.dispatch(SOURCE, "operator-1", command).duplicate()).isTrue();
        dispatcher.execute();

        assertThat(inboxCount("user-1")).isEqualTo(1);
        assertThat(fixture.delivered()).containsExactlyInAnyOrderEntriesOf(
                Map.of("EMAIL", "user@example.test", "SMS", "+8613800000000", "PUSH", "test-device-token"));
        assertThat(jdbc.queryForList(
                "SELECT status FROM notification_delivery WHERE notification_id = ?", String.class,
                accepted.notificationId())).containsOnly("DELIVERED").hasSize(4);
    }

    @Test
    void replayWithSameSourceKeyAndContentReturnsStableIdWithoutAddingRecords() {
        var first = notifications.submit(SOURCE, command("replay", "user-replay", "Same body"));
        var second = notifications.submit(SOURCE, command("replay", "user-replay", "Same body"));

        assertThat(second.notificationId()).isEqualTo(first.notificationId());
        assertThat(second.duplicate()).isTrue();
        assertThat(count("notification_request")).isEqualTo(1);
        assertThat(count("notification_delivery")).isEqualTo(1);
        assertThat(count("loadup_task")).isEqualTo(1);
    }

    @Test
    void replayWithDifferentBodyConflictsWithoutAddingRecords() {
        notifications.submit(SOURCE, command("conflict", "user-conflict", "Original body"));

        assertThatThrownBy(() -> notifications.submit(SOURCE, command("conflict", "user-conflict", "Changed body")))
                .isInstanceOf(CommonException.class);
        assertThat(count("notification_request")).isEqualTo(1);
        assertThat(count("notification_delivery")).isEqualTo(1);
        assertThat(count("loadup_task")).isEqualTo(1);
    }

    @Test
    void outerRollbackRemovesRequestDeliveryAndTaskTogether() {
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.execute(status -> {
                    notifications.submit(SOURCE, command("outer-rollback", "user-rollback", "Rollback"));
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("notification_request")).isZero();
        assertThat(count("notification_delivery")).isZero();
        assertThat(count("loadup_task")).isZero();
    }

    @Test
    void handlingTheSameTaskTwiceDoesNotDuplicateInboxMessage() {
        var accepted = notifications.submit(SOURCE, command("handler-replay", "user-handler", "Once"));
        String eventId = jdbc.queryForObject(
                "SELECT event_id FROM notification_delivery WHERE notification_id = ?",
                String.class,
                accepted.notificationId());
        String deliveryId = jdbc.queryForObject(
                "SELECT id FROM notification_delivery WHERE notification_id = ?",
                String.class,
                accepted.notificationId());
        var event = new TaskExecution(
                eventId,
                TaskSubmission.immediate("notification.delivery", deliveryId, deliveryId),
                Instant.now(),
                null);

        deliveryHandler.handle(event);
        deliveryHandler.handle(event);

        assertThat(inboxCount("user-handler")).isEqualTo(1);
        assertThat(deliveryStatus(accepted.notificationId())).isEqualTo("DELIVERED");
    }

    @Test
    void sameKeyFromDifferentSourcesIsAcceptedIndependently() {
        var first = notifications.submit("source-one", command("shared-key", "user-one", "One"));
        var second = notifications.submit("source-two", command("shared-key", "user-two", "Two"));

        assertThat(second.notificationId()).isNotEqualTo(first.notificationId());
        assertThat(count("notification_request")).isEqualTo(2);
        assertThat(count("notification_delivery")).isEqualTo(2);
        assertThat(count("loadup_task")).isEqualTo(2);
    }

    @Test
    void expiredNewRequestRollsBackAllSubmissionRows() {
        var expired = new NotificationSubmitCommand(
                "expired",
                "billing",
                List.of("user-expired"),
                "IN_APP",
                "Expired",
                "Too late",
                null,
                LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> notifications.submit(SOURCE, expired)).isInstanceOf(CommonException.class);

        assertThat(count("notification_request")).isZero();
        assertThat(count("notification_delivery")).isZero();
        assertThat(count("loadup_task")).isZero();
    }

    @Test
    void concurrentSubmissionsConvergeOnOneRequest() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> submit = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return notifications
                        .submit(SOURCE, command("concurrent", "user-race", "Once"))
                        .notificationId();
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("notification_request")).isEqualTo(1);
        assertThat(count("notification_delivery")).isEqualTo(1);
        assertThat(count("loadup_task")).isEqualTo(1);
    }

    @Test
    void recipientOrderingAndWhitespaceDoNotChangeRequestIdentity() {
        var first = notifications.submit(
                SOURCE,
                new NotificationSubmitCommand(
                        "normalized",
                        "billing",
                        List.of(" user-b ", "user-a", "user-b"),
                        "IN_APP",
                        "Title",
                        "Body",
                        null,
                        null));
        var second = notifications.submit(
                SOURCE,
                new NotificationSubmitCommand(
                        "normalized", "billing", List.of("user-a", "user-b"), "IN_APP", "Title", "Body", null, null));
        assertThat(second.notificationId()).isEqualTo(first.notificationId());
        assertThat(second.acceptedAt()).isEqualTo(first.acceptedAt());
        assertThat(second.duplicate()).isTrue();
        assertThat(count("notification_delivery")).isEqualTo(2);
    }

    private NotificationSubmitCommand command(String key, String recipient, String body) {
        return new NotificationSubmitCommand(
                key, "billing", List.of(recipient), "IN_APP", "Notification", body, null, null);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long inboxCount(String recipient) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_inbox WHERE recipient_id = ? AND deleted = FALSE",
                Long.class,
                recipient);
    }

    private String deliveryStatus(String notificationId) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE notification_id = ?", String.class, notificationId);
    }

    private record RoutingFixture(
            AtomicReference<List<ChannelConfig>> routes, Map<String, String> delivered) {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        RoutingFixture routingFixture() {
            return new RoutingFixture(new AtomicReference<>(List.of()), new ConcurrentHashMap<>());
        }

        @Bean
        ChannelConfigProvider testRoutes(RoutingFixture fixture) {
            return code -> "servicecodeA".equals(code) ? fixture.routes().get() : List.of();
        }

        @Bean
        NotificationChannelProvider emailTestProvider(RoutingFixture fixture) {
            return channel("EMAIL", fixture);
        }

        @Bean
        NotificationChannelProvider smsTestProvider(RoutingFixture fixture) {
            return channel("SMS", fixture);
        }

        @Bean
        NotificationChannelProvider pushTestProvider(RoutingFixture fixture) {
            return channel("PUSH", fixture);
        }

        private NotificationChannelProvider channel(String type, RoutingFixture fixture) {
            return new NotificationChannelProvider() {
                @Override
                public String getChannelType() {
                    return type;
                }

                @Override
                public String getProviderName() {
                    return "fixture";
                }

                @Override
                public ChannelSendResponse send(ChannelSendRequest request) {
                    String recipient = request.receivers().getFirst();
                    fixture.delivered().put(type, recipient);
                    return new ChannelSendResponse(request.content(), 1, 0, Map.of(recipient, true), Map.of());
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }
            };
        }
    }
}
