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
import static io.github.loadup.modules.approval.client.ApprovalException.Code.*;

import io.github.loadup.modules.approval.client.ApprovalDirectory;
import io.github.loadup.modules.approval.client.ApprovalException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ApprovalRules {
    private final ApprovalDirectory directory;

    ApprovalRules(ApprovalDirectory directory) {
        this.directory = directory;
    }

    static ApprovalException error(ApprovalException.Code code, String message) {
        return new ApprovalException(code, message);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw error(APPROVAL_INVALID, message);
    }

    static void permit(Caller caller, String app, String permission) {
        if (!caller.permits(app, permission)) throw error(APPROVAL_FORBIDDEN, "没有此审批操作权限");
    }

    static void human(Caller caller) {
        if (!caller.human()) throw error(APPROVAL_FORBIDDEN, "此操作需要个人身份");
    }

    static void key(String value) {
        require(value != null && value.matches("[a-z][a-z0-9_.-]{0,63}"), "审批标识格式无效");
    }

    static void text(String value, int length, String label) {
        require(value != null && !value.isBlank() && value.length() <= length, label + "不能为空或超出长度");
    }

    static void page(int offset, int limit) {
        require(offset >= 0 && limit >= 1 && limit <= 100, "分页参数无效");
    }

    void validateSpec(String app, Spec spec, boolean publish) {
        require(spec != null, "请填写流程");
        text(spec.name(), 200, "流程名称");
        require(spec.steps().size() <= 32 && (!publish || !spec.steps().isEmpty()), "流程需有 1 至 32 个审批步骤");
        Set<String> keys = new HashSet<>();
        for (Rule rule : spec.steps()) {
            key(rule.key());
            require(keys.add(rule.key()), "步骤标识重复 " + rule.key());
            text(rule.name(), 200, "步骤名称");
            require(rule.mode() != null && rule.scope() != null, "请设置步骤指派方式和候选范围 " + rule.key());
            if (publish) validatePrincipals(app, rule);
        }
    }

    private void validatePrincipals(String app, Rule rule) {
        for (String id : rule.scope().users()) validateIdentity(app, DirectoryType.USER, id);
        for (String id : rule.scope().roles()) validateIdentity(app, DirectoryType.ROLE, id);
        for (String id : rule.scope().departments()) validateIdentity(app, DirectoryType.DEPARTMENT, id);
        switch (rule.mode()) {
            case ROLE -> {
                require(rule.defaultUserId() == null && !rule.allowOverride(), "角色待办请设置角色 " + rule.key());
                validateIdentity(app, DirectoryType.ROLE, rule.roleId());
            }
            case USER -> {
                require(rule.roleId() == null, "指定用户步骤不能设置共享角色 " + rule.key());
                validateUser(app, rule, rule.defaultUserId());
            }
            case SELECT_AT_START -> {
                require(rule.roleId() == null, "选人步骤请在候选范围设置角色 " + rule.key());
                if (rule.defaultUserId() != null) validateUser(app, rule, rule.defaultUserId());
            }
        }
    }

    private void validateIdentity(String app, DirectoryType type, String id) {
        text(id, 200, "候选主体");
        require(directory.exists(app, type, id), "候选主体不可用 " + id);
    }

    void validateUser(String app, Rule rule, String id) {
        text(id, 200, "步骤 " + rule.name() + " 的审批人");
        require(directory.person(app, id).filter(person -> matches(rule, person)).isPresent(),
                "审批人不符合步骤 " + rule.name() + " 的候选资格");
    }

    boolean matches(Rule rule, Person person) {
        Scope scope = rule.scope();
        return (scope.users().isEmpty() || scope.users().contains(person.id()))
                && (scope.roles().isEmpty() || person.roleIds().stream().anyMatch(scope.roles()::contains))
                && (scope.departments().isEmpty() || (person.departmentId() != null
                        && scope.departments().contains(person.departmentId())))
                && (rule.roleId() == null || person.roleIds().contains(rule.roleId()));
    }

    boolean assigned(Step step, Person person) {
        return matches(step.rule(), person)
                && (step.assigneeUserId() == null || step.assigneeUserId().equals(person.id()));
    }

    static boolean open(Step step) {
        return step.status() == Status.WAITING || step.status() == Status.PENDING;
    }

    boolean viable(String app, Spec spec, String applicant, List<Step> steps, String decidingStep, String actor) {
        Set<String> excluded = new HashSet<>();
        if (spec.excludeApplicant()) excluded.add(applicant);
        if (spec.distinctApprovers()) {
            for (Step step : steps) {
                if (step.completedBy() != null) excluded.add(step.completedBy());
            }
            if (actor != null && !excluded.add(actor)) return false;
        } else if (actor != null && excluded.contains(actor)) {
            return false;
        }
        var remaining = steps.stream().filter(ApprovalRules::open)
                .filter(step -> !step.id().equals(decidingStep)).toList();
        List<Set<String>> candidates = new ArrayList<>();
        List<Person> directoryPeople = remaining.stream().anyMatch(step -> step.assigneeUserId() == null)
                ? directory.people(app).toList() : List.of();
        for (Step step : remaining) {
            var people = step.assigneeUserId() == null ? directoryPeople.stream()
                    : directory.person(app, step.assigneeUserId()).stream();
            Set<String> ids = new HashSet<>();
            people.filter(person -> assigned(step, person) && !excluded.contains(person.id()))
                    .forEach(person -> ids.add(person.id()));
            if (ids.isEmpty()) return false;
            candidates.add(ids);
        }
        if (!spec.distinctApprovers()) return true;
        Map<String, Integer> assignments = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            if (!assign(index, candidates, assignments, new HashSet<>())) return false;
        }
        return true;
    }

    private boolean assign(int index, List<Set<String>> candidates, Map<String, Integer> assignments, Set<String> seen) {
        for (String person : candidates.get(index)) {
            if (!seen.add(person)) continue;
            Integer prior = assignments.get(person);
            if (prior == null || assign(prior, candidates, assignments, seen)) {
                assignments.put(person, index);
                return true;
            }
        }
        return false;
    }
}
