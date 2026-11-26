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

import io.github.loadup.components.task.TaskStatus;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Delivery;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;

public final class GotoneDeliveryReconciliation {
    private final GotoneDeliveryStore store;
    private final TaskTemplate tasks;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GotoneDeliveryReconciliation(
            GotoneDeliveryStore store, TaskTemplate tasks, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.tasks = tasks;
        this.tx = tx;
        this.clock = clock;
    }

    public boolean sync(Delivery delivery) {
        if (!delivery.status().equals("SENDING")
                && !delivery.status().equals("PENDING")
                && !delivery.status().equals("RETRY_WAIT")) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(transaction -> reconcile(delivery.id(), delivery.channel())));
    }

    public boolean reconcile(String id, Channel channel) {
        var work = store.lock(id).orElseThrow();
        var queue = tasks.find(GotoneSubmissionService.taskType(channel), id);
        if (queue.isPresent()
                && queue.get().status() != TaskStatus.FAILED
                && queue.get().status() != TaskStatus.CANCELLED) {
            return false;
        }
        switch (work.status()) {
            case "SENDING" -> store.finish(id, work.attemptNo(), "UNKNOWN", "WORKER_LOST", null);
            case "PENDING", "RETRY_WAIT" -> {
                if (!clock.instant().isBefore(work.expiresAt())) {
                    store.expire(id);
                } else {
                    int attempt = store.start(work, null, null);
                    store.finish(id, attempt, "FAILED", "TASK_EXHAUSTED", null);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
