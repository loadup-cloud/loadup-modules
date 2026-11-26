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
package io.github.loadup.modules.gotone.web;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.components.authorization.model.LoadUpUser;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Request;
import io.github.loadup.modules.gotone.client.Gotone.Resolution;
import io.github.loadup.modules.gotone.client.Gotone.Retry;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import io.github.loadup.modules.gotone.client.Gotone.Submit;
import io.github.loadup.modules.gotone.client.error.GotoneResultCode;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.gotone.client.facade.GotoneOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Gotone Operations", description = "Notification administration for callers with ROLE_SUPER_ADMIN")
@RequestMapping("/gotone")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class GotoneOperationsController {
    private final GotoneFacade gotone;
    private final GotoneOperations operations;

    public GotoneOperationsController(GotoneFacade gotone, GotoneOperations operations) {
        this.gotone = gotone;
        this.operations = operations;
    }

    private static String actor(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoadUpUser user)
                || user.getUserId() == null
                || user.getUserId().isBlank()) {
            throw new CommonException(GotoneResultCode.AUTHENTICATED_USER_IS_REQUIRED);
        }
        return user.getUserId();
    }

    @GetMapping("/routes")
    @Operation(summary = "List published notification routes")
    public SuccessResponse<List<Route>> routes() {
        return SuccessResponse.of(operations.routes());
    }

    @GetMapping("/routes/drafts")
    @Operation(summary = "List my notification route drafts")
    public SuccessResponse<List<Route>> drafts(Authentication authentication) {
        return SuccessResponse.of(operations.drafts(actor(authentication)));
    }

    @PutMapping("/routes/drafts")
    @Operation(summary = "Save a notification route draft")
    public SuccessResponse<Void> saveDraft(@RequestBody Route route, Authentication authentication) {
        operations.saveDraft(route, actor(authentication));
        return SuccessResponse.success();
    }

    @PutMapping("/routes")
    @Operation(
            summary = "Publish a notification route",
            description = "Creates an immutable revision for future notification submissions.")
    public SuccessResponse<Route> publishRoute(@RequestBody Route route, Authentication authentication) {
        return SuccessResponse.of(operations.publish(route, actor(authentication)));
    }

    @PostMapping("/routes/preview")
    @Operation(
            summary = "Preview channel templates",
            description = "Returns rendered fields keyed by channel and field name.")
    public SuccessResponse<Map<String, String>> previewRoute(@RequestBody Preview preview) {
        return SuccessResponse.of(operations.preview(preview.route(), preview.parameters()));
    }

    @PostMapping("/routes/restore")
    @Operation(
            summary = "Restore a previous route revision",
            description = "Copies the historical templates, parameters and SMS bindings into a new route revision.")
    public SuccessResponse<Route> restoreRoute(@RequestBody Restore restore, Authentication authentication) {
        return SuccessResponse.of(operations.restore(
                restore.serviceCode(), restore.revision(), restore.expectedVersion(), actor(authentication)));
    }

    @GetMapping("/accounts")
    @Operation(summary = "List delivery provider accounts")
    public SuccessResponse<List<Account>> accounts() {
        return SuccessResponse.of(operations.accounts());
    }

    @PutMapping("/accounts")
    @Operation(
            summary = "Save a delivery provider account",
            description = "Supply the account settings and a reference to its stored credentials when required.")
    public SuccessResponse<Account> saveAccount(@RequestBody Account account) {
        return SuccessResponse.of(operations.saveAccount(account));
    }

    @GetMapping("/sms-bindings")
    @Operation(summary = "List SMS provider template bindings")
    public SuccessResponse<List<SmsBinding>> smsBindings() {
        return SuccessResponse.of(operations.smsBindings());
    }

    @PutMapping("/sms-bindings")
    @Operation(
            summary = "Save an SMS provider template binding",
            description = "Associates a route revision and provider account with a remote SMS template.")
    public SuccessResponse<Void> saveSmsBinding(@RequestBody SmsBinding binding) {
        operations.saveSmsBinding(binding);
        return SuccessResponse.success();
    }

    @PostMapping("/publish")
    @Operation(
            summary = "Submit a notification as the current operator",
            description = "Returns a receipt when the request is accepted for asynchronous delivery.")
    public SuccessResponse<Receipt> publish(@RequestBody Submit command, Authentication authentication) {
        String actor = actor(authentication);
        return SuccessResponse.of(gotone.submit("operator:" + actor, actor, command));
    }

    @GetMapping("/requests")
    @Operation(summary = "List recent notification request receipts")
    public SuccessResponse<List<Receipt>> requests() {
        return SuccessResponse.of(operations.recentRequests());
    }

    @GetMapping("/requests/{id}")
    @Operation(summary = "Get a notification request and its delivery attempts")
    public SuccessResponse<Request> request(@PathVariable String id) {
        return SuccessResponse.of(operations.request(id));
    }

    @PostMapping("/deliveries/retry")
    @Operation(
            summary = "Retry a failed notification delivery",
            description = "The delivery must be confirmed failed and still within its delivery window.")
    public SuccessResponse<Void> retry(@RequestBody Retry retry, Authentication authentication) {
        operations.retry(retry, actor(authentication));
        return SuccessResponse.success();
    }

    @PostMapping("/deliveries/resolve")
    @Operation(
            summary = "Reconcile an uncertain notification delivery",
            description = "Records whether the provider accepted the delivery after the operator checks its outcome.")
    public SuccessResponse<Void> resolve(@RequestBody Resolution resolution, Authentication authentication) {
        operations.resolveUnknown(resolution, actor(authentication));
        return SuccessResponse.success();
    }

    @Schema(name = "GotoneRoutePreview")
    public record Preview(
            Route route,

            @Schema(description = "Sample values keyed by template parameter name")
            Map<String, String> parameters) {}

    @Schema(name = "GotoneRouteRestore")
    public record Restore(
            String serviceCode,

            @Schema(description = "Historical published revision to restore")
            int revision,

            @Schema(description = "Current route configuration version")
            int expectedVersion) {}
}
