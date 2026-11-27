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
package io.github.loadup.modules.upms.web;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.modules.upms.client.command.UserPasswordResetCommand;
import io.github.loadup.modules.upms.client.dto.PasswordPolicyDTO;
import io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum;
import io.github.loadup.modules.upms.client.facade.PasswordPolicyFacade;
import io.github.loadup.modules.upms.client.facade.PasswordResetFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "UPMS Passwords", description = "Current password requirements and email recovery")
@SecurityRequirements
public class PasswordController {
    private final PasswordPolicyFacade policies;
    private final PasswordResetFacade resets;

    public PasswordController(PasswordPolicyFacade policies, PasswordResetFacade resets) {
        this.policies = policies;
        this.resets = resets;
    }

    @GetMapping("/password-policy")
    @Operation(summary = "Get the current new-password requirements")
    public SuccessResponse<PasswordPolicyDTO> requirements(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return SuccessResponse.of(policies.currentPolicy().requirements());
    }

    @PostMapping("/password-reset/code")
    @Operation(
            summary = "Request a code for a verified recovery email",
            description = "The response does not disclose whether the email belongs to an eligible account.")
    public SuccessResponse<Void> requestCode(@Valid @RequestBody ResetCodeRequest request) {
        resets.sendEmailVerificationCode(request.email());
        return SuccessResponse.success();
    }

    @PostMapping("/password-reset")
    @Operation(summary = "Reset a password using an email recovery code")
    public SuccessResponse<Void> reset(@Valid @RequestBody UserPasswordResetCommand command) {
        if (!"EMAIL".equalsIgnoreCase(command.getVerificationType())) {
            throw new CommonException(UpmsResultCodeEnum.UNSUPPORTED_VERIFICATION_METHOD);
        }
        resets.resetPassword(command);
        return SuccessResponse.success();
    }

    public record ResetCodeRequest(
            @NotBlank @Email @Size(max = 100) @Schema(description = "Verified account email", example = "user@example.test")
            String email) {
        @Override
        public String toString() {
            return "{\"email\":\"[redacted]\"}";
        }
    }
}
