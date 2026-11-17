package io.github.loadup.modules.upms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.loadup.modules.upms.app.autoconfigure.UpmsAutoConfiguration;
import io.github.loadup.modules.upms.app.service.AuthenticationService;
import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.gateway.LoginLogGateway;
import io.github.loadup.modules.upms.domain.gateway.PermissionGateway;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.gateway.UserOAuthBindingGateway;
import io.github.loadup.modules.upms.infrastructure.autoconfigure.UpmsPersistenceAutoConfiguration;
import io.github.loadup.modules.upms.infrastructure.mapper.DepartmentMapper;
import io.github.loadup.modules.upms.infrastructure.mapper.PermissionMapper;
import io.github.loadup.modules.upms.infrastructure.mapper.RoleMapper;
import io.github.loadup.modules.upms.infrastructure.mapper.UserMapper;
import io.github.loadup.modules.upms.infrastructure.mapper.UserOAuthBindingMapper;
import io.github.loadup.modules.upms.infrastructure.mapper.UserRoleMapper;
import io.github.loadup.modules.upms.infrastructure.repository.DepartmentGatewayImpl;
import io.github.loadup.modules.upms.infrastructure.repository.LoginLogGatewayImpl;
import io.github.loadup.modules.upms.infrastructure.repository.PermissionGatewayImpl;
import io.github.loadup.modules.upms.infrastructure.repository.RoleGatewayImpl;
import io.github.loadup.modules.upms.infrastructure.repository.UserGatewayImpl;
import io.github.loadup.modules.upms.infrastructure.repository.UserOAuthBindingGatewayImpl;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class UpmsPersistenceAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UpmsAutoConfiguration.class, UpmsPersistenceAutoConfiguration.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(UserMapper.class, () -> mock(UserMapper.class))
            .withBean(RoleMapper.class, () -> mock(RoleMapper.class))
            .withBean(PermissionMapper.class, () -> mock(PermissionMapper.class))
            .withBean(DepartmentMapper.class, () -> mock(DepartmentMapper.class))
            .withBean(UserOAuthBindingMapper.class, () -> mock(UserOAuthBindingMapper.class))
            .withBean(UserRoleMapper.class, () -> mock(UserRoleMapper.class));

    @Test
    void registersEveryDefaultExactlyOnceBeforeApplicationServices() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AuthenticationService.class);
            assertThat(context).hasSingleBean(UserGateway.class).hasSingleBean(UserGatewayImpl.class);
            assertThat(context).hasSingleBean(RoleGateway.class).hasSingleBean(RoleGatewayImpl.class);
            assertThat(context).hasSingleBean(PermissionGateway.class).hasSingleBean(PermissionGatewayImpl.class);
            assertThat(context).hasSingleBean(DepartmentGateway.class).hasSingleBean(DepartmentGatewayImpl.class);
            assertThat(context).hasSingleBean(LoginLogGateway.class).hasSingleBean(LoginLogGatewayImpl.class);
            assertThat(context).hasSingleBean(UserOAuthBindingGateway.class).hasSingleBean(UserOAuthBindingGatewayImpl.class);
        });
    }

    @Test
    void replacingOneGatewayKeepsAllOtherDefaultsAndApplicationServices() {
        assertReplacement(UserGateway.class);
        assertReplacement(RoleGateway.class);
        assertReplacement(PermissionGateway.class);
        assertReplacement(DepartmentGateway.class);
        assertReplacement(LoginLogGateway.class);
        assertReplacement(UserOAuthBindingGateway.class);
    }

    private <T> void assertReplacement(Class<T> gatewayType) {
        T replacement = mock(gatewayType);
        runner.withBean(gatewayType, () -> replacement).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AuthenticationService.class);
            assertThat(context.getBean(gatewayType)).isSameAs(replacement);
            assertThat(context).hasSingleBean(UserGateway.class);
            assertThat(context.getBeansOfType(UserGatewayImpl.class)).hasSize(gatewayType.equals(UserGateway.class) ? 0 : 1);
            assertThat(context).hasSingleBean(RoleGateway.class);
            assertThat(context.getBeansOfType(RoleGatewayImpl.class)).hasSize(gatewayType.equals(RoleGateway.class) ? 0 : 1);
            assertThat(context).hasSingleBean(PermissionGateway.class);
            assertThat(context.getBeansOfType(PermissionGatewayImpl.class)).hasSize(gatewayType.equals(PermissionGateway.class) ? 0 : 1);
            assertThat(context).hasSingleBean(DepartmentGateway.class);
            assertThat(context.getBeansOfType(DepartmentGatewayImpl.class)).hasSize(gatewayType.equals(DepartmentGateway.class) ? 0 : 1);
            assertThat(context).hasSingleBean(LoginLogGateway.class);
            assertThat(context.getBeansOfType(LoginLogGatewayImpl.class)).hasSize(gatewayType.equals(LoginLogGateway.class) ? 0 : 1);
            assertThat(context).hasSingleBean(UserOAuthBindingGateway.class);
            assertThat(context.getBeansOfType(UserOAuthBindingGatewayImpl.class)).hasSize(gatewayType.equals(UserOAuthBindingGateway.class) ? 0 : 1);
        });
    }
}
