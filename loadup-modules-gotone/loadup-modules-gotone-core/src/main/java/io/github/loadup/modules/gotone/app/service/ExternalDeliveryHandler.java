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

import io.github.loadup.components.task.TaskExecution;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider.Outcome;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider.Result;
import io.github.loadup.modules.gotone.app.integration.GotoneProviders;
import io.github.loadup.modules.gotone.app.integration.GotoneSecrets;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.transaction.support.TransactionTemplate;

abstract class ExternalDeliveryHandler extends GotoneDeliveryHandler {
    protected final GotoneSecrets secrets;
    private final GotoneConfigurationStore configuration;
    private final GotoneProviders providers;
    private final ExecutorService transport;
    private final Semaphore capacity = new Semaphore(4);

    ExternalDeliveryHandler(
            Channel channel,
            GotoneDeliveryStore store,
            GotoneConfigurationStore configuration,
            GotoneProviders providers,
            GotoneSecrets secrets,
            Clock clock,
            TransactionTemplate tx,
            ExecutorService transport) {
        super(channel, store, clock, tx);
        this.configuration = configuration;
        this.providers = providers;
        this.secrets = secrets;
        this.transport = transport;
    }

    private static void retryTask() {
        throw new IllegalStateException("Gotone delivery deferred");
    }

    @Override
    protected final void deliver(String id, TaskExecution task) {
        Work work = tx.execute(status -> readWork(id));
        if (work == null) {
            return;
        }
        Future<Boolean> future = transport.submit(() -> {
            if (!capacity.tryAcquire()) {
                return recordDeferred(work, task, "CHANNEL_BUSY");
            }
            try {
                return sendExternal(work, task);
            } finally {
                capacity.release();
            }
        });
        long leaseMillis = task.leaseUntil() == null
                ? 65000
                : Duration.between(LocalDateTime.now(clock), task.leaseUntil()).toMillis() - 1000;
        long deliveryMillis =
                Duration.between(clock.instant(), work.expiresAt()).toMillis();
        long waitMillis = Math.max(1, Math.min(Math.min(65000, leaseMillis), deliveryMillis));
        try {
            if (future.get(waitMillis, TimeUnit.MILLISECONDS)) {
                retryTask();
            }
        } catch (TimeoutException exception) {
            future.cancel(true);
            timeout(work, task);
        } catch (InterruptedException exception) {
            future.cancel(true);
            try {
                timeout(work, task);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (ExecutionException exception) {
            // Do not include adapter exception messages in the task log.
            throw new IllegalStateException("Gotone worker failed");
        }
    }

    protected boolean acceptsAccount(Work work, Account account) {
        return true;
    }

    protected Account resolveDestination(Account account) {
        return account;
    }

    private boolean sendExternal(Work original, TaskExecution task) {
        List<Candidate> candidates = candidates(original);
        for (Candidate candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            Account account = candidate.account();
            String secret;
            try {
                secret = secrets.resolve(account.secretRef());
                if (account.secretRef() != null && (secret == null || secret.isBlank())) {
                    continue;
                }
                account = resolveDestination(account);
                if (account == null) {
                    continue;
                }
            } catch (RuntimeException exception) {
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            Account selected = account;
            Pending pending = tx.execute(status -> {
                Work latest = store.lock(original.id()).orElseThrow();
                if (Thread.currentThread().isInterrupted()
                        || latest.attemptNo() != original.attemptNo()
                        || !latest.status().equals(original.status())) {
                    return null;
                }
                if (!clock.instant().isBefore(latest.expiresAt())) {
                    store.expire(original.id());
                    return null;
                }
                return new Pending(latest, store.start(latest, selected.accountCode(), selected.version()));
            });
            if (pending == null) {
                return false;
            }
            Outcome outcome;
            try {
                outcome = candidate.provider().send(selected, secret, pending.work(), candidate.binding());
            } catch (RuntimeException exception) {
                outcome = Outcome.unknown("ADAPTER_FAILED_WITHOUT_RESULT");
            }
            return finish(pending.work().id(), pending.attempt(), outcome, task);
        }
        return recordDeferred(original, task, "NO_AVAILABLE_ACCOUNT");
    }

    private List<Candidate> candidates(Work work) {
        var tried = store.triedAccounts(work.id());
        return configuration.accounts().stream()
                .filter(account -> account.enabled() && account.channel() == work.channel())
                .filter(account -> acceptsAccount(work, account))
                .flatMap(account -> providers.find(account).stream()
                        .filter(provider -> provider.valid(account))
                        .flatMap(provider -> {
                            if (!provider.requiresSmsBinding()) {
                                return java.util.stream.Stream.of(new Candidate(account, provider, null));
                            }
                            return configuration.binding(work.mapId(), account.accountCode()).stream()
                                    .map(binding -> new Candidate(account, provider, binding));
                        }))
                .sorted(Comparator.comparingInt(
                        candidate -> tried.getOrDefault(candidate.account().accountCode(), 0)))
                .toList();
    }

    private void timeout(Work original, TaskExecution task) {
        String id = original.id();
        boolean retry = Boolean.TRUE.equals(tx.execute(status -> {
            Work work = store.lock(id).orElseThrow();
            if (work.status().equals("SENDING")) {
                store.finish(id, work.attemptNo(), "UNKNOWN", "DELIVERY_DEADLINE", null);
                return false;
            }
            if (!work.status().equals("PENDING") && !work.status().equals("RETRY_WAIT")) {
                return false;
            }
            if (!clock.instant().isBefore(work.expiresAt())) {
                store.expire(id);
                return false;
            }
            if (work.attemptNo() != original.attemptNo()) {
                return work.status().equals("RETRY_WAIT");
            }
            int attempt = store.start(work, null, null);
            return complete(work.id(), attempt, Outcome.retry("PREPARATION_TIMEOUT"), task);
        }));
        if (retry) {
            retryTask();
        }
    }

    private boolean recordDeferred(Work work, TaskExecution task, String reason) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Work latest = store.lock(work.id()).orElseThrow();
            if (latest.attemptNo() != work.attemptNo() || !latest.status().equals(work.status())) {
                return false;
            }
            if (!clock.instant().isBefore(latest.expiresAt())) {
                store.expire(latest.id());
                return false;
            }
            return complete(latest.id(), store.start(latest, null, null), Outcome.retry(reason), task);
        }));
    }

    private boolean finish(String id, int attempt, Outcome outcome, TaskExecution task) {
        return Boolean.TRUE.equals(tx.execute(status -> complete(id, attempt, outcome, task)));
    }

    private boolean complete(String id, int attempt, Outcome outcome, TaskExecution task) {
        boolean retry = outcome.result() == Result.RETRY_WAIT && task.attempts() < task.maxAttempts();
        String status = !retry && outcome.result() == Result.RETRY_WAIT
                ? "FAILED"
                : outcome.result().name();
        String remote = outcome.remoteId();
        if (remote != null && remote.length() > 256) {
            remote = remote.substring(0, 256);
        }
        String reason = outcome.reason();
        if (reason != null && !reason.matches("[A-Z0-9_]{1,64}")) {
            reason = "ADAPTER_RESULT";
        }
        store.finish(id, attempt, status, reason, remote);
        return retry;
    }

    private record Candidate(Account account, GotoneProvider provider, Binding binding) {}

    private record Pending(Work work, int attempt) {}
}
