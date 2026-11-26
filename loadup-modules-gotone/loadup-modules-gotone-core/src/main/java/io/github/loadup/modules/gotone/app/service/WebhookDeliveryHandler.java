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
package io.github.loadup.modules.gotone.app.service;

import io.github.loadup.modules.gotone.app.integration.GotoneProviders;
import io.github.loadup.modules.gotone.app.integration.GotoneSecrets;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.transaction.support.TransactionTemplate;

public final class WebhookDeliveryHandler extends ExternalDeliveryHandler {
    public WebhookDeliveryHandler(GotoneDeliveryStore store, GotoneConfigurationStore configuration,
            GotoneProviders providers, GotoneSecrets secrets, Clock clock, TransactionTemplate tx,
            ExecutorService transport) {
        super(Channel.WEBHOOK, store, configuration, providers, secrets, clock, tx, transport);
    }

    @Override
    protected boolean acceptsAccount(Work work, Account account) {
        return work.snapshot().target().address().equals(account.accountCode());
    }

    @Override
    protected Account resolveDestination(Account account) {
        if (!account.settings().containsKey("destinationRef")) return account;
        String url = secrets.resolve(account.settings().get("destinationRef"));
        if (url == null || url.isBlank()) return null;
        return new Account(account.accountCode(), account.channel(), account.adapter(), account.enabled(),
                account.priority(), account.secretRef(), account.connectTimeoutMs(), account.requestTimeoutMs(),
                account.version(), Map.of("url", url));
    }
}
