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
package io.github.loadup.modules.approval.web;

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

import io.github.loadup.commons.result.FailureResponse;
import io.github.loadup.commons.result.Result;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.components.webmvc.ProtocolResponse;
import io.github.loadup.modules.approval.client.ApprovalException;
import io.github.loadup.modules.approval.client.ApprovalFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "Approvals", description = "Versioned approval flows, approval requests and assigned tasks")
@ProtocolResponse
@RequestMapping("/v1/approval/{app}")
public class ApprovalController {
    private static final String REVISION_DESCRIPTION =
            "Current definition or request revision as a quoted positive integer. A stale revision returns HTTP 412.";
    // Springdoc parses the JSON example before using it as the header value.
    private static final String REVISION_EXAMPLE = "\"\\\"2\\\"\"";
    private static final String IDEMPOTENCY_DESCRIPTION =
            "Unique key for this command. Reuse it with the same request to replay the original result.";

    private final ApprovalFacade approval;

    public ApprovalController(ApprovalFacade approval) {
        this.approval = approval;
    }

    private static Caller caller(JwtAuthenticationToken token) {
        if (token == null || !token.isAuthenticated() || token.getToken().getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String kind = token.getToken().getClaimAsString("principal_type");
        if (!"OPERATOR".equals(kind) && !"SERVICE".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return new Caller(
                token.getToken().getSubject(),
                "OPERATOR".equals(kind),
                token.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet()));
    }

    private static long revision(String header) {
        if (header.matches("\"[1-9][0-9]{0,18}\"")) {
            try {
                return Long.parseLong(header.substring(1, header.length() - 1));
            } catch (NumberFormatException ignored) {
                // The precondition must be a representable version.
            }
        }
        throw new ApprovalException(ApprovalException.Code.APPROVAL_INVALID, "If-Match 需要包含带引号的版本号");
    }

    @GetMapping("/definitions")
    @Operation(summary = "List approval definitions")
    public SuccessResponse<List<Definition>> definitions(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.definitions(caller(token), app, offset, limit));
    }

    @GetMapping("/definitions/{id}")
    @Operation(summary = "Get an approval definition version")
    public SuccessResponse<Definition> definition(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("id") String id) {
        return SuccessResponse.of(approval.definition(caller(token), app, id));
    }

    @PostMapping("/definitions/drafts")
    @Operation(
            summary = "Create an approval draft",
            description = "Provide a source definition ID to copy an existing version of the same flow.")
    public SuccessResponse<Definition> create(
            JwtAuthenticationToken token, @PathVariable("app") String app, @RequestBody Draft draft) {
        return SuccessResponse.of(
                approval.createDraft(caller(token), app, draft.flowKey(), draft.spec(), draft.sourceId()));
    }

    @PutMapping("/definitions/{id}/draft")
    @Operation(summary = "Update an approval draft")
    public SuccessResponse<Definition> save(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision,
            @RequestBody Spec spec) {
        return SuccessResponse.of(approval.saveDraft(caller(token), app, id, revision(revision), spec));
    }

    @PostMapping("/definitions/{id}/publish")
    @Operation(
            summary = "Publish an approval draft",
            description = "Activates the draft and retires the previous active version of the same flow.")
    public SuccessResponse<Definition> publish(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision) {
        return SuccessResponse.of(approval.publish(caller(token), app, id, revision(revision)));
    }

    @PostMapping("/definitions/{id}/retire")
    @Operation(
            summary = "Retire an active approval definition",
            description =
                    "Stops new requests from using this definition. Existing requests continue with their recorded"
                            + " version.")
    public SuccessResponse<Definition> retire(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision) {
        return SuccessResponse.of(approval.retire(caller(token), app, id, revision(revision)));
    }

