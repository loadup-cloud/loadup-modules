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
package io.github.loadup.modules.approval.app;

import io.github.loadup.components.task.TaskAutoConfiguration;
import io.github.loadup.components.task.TaskHandler;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.approval.client.ApprovalDirectory;
import io.github.loadup.modules.approval.client.ApprovalFacade;
import io.github.loadup.modules.approval.client.ApprovalOutcomeHandler;
import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import io.github.loadup.modules.approval.infrastructure.UpmsApprovalDirectory;
import io.github.loadup.modules.gotone.client.facade.GotoneFacade;
import io.github.loadup.modules.upms.client.facade.AccountSecurityFacade;
import io.github.loadup.modules.upms.client.facade.DepartmentFacade;
import io.github.loadup.modules.upms.client.facade.RoleFacade;
import io.github.loadup.modules.upms.client.facade.UserFacade;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration(after = TaskAutoConfiguration.class,
        afterName = "io.github.loadup.modules.upms.app.autoconfigure.UpmsAutoConfiguration")
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnBean(TaskTemplate.class)
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalAutoConfiguration {
    @Bean
    ApprovalStore approvalStore(DataSource source) {
        return new ApprovalStore(JdbcClient.create(source));
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalDirectory.class)
    @ConditionalOnBean({UserFacade.class, RoleFacade.class, DepartmentFacade.class, AccountSecurityFacade.class})
    ApprovalDirectory upmsApprovalDirectory(
            UserFacade users, RoleFacade roles, DepartmentFacade departments, AccountSecurityFacade accounts) {
        return new UpmsApprovalDirectory(users, roles, departments, accounts);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalFacade.class)
    @ConditionalOnBean(ApprovalDirectory.class)
    ApprovalFacade approvalFacade(ApprovalStore store, ApprovalDirectory directory, TaskTemplate tasks,
                                  PlatformTransactionManager transactionManager, ApprovalReminder reminder) {
        return new ApprovalService(store, directory, tasks, new TransactionTemplate(transactionManager), reminder);
    }

    @Bean
    ApprovalReminder approvalReminder(ApprovalStore store, ObjectProvider<TaskTemplate> tasks,
                                      ObjectProvider<GotoneFacade> notifications) {
        return new ApprovalReminder(store, tasks, notifications);
    }

    @Bean
    TaskHandler approvalOutcomeTask(ApprovalStore store, ObjectProvider<ApprovalOutcomeHandler> handlers,
                                    ApprovalProperties properties) {
        return new ApprovalDelivery(store, handlers, properties);
    }
}
