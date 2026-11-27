/*
 * Copyright (C) 2026 LoadUp Cloud
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
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.configcenter.ConfigCenterProvider;
import io.github.loadup.components.configcenter.autoconfigure.ConfigCenterAutoConfiguration;
import io.github.loadup.modules.upms.app.autoconfigure.UpmsAutoConfiguration;
import io.github.loadup.modules.upms.app.security.PasswordPolicySettings;
import io.github.loadup.modules.upms.app.service.AuthenticationService;
import io.github.loadup.modules.upms.app.service.PermissionService;
import io.github.loadup.modules.upms.app.service.UserService;
import io.github.loadup.modules.upms.app.strategy.LoginStrategyManager;
import io.github.loadup.modules.upms.client.dto.PermissionDTO;
import io.github.loadup.modules.upms.client.validation.PasswordPolicy.CharacterClass;
import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.gateway.LoginLogGateway;
import io.github.loadup.modules.upms.domain.gateway.PermissionGateway;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.gateway.UserOAuthBindingGateway;
import io.github.loadup.modules.upms.domain.model.Permission;
import io.github.loadup.modules.upms.domain.model.Role;
import io.github.loadup.modules.upms.domain.service.AccessDecisionService;
import io.github.loadup.modules.upms.domain.service.UserPermissionService;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UpmsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader(
                    "io.github.loadup.modules.upms.infrastructure", "org.springframework.jdbc", "com.mybatisflex"))
            .withConfiguration(AutoConfigurations.of(UpmsAutoConfiguration.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(UserGateway.class, () -> mock(UserGateway.class))
            .withBean(RoleGateway.class, () -> mock(RoleGateway.class))
            .withBean(PermissionGateway.class, () -> mock(PermissionGateway.class))
            .withBean(DepartmentGateway.class, () -> mock(DepartmentGateway.class))
            .withBean(LoginLogGateway.class, () -> mock(LoginLogGateway.class))
            .withBean(UserOAuthBindingGateway.class, () -> mock(UserOAuthBindingGateway.class));

    @Test
    void applicationServicesAssembleWithCustomGatewaysWithoutPersistence() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AuthenticationService.class);
            assertThat(context).hasSingleBean(UserService.class).hasSingleBean(LoginStrategyManager.class);
            assertThat(context).hasSingleBean(AccessDecisionService.class).hasSingleBean(UserPermissionService.class);
            assertThat(context).doesNotHaveBean("userGatewayImpl").doesNotHaveBean("upmsPersistenceAutoConfiguration");
        });
    }

    @Test
    void preservesDelegatingAndLegacyBcryptPasswordMatching() {
        runner.run(context -> {
            var encoder = context.getBean(PasswordEncoder.class);
            var encoded = encoder.encode("synthetic-password");
            assertThat(encoded).startsWith("{bcrypt}");
            assertThat(encoder.matches("synthetic-password", encoded)).isTrue();
            assertThat(encoder.matches("synthetic-password", new BCryptPasswordEncoder().encode("synthetic-password")))
                    .isTrue();
            assertThat(encoder.matches("wrong", encoded)).isFalse();
        });
    }

    @Test
    void updatesPasswordStrengthFromConfigCenterAndRestoresTheLocalDefaultOnDeletion() {
        var provider = mock(ConfigCenterProvider.class);
        var unsubscribe = mock(Runnable.class);
        var listener = new AtomicReference<Consumer<String>>();
        when(provider.getConfig(PasswordPolicySettings.CONFIG_KEY)).thenReturn("strong");
        when(provider.subscribe(eq(PasswordPolicySettings.CONFIG_KEY), any())).thenAnswer(call -> {
            listener.set(call.getArgument(1));
            return unsubscribe;
        });

        runner.withConfiguration(AutoConfigurations.of(ConfigCenterAutoConfiguration.class))
                .withBean(ConfigCenterProvider.class, () -> provider)
                .run(context -> {
                    var policy = context.getBean(PasswordPolicySettings.class);
                    var strong = policy.currentPolicy().requirements();
                    assertThat(strong.minCharacters()).isEqualTo(12);
                    assertThat(strong.requiredCharacterClasses())
                            .containsExactly(
                                    CharacterClass.UPPERCASE,
                                    CharacterClass.LOWERCASE,
                                    CharacterClass.DIGIT,
                                    CharacterClass.SYMBOL);
                    assertThatExceptionOfType(CommonException.class)
                            .isThrownBy(() -> policy.currentPolicy().requireValid("normal-only"));
                    policy.currentPolicy().requireValid("ExamplePass1!");
                    listener.get().accept("unsupported");
                    assertThatExceptionOfType(CommonException.class)
                            .isThrownBy(() -> policy.currentPolicy().requireValid("normal-only"));
                    listener.get().accept(null);
                    var normal = policy.currentPolicy().requirements();
                    assertThat(normal.minCharacters()).isEqualTo(8);
                    assertThat(normal.requiredCharacterClasses()).isEmpty();
                    policy.currentPolicy().requireValid("normal-only");
                    listener.get().accept("strong");
                    assertThatExceptionOfType(CommonException.class)
                            .isThrownBy(() -> policy.currentPolicy().requireValid("normal-only"));
                });
        verify(unsubscribe).run();
    }

    @Test
    void showsOnlyGrantedPagesInTheirVisibleMenuGroups() {
        runner.run(context -> {
            var role = new Role();
            role.setId("operator");
            role.setStatus((short) 1);
            var group = menu("operations", null, null, 10);
            var files = menu("files", group.getId(), "/files", 10);
            var transfer = menu("transfer", group.getId(), "/transfer", 20);
            var approval = menu("approval", group.getId(), "/approval", 30);
            var hidden = menu("hidden", group.getId(), "/forms/design", 40);
            hidden.setVisible(false);
            var roles = context.getBean(RoleGateway.class);
            var permissions = context.getBean(PermissionGateway.class);
            when(roles.findByUserId("operator-user")).thenReturn(List.of(role));
            when(permissions.findByRoleId(role.getId())).thenReturn(List.of(transfer, hidden, files));
            when(permissions.findByPermissionType((short) 1))
                    .thenReturn(List.of(approval, transfer, hidden, files, group));

            var service = context.getBean(PermissionService.class);
            assertThat(service.getUserMenuTree("operator-user")).singleElement().satisfies(root -> {
                assertThat(root.getId()).isEqualTo(group.getId());
                assertThat(root.getChildren()).extracting(PermissionDTO::getId).containsExactly("files", "transfer");
            });
            assertThat(service.getUserMenuTree("unassigned-user")).isEmpty();
            group.setStatus((short) 0);
            assertThat(service.getUserMenuTree("operator-user")).isEmpty();
        });
    }

    private static Permission menu(String id, String parentId, String path, int order) {
        var permission = new Permission();
        permission.setId(id);
        permission.setParentId(parentId);
        permission.setResourcePath(path);
        permission.setPermissionCode("admin:menu:" + id);
        permission.setPermissionType((short) 1);
        permission.setSortOrder(order);
        permission.setStatus((short) 1);
        permission.setVisible(true);
        return permission;
    }

    @Test
    void preservesDomainServiceReplacementConditions() {
        var permissions = mock(UserPermissionService.class);
        var decisions = mock(AccessDecisionService.class);
        runner.withBean(UserPermissionService.class, () -> permissions)
                .withBean(AccessDecisionService.class, () -> decisions)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(UserPermissionService.class);
                    assertThat(context).hasSingleBean(AccessDecisionService.class);
                    assertThat(context.getBean(UserPermissionService.class)).isSameAs(permissions);
                    assertThat(context.getBean(AccessDecisionService.class)).isSameAs(decisions);
                });
    }
}
