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
    public SuccessResponse<List<Route>> routes() {
        return SuccessResponse.of(operations.routes());
    }

    @GetMapping("/routes/drafts")
    public SuccessResponse<List<Route>> drafts(Authentication authentication) {
        return SuccessResponse.of(operations.drafts(actor(authentication)));
    }

    @PutMapping("/routes/drafts")
    public SuccessResponse<Void> saveDraft(@RequestBody Route route, Authentication authentication) {
        operations.saveDraft(route, actor(authentication));
        return SuccessResponse.success();
    }

    @PutMapping("/routes")
    public SuccessResponse<Route> publishRoute(@RequestBody Route route, Authentication authentication) {
        return SuccessResponse.of(operations.publish(route, actor(authentication)));
    }

    @PostMapping("/routes/preview")
    public SuccessResponse<Map<String, String>> previewRoute(@RequestBody Preview preview) {
        return SuccessResponse.of(operations.preview(preview.route(), preview.parameters()));
    }

    @PostMapping("/routes/restore")
    public SuccessResponse<Route> restoreRoute(@RequestBody Restore restore, Authentication authentication) {
        return SuccessResponse.of(operations.restore(
                restore.serviceCode(), restore.revision(), restore.expectedVersion(), actor(authentication)));
    }

    @GetMapping("/accounts")
    public SuccessResponse<List<Account>> accounts() {
        return SuccessResponse.of(operations.accounts());
    }

    @PutMapping("/accounts")
    public SuccessResponse<Account> saveAccount(@RequestBody Account account) {
        return SuccessResponse.of(operations.saveAccount(account));
    }

    @GetMapping("/sms-bindings")
    public SuccessResponse<List<SmsBinding>> smsBindings() {
        return SuccessResponse.of(operations.smsBindings());
    }

    @PutMapping("/sms-bindings")
    public SuccessResponse<Void> saveSmsBinding(@RequestBody SmsBinding binding) {
        operations.saveSmsBinding(binding);
        return SuccessResponse.success();
    }

    @PostMapping("/publish")
    public SuccessResponse<Receipt> publish(@RequestBody Submit command, Authentication authentication) {
        String actor = actor(authentication);
        return SuccessResponse.of(gotone.submit("operator:" + actor, actor, command));
    }

    @GetMapping("/requests")
    public SuccessResponse<List<Receipt>> requests() {
        return SuccessResponse.of(operations.recentRequests());
    }

    @GetMapping("/requests/{id}")
    public SuccessResponse<Request> request(@PathVariable String id) {
        return SuccessResponse.of(operations.request(id));
    }

    @PostMapping("/deliveries/retry")
    public SuccessResponse<Void> retry(@RequestBody Retry retry, Authentication authentication) {
        operations.retry(retry, actor(authentication));
        return SuccessResponse.success();
    }

    @PostMapping("/deliveries/resolve")
    public SuccessResponse<Void> resolve(@RequestBody Resolution resolution, Authentication authentication) {
        operations.resolveUnknown(resolution, actor(authentication));
        return SuccessResponse.success();
    }

    public record Preview(Route route, Map<String, String> parameters) {}

    public record Restore(String serviceCode, int revision, int expectedVersion) {}
}
