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
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public abstract class GotoneDeliveryHandler implements TaskHandler {
    private final Channel channel;
    protected final GotoneDeliveryStore store;
    protected final Clock clock;
    protected final TransactionTemplate tx;

    protected GotoneDeliveryHandler(Channel channel, GotoneDeliveryStore store, Clock clock, TransactionTemplate tx) {
        this.channel = channel;
        this.store = store;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    public final String taskType() {
        return GotoneSubmissionService.taskType(channel);
    }

    @Override
    public final void handle(TaskExecution task) {
        UUID id = UUID.fromString(task.bizId());
        if (!task.bizId().equals(task.payload())) throw new IllegalArgumentException("Invalid Gotone task");
        deliver(id, task);
    }

    protected abstract void deliver(UUID id, TaskExecution task);

    protected final Work readWork(UUID id) {
        Work work = store.lock(id).orElse(null);
        if (work == null) return null;
        if (work.channel() != channel) throw new IllegalArgumentException("Gotone task channel mismatch");
        switch (work.status()) {
            case "SENDING" -> store.finish(id, work.attemptNo(), "UNKNOWN", "WORKER_LOST", null);
            case "PENDING", "RETRY_WAIT" -> {
                if (clock.instant().isBefore(work.expiresAt())) return work;
                store.expire(id);
            }
            default -> { }
        }
        return null;
    }
}
