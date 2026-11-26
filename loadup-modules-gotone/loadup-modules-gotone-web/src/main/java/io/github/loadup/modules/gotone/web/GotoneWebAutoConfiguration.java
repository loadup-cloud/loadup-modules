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
package io.github.loadup.modules.gotone.web;

import io.github.loadup.modules.gotone.app.autoconfigure.GotoneAutoConfiguration;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.gotone.client.facade.GotoneOperations;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = GotoneAutoConfiguration.class)
@ConditionalOnProperty(prefix = "loadup.modules.gotone.web", name = "enabled", matchIfMissing = true)
public class GotoneWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(GotoneFacade.class)
    GotoneSubmissionController gotoneSubmissionController(GotoneFacade gotone) {
        return new GotoneSubmissionController(gotone);
    }

    @Bean
    @ConditionalOnBean({GotoneFacade.class, GotoneOperations.class})
    GotoneOperationsController gotoneOperationsController(GotoneFacade gotone, GotoneOperations operations) {
        return new GotoneOperationsController(gotone, operations);
    }
}
