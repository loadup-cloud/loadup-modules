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
package io.github.loadup.modules.inbox.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.components.runtime.time.ClockAutoConfiguration;
import io.github.loadup.modules.inbox.app.InboxAutoConfiguration;
import io.github.loadup.modules.inbox.app.InboxDTOConverter;
import io.github.loadup.modules.inbox.app.InboxService;
import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import io.github.loadup.modules.inbox.domain.InboxGateway;
import io.github.loadup.modules.inbox.infrastructure.InboxPersistenceAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class InboxAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(IdGenerator.class, () -> () -> "notification-id")
            .withConfiguration(AutoConfigurations.of(
                    ClockAutoConfiguration.class,
                    InboxPersistenceAutoConfiguration.class,
                    InboxAutoConfiguration.class));

    @Test
    void wiresDomainPortAndApplicationMapper() {
        runner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(InboxGateway.class)
                    .hasSingleBean(InboxDTOConverter.class)
                    .hasSingleBean(InboxService.class)
                    .hasSingleBean(InboxReceiver.class);
        });
    }

    @Test
    void consumerGatewayOverridesDefaultAdapter() {
        InboxGateway gateway = mock(InboxGateway.class);
        runner.withBean(InboxGateway.class, () -> gateway).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InboxGateway.class).hasSingleBean(InboxService.class);
            assertThat(context.getBean(InboxGateway.class)).isSameAs(gateway);
        });
    }

    @Test
    void aCustomInboxQueryFacadeDoesNotDisableReceivingDeliveries() {
        var custom = mock(InboxFacade.class);
        runner.withBean(InboxFacade.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InboxFacade.class).hasSingleBean(InboxReceiver.class);
            assertThat(context.getBean(InboxFacade.class)).isSameAs(custom);
        });
    }

    @Test
    void respectsDisabledFeature() {
        runner.withPropertyValues("loadup.modules.inbox.enabled=false").run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(InboxGateway.class)
                    .doesNotHaveBean(InboxService.class)
                    .doesNotHaveBean(InboxReceiver.class);
        });
    }
}
