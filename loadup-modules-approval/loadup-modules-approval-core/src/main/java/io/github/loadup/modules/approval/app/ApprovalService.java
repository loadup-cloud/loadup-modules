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

import static io.github.loadup.modules.approval.app.ApprovalRules.error;
import static io.github.loadup.modules.approval.app.ApprovalRules.human;
import static io.github.loadup.modules.approval.app.ApprovalRules.key;
import static io.github.loadup.modules.approval.app.ApprovalRules.open;
import static io.github.loadup.modules.approval.app.ApprovalRules.page;
import static io.github.loadup.modules.approval.app.ApprovalRules.permit;
import static io.github.loadup.modules.approval.app.ApprovalRules.require;
import static io.github.loadup.modules.approval.app.ApprovalRules.text;
import static io.github.loadup.modules.approval.client.Approval.Action;
import static io.github.loadup.modules.approval.client.Approval.AssignmentChange;
import static io.github.loadup.modules.approval.client.Approval.Business;
import static io.github.loadup.modules.approval.client.Approval.Caller;
import static io.github.loadup.modules.approval.client.Approval.Change;
import static io.github.loadup.modules.approval.client.Approval.Decide;
import static io.github.loadup.modules.approval.client.Approval.Decision;
import static io.github.loadup.modules.approval.client.Approval.Definition;
import static io.github.loadup.modules.approval.client.Approval.Detail;
import static io.github.loadup.modules.approval.client.Approval.DirectoryType;
import static io.github.loadup.modules.approval.client.Approval.Instance;
import static io.github.loadup.modules.approval.client.Approval.Mode;
import static io.github.loadup.modules.approval.client.Approval.Option;
import static io.github.loadup.modules.approval.client.Approval.Outcome;
import static io.github.loadup.modules.approval.client.Approval.Person;
import static io.github.loadup.modules.approval.client.Approval.Reassign;
import static io.github.loadup.modules.approval.client.Approval.Rule;
import static io.github.loadup.modules.approval.client.Approval.Spec;
import static io.github.loadup.modules.approval.client.Approval.Start;
import static io.github.loadup.modules.approval.client.Approval.Status;
import static io.github.loadup.modules.approval.client.Approval.Step;
import static io.github.loadup.modules.approval.client.Approval.Task;
import static io.github.loadup.modules.approval.client.ApprovalException.Code.APPROVAL_CONFLICT;
import static io.github.loadup.modules.approval.client.ApprovalException.Code.APPROVAL_FORBIDDEN;
import static io.github.loadup.modules.approval.client.ApprovalException.Code.APPROVAL_NOT_FOUND;
import static io.github.loadup.modules.approval.client.ApprovalException.Code.APPROVAL_STALE;

import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.approval.client.ApprovalDirectory;
import io.github.loadup.modules.approval.client.ApprovalFacade;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore.Record;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionTemplate;

final class ApprovalService implements ApprovalFacade {
    static final String OUTCOME_TASK = "approval.outcome";

    private final ApprovalStore store;
    private final ApprovalDirectory directory;
    private final TaskTemplate tasks;
    private final TransactionTemplate tx;
    private final ApprovalRules rules;
    private final ApprovalDefinitions definitions;
    private final ApprovalReminder reminder;

    ApprovalService(
            ApprovalStore store,
            ApprovalDirectory directory,
            TaskTemplate tasks,
            TransactionTemplate tx,
            ApprovalReminder reminder) {
        this.store = store;
        this.directory = directory;
        this.tasks = tasks;
        this.tx = tx;
        this.reminder = reminder;
        this.rules = new ApprovalRules(directory);
        this.definitions = new ApprovalDefinitions(store, rules, tx);
    }

    @Override
    public List<Definition> definitions(Caller caller, String app, int offset, int limit) {
        return definitions.list(caller, app, offset, limit);
    }

    @Override
    public Definition definition(Caller caller, String app, String id) {
        permit(caller, app, "design");
        return definitions.get(app, id);
    }

