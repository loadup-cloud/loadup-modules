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
package io.github.loadup.modules.gotone.app.autoconfigure;

import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.components.kms.KmsSecretManager;
import io.github.loadup.components.task.TaskAutoConfiguration;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.app.integration.GotoneProviders;
import io.github.loadup.modules.gotone.app.integration.GotoneSecrets;
import io.github.loadup.modules.gotone.app.integration.email.SmtpGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.push.FcmGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.push.HuaweiPushGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.push.JpushGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.push.XiaomiPushGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.sms.AliyunSmsGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.sms.TencentSmsGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.sms.TwilioSmsProvider;
import io.github.loadup.modules.gotone.app.integration.webhook.DingtalkWebhookGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.webhook.FeishuWebhookGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.webhook.WebhookGotoneProvider;
import io.github.loadup.modules.gotone.app.integration.webhook.WecomWebhookGotoneProvider;
import io.github.loadup.modules.gotone.app.service.EmailDeliveryHandler;
import io.github.loadup.modules.gotone.app.service.GotoneAdministration;
import io.github.loadup.modules.gotone.app.service.GotoneDeliveryReconciliation;
import io.github.loadup.modules.gotone.app.service.GotoneSubmissionService;
import io.github.loadup.modules.gotone.app.service.GotoneTemplates;
import io.github.loadup.modules.gotone.app.service.InboxDeliveryHandler;
import io.github.loadup.modules.gotone.app.service.PushDeliveryHandler;
import io.github.loadup.modules.gotone.app.service.SmsDeliveryHandler;
import io.github.loadup.modules.gotone.app.service.WebhookDeliveryHandler;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneDeliveryAttemptMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneDeliveryMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneDeliveryResolutionMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneDeliveryRetryMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneProviderAccountMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneRequestMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceChannelMapMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceDraftMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceRevisionMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneSmsTemplateBindingMapper;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneRows;
import io.github.loadup.modules.inbox.client.facade.InboxReceiver;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(after = TaskAutoConfiguration.class)
@ConditionalOnBean(TaskTemplate.class)
@ConditionalOnProperty(prefix = "loadup.modules.gotone", name = "enabled", matchIfMissing = true)
@Import(GotoneAutoConfiguration.SmtpConfiguration.class)
public class GotoneAutoConfiguration {
    @Bean
    GotoneRows gotoneRows(IdGenerator ids, Clock clock) {
        return new GotoneRows(ids, clock);
    }

    @Bean
    GotoneConfigurationStore gotoneConfigurationStore(
            GotoneServiceMapper services,
            GotoneServiceDraftMapper drafts,
            GotoneServiceRevisionMapper revisions,
            GotoneServiceChannelMapMapper channels,
            GotoneProviderAccountMapper accounts,
            GotoneSmsTemplateBindingMapper bindings,
            GotoneRows rows,
            JsonMapper json) {
        return new GotoneConfigurationStore(services, drafts, revisions, channels, accounts, bindings, rows, json);
    }

    @Bean
    GotoneDeliveryStore gotoneDeliveryStore(
            GotoneRequestMapper requests,
            GotoneDeliveryMapper deliveries,
            GotoneDeliveryAttemptMapper attempts,
            GotoneDeliveryRetryMapper retries,
            GotoneDeliveryResolutionMapper resolutions,
            GotoneRows rows,
            JsonMapper json) {
        return new GotoneDeliveryStore(requests, deliveries, attempts, retries, resolutions, rows, json);
    }

    @Bean
    GotoneTemplates gotoneTemplates(JsonMapper json) {
        return new GotoneTemplates(json);
    }

    @Bean
    GotoneSecrets gotoneSecrets(ObjectProvider<KmsSecretManager> kms, Environment environment) {
        return new GotoneSecrets(kms, environment);
    }

    @Bean
    GotoneProvider twilioGotoneProvider(JsonMapper json) {
        return new TwilioSmsProvider(json);
    }

    @Bean
    GotoneProvider aliyunGotoneProvider(JsonMapper json, Clock clock) {
        return new AliyunSmsGotoneProvider(json, clock);
    }

    @Bean
    GotoneProvider fcmGotoneProvider(JsonMapper json, Clock clock) {
        return new FcmGotoneProvider(json, clock);
    }

    @Bean
    GotoneProvider tencentSmsGotoneProvider(JsonMapper json, Clock clock) {
        return new TencentSmsGotoneProvider(json, clock);
    }

