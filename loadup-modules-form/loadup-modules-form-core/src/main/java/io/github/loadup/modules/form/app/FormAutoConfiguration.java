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
package io.github.loadup.modules.form.app;

import io.github.loadup.modules.form.client.FormCatalog;
import io.github.loadup.modules.form.client.FormRuntime;
import io.github.loadup.modules.form.infrastructure.FormStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "loadup.modules.form", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FormAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    FormStore formStore(DataSource dataSource) {
        return new FormStore(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    FormCatalog formCatalog(FormStore store) {
        return new FormCatalogService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    FormRuntime formRuntime(FormStore store) {
        return new FormRuntimeService(store);
    }
}
