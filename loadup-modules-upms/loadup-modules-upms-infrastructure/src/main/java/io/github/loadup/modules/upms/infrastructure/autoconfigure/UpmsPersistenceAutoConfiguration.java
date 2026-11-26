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
package io.github.loadup.modules.upms.infrastructure.autoconfigure;

import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.gateway.LoginLogGateway;
import io.github.loadup.modules.upms.domain.gateway.PermissionGateway;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.gateway.UserGateway;
import io.github.loadup.modules.upms.domain.gateway.UserOAuthBindingGateway;
import io.github.loadup.modules.upms.infrastructure.converter.DepartmentConverter;
import io.github.loadup.modules.upms.infrastructure.converter.LoginLogConverter;
import io.github.loadup.modules.upms.infrastructure.converter.PermissionConverter;
import io.github.loadup.modules.upms.infrastructure.converter.RoleConverter;
import io.github.loadup.modules.upms.infrastructure.converter.UserConverter;
import io.github.loadup.modules.upms.infrastructure.converter.UserOAuthBindingConverter;
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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers replaceable persistence adapters independently of application services.
 */
@AutoConfiguration(beforeName = "io.github.loadup.modules.upms.app.autoconfigure.UpmsAutoConfiguration")
@ComponentScan(basePackageClasses = UserConverter.class)
public class UpmsPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DepartmentGateway.class)
    public DepartmentGatewayImpl departmentGatewayImpl(
            NamedParameterJdbcTemplate jdbc,
            DepartmentMapper mapper,
            UserMapper userMapper,
            DepartmentConverter converter,
            Clock clock) {
        return new DepartmentGatewayImpl(jdbc, mapper, userMapper, converter, clock);
    }

    @Bean
    @ConditionalOnMissingBean(LoginLogGateway.class)
    public LoginLogGatewayImpl loginLogGatewayImpl(JdbcClient jdbc, LoginLogConverter converter, Clock clock) {
        return new LoginLogGatewayImpl(jdbc, converter, clock);
    }

    @Bean
    @ConditionalOnMissingBean(PermissionGateway.class)
    public PermissionGatewayImpl permissionGatewayImpl(
            NamedParameterJdbcTemplate jdbc, PermissionMapper mapper, PermissionConverter converter, Clock clock) {
        return new PermissionGatewayImpl(jdbc, mapper, converter, clock);
    }

    @Bean
    @ConditionalOnMissingBean(RoleGateway.class)
    public RoleGatewayImpl roleGatewayImpl(
            NamedParameterJdbcTemplate jdbc,
            RoleMapper mapper,
            UserRoleMapper userRoleMapper,
            RoleConverter converter,
            Clock clock) {
        return new RoleGatewayImpl(jdbc, mapper, userRoleMapper, converter, clock);
    }

    @Bean
    @ConditionalOnMissingBean(UserGateway.class)
    public UserGatewayImpl userGatewayImpl(
            NamedParameterJdbcTemplate jdbc, UserMapper mapper, UserConverter converter, Clock clock) {
        return new UserGatewayImpl(jdbc, mapper, converter, clock);
    }

    @Bean
    @ConditionalOnMissingBean(UserOAuthBindingGateway.class)
    public UserOAuthBindingGatewayImpl userOAuthBindingGatewayImpl(
            NamedParameterJdbcTemplate jdbc,
            UserOAuthBindingMapper mapper,
            UserOAuthBindingConverter converter,
            Clock clock) {
        return new UserOAuthBindingGatewayImpl(jdbc, mapper, converter, clock);
    }
}