    @Override
    public Definition createDraft(Caller caller, String app, String flow, Spec spec, String source) {
        return definitions.create(caller, app, flow, spec, source);
    }

    @Override
    public Definition saveDraft(Caller caller, String app, String id, long revision, Spec spec) {
        return definitions.save(caller, app, id, revision, spec);
    }

    @Override
    public Definition publish(Caller caller, String app, String id, long revision) {
        return definitions.publish(caller, app, id, revision);
    }

    @Override
    public Definition retire(Caller caller, String app, String id, long revision) {
        return definitions.retire(caller, app, id, revision);
    }

    @Override
    public Definition active(Caller caller, String app, String flow) {
        if (!caller.permits(app, "design")) {
            permit(caller, app, "start");
        }
        return definitions.active(app, flow);
    }

    @Override
    public List<Option> directory(Caller caller, String app, DirectoryType type, String query, int offset, int limit) {
        permit(caller, app, "design");
        page(offset, limit);
        return directory.search(app, type, query, offset, limit);
    }

    @Override
    public List<Option> candidates(
            Caller caller, String app, String flow, int version, String stepKey, String query, int offset, int limit) {
        human(caller);
        permit(caller, app, "start");
        Definition definition = expectedDefinition(app, flow, version);
        Rule rule = definition.spec().steps().stream()
                .filter(step -> step.key().equals(stepKey))
                .findFirst()
                .orElseThrow(() -> error(APPROVAL_NOT_FOUND, "审批步骤不存在"));
        if (rule.mode() == Mode.ROLE || (rule.mode() == Mode.USER && !rule.allowOverride())) {
            return List.of();
        }
        return options(
                app,
                rule,
                query,
                offset,
                limit,
                person -> !definition.spec().excludeApplicant() || !person.id().equals(caller.subject()));
    }

    @Override
    public List<Option> instanceCandidates(
            Caller caller, String app, String id, String stepKey, String query, int offset, int limit) {
        human(caller);
        permit(caller, app, "adjust");
        Record record = get(app, id, false);
        List<Step> steps = store.steps(id);
        Step step = openStep(steps, stepKey);
        Spec spec = spec(record);
        return options(
                app,
                step.rule(),
                query,
                offset,
                limit,
                person -> (!spec.excludeApplicant()
                                || !record.instance().applicantId().equals(person.id()))
                        && (!spec.distinctApprovers()
                                || steps.stream().noneMatch(other -> person.id().equals(other.completedBy()))));
    }

    private List<Option> options(
            String app, Rule rule, String search, int offset, int limit, java.util.function.Predicate<Person> allowed) {
        page(offset, limit);
        String query = search == null ? "" : search.toLowerCase(Locale.ROOT);
        return directory
                .people(app)
                .filter(person -> rules.matches(rule, person))
                .filter(allowed)
                .filter(person -> person.label().toLowerCase(Locale.ROOT).contains(query)
                        || person.id().toLowerCase(Locale.ROOT).contains(query))
                .skip(offset)
                .limit(limit)
                .map(person -> new Option(person.id(), person.label()))
                .toList();
    }

