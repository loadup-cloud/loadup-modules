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

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(name = "ApprovalScope", description = "Eligible users, roles and departments for an approval step")
    public record Scope(Set<String> users, Set<String> roles, Set<String> departments) {
        public Scope {
            users = users == null ? Set.of() : Set.copyOf(users);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            departments = departments == null ? Set.of() : Set.copyOf(departments);
        }
    }

    @Schema(name = "ApprovalRule", description = "Assignment and eligibility policy for an approval step")
    public record Rule(
            String key,
            String name,
            Mode mode,
            Scope scope,
            String defaultUserId,
            String roleId,
            boolean allowOverride) {}

    @Schema(name = "ApprovalSpec", description = "Workflow policies and ordered approval steps")
    public record Spec(
            String name, boolean allowWithdraw, boolean excludeApplicant, boolean distinctApprovers, List<Rule> steps) {
        public Spec {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    @Schema(name = "ApprovalDefinition", description = "Versioned approval flow definition")
    public record Definition(
            String id,
            String appCode,
            String flowKey,

            @Schema(description = "Published flow version number")
            int version,

            @Schema(description = "Current row revision used in the If-Match header")
            long revision,

            Status status,
            Spec spec) {}

    @Schema(name = "ApprovalBusiness", description = "Reference to the business record and its submitted snapshot")
    public record Business(
            String type,
            String id,
            String version,

            @Schema(description = "Digest of the submitted business snapshot")
            String contentHash) {}

    @Schema(name = "ApprovalStart", description = "Request to start an approval instance")
    public record Start(
            String flowKey,

            @Schema(description = "Version read from the active definition before submitting")
            int expectedDefinitionVersion,

            Business business,
            String title,

            @Schema(description = "Selected user IDs keyed by step key")
            Map<String, String> selectedApprovers) {}

    @Schema(name = "ApprovalChange", description = "New assignee for one approval step")
    public record Change(String stepKey, String assigneeId) {}

    @Schema(name = "ApprovalReassign", description = "Batch of approver changes and the reason for making them")
    public record Reassign(List<Change> changes, String reason) {}

    @Schema(name = "ApprovalDecide", description = "Decision for the active approval step")
    public record Decide(
            Decision decision,

            @Schema(description = "Decision comment. Required when the decision is REJECT.")
            String comment) {}

    @Schema(name = "ApprovalAssignmentChange", description = "Recorded change to a step assignment")
    public record AssignmentChange(
            String stepKey, String beforeUserId, String beforeRoleId, String afterUserId, String afterRoleId) {}

    @Schema(name = "ApprovalInstance", description = "Approval request tied to a specific flow definition version")
    public record Instance(
            String id,
            String appCode,
            String flowKey,

            @Schema(description = "Version of the flow definition used at creation")
            int definitionVersion,

            Business business,
            String title,
            String applicantId,
            Status status,

            @Schema(description = "Current row revision used in the If-Match header")
            long revision,

            Instant createdAt,
            Instant finishedAt) {}

    @Schema(name = "ApprovalStep", description = "Approval step with its current assignment and status")
    public record Step(
            String id,
            Rule rule,
            String assigneeUserId,
            String assigneeRoleId,
            Status status,
            String completedBy,
            Instant activatedAt,
            Instant completedAt) {}

    @Schema(name = "ApprovalAction", description = "Recorded action in the approval history")
    public record Action(
            String action,
            String stepId,
            String actorId,
            String matchedPrincipal,
            String reason,
            List<AssignmentChange> changes,
            long revision,
            Instant occurredAt) {}

    @Schema(name = "ApprovalDetail", description = "Approval request details and actions available to the caller")
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

    @Schema(name = "ApprovalTask", description = "Pending approval task and its request")
    public record Task(Instance instance, String stepId, String stepName) {}

    @Schema(name = "ApprovalOption", description = "Selectable directory entry")
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
