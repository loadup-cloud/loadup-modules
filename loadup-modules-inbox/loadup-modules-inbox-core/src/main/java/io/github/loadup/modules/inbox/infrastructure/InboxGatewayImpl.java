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

import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.modules.inbox.domain.InboxGateway;
import io.github.loadup.modules.inbox.domain.InboxMessage;
import io.github.loadup.modules.inbox.infrastructure.dataobject.InboxMessageDO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class InboxGatewayImpl implements InboxGateway {
    private static final String OWNED = " WHERE recipient_id = :recipient";
    private static final String VISIBLE = OWNED + " AND archived_at IS NULL";
    private static final String UNREAD = VISIBLE + " AND read_at IS NULL";

    private final JdbcClient jdbc;
    private final TransactionTemplate insertTransaction;
    private final IdGenerator ids;
    private final Clock clock;

    public InboxGatewayImpl(JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock, IdGenerator ids) {
        this.jdbc = jdbc;
        this.insertTransaction = new TransactionTemplate(transactions);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.clock = clock;
        this.ids = ids;
    }

    private static InboxMessage message(InboxMessageDO row) {
        return new InboxMessage(
                row.getId(),
                row.getRecipientId(),
                row.getSenderId(),
                row.getCategory(),
                row.getTitle(),
                row.getBody(),
                row.getActionUrl(),
                row.getReadAt(),
                row.getCreatedAt());
    }

    @Override
    public int insert(InboxMessage message, String requestKey) {
        var now = LocalDateTime.now(clock);
        var id = message.id() == null || message.id().isBlank() ? ids.generate() : message.id();
        return insertTransaction.execute(status -> jdbc.sql("""
                    INSERT INTO inbox_message (id, recipient_id, sender_id, category, title, body, action_url,
                        read_at, request_key, created_at, updated_at)
                    VALUES (:id, :recipient, :sender, :category, :title, :body, :url, :readAt, :requestKey,
                        :createdAt, :now)
                    ON CONFLICT (recipient_id, request_key) DO NOTHING
                    """)
                .param("id", id)
                .param("recipient", message.recipientId())
                .param("sender", message.senderId())
                .param("category", message.category())
                .param("title", message.title())
                .param("body", message.body())
                .param("url", message.actionUrl())
                .param("readAt", message.readAt())
                .param("requestKey", requestKey)
                .param("createdAt", message.createdAt() == null ? now : message.createdAt())
                .param("now", now)
                .update());
    }

    @Override
    public PageResult<InboxMessage> list(String recipient, boolean unread, int page, int size) {
        var where = unread ? UNREAD : VISIBLE;
        long total = count(where, recipient);
        var messages = jdbc
                .sql("SELECT * FROM inbox_message" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("recipient", recipient)
                .param("limit", size)
                .param("offset", (page - 1L) * size)
                .query(InboxMessageDO.class)
                .list()
                .stream()
                .map(InboxGatewayImpl::message)
                .toList();
        return new PageResult<>(messages, total, page, size);
    }

    @Override
    public long unreadCount(String recipient) {
        return count(UNREAD, recipient);
    }

    @Override
    public int markRead(String recipient, String id) {
        int changed = jdbc.sql("UPDATE inbox_message SET read_at = :now, updated_at = :now" + UNREAD + " AND id = :id")
                .param("recipient", Objects.requireNonNull(recipient))
                .param("id", Objects.requireNonNull(id))
                .param("now", LocalDateTime.now(clock))
                .update();
        return changed != 0 ? changed : existing(VISIBLE, recipient, id);
    }

    @Override
    public int markAllRead(String recipient) {
        return jdbc.sql("UPDATE inbox_message SET read_at = :now, updated_at = :now" + UNREAD)
                .param("recipient", Objects.requireNonNull(recipient))
                .param("now", LocalDateTime.now(clock))
                .update();
    }

    @Override
    public int archive(String recipient, String id) {
        int changed = jdbc.sql(
                        "UPDATE inbox_message SET archived_at = :now, updated_at = :now" + VISIBLE + " AND id = :id")
                .param("recipient", Objects.requireNonNull(recipient))
                .param("id", Objects.requireNonNull(id))
                .param("now", LocalDateTime.now(clock))
                .update();
        return changed != 0 ? changed : existing(OWNED, recipient, id);
    }

    private long count(String where, String recipient) {
        return jdbc.sql("SELECT COUNT(*) FROM inbox_message" + where)
                .param("recipient", Objects.requireNonNull(recipient))
                .query(Long.class)
                .single();
    }

    private int existing(String where, String recipient, String id) {
        return jdbc.sql("SELECT COUNT(*) FROM inbox_message" + where + " AND id = :id")
                .param("recipient", recipient)
                .param("id", id)
                .query(Integer.class)
                .single();
    }
}