    @Override
    public Instance start(Caller caller, String app, String key, Start command) {
        human(caller);
        permit(caller, app, "start");
        text(caller.subject(), 200, "发起人");
        text(key, 128, "幂等键");
        require(command != null && command.business() != null, "请提供业务引用");
        key(command.flowKey());
        Business business = command.business();
        text(command.title(), 200, "申请标题");
        text(business.type(), 64, "业务类型");
        text(business.id(), 128, "业务 ID");
        text(business.version(), 64, "业务版本");
        text(business.contentHash(), 128, "内容摘要");
        Map<String, String> selected = new TreeMap<>();
        if (command.selectedApprovers() != null) {
            selected.putAll(command.selectedApprovers());
        }
        String digest = store.hash(List.of(
                caller.subject(),
                command.flowKey(),
                command.expectedDefinitionVersion(),
                business,
                command.title(),
                selected));
        return tx.execute(status -> {
            store.lockFlow(app, command.flowKey());
            var existing = store.byStartKey(app, key);
            if (existing.isPresent()) {
                return sameRequest(existing.get(), caller, key, digest);
            }
            Definition definition = expectedDefinition(app, command.flowKey(), command.expectedDefinitionVersion());
            String id = ApprovalStore.id();
            List<Step> steps = assignAtStart(app, definition.spec(), selected);
            require(rules.viable(app, definition.spec(), caller.subject(), steps, null, null), "候选审批人无法满足流程规则");
            var earlierHash = store.sql("""
                            SELECT content_hash FROM approval_instance WHERE app_code = ? AND flow_key = ?
                              AND business_type = ? AND business_id = ? AND business_version = ? LIMIT 1
                            """, app, command.flowKey(), business.type(), business.id(), business.version())
                    .query(String.class)
                    .optional();
            if (earlierHash.isPresent() && !earlierHash.get().equals(business.contentHash())) {
                throw error(APPROVAL_CONFLICT, "同一业务版本已绑定不同的内容摘要");
            }
            int created = store.sql(
                            """
                            INSERT INTO approval_instance
                              (id, definition_id, app_code, flow_key, business_type, business_id, business_version,
                               content_hash, title, applicant_id, start_key, request_hash, status, revision)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1) ON CONFLICT DO NOTHING
                            """,
                            id,
                            definition.id(),
                            app,
                            command.flowKey(),
                            business.type(),
                            business.id(),
                            business.version(),
                            business.contentHash(),
                            command.title(),
                            caller.subject(),
                            key,
                            digest)
                    .update();
            if (created == 0) {
                return store.byStartKey(app, key)
                        .map(record -> sameRequest(record, caller, key, digest))
                        .orElseThrow(() -> error(APPROVAL_CONFLICT, "同一业务版本已有待审申请"));
            }
            int order = 0;
            List<AssignmentChange> initial = new ArrayList<>();
            for (Step step : steps) {
                store.sql(
                                """
                                INSERT INTO approval_instance_step
                                  (id, instance_id, step_key, step_order, step_name, rule_snapshot,
                                   assignee_user_id, assignee_role_id, status, activated_at)
                                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CASE WHEN ? = 'PENDING' THEN now() END)
                                """,
                                step.id(),
                                id,
                                step.rule().key(),
                                ++order,
                                step.rule().name(),
                                store.json(step.rule()),
                                step.assigneeUserId(),
                                step.assigneeRoleId(),
                                step.status().name(),
                                step.status().name())
                        .update();
                initial.add(new AssignmentChange(
                        step.rule().key(), null, null, step.assigneeUserId(), step.assigneeRoleId()));
            }
            Instance result = get(app, id, false).instance();
            log(result, caller, key, digest, "SUBMITTED", null, null, null, initial);
            reminder.enqueue(result, steps.getFirst());
            return result;
        });
    }

    private List<Step> assignAtStart(String app, Spec spec, Map<String, String> selected) {
        Map<String, String> remaining = new HashMap<>(selected);
        List<Step> steps = new ArrayList<>();
        for (Rule rule : spec.steps()) {
            boolean supplied = remaining.containsKey(rule.key());
            String user = supplied ? remaining.remove(rule.key()) : rule.defaultUserId();
            String role = rule.roleId();
            if (rule.mode() == Mode.ROLE) {
                require(!supplied, "共享角色步骤不能在发起时指定人员 " + rule.key());
            } else {
                require(
                        !supplied || rule.mode() == Mode.SELECT_AT_START || rule.allowOverride(),
                        "步骤不允许发起时改选 " + rule.key());
                rules.validateUser(app, rule, user);
            }
            steps.add(new Step(
                    ApprovalStore.id(),
                    rule,
                    user,
                    role,
                    steps.isEmpty() ? Status.PENDING : Status.WAITING,
                    null,
                    null,
                    null));
        }
        require(remaining.isEmpty(), "提交包含未知审批步骤");
        return steps;
    }

