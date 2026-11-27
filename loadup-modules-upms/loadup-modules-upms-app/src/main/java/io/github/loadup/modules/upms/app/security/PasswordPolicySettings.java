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
package io.github.loadup.modules.upms.app.security;

import io.github.loadup.commons.log.LogUtil;
import io.github.loadup.components.configcenter.DynamicConfig;
import io.github.loadup.modules.upms.app.autoconfigure.UpmsSecurityProperties;
import io.github.loadup.modules.upms.client.facade.PasswordPolicyFacade;
import io.github.loadup.modules.upms.client.validation.PasswordPolicy;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicySettings implements PasswordPolicyFacade {
    public static final String CONFIG_KEY = "loadup.modules.upms.security.password-strength";

    private final PasswordPolicy localPolicy;
    private volatile PasswordPolicy policy;

    public PasswordPolicySettings(UpmsSecurityProperties properties) {
        localPolicy = properties.getPasswordStrength();
        policy = localPolicy;
    }

    @DynamicConfig(CONFIG_KEY)
    void updateStrength(String value) {
        if (value == null) {
            policy = localPolicy;
            return;
        }
        try {
            policy = PasswordPolicy.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            LogUtil.warn(PasswordPolicySettings.class, "Ignoring invalid value for {}", CONFIG_KEY);
        }
    }

    @Override
    public PasswordPolicy currentPolicy() {
        return policy;
    }
}
