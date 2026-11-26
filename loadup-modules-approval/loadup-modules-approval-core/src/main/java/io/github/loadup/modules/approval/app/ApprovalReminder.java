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

import static io.github.loadup.modules.approval.client.Approval.Instance;
import static io.github.loadup.modules.approval.client.Approval.Status;
import static io.github.loadup.modules.approval.client.Approval.Step;

import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Submit;
import io.github.loadup.modules.gotone.client.Gotone.Target;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

final class ApprovalReminder implements TaskHandler {
    private final ApprovalStore store;
    private final ObjectProvider<TaskTemplate> tasks;
    private final ObjectProvider<GotoneFacade> notifications;

    ApprovalReminder(
            ApprovalStore store, ObjectProvider<TaskTemplate> tasks, ObjectProvider<GotoneFacade> notifications) {
        this.store = store;
        this.tasks = tasks;
        this.notifications = notifications;
    }

    @Override
    public String taskType() {
        return "approval.reminder";
    }

    void enqueue(Instance instance, Step step) {
        if (step.assigneeUserId() == null
                || step.assigneeUserId().length() > 64
                || notifications.getIfAvailable() == null) {
            return;
        }
        var pending = new Pending(instance.appCode(), instance.id(), step.id(), step.assigneeUserId());
        tasks.getObject()
                .submit(TaskSubmission.immediate(
                        taskType(), instance.id() + ":" + instance.revision(), store.json(pending)));
    }

    @Override
    public void handle(TaskExecution task) {
        var pending = store.read(task.payload(), Pending.class);
        var sender = notifications.getIfAvailable();
        if (sender == null
                || store.instance(pending.app(), pending.instanceId(), false).isEmpty()) {
            return;
        }
        boolean stillAssigned = store.steps(pending.instanceId()).stream()
                .anyMatch(step -> step.id().equals(pending.stepId())
                        && step.status() == Status.PENDING
                        && pending.userId().equals(step.assigneeUserId()));
        if (!stillAssigned) {
            return;
        }
        sender.submit(
                "approval",
                null,
                new Submit(
                        "approval.pending",
                        "pending:" + task.bizId(),
                        Map.of("actionUrl", "/approval?app=" + pending.app() + "&instance=" + pending.instanceId()),
                        Map.of(Channel.IN_APP, List.of(Target.address(pending.userId()))),
                        null));
    }

    public record Pending(String app, String instanceId, String stepId, String userId) {}
}
