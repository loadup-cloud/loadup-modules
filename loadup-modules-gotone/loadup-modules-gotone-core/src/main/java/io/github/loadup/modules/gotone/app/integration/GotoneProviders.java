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

import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GotoneProviders {
    private final Map<Key, GotoneProvider> adapters;

    public GotoneProviders(List<GotoneProvider> adapters) {
        Map<Key, GotoneProvider> registered = new HashMap<>();
        for (GotoneProvider adapter : adapters) {
            if (registered.putIfAbsent(new Key(adapter.channel(), adapter.code()), adapter) != null) {
                throw new IllegalArgumentException("Duplicate Gotone adapter " + adapter.code());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public Optional<GotoneProvider> find(Account account) {
        return Optional.ofNullable(adapters.get(new Key(account.channel(), account.adapter())));
    }

    private record Key(Channel channel, String adapter) {}
}
