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

import io.github.loadup.commons.dto.PageQuery;
import io.github.loadup.commons.enums.CommonResultCodeEnum;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.commons.result.PageDTO;
import io.github.loadup.modules.inbox.client.dto.InboxMessageDTO;
import io.github.loadup.modules.inbox.client.error.InboxResultCode;
import io.github.loadup.modules.inbox.client.facade.InboxFacade;
import io.github.loadup.modules.inbox.domain.InboxGateway;

public class InboxService implements InboxFacade {
    private final InboxDTOConverter converter;
    private final InboxGateway repository;

    public InboxService(InboxGateway repository, InboxDTOConverter converter) {
        this.converter = converter;
        this.repository = repository;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new CommonException(CommonResultCodeEnum.PARAM_ILLEGAL);
        }
        return value.trim();
    }

    @Override
    public PageDTO<InboxMessageDTO> list(String recipientId, boolean unreadOnly, int page, int size) {
        PageQuery.normalized(page, size, InboxResultCode.INVALID_PAGE);
        return converter.toPageDTO(repository.list(required(recipientId, 64), unreadOnly, page, size));
    }

    @Override
    public long unreadCount(String recipientId) {
        return repository.unreadCount(required(recipientId, 64));
    }

    @Override
    public void markRead(String recipientId, String id) {
        if (repository.markRead(required(recipientId, 64), required(id, 64)) == 0) {
            throw new CommonException(InboxResultCode.MESSAGE_NOT_FOUND);
        }
    }

    @Override
    public int markAllRead(String recipientId) {
        return repository.markAllRead(required(recipientId, 64));
    }

    @Override
    public void archive(String recipientId, String id) {
        if (repository.archive(required(recipientId, 64), required(id, 64)) == 0) {
            throw new CommonException(InboxResultCode.MESSAGE_NOT_FOUND);
        }
    }
}
