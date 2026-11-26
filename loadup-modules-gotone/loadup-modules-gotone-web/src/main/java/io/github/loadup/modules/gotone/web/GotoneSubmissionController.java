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
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Request;
import io.github.loadup.modules.gotone.client.Gotone.Submit;
import io.github.loadup.modules.gotone.client.error.GotoneResultCode;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/gotone")
public class GotoneSubmissionController {
    private final GotoneFacade gotone;

    public GotoneSubmissionController(GotoneFacade gotone) { this.gotone = gotone; }

    @PostMapping("/submit")
    @PreAuthorize("hasAuthority('SCOPE_gotone:submit')")
    public SuccessResponse<Receipt> submit(@RequestBody Submit command, Authentication authentication) {
        return SuccessResponse.of(gotone.submit(serviceIdentity(authentication), null, command));
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("hasAuthority('SCOPE_gotone:status')")
    public SuccessResponse<Request> request(@PathVariable String id, Authentication authentication) {
        return SuccessResponse.of(gotone.status(serviceIdentity(authentication), id));
    }

    private static String serviceIdentity(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getCredentials() instanceof Jwt jwt) {
            String source = jwt.getClaimAsString("service_id");
            if ("SERVICE".equals(jwt.getClaimAsString("principal_type")) && source != null
                    && !source.isBlank() && source.equals(jwt.getSubject())) return "service:" + source;
        }
        throw new CommonException(GotoneResultCode.SERVICE_IDENTITY_REQUIRED);
    }
}
