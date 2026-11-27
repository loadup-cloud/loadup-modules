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
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.util.Set;

/**
 * Core integration seam for a system account backed by a real transport.
 */
public interface GotoneProvider {
    String code();

    Channel channel();

    Set<String> settings();

    boolean valid(Account account);

    default boolean requiresSmsBinding() {
        return false;
    }

    default boolean validSmsBinding(String templateId, Set<String> variables) {
        return true;
    }

    default String webhookBodyField() {
        return "payload";
    }

    Outcome send(Account account, String secret, Work work, Binding binding);

    enum Result {
        SENT,
        RETRY_WAIT,
        FAILED,
        UNKNOWN
    }

    record Outcome(Result result, String reason, String remoteId) {
        public static Outcome sent(String remoteId) {
            return new Outcome(Result.SENT, null, remoteId);
        }

        public static Outcome retry(String reason) {
            return new Outcome(Result.RETRY_WAIT, reason, null);
        }

        public static Outcome failed(String reason) {
            return new Outcome(Result.FAILED, reason, null);
        }

        public static Outcome unknown(String reason) {
            return new Outcome(Result.UNKNOWN, reason, null);
        }
    }
}