    private Instance sameRequest(Record record, Caller caller, String key, String digest) {
        if (!record.instance().applicantId().equals(caller.subject())
                || !record.requestHash().equals(digest)) {
            throw error(APPROVAL_CONFLICT, "幂等键已经用于其他提交");
        }
        return store.receipt(record.instance().id(), key).orElseThrow().result();
    }

    private Definition expectedDefinition(String app, String flow, int version) {
        Definition definition = definitions.active(app, flow);
        if (definition.version() != version) {
            throw error(APPROVAL_CONFLICT, "启用流程已更新，请重新确认审批人");
        }
        return definition;
    }

    @Override
    public Detail detail(Caller caller, String app, String id) {
        return tx.execute(status -> {
            Record record = get(app, id, true);
            Instance instance = record.instance();
            Spec spec = spec(record);
            List<Step> steps = store.steps(id);
            Step pending = steps.stream()
                    .filter(step -> step.status() == Status.PENDING)
                    .findFirst()
                    .orElse(null);
            boolean mine = caller.human() && instance.applicantId().equals(caller.subject());
            boolean decide = pending != null && eligible(caller, app, instance, spec, steps, pending);
            boolean participated = caller.human()
                    && steps.stream().anyMatch(step -> caller.subject().equals(step.completedBy()));
            boolean adjust = caller.human() && caller.permits(app, "adjust");
            if (!mine && !decide && !participated && !adjust && !caller.permits(app, "read")) {
                throw error(APPROVAL_NOT_FOUND, "审批申请不存在");
            }
            boolean running = instance.status() == Status.PENDING;
            boolean blocked = running && !rules.viable(app, spec, instance.applicantId(), steps, null, null);
            String delivery = record.deliveredAt() != null
                    ? "DELIVERED"
                    : record.deliveryTaskId() == null
                            ? null
                            : tasks.find(record.deliveryTaskId())
                                    .map(task -> task.status().name())
                                    .orElse("POLL_ONLY");
            List<Action> actions = store.actions(id);
            var changes = actions.stream()
                    .flatMap(action -> action.changes().stream())
                    .toList();
            var users = Stream.of(
                            Stream.of(instance.applicantId()),
                            actions.stream().map(Action::actorId),
                            steps.stream().flatMap(step -> Stream.of(step.assigneeUserId(), step.completedBy())),
                            changes.stream().flatMap(change -> Stream.of(change.beforeUserId(), change.afterUserId())))
                    .flatMap(stream -> stream);
            var roles = Stream.concat(
                    steps.stream().map(Step::assigneeRoleId),
                    changes.stream().flatMap(change -> Stream.of(change.beforeRoleId(), change.afterRoleId())));
            return new Detail(
                    instance,
                    steps,
                    actions,
                    running && adjust,
                    decide,
                    running && mine && spec.allowWithdraw(),
                    blocked,
                    delivery,
                    labels(app, DirectoryType.USER, users),
                    labels(app, DirectoryType.ROLE, roles));
        });
    }

    private Map<String, String> labels(String app, DirectoryType type, Stream<String> ids) {
        Map<String, String> labels = new HashMap<>();
        ids.filter(Objects::nonNull)
                .distinct()
                .forEach(id -> directory.label(app, type, id).ifPresent(label -> labels.put(id, label)));
        return labels;
    }

    @Override
    public List<Instance> instances(Caller caller, String app, int offset, int limit) {
        boolean readAll = caller.permits(app, "read") || (caller.human() && caller.permits(app, "adjust"));
        if (!readAll) {
            human(caller);
            permit(caller, app, "start");
        }
        page(offset, limit);
        return store.instances(app, caller.subject(), readAll, offset, limit);
    }

