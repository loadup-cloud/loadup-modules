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
import io.github.loadup.modules.gotone.client.Gotone.*;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.gotone.client.facade.GotoneOperations;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

@SpringBootTest(classes = GotoneDeliveryIT.Application.class, properties = {
        "loadup.task.worker-enabled=false", "loadup.flyway.enabled=false", "spring.flyway.enabled=false",
        "GOTONE_SECRET_TEST=fixture"
})
@EnableTestContainers(ContainerType.POSTGRESQL)
@Sql(scripts = {
        "classpath:db/schema/sequence_allocation.sql",
        "classpath:db/task/pg/schema.sql",
        "classpath:db/migration/modules/inbox/V20261126000001__create_inbox.sql",
        "classpath:db/migration/modules/gotone/V20261126000002__create_gotone.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class GotoneDeliveryIT {
    @Autowired GotoneFacade gotone;
    @Autowired GotoneOperations operations;
    @Autowired TaskDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired SendLog log;
    @Autowired InboxFacade inbox;
    @Autowired InboxReceiver receiver;
    @Autowired InboxDeliveryHandler inboxHandler;
    @Autowired List<GotoneDeliveryHandler> handlers;
    @MockitoSpyBean GotoneDeliveryStore deliveryStore;
    @Autowired PlatformTransactionManager transactions;

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
            receiver.receive("request", recipient, null, "NOTICE", "Duplicate", "Body", null);
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
    void publishesFourIndependentTargetsAndFreezesTheRenderedTemplates() {
        assertThat(handlers).extracting(GotoneDeliveryHandler::taskType).containsExactlyInAnyOrder(
                "gotone.deliver.sms", "gotone.deliver.email", "gotone.deliver.push",
                "gotone.deliver.inbox", "gotone.deliver.webhook");

        Account sms = account(Channel.SMS, Map.of(), 100);
        operations.saveAccount(sms);
        operations.saveAccount(new Account("preferred-sms", Channel.SMS, sms.adapter(), true, 1, sms.secretRef(),
                sms.connectTimeoutMs(), sms.requestTimeoutMs(), 0, Map.of()));
        operations.saveAccount(account(Channel.EMAIL, Map.of(), 100));
        operations.saveAccount(account(Channel.PUSH, Map.of("platform", "ANDROID", "appId", "buyer-app"), 100));
        var routes = List.of(
                new Template(Channel.SMS, Map.of("body", "Order ${orderNo}")),
                new Template(Channel.EMAIL, Map.of("subject", "Order ${orderNo}", "text", "Successful")),
                new Template(Channel.PUSH, Map.of("title", "Order", "body", "Successful")),
                new Template(Channel.IN_APP, Map.of("category", "ORDER", "title", "Order ${orderNo}", "body", "Successful")));
        operations.publish(new Route("servicecodeA", "Orders", null, true, 0, List.of("orderNo"), routes), "admin");
        var targets = Map.of(Channel.SMS, List.of(Target.address("+8613800000000")),
                Channel.EMAIL, List.of(Target.address("buyer@example.org")),
                Channel.PUSH, List.of(new Target("token-42", "ANDROID", "buyer-app")),
                Channel.IN_APP, List.of(Target.address("user-42")));
        var command = new Submit("servicecodeA", "order-42", Map.of("orderNo", "42"), targets, null);
        Receipt receipt = gotone.submit("orders", null, command);
        assertThat(receipt.targetCount()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loadup_task", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message", Integer.class)).isZero();

        operations.publish(new Route("servicecodeA", "Orders", null, false, 1, List.of(), List.of()), "admin");
        assertThat(gotone.submit("orders", null, command)).isEqualTo(receipt);
        assertThatThrownBy(() -> gotone.submit("orders", null,
                new Submit("servicecodeA", "order-42", Map.of("orderNo", "43"), targets, null)))
                .isInstanceOf(CommonException.class);
        var queued = gotone.status("orders", receipt.requestId());
        String inboxDelivery = queued.deliveries().stream()
                .filter(row -> row.channel() == Channel.IN_APP).findFirst().orElseThrow().id();
        var deliveryId = UUID.fromString(inboxDelivery);
        var task = new TaskExecution("inbox-fixture",
                TaskSubmission.immediate(inboxHandler.taskType(), inboxDelivery, inboxDelivery), Instant.now(), null);
        var failure = new IllegalStateException("Fixture: unable to finish the inbox delivery");
        doThrow(failure).when(deliveryStore).finish(deliveryId, 1, "SENT", null, null);
        assertThatThrownBy(() -> inboxHandler.handle(task)).isSameAs(failure);
        assertThat(inbox.unreadCount("user-42")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM gotone_delivery WHERE id = ?", String.class, deliveryId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gotone_delivery_attempt WHERE delivery_id = ?",
                Integer.class, deliveryId)).isZero();
        doCallRealMethod().when(deliveryStore).finish(deliveryId, 1, "SENT", null, null);

        dispatcher.execute();

        var delivery = gotone.status("orders", receipt.requestId());
        assertThat(delivery.deliveries()).hasSize(4);
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() == Channel.EMAIL).findFirst().orElseThrow().status())
                .isEqualTo("UNKNOWN");
        assertThat(delivery.deliveries().stream().filter(row -> row.channel() != Channel.EMAIL).map(Delivery::status))
                .containsExactly("SENT", "SENT", "SENT");
        assertThat(log.values).containsEntry(Channel.SMS, "preferred-sms:+8613800000000")
                .containsEntry(Channel.EMAIL, "fixture-EMAIL:buyer@example.org")
                .containsEntry(Channel.PUSH, "fixture-PUSH:token-42");
        assertThat(jdbc.queryForObject("SELECT title FROM inbox_message WHERE recipient_id = 'user-42'", String.class))
                .isEqualTo("Order 42");
        dispatcher.execute();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> gotone.status("other", receipt.requestId())).isInstanceOf(CommonException.class);
    }

    private static Account account(Channel channel, Map<String, String> settings, int priority) {
        return new Account("fixture-" + channel.name(), channel, "fixture", true, priority, "env:GOTONE_SECRET_TEST",
                1000, 2000, 0, settings);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean SendLog sendLog() { return new SendLog(); }
        @Bean GotoneProvider smsFixture(SendLog log) { return provider(Channel.SMS, log); }
        @Bean GotoneProvider emailFixture(SendLog log) { return provider(Channel.EMAIL, log); }
        @Bean GotoneProvider pushFixture(SendLog log) { return provider(Channel.PUSH, log); }

        private GotoneProvider provider(Channel channel, SendLog log) {
            return new GotoneProvider() {
                @Override public String code() { return "fixture"; }
                @Override public Channel channel() { return channel; }
                @Override public Set<String> settings() { return Set.of("appId", "platform"); }
                @Override public boolean valid(Account account) { return true; }
                @Override public Outcome send(Account account, String secret, Work work, Binding binding) {
                    log.values.put(channel, account.accountCode() + ":" + work.snapshot().target().address());
                    if (channel == Channel.EMAIL) return Outcome.unknown("FIXTURE_TIMEOUT");
                    return Outcome.sent("fixture-id");
                }
            };
        }
    }

    static final class SendLog {
        final Map<Channel, String> values = new ConcurrentHashMap<>();
    }
}