    @GetMapping("/directory")
    @Operation(
            summary = "Search the approval directory",
            description = "Searches users, roles or departments available to the application.")
    public SuccessResponse<List<Option>> directory(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam("type") DirectoryType type,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.directory(caller(token), app, type, q, offset, limit));
    }

    @GetMapping("/flows/{flowKey}/active")
    @Operation(summary = "Get the active approval definition")
    public SuccessResponse<Definition> active(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("flowKey") String flowKey) {
        return SuccessResponse.of(approval.active(caller(token), app, flowKey));
    }

    @GetMapping("/flows/{flowKey}/steps/{stepKey}/eligible-users")
    @Operation(
            summary = "List eligible approvers for a definition step",
            description = "Use the definition version from the active flow response.")
    public SuccessResponse<List<Option>> candidates(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("flowKey") String flowKey,
            @PathVariable("stepKey") String stepKey,
            @RequestParam("version") int version,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.candidates(caller(token), app, flowKey, version, stepKey, q, offset, limit));
    }

    @PostMapping("/instances")
    @Operation(
            summary = "Start an approval request",
            description = "Submits a business reference using the expected active definition version and any approvers"
                    + " selected by the applicant.")
    public SuccessResponse<Instance> start(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, example = "approval-command-123")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @RequestBody Start command) {
        return SuccessResponse.of(approval.start(caller(token), app, key, command));
    }

    @GetMapping("/instances/{id}")
    @Operation(
            summary = "Get approval request details",
            description = "Includes steps, action history, available actions and outcome delivery status.")
    public SuccessResponse<Detail> detail(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("id") String id) {
        return SuccessResponse.of(approval.detail(caller(token), app, id));
    }

    @GetMapping("/instances")
    @Operation(summary = "List approval requests visible to the caller")
    public SuccessResponse<List<Instance>> instances(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.instances(caller(token), app, offset, limit));
    }

    @GetMapping("/tasks/mine")
    @Operation(summary = "List my pending approval tasks")
    public SuccessResponse<List<Task>> myTasks(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.myTasks(caller(token), app, offset, limit));
    }

    @GetMapping("/instances/{id}/steps/{stepKey}/eligible-users")
    @Operation(
            summary = "List eligible approvers for a request step",
            description = "Uses the definition version recorded on the approval request.")
    public SuccessResponse<List<Option>> instanceCandidates(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @PathVariable("stepKey") String stepKey,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.instanceCandidates(caller(token), app, id, stepKey, q, offset, limit));
    }

    @PatchMapping("/instances/{id}/assignments")
    @Operation(
            summary = "Update approver assignments",
            description = "Applies the assignment changes together after validating the resulting set of approvers.")
    public SuccessResponse<Instance> change(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, example = "approval-command-123")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @RequestBody Reassign command) {
        return SuccessResponse.of(approval.changeAssignees(caller(token), app, id, revision(revision), key, command));
    }

    @PostMapping("/tasks/{stepId}/decisions")
    @Operation(summary = "Approve or reject an assigned step", description = "A rejection requires a comment.")
    public SuccessResponse<Instance> decide(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("stepId") String stepId,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, example = "approval-command-123")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @RequestBody Decide command) {
        return SuccessResponse.of(approval.decide(caller(token), app, stepId, revision(revision), key, command));
    }

    @PostMapping("/instances/{id}/withdraw")
    @Operation(
            summary = "Withdraw a pending approval request",
            description = "The applicant can withdraw while the definition permits withdrawal.")
    public SuccessResponse<Instance> withdraw(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @Parameter(description = REVISION_DESCRIPTION, example = REVISION_EXAMPLE) @RequestHeader("If-Match")
                    String revision,
            @Parameter(description = IDEMPOTENCY_DESCRIPTION, example = "approval-command-123")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @RequestBody Reason command) {
        return SuccessResponse.of(approval.withdraw(caller(token), app, id, revision(revision), key, command.reason()));
    }

    @PostMapping("/instances/{id}/delivery/retry")
    @Operation(
            summary = "Retry delivery of an approval outcome",
            description = "Schedules another attempt to notify the business application of the outcome.")
    public SuccessResponse<Void> retry(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestBody Reason command) {
        approval.retryDelivery(caller(token), app, id, command.reason());
        return SuccessResponse.success();
    }

    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<FailureResponse<ErrorDetail>> approvalError(ApprovalException error) {
        return ResponseEntity.status(error.code().httpStatus)
                .body(new FailureResponse<>(
                        Result.buildFailure(error.code()), new ErrorDetail(error.getMessage()), null));
    }

    @Schema(name = "ApprovalDraft", description = "New or copied approval definition draft")
    public record Draft(
            String flowKey,
            Spec spec,

            @Schema(description = "Optional definition version ID to copy from the same flow")
            String sourceId) {}

    @Schema(name = "ApprovalActionReason")
    public record Reason(
            @Schema(description = "Reason for withdrawal or retrying outcome delivery")
            String reason) {}

    @Schema(name = "ApprovalErrorDetail", description = "Details of an approval failure")
    public record ErrorDetail(String reason) {}
}