    @Override
    public List<Task> myTasks(Caller caller, String app, int offset, int limit) {
        human(caller);
        permit(caller, app, "decide");
        page(offset, limit);
        var person = directory.person(app, caller.subject());
        if (person.isEmpty()) {
            return List.of();
        }
        List<Task> result = new ArrayList<>();
        int seen = 0;
        for (int scanned = 0; result.size() < limit; scanned += 100) {
            var ids = store.pendingIds(
                    app, caller.subject(), List.copyOf(person.get().roleIds()), scanned, 100);
            for (String id : ids) {
                Record record = get(app, id, false);
                List<Step> steps = store.steps(id);
                var pending = steps.stream()
                        .filter(step -> step.status() == Status.PENDING)
                        .findFirst();
                if (pending.isPresent()
                        && eligible(caller, app, record.instance(), spec(record), steps, pending.get())
                        && seen++ >= offset) {
                    result.add(new Task(
                            record.instance(),
                            pending.get().id(),
                            pending.get().rule().name()));
                    if (result.size() == limit) {
                        break;
                    }
                }
            }
            if (ids.size() < 100) {
                break;
            }
        }
        return result;
    }

    @Override
    public Instance changeAssignees(Caller caller, String app, String id, long revision, String key, Reassign command) {
        permit(caller, app, "adjust");
        require(
                command != null
                        && command.changes() != null
                        && !command.changes().isEmpty(),
                "请选择要调整的步骤");
        text(command.reason(), 2000, "调整原因");
        Map<String, String> replacements = new TreeMap<>();
        for (Change change : command.changes()) {
            require(change != null, "调整内容无效");
            key(change.stepKey());
            text(change.assigneeId(), 200, "新人选");
            require(replacements.put(change.stepKey(), change.assigneeId()) == null, "步骤重复调整");
        }
        return mutate(
                caller, app, id, revision, key, List.of("adjust", replacements, command.reason()), (record, steps) -> {
                    List<Step> updated = new ArrayList<>(steps);
                    List<AssignmentChange> changes = new ArrayList<>();
                    replacements.forEach((stepKey, user) -> {
                        Step step = openStep(steps, stepKey);
                        rules.validateUser(app, step.rule(), user);
                        if (!Objects.equals(step.assigneeUserId(), user)) {
                            updated.set(
                                    steps.indexOf(step),
                                    new Step(
                                            step.id(),
                                            step.rule(),
                                            user,
                                            null,
                                            step.status(),
                                            null,
                                            step.activatedAt(),
                                            null));
                            changes.add(new AssignmentChange(
                                    stepKey, step.assigneeUserId(), step.assigneeRoleId(), user, null));
                        }
                    });
                    require(!changes.isEmpty(), "所选审批人没有变化");
                    require(
                            rules.viable(app, spec(record), record.instance().applicantId(), updated, null, null),
                            "调整后的审批人无法满足流程规则");
                    for (int index = 0; index < steps.size(); index++) {
                        if (updated.get(index).equals(steps.get(index))) {
                            continue;
                        }
                        Step step = updated.get(index);
                        store.sql(
                                        "UPDATE approval_instance_step SET assignee_user_id = ?, assignee_role_id = NULL WHERE id = ?",
                                        step.assigneeUserId(),
                                        step.id())
                                .update();
                    }
                    return new Mutation(Status.PENDING, "ASSIGNEE_CHANGED", null, null, command.reason(), changes);
                });
    }

