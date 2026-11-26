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

import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;

public final class InboxDeliveryHandler extends GotoneDeliveryHandler {
    private final InboxReceiver inbox;

    public InboxDeliveryHandler(GotoneDeliveryStore store, InboxReceiver inbox, Clock clock, TransactionTemplate tx) {
        super(Channel.IN_APP, store, clock, tx);
        this.inbox = inbox;
    }

    @Override
    protected void deliver(String id, TaskExecution task) {
        tx.executeWithoutResult(status -> sendInbox(id));
    }

    private void sendInbox(String id) {
        Work work = readWork(id);
        if (work == null) {
            return;
        }
        int attempt = store.start(work, null, null);
        var fields = work.snapshot().fields();
        inbox.receive(
                id,
                work.snapshot().target().address(),
                work.senderId(),
                fields.get("category"),
                fields.get("title"),
                fields.get("body"),
                fields.get("actionPath"));
        store.finish(id, attempt, "SENT", null, null);
    }
}
