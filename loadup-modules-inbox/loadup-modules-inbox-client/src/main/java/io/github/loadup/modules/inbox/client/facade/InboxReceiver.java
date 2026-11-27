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
package io.github.loadup.modules.inbox.client.facade;

public interface InboxReceiver {
    /**
     * Stores a rendered message once per recipient and request key. The write joins the caller's JDBC
     * transaction when present. Use the same key when retrying the same delivery.
     */
    void receive(
            String requestKey,
            String recipientId,
            String senderId,
            String category,
            String title,
            String body,
            String actionUrl);
}
