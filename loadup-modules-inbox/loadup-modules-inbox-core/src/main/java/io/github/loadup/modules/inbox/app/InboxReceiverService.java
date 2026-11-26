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
package io.github.loadup.modules.inbox.app;

import io.github.loadup.commons.enums.CommonResultCodeEnum;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.inbox.client.error.InboxResultCode;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import io.github.loadup.modules.inbox.domain.InboxGateway;
import io.github.loadup.modules.inbox.domain.InboxMessage;
import java.time.Clock;
import java.time.LocalDateTime;

final class InboxReceiverService implements InboxReceiver {
    private final InboxGateway inbox;
    private final Clock clock;

    InboxReceiverService(InboxGateway inbox, Clock clock) {
        this.inbox = inbox;
        this.clock = clock;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new CommonException(CommonResultCodeEnum.PARAM_ILLEGAL);
        }
        return value.trim();
    }

    @Override
    public void receive(
            String requestKey,
            String recipientId,
            String senderId,
            String category,
            String title,
            String body,
            String actionUrl) {
        String recipient = required(recipientId, 64);
        String sender = senderId == null || senderId.isBlank() ? null : required(senderId, 64);
        String kind = required(category, 64);
        String heading = required(title, 200);
        String text = required(body, 4000);
        String path = actionUrl == null || actionUrl.isBlank() ? null : required(actionUrl, 500);
        if (path != null
                && (!path.startsWith("/")
                        || path.startsWith("//")
                        || path.indexOf('\\') >= 0
                        || path.chars().anyMatch(Character::isISOControl))) {
            throw new CommonException(InboxResultCode.INVALID_ACTION_URL);
        }
        String key = required(requestKey, 128);
        var message =
                new InboxMessage(null, recipient, sender, kind, heading, text, path, null, LocalDateTime.now(clock));
        inbox.insert(message, key);
    }
}
