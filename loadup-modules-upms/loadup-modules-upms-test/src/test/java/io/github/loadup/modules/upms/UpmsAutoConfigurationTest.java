package io.github.loadup.modules.upms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.loadup.modules.upms.app.autoconfigure.UpmsAutoConfiguration;
import io.github.loadup.modules.upms.app.service.AuthenticationService;
import io.github.loadup.modules.upms.app.service.UserService;
import io.github.loadup.modules.upms.app.strategy.LoginStrategyManager;
import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.gateway.LoginLogGateway;
import io.github.loadup.modules.upms.domain.gateway.PermissionGateway;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.gateway.UserOAuthBindingGateway;
import io.github.loadup.modules.upms.domain.service.AccessDecisionService;
import io.github.loadup.modules.upms.domain.service.UserPermissionService;
import java.time.Clock;
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
