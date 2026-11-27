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
package io.github.loadup.modules.upms.app.service;

import io.github.loadup.commons.enums.CommonResultCodeEnum;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.commons.log.LogUtil;
import io.github.loadup.modules.upms.client.command.UserPasswordResetCommand;
import io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum;
import io.github.loadup.modules.upms.client.facade.PasswordPolicyFacade;
import io.github.loadup.modules.upms.client.facade.PasswordResetFacade;
import io.github.loadup.modules.upms.client.spi.PasswordResetEmailSender;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.model.User;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService implements PasswordResetFacade {
    private static final String EMAIL = "EMAIL";
    private static final String SMS = "SMS";
    private final Clock clock;

    private final UserGateway userGateway;
    private final VerificationCodeService verificationCodeService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyFacade passwordPolicies;
    private final Optional<PasswordResetEmailSender> emailSender;

    public PasswordResetService(
            UserGateway userGateway,
            VerificationCodeService verificationCodeService,
            PasswordEncoder passwordEncoder,
            Clock clock,
            PasswordPolicyFacade passwordPolicies,
            Optional<PasswordResetEmailSender> emailSender) {
        this.clock = clock;
        this.userGateway = userGateway;
        this.verificationCodeService = verificationCodeService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicies = passwordPolicies;
        this.emailSender = emailSender;
    }

    @Override
    public void sendEmailVerificationCode(String email) {
        var sender = emailSender.orElseThrow(() -> new CommonException(UpmsResultCodeEnum.PASSWORD_RESET_UNAVAILABLE));
        userGateway
                .findByEmail(email)
                .filter(User::isEnabled)
                .filter(user -> Boolean.TRUE.equals(user.getEmailVerified()))
                .ifPresent(user -> verificationCodeService
                        .issueResetCode(user.getId(), user.getEmail(), EMAIL)
                        .ifPresent(code -> sendEmailCode(sender, user, code)));
    }

    private void sendEmailCode(PasswordResetEmailSender sender, User user, String code) {
        try {
            sender.send(user.getEmail(), code);
        } catch (RuntimeException failure) {
            verificationCodeService.discardResetCode(user.getId(), user.getEmail(), EMAIL, code);
            LogUtil.warn(
                    PasswordResetService.class,
                    "Password reset email could not be delivered ({})",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public void sendSmsVerificationCode(String mobile) {
        User user = userGateway
                .findByMobile(mobile)
                .orElseThrow(() -> new CommonException(UpmsResultCodeEnum.PHONE_NUMBER_IS_NOT_REGISTERED));
        if (verificationCodeService.issueResetCode(user.getId(), mobile, SMS).isEmpty()) {
            throw new CommonException(UpmsResultCodeEnum.VERIFICATION_REQUESTS_ARE_RATE_LIMITED);
        }
    }

    /** Reset password with verification code */
    @Transactional
    public void resetPassword(UserPasswordResetCommand command) {
        if (command == null) {
            throw new CommonException(CommonResultCodeEnum.PARAM_ILLEGAL);
        }
        passwordPolicies.currentPolicy().requireValid(command.getNewPassword());
        // Check new password confirmation
        if (!command.getNewPassword().equals(command.getConfirmPassword())) {
            throw new CommonException(UpmsResultCodeEnum.PASSWORD_CONFIRMATION_DOES_NOT_MATCH);
        }

        boolean email = EMAIL.equalsIgnoreCase(command.getVerificationType());
        if (!email && !SMS.equalsIgnoreCase(command.getVerificationType())) {
            throw new CommonException(UpmsResultCodeEnum.UNSUPPORTED_VERIFICATION_METHOD);
        }
        User user = userGateway
                .findByUsername(command.getUsername())
                .filter(User::isEnabled)
                .orElseThrow(PasswordResetService::invalidCode);
        String target = email ? user.getEmail() : user.getMobile();
        if (target == null
                || (email && !Boolean.TRUE.equals(user.getEmailVerified()))
                || !verificationCodeService.consumeResetCode(
                        user.getId(), target, email ? EMAIL : SMS, command.getVerificationCode())) {
            throw invalidCode();
        }

        String hash = passwordEncoder.encode(command.getNewPassword());
        if (email) {
            if (!userGateway.resetPasswordWithVerifiedEmail(user.getId(), target, user.getPassword(), hash)) {
                throw invalidCode();
            }
        } else {
            user.setPassword(hash);
            user.setPasswordUpdateTime(LocalDateTime.now(clock));
            userGateway.update(user);
        }
    }

    private static CommonException invalidCode() {
        return new CommonException(UpmsResultCodeEnum.VERIFICATION_CODE_IS_INVALID_OR_EXPIRED);
    }
}
