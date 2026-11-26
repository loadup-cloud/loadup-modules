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
package io.github.loadup.modules.approval.app;

import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.modules.approval.client.Approval.Outcome;
import io.github.loadup.modules.approval.client.ApprovalOutcomeHandler;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

final class ApprovalDelivery implements TaskHandler {
    private final ApprovalStore store;
    private final ObjectProvider<ApprovalOutcomeHandler> receivers;
    private final ApprovalProperties properties;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ApprovalDelivery(
            ApprovalStore store, ObjectProvider<ApprovalOutcomeHandler> receivers, ApprovalProperties properties) {
        this.store = store;
        this.receivers = receivers;
        this.properties = properties;
    }

    @Override
    public String taskType() {
        return ApprovalService.OUTCOME_TASK;
    }

    @Override
    public void handle(TaskExecution task) throws Exception {
        Outcome event = store.read(task.payload(), Outcome.class);
        if (event.schemaVersion() != 1 || !task.bizId().equals(event.instanceId())) {
            throw new IllegalStateException("Invalid approval event");
        }
        var row = store.instance(event.appCode(), event.instanceId(), false).orElseThrow();
        if (!task.id().equals(row.deliveryTaskId()) || row.instance().status() != event.outcome()) {
            throw new IllegalStateException("Unbound approval event");
        }
        if (row.deliveredAt() != null) {
            return;
        }

        List<ApprovalOutcomeHandler> matches = receivers.stream()
                .filter(handler -> handler.appCode().equals(event.appCode()))
                .toList();
        var target = properties.webhooks().get(event.appCode());
        if (matches.size() > 1 || (!matches.isEmpty() && target != null)) {
            throw new IllegalStateException("Approval application has multiple outcome receivers");
        }
        if (!matches.isEmpty()) {
            matches.getFirst().handle(event);
        } else if (target != null) {
            send(event, target);
        } else {
            // Applications without push delivery read outcomes through the instance interface.
            return;
        }
        store.sql("""
                UPDATE approval_instance SET delivered_at = now() WHERE id = ? AND delivered_at IS NULL
                """, event.instanceId()).update();
    }

    private void send(Outcome event, ApprovalProperties.Webhook target) throws Exception {
        URI endpoint = target.url();
        if (endpoint == null
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || target.bearerToken() == null
                || target.bearerToken().isBlank()) {
            throw new IllegalStateException("Approval webhook requires HTTPS and a service credential");
        }
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + target.bearerToken())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", event.eventId())
                .POST(HttpRequest.BodyPublishers.ofString(store.json(event)))
                .build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Approval receiver HTTP " + status);
        }
    }
}
