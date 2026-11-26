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
package io.github.loadup.modules.gotone.app.integration;

import io.github.loadup.components.kms.KmsSecretManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

public final class GotoneSecrets {
    private final ObjectProvider<KmsSecretManager> kms;
    private final Environment environment;

    public GotoneSecrets(ObjectProvider<KmsSecretManager> kms, Environment environment) {
        this.kms = kms;
        this.environment = environment;
    }

    public static boolean valid(String reference) {
        return reference != null
                && reference.matches("(?:env:GOTONE_SECRET_[A-Z0-9_]{1,100}|kms:[A-Za-z0-9_-]{1,128})");
    }

    public String resolve(String reference) {
        if (!valid(reference)) {
            return null;
        }
        String name = reference.substring(4);
        if (reference.startsWith("env:")) {
            return environment.getProperty(name);
        }
        KmsSecretManager manager = kms.getIfAvailable();
        return manager == null ? null : manager.get(name);
    }
}
