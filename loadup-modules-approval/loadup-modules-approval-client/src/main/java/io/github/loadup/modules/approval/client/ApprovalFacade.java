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
package io.github.loadup.modules.approval.client;

import static io.github.loadup.modules.approval.client.Approval.Caller;
import static io.github.loadup.modules.approval.client.Approval.Decide;
import static io.github.loadup.modules.approval.client.Approval.Definition;
import static io.github.loadup.modules.approval.client.Approval.Detail;
import static io.github.loadup.modules.approval.client.Approval.DirectoryType;
import static io.github.loadup.modules.approval.client.Approval.Instance;
import static io.github.loadup.modules.approval.client.Approval.Option;
import static io.github.loadup.modules.approval.client.Approval.Reassign;
import static io.github.loadup.modules.approval.client.Approval.Spec;
import static io.github.loadup.modules.approval.client.Approval.Start;
import static io.github.loadup.modules.approval.client.Approval.Task;

import java.util.List;

public interface ApprovalFacade {
    List<Definition> definitions(Caller caller, String app, int offset, int limit);

    Definition definition(Caller caller, String app, String id);

    Definition createDraft(Caller caller, String app, String flowKey, Spec spec, String sourceId);

    Definition saveDraft(Caller caller, String app, String id, long revision, Spec spec);

    Definition publish(Caller caller, String app, String id, long revision);

    Definition retire(Caller caller, String app, String id, long revision);

    Definition active(Caller caller, String app, String flowKey);

    List<Option> directory(Caller caller, String app, DirectoryType type, String query, int offset, int limit);

    List<Option> candidates(
            Caller caller,
            String app,
            String flowKey,
            int version,
            String stepKey,
            String query,
            int offset,
            int limit);

    List<Option> instanceCandidates(
            Caller caller, String app, String id, String stepKey, String query, int offset, int limit);

    Instance start(Caller caller, String app, String commandKey, Start command);

    Detail detail(Caller caller, String app, String id);

    List<Instance> instances(Caller caller, String app, int offset, int limit);

    List<Task> myTasks(Caller caller, String app, int offset, int limit);

    Instance changeAssignees(Caller caller, String app, String id, long revision, String commandKey, Reassign command);

    Instance decide(Caller caller, String app, String stepId, long revision, String commandKey, Decide command);

    Instance withdraw(Caller caller, String app, String id, long revision, String commandKey, String reason);

    void retryDelivery(Caller caller, String app, String id, String reason);
}
