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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Approval {
    private Approval() {}

    public enum Mode {
        USER,
        ROLE,
        SELECT_AT_START
    }

    public enum Status {
        DRAFT,
        ACTIVE,
        RETIRED,
        WAITING,
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED,
        WITHDRAWN
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public enum DirectoryType {
        USER,
        ROLE,
        DEPARTMENT
    }

    /**
     * Constructed by a trusted authentication adapter, never deserialized from request data.
     */
    public record Caller(String subject, boolean human, Set<String> authorities) {
        public Caller {
            authorities = Set.copyOf(authorities);
        }

        public boolean permits(String app, String action) {
            return authorities.contains("approval:" + app + ":" + action);
        }
    }

    public record Scope(Set<String> users, Set<String> roles, Set<String> departments) {
        public Scope {
            users = users == null ? Set.of() : Set.copyOf(users);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            departments = departments == null ? Set.of() : Set.copyOf(departments);
        }
    }

    public record Rule(
            String key,
            String name,
            Mode mode,
            Scope scope,
            String defaultUserId,
            String roleId,
            boolean allowOverride) {}

    public record Spec(
            String name, boolean allowWithdraw, boolean excludeApplicant, boolean distinctApprovers, List<Rule> steps) {
        public Spec {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record Definition(
            String id, String appCode, String flowKey, int version, long revision, Status status, Spec spec) {}

    public record Business(String type, String id, String version, String contentHash) {}

    public record Start(
            String flowKey,
            int expectedDefinitionVersion,
            Business business,
            String title,
            Map<String, String> selectedApprovers) {}

    public record Change(String stepKey, String assigneeId) {}

    public record Reassign(List<Change> changes, String reason) {}

    public record Decide(Decision decision, String comment) {}

    public record AssignmentChange(
            String stepKey, String beforeUserId, String beforeRoleId, String afterUserId, String afterRoleId) {}

    public record Instance(
            String id,
            String appCode,
            String flowKey,
            int definitionVersion,
            Business business,
            String title,
            String applicantId,
            Status status,
            long revision,
            Instant createdAt,
            Instant finishedAt) {}

    public record Step(
            String id,
            Rule rule,
            String assigneeUserId,
            String assigneeRoleId,
            Status status,
            String completedBy,
            Instant activatedAt,
            Instant completedAt) {}

    public record Action(
            String action,
            String stepId,
            String actorId,
            String matchedPrincipal,
            String reason,
            List<AssignmentChange> changes,
            long revision,
            Instant occurredAt) {}

    public record Detail(
            Instance instance,
            List<Step> steps,
            List<Action> actions,
            boolean canAdjust,
            boolean canDecide,
            boolean canWithdraw,
            boolean blocked,
            String deliveryStatus,
            Map<String, String> userLabels,
            Map<String, String> roleLabels) {}

    public record Task(Instance instance, String stepId, String stepName) {}

    public record Option(String id, String label) {}

    public record Person(String id, String label, String departmentId, Set<String> roleIds) {
        public Person {
            roleIds = Set.copyOf(roleIds);
        }
    }

    public record Outcome(
            int schemaVersion,
            String eventId,
            String instanceId,
            String appCode,
            String flowKey,
            int definitionVersion,
            Business business,
            Status outcome,
            Instant finishedAt) {}
}