    @Bean
    GotoneProvider jpushGotoneProvider(JsonMapper json) {
        return new JpushGotoneProvider(json);
    }

    @Bean
    GotoneProvider xiaomiPushGotoneProvider(JsonMapper json) {
        return new XiaomiPushGotoneProvider(json);
    }

    @Bean
    GotoneProvider huaweiPushGotoneProvider(JsonMapper json) {
        return new HuaweiPushGotoneProvider(json);
    }

    @Bean
    GotoneProvider dingtalkWebhookGotoneProvider(JsonMapper json, Clock clock) {
        return new DingtalkWebhookGotoneProvider(json, clock);
    }

    @Bean
    GotoneProvider wecomWebhookGotoneProvider(JsonMapper json) {
        return new WecomWebhookGotoneProvider(json);
    }

    @Bean
    GotoneProvider feishuWebhookGotoneProvider(JsonMapper json, Clock clock) {
        return new FeishuWebhookGotoneProvider(json, clock);
    }

    @Bean
    GotoneProvider webhookGotoneProvider(Environment environment) {
        Set<String> hosts = Arrays.stream(environment
                        .getProperty("loadup.modules.gotone.webhook.allowed-hosts", "")
                        .split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toSet());
        return new WebhookGotoneProvider(hosts);
    }

    @Bean
    GotoneProviders gotoneProviders(List<GotoneProvider> providers) {
        return new GotoneProviders(providers);
    }

    @Bean(defaultCandidate = false)
    TransactionTemplate gotoneTransactions(PlatformTransactionManager transactionManager) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    @Bean(destroyMethod = "shutdown", defaultCandidate = false)
    ExecutorService gotoneTransportExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    GotoneDeliveryReconciliation gotoneDeliveryReconciliation(
            GotoneDeliveryStore store,
            TaskTemplate tasks,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx) {
        return new GotoneDeliveryReconciliation(store, tasks, tx, clock);
    }

    @Bean
    GotoneSubmissionService gotoneSubmissionService(
            GotoneConfigurationStore configuration,
            GotoneDeliveryStore store,
            TaskTemplate tasks,
            Clock clock,
            JsonMapper json,
            GotoneTemplates templates,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            GotoneDeliveryReconciliation reconciliation) {
        return new GotoneSubmissionService(configuration, store, tasks, clock, json, templates, tx, reconciliation);
    }

    @Bean
    GotoneAdministration gotoneAdministration(
            GotoneConfigurationStore configuration,
            GotoneDeliveryStore store,
            GotoneSubmissionService submissions,
            GotoneTemplates templates,
            GotoneProviders providers,
            GotoneDeliveryReconciliation reconciliation,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            TaskTemplate tasks,
            Clock clock) {
        return new GotoneAdministration(
                configuration, store, submissions, templates, providers, reconciliation, tx, tasks, clock);
    }

    @Bean
    SmsDeliveryHandler gotoneSmsHandler(
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            @Qualifier("gotoneTransportExecutor") ExecutorService executor) {
        return new SmsDeliveryHandler(store, configuration, providers, secrets, clock, tx, executor);
    }

    @Bean
    EmailDeliveryHandler gotoneEmailHandler(
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            @Qualifier("gotoneTransportExecutor") ExecutorService executor) {
        return new EmailDeliveryHandler(store, configuration, providers, secrets, clock, tx, executor);
    }

    @Bean
    PushDeliveryHandler gotonePushHandler(
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            @Qualifier("gotoneTransportExecutor") ExecutorService executor) {
        return new PushDeliveryHandler(store, configuration, providers, secrets, clock, tx, executor);
    }

    @Bean
    InboxDeliveryHandler gotoneInboxHandler(
            GotoneDeliveryStore store,
            InboxReceiver inbox,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx) {
        return new InboxDeliveryHandler(store, inbox, clock, tx);
    }

    @Bean
    WebhookDeliveryHandler gotoneWebhookHandler(
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            @Qualifier("gotoneTransactions") TransactionTemplate tx,
            @Qualifier("gotoneTransportExecutor") ExecutorService executor) {
        return new WebhookDeliveryHandler(store, configuration, providers, secrets, clock, tx, executor);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"org.springframework.mail.javamail.JavaMailSenderImpl", "jakarta.mail.Session"})
    static class SmtpConfiguration {
        @Bean
        GotoneProvider smtpGotoneProvider() {
            return new SmtpGotoneProvider();
        }
    }
}
