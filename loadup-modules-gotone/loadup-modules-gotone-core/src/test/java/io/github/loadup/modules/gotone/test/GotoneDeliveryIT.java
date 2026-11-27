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
package io.github.loadup.modules.gotone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.task.TaskDispatcher;
import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.testcontainers.annotation.ContainerType;
import io.github.loadup.components.testcontainers.annotation.EnableTestContainers;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.app.service.GotoneDeliveryHandler;
import io.github.loadup.modules.gotone.app.service.InboxDeliveryHandler;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import io.github.loadup.modules.gotone.client.Gotone.Submit;
import io.github.loadup.modules.gotone.client.Gotone.Target;
import io.github.loadup.modules.gotone.client.Gotone.Template;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.gotone.client.facade.GotoneOperations;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = GotoneDeliveryIT.Application.class,
        properties = {
            "loadup.task.worker-enabled=false",
            "loadup.database.schema=gotone_it",
            "loadup.flyway.locations=classpath:db/migration/modules/inbox,classpath:db/migration/modules/gotone",
            "GOTONE_SECRET_TEST=fixture"
        })
@EnableTestContainers(value = ContainerType.POSTGRESQL, reuse = false)
@Sql(
        scripts = {"classpath:db/schema/sequence_allocation.sql", "classpath:db/task/pg/schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class GotoneDeliveryIT {
    @Autowired
    GotoneFacade gotone;

    @Autowired
    GotoneOperations operations;

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SendLog log;

    @Autowired
    InboxFacade inbox;

    @Autowired
    InboxReceiver receiver;

    @Autowired
    InboxDeliveryHandler inboxHandler;

    @Autowired
    List<GotoneDeliveryHandler> handlers;

    @MockitoSpyBean
    GotoneDeliveryStore deliveryStore;

    @Autowired
    PlatformTransactionManager transactions;

    private static Account account(Channel channel, Map<String, String> settings, int priority) {
        return new Account(
                "fixture-" + channel.name(),
                channel,
                "fixture",
                true,
                priority,
                "env:GOTONE_SECRET_TEST",
                1000,
                2000,
                0,
                settings);
    }

    @Test
    void inboxMapperUsesTheJdbcTransactionAndEnforcesTheRecipientAndIdempotencyKey() {
        String recipient = "orm-recipient";
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            receiver.receive("request", recipient, null, "NOTICE", "Title", "Body", null);
            assertThat(inbox.unreadCount(recipient)).isOne();
            status.setRollbackOnly();
        });
        assertThat(inbox.unreadCount(recipient)).isZero();

        try {
            receiver.receive("request", recipient, null, "NOTICE", "Title", "Body", null);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                receiver.receive("request", recipient, null, "NOTICE", "Duplicate", "Body", null);
                assertThat(inbox.unreadCount(recipient)).isOne();
            });
            var listed = inbox.list(recipient, true, 1, 20).getData();
            assertThat(listed).singleElement().satisfies(item -> {
                assertThat(item.title()).isEqualTo("Title");
                assertThat(item.readAt()).isNull();
            });
            String id = listed.iterator().next().id();
            assertThatThrownBy(() -> inbox.markRead("another-recipient", id)).isInstanceOf(CommonException.class);
            inbox.markRead(recipient, id);
            inbox.markRead(recipient, id);
            assertThat(inbox.unreadCount(recipient)).isZero();
            inbox.archive(recipient, id);
            assertThat(inbox.list(recipient, false, 1, 20).getData()).isEmpty();
        } finally {
            jdbc.update("DELETE FROM inbox_message WHERE recipient_id = ?", recipient);
        }
    }

    @Test
    void restoringASmsRevisionCopiesItsTemplateAndBinding() {
        String code = "restore-sms";
        String account = "restore-account";
        operations.saveAccount(new Account(
                account, Channel.SMS, "fixture", false, 100, "env:GOTONE_SECRET_TEST", 1000, 2000, 0, Map.of()));
        var template = new Template(Channel.SMS, Map.of("body", "Order ${orderNo}"));
        var route = new Route(code, "Orders", null, true, 0, List.of("orderNo"), List.of(template));
        operations.publish(route, "admin");
        var variables = Map.of("order", "orderNo");
        var binding = new SmsBinding(code, 1, account, "template-1", null, variables);
        operations.saveSmsBinding(binding);
        assertThatThrownBy(() -> operations.publish(route, "admin")).isInstanceOf(CommonException.class);
        operations.publish(
                new Route(
                        code,
                        "Orders",
                        null,
                        true,
                        1,
                        List.of(),
                        List.of(new Template(Channel.SMS, Map.of("body", "Updated")))),
                "admin");

        var restored = operations.restore(code, 1, 2, "admin");
        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.channels()).containsExactly(template);
        assertThat(operations.routes()).contains(restored);
        assertThat(operations.smsBindings())
                .contains(binding, new SmsBinding(code, 3, account, "template-1", null, variables));
    }

    @Test
    void dispatchesFanOutConcurrentlyAndStopsFallbackWhenTheResultIsUncertain() throws Exception {
        assertThat(handlers)
                .extracting(GotoneDeliveryHandler::taskType)
                .containsExactlyInAnyOrder(
                        "gotone.deliver.sms",
                        "gotone.deliver.email",
                        "gotone.deliver.push",
                        "gotone.deliver.inbox",
                        "gotone.deliver.webhook");

        Account sms = account(Channel.SMS, Map.of(), 100);
        operations.saveAccount(sms);
        operations.saveAccount(new Account(
                "preferred-sms",
                Channel.SMS,
                sms.adapter(),
                true,
                1,
                sms.secretRef(),
                sms.connectTimeoutMs(),
                sms.requestTimeoutMs(),
                0,
                Map.of()));
        operations.saveAccount(account(Channel.EMAIL, Map.of(), 100));
        var pushSettings = Map.of("platform", "ANDROID", "appId", "buyer-app");
        operations.saveAccount(account(Channel.PUSH, pushSettings, 100));
        for (var entry : Map.of(
                        "backup-email",
                        Channel.EMAIL,
                        "unused-sms",
                        Channel.SMS,
                        "second-push",
                        Channel.PUSH,
                        "first-webhook",
                        Channel.WEBHOOK,
                        "second-webhook",
                        Channel.WEBHOOK)
                .entrySet()) {
            Channel channel = entry.getValue();
            operations.saveAccount(new Account(
                    entry.getKey(),
                    channel,
                    "fixture",
                    true,
                    200,
                    "env:GOTONE_SECRET_TEST",
                    1000,
                    2000,
                    0,
                    channel == Channel.PUSH ? pushSettings : Map.of()));
        }
        var routes = List.of(
                new Template(Channel.SMS, Map.of("body", "Order ${orderNo}")),
                new Template(Channel.EMAIL, Map.of("subject", "Order ${orderNo}", "text", "Successful")),
                new Template(Channel.PUSH, Map.of("title", "Order", "body", "Successful")),
                new Template(Channel.WEBHOOK, Map.of("endpointCodes", "first-webhook,second-webhook", "payload", "{}")),
                new Template(
                        Channel.IN_APP,
                        Map.of("category", "ORDER", "title", "Order ${orderNo}", "body", "Successful")));
        var draft = new Route("servicecodeA", "Orders", null, true, 0, List.of("orderNo"), routes);
        operations.saveDraft(draft, "admin");
        assertThat(operations.drafts("admin")).containsExactly(draft);
        operations.publish(draft, "admin");
        assertThat(operations.drafts("admin")).isEmpty();
        var targets = Map.of(
                Channel.SMS,
                List.of(Target.address("+8613800000000")),
                Channel.EMAIL,
                List.of(Target.address("buyer@example.org")),
                Channel.PUSH,
                List.of(
                        new Target("token-42", "ANDROID", "buyer-app", "fixture-PUSH"),
                        new Target("token-42", "ANDROID", "buyer-app", "second-push")),
                Channel.IN_APP,
                List.of(Target.address("user-42")));
        var command = new Submit("servicecodeA", "order-42", Map.of("orderNo", "42"), targets, null);
        var ambiguous = new HashMap<>(targets);
        ambiguous.put(Channel.PUSH, List.of(new Target("token-42", "ANDROID", "buyer-app")));
        assertThatThrownBy(() -> gotone.submit(
                        "orders",
                        null,
                        new Submit("servicecodeA", "ambiguous", Map.of("orderNo", "42"), ambiguous, null)))
                .isInstanceOf(CommonException.class);
        Receipt receipt;
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Receipt> submit = () -> {
                start.await(10, TimeUnit.SECONDS);
                return gotone.submit("orders", null, command);
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            receipt = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(receipt);
        }
        assertThat(receipt.targetCount()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loadup_task", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message", Integer.class))
                .isZero();

        operations.publish(new Route("servicecodeA", "Orders", null, false, 1, List.of(), List.of()), "admin");
        assertThat(gotone.submit("orders", null, command)).isEqualTo(receipt);
        assertThatThrownBy(() -> gotone.submit(
                        "orders", null, new Submit("servicecodeA", "order-42", Map.of("orderNo", "43"), targets, null)))
                .isInstanceOf(CommonException.class);
        var queued = gotone.status("orders", receipt.requestId());
        String deliveryId = queued.deliveries().stream()
                .filter(row -> row.channel() == Channel.IN_APP)
                .findFirst()
                .orElseThrow()
                .id();
        var task = new TaskExecution(
                "inbox-fixture",
                TaskSubmission.immediate(inboxHandler.taskType(), deliveryId, deliveryId),
                Instant.now(),
                null);
        var failure = new IllegalStateException("Fixture: unable to finish the inbox delivery");
        doThrow(failure).when(deliveryStore).finish(deliveryId, 1, "SENT", null, null);
        assertThatThrownBy(() -> inboxHandler.handle(task)).isSameAs(failure);
        assertThat(inbox.unreadCount("user-42")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM gotone_delivery WHERE id = ?", String.class, deliveryId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM gotone_delivery_attempt WHERE delivery_id = ?",
                        Integer.class,
                        deliveryId))
                .isZero();
        doCallRealMethod().when(deliveryStore).finish(deliveryId, 1, "SENT", null, null);

        dispatcher.execute();

        var delivery = gotone.status("orders", receipt.requestId());
        assertThat(delivery.deliveries()).hasSize(7);
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() == Channel.EMAIL))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("UNKNOWN");
                    assertThat(row.attempts())
                            .singleElement()
                            .satisfies(
                                    attempt -> assertThat(attempt.accountCode()).isEqualTo("fixture-EMAIL"));
                });
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() == Channel.SMS))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("SENT");
                    assertThat(row.attempts())
                            .extracting(attempt -> attempt.accountCode() + ":" + attempt.status())
                            .containsExactly("preferred-sms:FAILED", "fixture-SMS:SENT");
                });
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() == Channel.PUSH))
                .extracting(row -> row.attempts().getFirst().accountCode() + ":" + row.status())
                .containsExactlyInAnyOrder("fixture-PUSH:SENT", "second-push:SENT");
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() == Channel.WEBHOOK))
                .extracting(row -> row.attempts().getFirst().accountCode() + ":" + row.status())
                .containsExactlyInAnyOrder("first-webhook:SENT", "second-webhook:FAILED");
        assertThat(log.calls)
                .containsEntry("fixture-SMS", "+8613800000000")
                .containsEntry("fixture-EMAIL", "buyer@example.org")
                .doesNotContainKeys("backup-email", "unused-sms");
        assertThat(jdbc.queryForObject("SELECT title FROM inbox_message WHERE recipient_id = 'user-42'", String.class))
                .isEqualTo("Order 42");
        dispatcher.execute();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> gotone.status("other", receipt.requestId())).isInstanceOf(CommonException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        SendLog sendLog() {
            return new SendLog();
        }

        @Bean
        GotoneProvider smsFixture(SendLog log) {
            return provider(Channel.SMS, log);
        }

        @Bean
        GotoneProvider emailFixture(SendLog log) {
            return provider(Channel.EMAIL, log);
        }

        @Bean
        GotoneProvider pushFixture(SendLog log) {
            return provider(Channel.PUSH, log);
        }

        @Bean
        GotoneProvider webhookFixture(SendLog log) {
            return provider(Channel.WEBHOOK, log);
        }

        private GotoneProvider provider(Channel channel, SendLog log) {
            return new GotoneProvider() {
                @Override
                public String code() {
                    return "fixture";
                }

                @Override
                public Channel channel() {
                    return channel;
                }

                @Override
                public Set<String> settings() {
                    return Set.of("appId", "platform");
                }

                @Override
                public boolean valid(Account account) {
                    return true;
                }

                @Override
                public Outcome send(Account account, String secret, Work work, Binding binding) {
                    String code = account.accountCode();
                    log.calls.put(code, work.snapshot().target().address());
                    var barrier = log.fanOut.get(channel);
                    if (barrier != null) {
                        barrier.countDown();
                        try {
                            if (!barrier.await(5, TimeUnit.SECONDS)) {
                                return Outcome.failed("FIXTURE_FAN_OUT_SERIALIZED");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return Outcome.unknown("FIXTURE_INTERRUPTED");
                        }
                    }
                    if (channel == Channel.EMAIL) {
                        return Outcome.unknown("FIXTURE_TIMEOUT");
                    }
                    if (code.equals("preferred-sms") || code.equals("second-webhook")) {
                        return Outcome.failed("FIXTURE_REJECTED");
                    }
                    return Outcome.sent("fixture-id");
                }
            };
        }
    }

    static final class SendLog {
        final Map<String, String> calls = new ConcurrentHashMap<>();
        final Map<Channel, CountDownLatch> fanOut =
                Map.of(Channel.PUSH, new CountDownLatch(2), Channel.WEBHOOK, new CountDownLatch(2));
    }
}
