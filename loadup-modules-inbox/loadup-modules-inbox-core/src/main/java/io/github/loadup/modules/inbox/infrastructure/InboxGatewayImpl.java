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
package io.github.loadup.modules.inbox.infrastructure;

import static io.github.loadup.modules.inbox.infrastructure.table.Tables.INBOX_MESSAGE;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.modules.inbox.domain.InboxGateway;
import io.github.loadup.modules.inbox.domain.InboxMessage;
import io.github.loadup.modules.inbox.infrastructure.dataobject.InboxMessageDO;
import io.github.loadup.modules.inbox.infrastructure.mapper.InboxMessageMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

public class InboxGatewayImpl implements InboxGateway {
    private final InboxMessageMapper mapper;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    public InboxGatewayImpl(InboxMessageMapper mapper, JdbcClient jdbc, Clock clock, IdGenerator ids) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public int insert(InboxMessage message, String requestKey) {
        var now = LocalDateTime.now(clock);
        var row = new InboxMessageDO();
        row.setId(message.id() == null || message.id().isBlank() ? ids.generate() : message.id());
        row.setRecipientId(message.recipientId());
        row.setSenderId(message.senderId());
        row.setCategory(message.category());
        row.setTitle(message.title());
        row.setBody(message.body());
        row.setActionUrl(message.actionUrl());
        row.setReadAt(message.readAt());
        row.setRequestKey(requestKey);
        row.setCreatedAt(message.createdAt() == null ? now : message.createdAt());
        row.setUpdatedAt(now);
        return jdbc.sql("""
                INSERT INTO inbox_message (id, recipient_id, sender_id, category, title, body, action_url,
                    request_key, read_at, created_at, updated_at, deleted)
                VALUES (:id, :recipientId, :senderId, :category, :title, :body, :actionUrl,
                    :requestKey, :readAt, :createdAt, :updatedAt, FALSE)
                ON CONFLICT (recipient_id, request_key) DO NOTHING
                """).paramSource(new BeanPropertySqlParameterSource(row)).update();
    }

    @Override
    public PageResult<InboxMessage> list(String recipient, boolean unread, int page, int size) {
        QueryWrapper query = visible(recipient);
        if (unread) query.and(INBOX_MESSAGE.READ_AT.isNull());
        var result = mapper.paginate(new Page<>(page, size),
                query.orderBy(INBOX_MESSAGE.CREATED_AT.desc(), INBOX_MESSAGE.ID.desc()));
        return new PageResult<>(result.getRecords().stream().map(InboxGatewayImpl::message).toList(),
                result.getTotalRow(), page, size);
    }

    @Override
    public long unreadCount(String recipient) {
        return mapper.selectCountByQuery(visible(recipient).and(INBOX_MESSAGE.READ_AT.isNull()));
    }

    @Override
    public int markRead(String recipient, String id) {
        QueryWrapper owned = visible(recipient).and(INBOX_MESSAGE.ID.eq(Objects.requireNonNull(id)));
        var row = new InboxMessageDO();
        LocalDateTime now = LocalDateTime.now(clock);
        row.setReadAt(now);
        row.setUpdatedAt(now);
        int changed = mapper.updateByQuery(row, visible(recipient).and(INBOX_MESSAGE.ID.eq(id)).and(INBOX_MESSAGE.READ_AT.isNull()));
        if (changed != 0) return changed;
        return mapper.selectCountByQuery(owned) != 0 ? 1 : 0;
    }

    @Override
    public int markAllRead(String recipient) {
        var row = new InboxMessageDO();
        LocalDateTime now = LocalDateTime.now(clock);
        row.setReadAt(now);
        row.setUpdatedAt(now);
        return mapper.updateByQuery(row, visible(recipient).and(INBOX_MESSAGE.READ_AT.isNull()));
    }

    @Override
    public int archive(String recipient, String id) {
        Objects.requireNonNull(id);
        var row = new InboxMessageDO();
        LocalDateTime now = LocalDateTime.now(clock);
        row.setArchivedAt(now);
        row.setUpdatedAt(now);
        int changed = mapper.updateByQuery(row, visible(recipient).and(INBOX_MESSAGE.ID.eq(id)));
        if (changed != 0) return changed;
        return mapper.selectCountByQuery(owned(recipient).and(INBOX_MESSAGE.ID.eq(id))) != 0 ? 1 : 0;
    }

    private static QueryWrapper owned(String recipient) {
        return QueryWrapper.create().where(INBOX_MESSAGE.RECIPIENT_ID.eq(Objects.requireNonNull(recipient)));
    }

    private static QueryWrapper visible(String recipient) {
        return owned(recipient).and(INBOX_MESSAGE.ARCHIVED_AT.isNull());
    }

    private static InboxMessage message(InboxMessageDO row) {
        return new InboxMessage(row.getId(), row.getRecipientId(), row.getSenderId(), row.getCategory(), row.getTitle(),
                row.getBody(), row.getActionUrl(), row.getReadAt(), row.getCreatedAt());
    }
}