    @Override
    public Instance decide(Caller caller, String app, String stepId, long revision, String key, Decide command) {
        human(caller);
        permit(caller, app, "decide");
        require(command != null && command.decision() != null, "请选择审批决定");
        if (command.decision() == Decision.REJECT || command.comment() != null) {
            text(command.comment(), 2000, "审批意见");
        }
        String id = store.stepInstance(app, stepId).orElseThrow(() -> error(APPROVAL_NOT_FOUND, "审批待办不存在"));
        return mutate(caller, app, id, revision, key, List.of("decide", stepId, command), (record, steps) -> {
            Step step = steps.stream()
                    .filter(item -> item.id().equals(stepId) && item.status() == Status.PENDING)
                    .findFirst()
                    .orElseThrow(() -> error(APPROVAL_CONFLICT, "该步骤已办理或尚未开始"));
            Instance instance = record.instance();
            Spec spec = spec(record);
            if (!eligible(caller, app, instance, spec, steps, step)) {
                throw error(APPROVAL_FORBIDDEN, "当前人员无权办理此步骤");
            }
            boolean approved = command.decision() == Decision.APPROVE;
            if (approved) {
                require(
                        rules.viable(app, spec, instance.applicantId(), steps, stepId, caller.subject()),
                        "此决定会使后续步骤无法满足审批人规则，请先调整人选");
            }
            store.sql("""
                    UPDATE approval_instance_step SET status = ?, completed_at = now(), completed_by = ? WHERE id = ?
                    """, approved ? "APPROVED" : "REJECTED", caller.subject(), stepId)
                    .update();
            Status status = Status.REJECTED;
            if (approved) {
                var next = steps.stream()
                        .filter(item -> item.status() == Status.WAITING)
                        .findFirst();
                status = next.map(item -> {
                            store.sql(
                                            "UPDATE approval_instance_step SET status = 'PENDING', activated_at = now() WHERE id = ?",
                                            item.id())
                                    .update();
                            return Status.PENDING;
                        })
                        .orElse(Status.APPROVED);
            } else {
                cancel(id);
            }
            String principal =
                    step.assigneeUserId() == null ? "ROLE:" + step.assigneeRoleId() : "USER:" + caller.subject();
            return new Mutation(
                    status,
                    approved ? "APPROVED_STEP" : "REJECTED_STEP",
                    stepId,
                    principal,
                    command.comment(),
                    List.of());
        });
    }

    @Override
    public Instance withdraw(Caller caller, String app, String id, long revision, String key, String reason) {
        human(caller);
        if (!get(app, id, false).instance().applicantId().equals(caller.subject())) {
            throw error(APPROVAL_NOT_FOUND, "审批申请不存在");
        }
        if (reason != null) {
            text(reason, 2000, "撤回原因");
        }
        return mutate(caller, app, id, revision, key, java.util.Arrays.asList("withdraw", reason), (record, steps) -> {
            if (!record.instance().applicantId().equals(caller.subject())
                    || !spec(record).allowWithdraw()) {
                throw error(APPROVAL_FORBIDDEN, "当前申请不允许撤回");
            }
            cancel(id);
            return new Mutation(Status.WITHDRAWN, "WITHDRAWN", null, null, reason, List.of());
        });
    }

    private boolean eligible(Caller caller, String app, Instance instance, Spec spec, List<Step> steps, Step step) {
        return caller.human()
                && caller.permits(app, "decide")
                && (!spec.excludeApplicant() || !caller.subject().equals(instance.applicantId()))
                && (!spec.distinctApprovers()
                        || steps.stream()
                                .noneMatch(other -> caller.subject().equals(other.completedBy())
                                        || (!other.id().equals(step.id())
                                                && other.status() == Status.WAITING
                                                && caller.subject().equals(other.assigneeUserId()))))
                && directory
                        .person(app, caller.subject())
                        .filter(person -> rules.assigned(step, person))
                        .isPresent();
    }

    @Override
    public void retryDelivery(Caller caller, String app, String id, String reason) {
        human(caller);
        permit(caller, app, "retry");
        text(reason, 2000, "重投原因");
        Record record = get(app, id, false);
        String taskId = record.deliveryTaskId();
        if (taskId == null) {
            throw error(APPROVAL_CONFLICT, "此申请没有可重投的事件");
        }
        if (tasks.find(taskId)
                .filter(task -> task.status().name().equals("FAILED"))
                .isEmpty()) {
            throw error(APPROVAL_CONFLICT, "仅失败的事件可以重投");
        }
        tasks.retryFailed(taskId, caller.subject(), reason);
    }

