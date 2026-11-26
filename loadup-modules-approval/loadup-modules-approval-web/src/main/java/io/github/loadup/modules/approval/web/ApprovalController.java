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
import static io.github.loadup.modules.approval.client.Approval.DirectoryType;
import static io.github.loadup.modules.approval.client.Approval.Reassign;
import static io.github.loadup.modules.approval.client.Approval.Spec;
import static io.github.loadup.modules.approval.client.Approval.Start;

import io.github.loadup.commons.result.FailureResponse;
import io.github.loadup.commons.result.Result;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.components.webmvc.ProtocolResponse;
import io.github.loadup.modules.approval.client.ApprovalException;
import io.github.loadup.modules.approval.client.ApprovalFacade;
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
@ProtocolResponse
@RequestMapping("/v1/approval/{app}")
public class ApprovalController {
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
    public SuccessResponse<?> definitions(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.definitions(caller(token), app, offset, limit));
    }

    @GetMapping("/definitions/{id}")
    public SuccessResponse<?> definition(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("id") String id) {
        return SuccessResponse.of(approval.definition(caller(token), app, id));
    }

    @PostMapping("/definitions/drafts")
    public SuccessResponse<?> create(
            JwtAuthenticationToken token, @PathVariable("app") String app, @RequestBody Draft draft) {
        return SuccessResponse.of(
                approval.createDraft(caller(token), app, draft.flowKey(), draft.spec(), draft.sourceId()));
    }

    @PutMapping("/definitions/{id}/draft")
    public SuccessResponse<?> save(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestHeader("If-Match") String revision,
            @RequestBody Spec spec) {
        return SuccessResponse.of(approval.saveDraft(caller(token), app, id, revision(revision), spec));
    }

    @PostMapping("/definitions/{id}/publish")
    public SuccessResponse<?> publish(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestHeader("If-Match") String revision) {
        return SuccessResponse.of(approval.publish(caller(token), app, id, revision(revision)));
    }

    @PostMapping("/definitions/{id}/retire")
    public SuccessResponse<?> retire(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestHeader("If-Match") String revision) {
        return SuccessResponse.of(approval.retire(caller(token), app, id, revision(revision)));
    }

    @GetMapping("/directory")
    public SuccessResponse<?> directory(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam("type") DirectoryType type,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.directory(caller(token), app, type, q, offset, limit));
    }

    @GetMapping("/flows/{flowKey}/active")
    public SuccessResponse<?> active(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("flowKey") String flowKey) {
        return SuccessResponse.of(approval.active(caller(token), app, flowKey));
    }

    @GetMapping("/flows/{flowKey}/steps/{stepKey}/eligible-users")
    public SuccessResponse<?> candidates(
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
    public SuccessResponse<?> start(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Start command) {
        return SuccessResponse.of(approval.start(caller(token), app, key, command));
    }

    @GetMapping("/instances/{id}")
    public SuccessResponse<?> detail(
            JwtAuthenticationToken token, @PathVariable("app") String app, @PathVariable("id") String id) {
        return SuccessResponse.of(approval.detail(caller(token), app, id));
    }

    @GetMapping("/instances")
    public SuccessResponse<?> instances(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.instances(caller(token), app, offset, limit));
    }

    @GetMapping("/tasks/mine")
    public SuccessResponse<?> myTasks(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit) {
        return SuccessResponse.of(approval.myTasks(caller(token), app, offset, limit));
    }

    @GetMapping("/instances/{id}/steps/{stepKey}/eligible-users")
    public SuccessResponse<?> instanceCandidates(
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
    public SuccessResponse<?> change(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestHeader("If-Match") String revision,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Reassign command) {
        return SuccessResponse.of(approval.changeAssignees(caller(token), app, id, revision(revision), key, command));
    }

    @PostMapping("/tasks/{stepId}/decisions")
    public SuccessResponse<?> decide(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("stepId") String stepId,
            @RequestHeader("If-Match") String revision,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Decide command) {
        return SuccessResponse.of(approval.decide(caller(token), app, stepId, revision(revision), key, command));
    }

    @PostMapping("/instances/{id}/withdraw")
    public SuccessResponse<?> withdraw(
            JwtAuthenticationToken token,
            @PathVariable("app") String app,
            @PathVariable("id") String id,
            @RequestHeader("If-Match") String revision,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Reason command) {
        return SuccessResponse.of(approval.withdraw(caller(token), app, id, revision(revision), key, command.reason()));
    }

    @PostMapping("/instances/{id}/delivery/retry")
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

    public record Draft(String flowKey, Spec spec, String sourceId) {}

    public record Reason(String reason) {}

    public record ErrorDetail(String reason) {}
}
