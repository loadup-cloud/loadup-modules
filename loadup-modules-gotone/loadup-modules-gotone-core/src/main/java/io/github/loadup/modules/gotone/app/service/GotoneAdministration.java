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
package io.github.loadup.modules.gotone.app.service;

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.GOTONE_NOT_FOUND;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_SUBMISSION;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_TEMPLATE;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.REQUEST_CONFLICT;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.task.TaskStatus;
import io.github.loadup.components.task.TaskSubmission;
import io.github.loadup.components.task.TaskTemplate;
import io.github.loadup.modules.gotone.app.integration.GotoneProviders;
import io.github.loadup.modules.gotone.app.integration.GotoneSecrets;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Request;
import io.github.loadup.modules.gotone.client.Gotone.Resolution;
import io.github.loadup.modules.gotone.client.Gotone.Retry;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import io.github.loadup.modules.gotone.client.Gotone.Template;
import io.github.loadup.modules.gotone.client.facade.GotoneOperations;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class GotoneAdministration implements GotoneOperations {
    private final GotoneConfigurationStore configuration;
    private final GotoneDeliveryStore store;
    private final GotoneSubmissionService submissions;
    private final GotoneTemplates templates;
    private final GotoneProviders providers;
    private final GotoneDeliveryReconciliation reconciliation;
    private final TransactionTemplate tx;
    private final TaskTemplate tasks;
    private final Clock clock;

    public GotoneAdministration(
            GotoneConfigurationStore configuration,
            GotoneDeliveryStore store,
            GotoneSubmissionService submissions,
            GotoneTemplates templates,
            GotoneProviders providers,
            GotoneDeliveryReconciliation reconciliation,
            TransactionTemplate tx,
            TaskTemplate tasks,
            Clock clock) {
        this.configuration = configuration;
        this.store = store;
        this.submissions = submissions;
        this.templates = templates;
        this.providers = providers;
        this.reconciliation = reconciliation;
        this.tx = tx;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Override
    public List<Route> routes() {
        return configuration.routes();
    }

    @Override
    public List<Route> drafts(String actor) {
        return configuration.drafts(actor);
    }

    @Override
    public void saveDraft(Route route, String actor) {
        validateShape(route);
        try {
            tx.executeWithoutResult(status -> configuration.saveDraft(route, actor));
        } catch (DuplicateKeyException concurrentInsert) {
            tx.executeWithoutResult(status -> configuration.saveDraft(route, actor));
        }
    }

    @Override
    public Route publish(Route route, String actor) {
        validate(route);
        return tx.execute(status -> configuration.publish(route, actor));
    }

    @Override
    public Map<String, String> preview(Route route, Map<String, String> parameters) {
        validate(route);
        var values = GotoneSubmissionService.parameters(parameters);
        if (!values.keySet().containsAll(route.requiredParameters())) {
            throw new CommonException(INVALID_TEMPLATE);
        }
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        for (Template template : route.channels()) {
            templates
                    .render(template, values)
                    .forEach((key, value) -> result.put(template.channel() + "." + key, value));
        }
        return result;
    }

    @Override
    public Route restore(String serviceCode, int revision, int expectedVersion, String actor) {
        return tx.execute(status -> configuration.restore(
                GotoneSubmissionService.serviceCode(serviceCode), revision, expectedVersion, actor));
    }

    @Override
    public List<Account> accounts() {
        return configuration.accounts();
    }

    @Override
    public Account saveAccount(Account account) {
        if (account == null
                || account.channel() == null
                || account.channel() == Channel.IN_APP
                || account.adapter() == null
                || account.settings() == null
                || account.version() < 0
                || account.connectTimeoutMs() < 100
                || account.connectTimeoutMs() > 5000
                || account.requestTimeoutMs() < account.connectTimeoutMs()
                || account.requestTimeoutMs() > 15000) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        GotoneSubmissionService.serviceCode(account.accountCode());
        if ((account.channel() != Channel.WEBHOOK || account.secretRef() != null)
                && !GotoneSecrets.valid(account.secretRef())) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var adapter = providers.find(account).orElseThrow(() -> new CommonException(INVALID_SUBMISSION));
        if (!adapter.settings().containsAll(account.settings().keySet())
                || account.settings().values().stream()
                        .anyMatch(value -> value == null
                                || value.length() > 2000
                                || value.chars().anyMatch(Character::isISOControl))
                || !adapter.valid(account)) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        return tx.execute(status -> configuration.saveAccount(account));
    }

    @Override
    public List<SmsBinding> smsBindings() {
        return configuration.smsBindings();
    }

    @Override
    public void saveSmsBinding(SmsBinding binding) {
        if (binding == null || binding.revision() < 1) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        GotoneSubmissionService.serviceCode(binding.serviceCode());
        GotoneSubmissionService.serviceCode(binding.accountCode());
        GotoneSubmissionService.required(binding.remoteTemplateId(), 200);
        if (binding.remoteSignature() != null) {
            GotoneSubmissionService.required(binding.remoteSignature(), 200);
        }
        var account = configuration
                .account(binding.accountCode())
                .filter(item -> item.channel() == Channel.SMS)
                .orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
        var adapter = providers.find(account).orElseThrow(() -> new CommonException(INVALID_SUBMISSION));
        Map<String, String> variables = binding.variableMapping() == null ? Map.of() : binding.variableMapping();
        if (variables.size() > 50
                || variables.entrySet().stream()
                        .anyMatch(entry -> entry.getKey() == null
                                || !entry.getKey().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                                || entry.getValue() == null
                                || !entry.getValue().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))
                || !adapter.validSmsBinding(binding.remoteTemplateId(), variables.keySet())) {
            throw new CommonException(INVALID_TEMPLATE);
        }
        tx.executeWithoutResult(status -> configuration.saveSmsBinding(new SmsBinding(
                binding.serviceCode(),
                binding.revision(),
                binding.accountCode(),
                binding.remoteTemplateId(),
                binding.remoteSignature(),
                variables)));
    }

    @Override
    public List<Receipt> recentRequests() {
        return store.recent();
    }

    @Override
    public Request request(String requestId) {
        return submissions.request(requestId);
    }

    @Override
    public void retry(Retry retry, String actor) {
        if (retry == null) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var id = GotoneSubmissionService.id(retry.deliveryId());
        GotoneSubmissionService.required(retry.reason(), 500);
        tx.executeWithoutResult(status -> {
            var work = store.lock(id).orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
            if (!work.status().equals("FAILED") || !clock.instant().isBefore(work.expiresAt())) {
                throw new CommonException(REQUEST_CONFLICT);
            }
            String taskType = GotoneSubmissionService.taskType(work.channel());
            var existing = tasks.find(taskType, id);
            if (existing.isPresent()) {
                if (existing.get().status() != TaskStatus.FAILED) {
                    throw new CommonException(REQUEST_CONFLICT);
                }
                tasks.retryFailed(existing.get().id(), actor, retry.reason());
            } else {
                tasks.submit(TaskSubmission.immediate(taskType, id, id));
            }
            store.retry(work, retry, actor);
        });
    }

    @Override
    public void resolveUnknown(Resolution resolution, String actor) {
        if (resolution == null || resolution.accepted() == null) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var id = GotoneSubmissionService.id(resolution.deliveryId());
        GotoneSubmissionService.required(resolution.reason(), 500);
        tx.executeWithoutResult(status -> {
            var work = store.lock(id).orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
            if (work.status().equals("SENDING")) {
                reconciliation.reconcile(id, work.channel());
            }
            store.resolve(store.lock(id).orElseThrow(), resolution, actor);
        });
    }

    private void validateShape(Route route) {
        if (route == null
                || route.channels() == null
                || route.requiredParameters() == null
                || route.version() < 0
                || route.channels().size() > Channel.values().length
                || route.requiredParameters().size() > 50) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        GotoneSubmissionService.serviceCode(route.serviceCode());
        GotoneSubmissionService.required(route.name(), 200);
        if (route.description() != null
                && (route.description().length() > 500 || route.description().indexOf(0) >= 0)) {
            throw new CommonException(INVALID_SUBMISSION);
        }
    }

    private void validate(Route route) {
        validateShape(route);
        if (route.enabled() && route.channels().isEmpty()) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var parameters = new HashSet<String>();
        for (String parameter : route.requiredParameters()) {
            if (parameter == null || !parameter.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || !parameters.add(parameter)) {
                throw new CommonException(INVALID_TEMPLATE);
            }
        }
        var channels = EnumSet.noneOf(Channel.class);
        for (Template template : route.channels()) {
            if (template == null
                    || template.channel() == null
                    || template.fields() == null
                    || !channels.add(template.channel())
                    || !GotoneTemplates.fields(template.channel())
                            .containsAll(template.fields().keySet())
                    || template.fields().values().stream().anyMatch(value -> value == null || value.indexOf(0) >= 0)) {
                throw new CommonException(INVALID_TEMPLATE);
            }
            if (template.channel() == Channel.WEBHOOK) {
                for (String endpoint : GotoneTemplates.webhookEndpoints(template.fields())) {
                    var account = configuration
                            .account(endpoint)
                            .filter(item -> item.channel() == Channel.WEBHOOK)
                            .orElseThrow(() -> new CommonException(INVALID_SUBMISSION));
                    var adapter = providers.find(account).orElseThrow(() -> new CommonException(INVALID_SUBMISSION));
                    if (!template.fields().containsKey(adapter.webhookBodyField())) {
                        throw new CommonException(INVALID_TEMPLATE);
                    }
                }
            }
            templates.validate(template, parameters);
        }
    }
}
