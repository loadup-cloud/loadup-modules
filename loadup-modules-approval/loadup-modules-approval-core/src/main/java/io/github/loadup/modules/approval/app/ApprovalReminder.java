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

import static io.github.loadup.modules.approval.client.Approval.*;

import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import io.github.loadup.modules.notification.client.command.NotificationSubmitCommand;
import io.github.loadup.modules.notification.client.facade.NotificationFacade;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

final class ApprovalReminder implements TaskHandler {
    private final ApprovalStore store;
    private final ObjectProvider<TaskTemplate> tasks;
    private final ObjectProvider<NotificationFacade> notifications;

    ApprovalReminder(ApprovalStore store, ObjectProvider<TaskTemplate> tasks,
                     ObjectProvider<NotificationFacade> notifications) {
        this.store = store;
        this.tasks = tasks;
        this.notifications = notifications;
    }

    public record Pending(String app, String instanceId, String stepId, String userId) {}

    @Override
    public String taskType() {
        return "approval.reminder";
    }

    void enqueue(Instance instance, Step step) {
        if (step.assigneeUserId() == null || step.assigneeUserId().length() > 64
                || notifications.getIfAvailable() == null) return;
        var pending = new Pending(instance.appCode(), instance.id(), step.id(), step.assigneeUserId());
        tasks.getObject().submit(TaskSubmission.immediate(taskType(), instance.id() + ":" + instance.revision(),
                store.json(pending)));
    }

    @Override
    public void handle(TaskExecution task) {
        var pending = store.read(task.payload(), Pending.class);
        var sender = notifications.getIfAvailable();
        if (sender == null || store.instance(pending.app(), pending.instanceId(), false).isEmpty()) return;
        boolean stillAssigned = store.steps(pending.instanceId()).stream().anyMatch(step ->
                step.id().equals(pending.stepId()) && step.status() == Status.PENDING
                        && pending.userId().equals(step.assigneeUserId()));
        if (!stillAssigned) return;
        sender.submit("approval", new NotificationSubmitCommand(
                "pending:" + task.bizId(), "approval.pending", List.of(pending.userId()), "IN_APP",
                "有新的审批待办", "请在审批管理中查看待办并办理。",
                "/approval?app=" + pending.app() + "&instance=" + pending.instanceId(), null));
    }
}
