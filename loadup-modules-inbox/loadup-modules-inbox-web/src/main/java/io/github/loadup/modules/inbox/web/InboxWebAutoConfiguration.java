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
package io.github.loadup.modules.inbox.web;

import io.github.loadup.modules.inbox.app.InboxAutoConfiguration;
import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = InboxAutoConfiguration.class)
@ConditionalOnBean(InboxFacade.class)
@ConditionalOnProperty(prefix = "loadup.modules.inbox.web", name = "enabled", matchIfMissing = true)
@Import(InboxWebConverterImpl.class)
public class InboxWebAutoConfiguration {
    @Bean
    InboxController inboxController(InboxFacade inbox, InboxWebConverter converter) {
        return new InboxController(inbox, converter);
    }
}