    private Instance mutate(
            Caller caller,
            String app,
            String id,
            long revision,
            String key,
            Object payload,
            java.util.function.BiFunction<Record, List<Step>, Mutation> operation) {
        human(caller);
        text(key, 128, "幂等键");
        String digest = store.hash(payload);
        return tx.execute(status -> {
            Record record = get(app, id, true);
            var receipt = store.receipt(id, key);
            if (receipt.isPresent()) {
                if (!receipt.get().actor().equals(caller.subject())
                        || !receipt.get().hash().equals(digest)) {
                    throw error(APPROVAL_CONFLICT, "幂等键已经用于其他操作");
                }
                return receipt.get().result();
            }
            if (record.instance().revision() != revision) {
                throw error(APPROVAL_STALE, "申请已更新，请刷新");
            }
            if (record.instance().status() != Status.PENDING) {
                throw error(APPROVAL_CONFLICT, "审批申请已结束");
            }

            Mutation result = operation.apply(record, store.steps(id));
            boolean terminal = result.status() != Status.PENDING;
            store.sql("""
                    UPDATE approval_instance SET status = ?, revision = revision + 1,
                      finished_at = CASE WHEN ? THEN now() ELSE NULL END WHERE id = ?
                    """, result.status().name(), terminal, id).update();
            Instance updated = get(app, id, false).instance();
            if (terminal) {
                Outcome event = new Outcome(
                        1,
                        "approval:" + id,
                        id,
                        app,
                        updated.flowKey(),
                        updated.definitionVersion(),
                        updated.business(),
                        updated.status(),
                        updated.finishedAt());
                String taskId = tasks.submit(TaskSubmission.immediate(OUTCOME_TASK, id, store.json(event)));
                store.sql("UPDATE approval_instance SET delivery_task_id = ? WHERE id = ?", taskId, id)
                        .update();
            }
            if (!terminal) {
                store.steps(id).stream()
                        .filter(step -> step.status() == Status.PENDING)
                        .filter(step -> result.action().equals("APPROVED_STEP")
                                || result.changes().stream()
                                        .anyMatch(change -> change.stepKey()
                                                .equals(step.rule().key())))
                        .findFirst()
                        .ifPresent(step -> reminder.enqueue(updated, step));
            }
            log(
                    updated,
                    caller,
                    key,
                    digest,
                    result.action(),
                    result.stepId(),
                    result.principal(),
                    result.reason(),
                    result.changes());
            return updated;
        });
    }

    private void log(
            Instance result,
            Caller caller,
            String key,
            String digest,
            String action,
            String stepId,
            String principal,
            String reason,
            List<AssignmentChange> changes) {
        store.sql(
                        """
                        INSERT INTO approval_action
                          (id, instance_id, step_id, action, actor_id, matched_principal, reason, changes_jsonb,
                           command_key, command_hash, result_revision, result_jsonb)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb))
                        """,
                        ApprovalStore.id(),
                        result.id(),
                        stepId,
                        action,
                        caller.subject(),
                        principal,
                        reason,
                        store.json(changes),
                        key,
                        digest,
                        result.revision(),
                        store.json(result))
                .update();
    }

    private void cancel(String id) {
        store.sql("""
                UPDATE approval_instance_step SET status = 'CANCELLED', completed_at = now()
                WHERE instance_id = ? AND status IN ('WAITING', 'PENDING')
                """, id).update();
    }

    private Step openStep(List<Step> steps, String key) {
        return steps.stream()
                .filter(step -> step.rule().key().equals(key) && open(step))
                .findFirst()
                .orElseThrow(() -> error(APPROVAL_CONFLICT, "只能调整尚未完成的步骤 " + key));
    }

    private Record get(String app, String id, boolean lock) {
        return store.instance(app, id, lock).orElseThrow(() -> error(APPROVAL_NOT_FOUND, "审批申请不存在"));
    }

    private Spec spec(Record record) {
        return definitions
                .get(record.instance().appCode(), record.definitionId())
                .spec();
    }

    private record Mutation(
            Status status,
            String action,
            String stepId,
            String principal,
            String reason,
            List<AssignmentChange> changes) {}
}
