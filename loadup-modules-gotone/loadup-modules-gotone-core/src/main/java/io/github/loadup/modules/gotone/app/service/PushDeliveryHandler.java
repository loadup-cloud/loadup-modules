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
import java.util.concurrent.ExecutorService;
import org.springframework.transaction.support.TransactionTemplate;

public final class PushDeliveryHandler extends ExternalDeliveryHandler {
    public PushDeliveryHandler(
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            TransactionTemplate tx,
            ExecutorService transport) {
        super(Channel.PUSH, store, configuration, providers, secrets, clock, tx, transport);
    }

    @Override
    protected boolean acceptsAccount(Work work, Account account) {
        var target = work.snapshot().target();
        return target.platform().equals(account.settings().get("platform"))
                && target.appId().equals(account.settings().get("appId"));
    }
}
