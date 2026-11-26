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
package io.github.loadup.modules.inbox.app;

import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import io.github.loadup.modules.inbox.domain.InboxGateway;
import io.github.loadup.modules.inbox.infrastructure.InboxPersistenceAutoConfiguration;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(InboxDTOConverterImpl.class)
@AutoConfiguration(after = InboxPersistenceAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnBean(InboxGateway.class)
@ConditionalOnProperty(
        prefix = "loadup.modules.inbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InboxFacade.class)
    public InboxService inboxService(InboxGateway repository, InboxDTOConverter converter) {
        return new InboxService(repository, converter);
    }

    @Bean
    InboxReceiver inboxReceiver(InboxGateway repository, Clock clock) {
        return new InboxReceiverService(repository, clock);
    }
}
