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
package io.github.loadup.modules.upms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.upms.app.autoconfigure.UpmsSecurityProperties;
import io.github.loadup.modules.upms.app.converter.UpmsDTOConverter;
import io.github.loadup.modules.upms.app.security.PasswordPolicySettings;
import io.github.loadup.modules.upms.app.service.AuthenticationService;
import io.github.loadup.modules.upms.app.service.PasswordResetService;
import io.github.loadup.modules.upms.app.service.UserService;
import io.github.loadup.modules.upms.app.service.VerificationCodeService;
import io.github.loadup.modules.upms.app.strategy.LoginStrategyManager;
import io.github.loadup.modules.upms.client.command.UserCreateCommand;
import io.github.loadup.modules.upms.client.command.UserPasswordChangeCommand;
import io.github.loadup.modules.upms.client.command.UserPasswordResetCommand;
import io.github.loadup.modules.upms.client.command.UserRegisterCommand;
import io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum;
import io.github.loadup.modules.upms.client.facade.UserQueryFacade;
import io.github.loadup.modules.upms.client.query.UserPageQuery;
import io.github.loadup.modules.upms.client.validation.PasswordPolicy;
import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.gateway.LoginLogGateway;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.model.User;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class ServiceInputContractTest {
    private final UserGateway users = mock(UserGateway.class);
    private final RoleGateway roles = mock(RoleGateway.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    private UserService service() {
        return service(new PasswordPolicySettings(new UpmsSecurityProperties()));
    }

    private UserService service(PasswordPolicySettings policy) {
        return new UserService(
                users,
                roles,
                mock(DepartmentGateway.class),
                passwords,
                mock(UpmsDTOConverter.class),
                Clock.systemUTC(),
                policy);
    }

    @ParameterizedTest
    @EnumSource(PasswordPolicy.class)
    void allPasswordWriteEntryPointsRejectInvalidInputBeforeSideEffects(PasswordPolicy strength) {
        var properties = new UpmsSecurityProperties();
        properties.setPasswordStrength(strength);
        var policy = new PasswordPolicySettings(properties);
        var value = strength == PasswordPolicy.NORMAL ? "short" : "normal-only-password";
        var code = strength == PasswordPolicy.NORMAL
                ? UpmsResultCodeEnum.INVALID_PASSWORD
                : UpmsResultCodeEnum.PASSWORD_STRENGTH_REQUIRED;
        var service = service(policy);
        var create = new UserCreateCommand();
        create.setPassword(value);
        var change = new UserPasswordChangeCommand();
        change.setOldPassword("old");
        change.setNewPassword(value);
        change.setConfirmPassword(value);
        var reset = new UserPasswordResetCommand();
        reset.setNewPassword(value);
        var register = new UserRegisterCommand();
        register.setPassword(value);
        var codes = mock(VerificationCodeService.class);
        var resetService =
                new PasswordResetService(users, codes, passwords, Clock.systemUTC(), policy, Optional.empty());
        var auth = new AuthenticationService(
                users,
                roles,
                mock(LoginLogGateway.class),
                passwords,
                mock(LoginStrategyManager.class),
                mock(UserQueryFacade.class),
                mock(UpmsDTOConverter.class),
                Clock.systemUTC(),
                policy);
        assertThatExceptionOfType(CommonException.class)
                .isThrownBy(() -> service.createUser(create))
                .satisfies(error -> assertThat(error.getResultCode()).isEqualTo(code));
        assertThatExceptionOfType(CommonException.class)
                .isThrownBy(() -> service.changePassword(change))
                .satisfies(error -> assertThat(error.getResultCode()).isEqualTo(code));
        assertThatExceptionOfType(CommonException.class)
                .isThrownBy(() -> resetService.resetPassword(reset))
                .satisfies(error -> assertThat(error.getResultCode()).isEqualTo(code));
        assertThatExceptionOfType(CommonException.class)
                .isThrownBy(() -> auth.register(register))
                .satisfies(error -> assertThat(error.getResultCode()).isEqualTo(code));
        verifyNoInteractions(users, roles, passwords, codes);
    }

    @Test
    void onlyDeliversToVerifiedEmailsAndConsumesResetCodesSeparatelyFromLoginCodes() {
        var clock = Clock.systemUTC();
        var verification = new VerificationCodeService(clock);
        var sentCode = new AtomicReference<String>();
        var service = new PasswordResetService(
                users,
                verification,
                passwords,
                clock,
                () -> PasswordPolicy.NORMAL,
                Optional.of((email, code) -> sentCode.set(code)));
        var user = new User();
        user.setId("user-id");
        user.setStatus((short) 1);
        user.setPassword("old-hash");
        user.setEmail("account@example.test");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByUsername("account")).thenReturn(Optional.of(user));

        service.sendEmailVerificationCode(user.getEmail());
        assertThat(sentCode.get()).isNull();

        user.setEmailVerified(true);
        var command = new UserPasswordResetCommand(
                "account",
                verification.generateEmailCode(user.getEmail()),
                "a-new-password",
                "a-new-password",
                "EMAIL");
        assertThatExceptionOfType(CommonException.class).isThrownBy(() -> service.resetPassword(command));
        verify(users, never()).resetPasswordWithVerifiedEmail(any(), any(), any(), any());

        service.sendEmailVerificationCode(user.getEmail());
        assertThat(sentCode.get()).matches("[0-9]{6}");
        assertThat(verification.verifyEmailCode(user.getEmail(), command.getVerificationCode()))
                .isTrue();
        command.setVerificationCode(sentCode.get());
        when(passwords.encode(command.getNewPassword())).thenReturn("new-hash");
        when(users.resetPasswordWithVerifiedEmail(user.getId(), user.getEmail(), "old-hash", "new-hash"))
                .thenReturn(true);

        service.resetPassword(command);
        assertThatExceptionOfType(CommonException.class).isThrownBy(() -> service.resetPassword(command));
        verify(users).resetPasswordWithVerifiedEmail(user.getId(), user.getEmail(), "old-hash", "new-hash");
    }

    @Test
    void defaultsPaginationAndPassesWhitelistedSortToPersistencePort() {
        var service = service();
        var query = new UserPageQuery();
        query.setPage(null);
        query.setSize(null);
        query.setSortBy("username");
        query.setSortOrder("ASC");
        when(users.findAll(any(), eq("username"), eq("ASC"))).thenReturn(PageResult.of(List.of(), 0, 1, 20));
        var page = service.queryUsers(query);
        assertThat(page.getPageInfo().pageIndex()).isEqualTo(1);
        assertThat(page.getPageInfo().pageSize()).isEqualTo(20);
        verify(users).findAll(argThat(p -> p.pageNum() == 1 && p.pageSize() == 20), eq("username"), eq("ASC"));
        clearInvocations(users);
        query.setSortBy("id DESC; DROP TABLE users");
        assertThatExceptionOfType(CommonException.class).isThrownBy(() -> service.queryUsers(query));
        query.setSortBy("id");
        query.setSize(101);
        assertThatExceptionOfType(CommonException.class).isThrownBy(() -> service.queryUsers(query));
        verifyNoInteractions(users);
    }
}
