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
package io.github.loadup.modules.form.web;

import io.github.loadup.components.authorization.config.AuthorizationAutoConfiguration;
import io.github.loadup.modules.form.app.FormAutoConfiguration;
import io.github.loadup.modules.form.client.FormCatalog;
import io.github.loadup.modules.form.client.FormRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {FormAutoConfiguration.class, AuthorizationAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({FormCatalog.class, FormRuntime.class, AuthorizationAutoConfiguration.class})
public class FormWebAutoConfiguration {
    @Bean
    FormController formController(FormCatalog catalog, FormRuntime runtime) {
        return new FormController(catalog, runtime);
    }
}
