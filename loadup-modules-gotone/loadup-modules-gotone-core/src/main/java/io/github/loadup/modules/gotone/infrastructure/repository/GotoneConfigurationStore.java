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
package io.github.loadup.modules.gotone.infrastructure.repository;

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.GOTONE_NOT_FOUND;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_SUBMISSION;
import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.REQUEST_CONFLICT;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import io.github.loadup.modules.gotone.client.Gotone.Template;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneProviderAccountDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneServiceChannelMapDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneServiceDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneServiceDraftDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneServiceRevisionDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneSmsTemplateBindingDO;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneConfigurationStore {
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
    private static final TypeReference<List<String>> PARAMETERS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final JdbcAggregateTemplate aggregates;
    private final GotoneRows rows;
    private final JsonMapper json;

    public GotoneConfigurationStore(
            JdbcClient jdbc, JdbcAggregateTemplate aggregates, GotoneRows rows, JsonMapper json) {
        this.jdbc = jdbc;
        this.aggregates = aggregates;
        this.rows = rows;
        this.json = json;
    }

    private Optional<GotoneServiceDO> service(String code, boolean lock) {
        return jdbc.sql("SELECT * FROM gotone_service WHERE service_code = :code" + (lock ? " FOR UPDATE" : ""))
                .param("code", code)
                .query(GotoneServiceDO.class)
                .optional();
    }

    private Optional<GotoneProviderAccountDO> accountRow(String code, boolean lock) {
        return jdbc.sql("SELECT * FROM gotone_provider_account WHERE account_code = :code"
                        + (lock ? " FOR UPDATE" : ""))
                .param("code", code)
                .query(GotoneProviderAccountDO.class)
                .optional();
    }

    private Optional<GotoneSmsTemplateBindingDO> bindingRow(String mapId, String account, boolean lock) {
        return jdbc.sql("SELECT * FROM gotone_sms_template_binding WHERE map_id = :mapId AND account_code = :account"
                        + (lock ? " FOR UPDATE" : ""))
                .param("mapId", mapId)
                .param("account", account)
                .query(GotoneSmsTemplateBindingDO.class)
                .optional();
    }

    public List<Route> routes() {
        var published = jdbc.sql("SELECT * FROM gotone_service ORDER BY service_code")
                .query(GotoneServiceDO.class)
                .list();
        if (published.isEmpty()) {
            return List.of();
        }
        var codes = published.stream().map(GotoneServiceDO::getServiceCode).toList();
        var versions = published.stream()
                .map(GotoneServiceDO::getActiveRevision)
                .distinct()
                .toList();
        var history = jdbc
                .sql("SELECT * FROM gotone_service_revision WHERE service_code IN (:codes) AND revision IN (:versions)")
                .param("codes", codes)
                .param("versions", versions)
                .query(GotoneServiceRevisionDO.class)
                .list()
                .stream()
                .collect(Collectors.toMap(r -> new Version(r.getServiceCode(), r.getRevision()), r -> r));
        var templates = jdbc
                .sql("""
                    SELECT * FROM gotone_service_channel_map WHERE service_code IN (:codes) AND revision IN (:versions) ORDER BY channel
                    """)
                .param("codes", codes)
                .param("versions", versions)
                .query(GotoneServiceChannelMapDO.class)
                .list()
                .stream()
                .collect(Collectors.groupingBy(r -> new Version(r.getServiceCode(), r.getRevision())));
        return published.stream()
                .map(service -> {
                    var key = new Version(service.getServiceCode(), service.getActiveRevision());
                    return route(service, revision(history.get(key), templates.getOrDefault(key, List.of())));
                })
                .toList();
    }

    public Optional<Revision> active(String code) {
        return service(code, false)
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .map(s -> revision(code, s.getActiveRevision()));
    }

    public List<Route> drafts(String actor) {
        return jdbc
                .sql(
                        "SELECT * FROM gotone_service_draft WHERE actor = :actor AND config IS NOT NULL ORDER BY updated_at DESC")
                .param("actor", actor)
                .query(GotoneServiceDraftDO.class)
                .list()
                .stream()
                .map(draft -> json.readValue(draft.getConfig(), Route.class))
                .toList();
    }

    public void saveDraft(Route route, String actor) {
        var draft = rows.create(new GotoneServiceDraftDO());
        jdbc.sql("""
                    INSERT INTO gotone_service_draft (id, service_code, actor, config, created_at, updated_at)
                    VALUES (:id, :code, :actor, :config, :now, :now)
                    ON CONFLICT (service_code, actor) DO UPDATE
                    SET config = EXCLUDED.config, updated_at = EXCLUDED.updated_at
                    """)
                .param("id", draft.getId())
                .param("code", route.serviceCode())
                .param("actor", actor)
                .param("config", json.writeValueAsString(route))
                .param("now", draft.getCreatedAt())
                .update();
    }

    public Route publish(Route route, String actor) {
        var code = route.serviceCode();
        var current = route.version() == 0 ? rows.create(new GotoneServiceDO()) : lockedService(code, route.version());
        int version = route.version() + 1;
        current.setServiceCode(code);
        current.setName(route.name());
        current.setDescription(route.description());
        current.setEnabled(route.enabled());
        current.setVersion(version);
        current.setActiveRevision(version);
        if (route.version() == 0) {
            try {
                aggregates.insert(current);
            } catch (DuplicateKeyException conflict) {
                throw new CommonException(REQUEST_CONFLICT, conflict);
            }
        } else {
            aggregates.update(rows.touch(current));
        }
        var revision = rows.create(new GotoneServiceRevisionDO());
        revision.setServiceCode(code);
        revision.setRevision(version);
        revision.setPublishedBy(actor);
        revision.setPublishedAt(revision.getCreatedAt());
        revision.setRequiredParameters(json.writeValueAsString(route.requiredParameters()));
        aggregates.insert(revision);
        for (Template template : route.channels()) {
            var mapping = rows.create(new GotoneServiceChannelMapDO());
            mapping.setServiceCode(code);
            mapping.setRevision(version);
            mapping.setChannel(template.channel().name());
            mapping.setFields(json.writeValueAsString(template.fields()));
            aggregates.insert(mapping);
        }
        var draft = jdbc.sql(
                        "SELECT * FROM gotone_service_draft WHERE service_code = :code AND actor = :actor FOR UPDATE")
                .param("code", code)
                .param("actor", actor)
                .query(GotoneServiceDraftDO.class)
                .optional()
                .orElse(null);
        if (draft != null) {
            draft.setConfig(null);
            aggregates.update(rows.touch(draft));
        }
        return route(current, revision(code, version));
    }

    public Route restore(String code, int number, int expectedVersion, String actor) {
        var service = lockedService(code, expectedVersion);
        var old = revision(code, number);
        var restored = publish(
                new Route(
                        code,
                        service.getName(),
                        service.getDescription(),
                        service.getEnabled(),
                        expectedVersion,
                        old.parameters(),
                        old.templates().stream().map(MappedTemplate::template).toList()),
                actor);
        for (var row : jdbc.sql(
                        "SELECT * FROM gotone_sms_template_binding WHERE service_code = :code AND revision = :revision")
                .param("code", code)
                .param("revision", number)
                .query(GotoneSmsTemplateBindingDO.class)
                .list()) {
            var binding = toSmsBinding(row);
            saveSmsBinding(new SmsBinding(
                    code,
                    restored.version(),
                    binding.accountCode(),
                    binding.remoteTemplateId(),
                    binding.remoteSignature(),
                    binding.variableMapping()));
        }
        return restored;
    }

    public List<Account> accounts() {
        return jdbc
                .sql("SELECT * FROM gotone_provider_account ORDER BY priority, account_code")
                .query(GotoneProviderAccountDO.class)
                .list()
                .stream()
                .map(this::account)
                .toList();
    }

    public Optional<Account> account(String code) {
        return accountRow(code, false).map(this::account);
    }

    public Account saveAccount(Account account) {
        var fresh = account.version() == 0;
        var current = fresh
                ? rows.create(new GotoneProviderAccountDO())
                : accountRow(account.accountCode(), true).orElse(null);
        if (!fresh
                && (current == null
                        || current.getVersion() != account.version()
                        || !current.getChannel().equals(account.channel().name())
                        || !current.getAdapter().equals(account.adapter()))) {
            throw new CommonException(REQUEST_CONFLICT);
        }
        if (!fresh
                && account.channel() == Channel.WEBHOOK
                && !fields(current.getSettings()).equals(account.settings())) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        current.setAccountCode(account.accountCode());
        current.setChannel(account.channel().name());
        current.setAdapter(account.adapter());
        current.setEnabled(account.enabled());
        current.setPriority(account.priority());
        current.setSecretRef(account.secretRef());
        current.setConnectTimeoutMs(account.connectTimeoutMs());
        current.setRequestTimeoutMs(account.requestTimeoutMs());
        current.setSettings(json.writeValueAsString(account.settings()));
        current.setVersion(account.version() + 1);
        if (fresh) {
            try {
                aggregates.insert(current);
            } catch (DuplicateKeyException conflict) {
                throw new CommonException(REQUEST_CONFLICT, conflict);
            }
        } else {
            aggregates.update(rows.touch(current));
        }
        return account(current);
    }

    public List<SmsBinding> smsBindings() {
        return jdbc
                .sql("SELECT * FROM gotone_sms_template_binding ORDER BY service_code, revision, account_code")
                .query(GotoneSmsTemplateBindingDO.class)
                .list()
                .stream()
                .map(this::toSmsBinding)
                .toList();
    }

    public void saveSmsBinding(SmsBinding binding) {
        var mapping = jdbc.sql("""
                    SELECT * FROM gotone_service_channel_map WHERE service_code = :code AND revision = :revision AND channel = :channel FOR UPDATE
                    """)
                .param("code", binding.serviceCode())
                .param("revision", binding.revision())
                .param("channel", Channel.SMS.name())
                .query(GotoneServiceChannelMapDO.class)
                .optional()
                .orElse(null);
        if (mapping == null
                || account(binding.accountCode())
                        .filter(a -> a.channel() == Channel.SMS)
                        .isEmpty()) {
            throw new CommonException(GOTONE_NOT_FOUND);
        }
        var revision = revisionRow(binding.serviceCode(), binding.revision());
        if (!json.readValue(revision.getRequiredParameters(), PARAMETERS)
                .containsAll(binding.variableMapping().values())) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        var current = bindingRow(mapping.getId(), binding.accountCode(), true).orElse(null);
        boolean fresh = current == null;
        if (fresh) {
            current = rows.create(new GotoneSmsTemplateBindingDO());
        }
        current.setMapId(mapping.getId());
        current.setServiceCode(binding.serviceCode());
        current.setRevision(binding.revision());
        current.setAccountCode(binding.accountCode());
        current.setRemoteTemplateId(binding.remoteTemplateId());
        current.setRemoteSignature(binding.remoteSignature());
        current.setVariableMapping(json.writeValueAsString(binding.variableMapping()));
        if (fresh) {
            aggregates.insert(current);
        } else {
            aggregates.update(rows.touch(current));
        }
    }

    public Optional<Binding> binding(String mapId, String account) {
        return bindingRow(mapId, account, false)
                .map(b -> new Binding(b.getRemoteTemplateId(), b.getRemoteSignature(), fields(b.getVariableMapping())));
    }

    private GotoneServiceDO lockedService(String code, int version) {
        var row = service(code, true).orElse(null);
        if (row == null) {
            throw new CommonException(GOTONE_NOT_FOUND);
        }
        if (row.getVersion() != version) {
            throw new CommonException(REQUEST_CONFLICT);
        }
        return row;
    }

    private Route route(GotoneServiceDO service, Revision revision) {
        return new Route(
                service.getServiceCode(),
                service.getName(),
                service.getDescription(),
                service.getEnabled(),
                service.getVersion(),
                revision.parameters(),
                revision.templates().stream().map(MappedTemplate::template).toList());
    }

    private Revision revision(String code, int number) {
        var mappings = jdbc.sql(
                        "SELECT * FROM gotone_service_channel_map WHERE service_code = :code AND revision = :revision ORDER BY channel")
                .param("code", code)
                .param("revision", number)
                .query(GotoneServiceChannelMapDO.class)
                .list();
        return revision(revisionRow(code, number), mappings);
    }

    private GotoneServiceRevisionDO revisionRow(String code, int number) {
        return jdbc.sql("SELECT * FROM gotone_service_revision WHERE service_code = :code AND revision = :revision")
                .param("code", code)
                .param("revision", number)
                .query(GotoneServiceRevisionDO.class)
                .optional()
                .orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
    }

    private Revision revision(GotoneServiceRevisionDO revision, List<GotoneServiceChannelMapDO> mappings) {
        return new Revision(
                revision.getRevision(),
                json.readValue(revision.getRequiredParameters(), PARAMETERS),
                mappings.stream()
                        .map(m -> new MappedTemplate(
                                m.getId(), new Template(Channel.valueOf(m.getChannel()), fields(m.getFields()))))
                        .toList());
    }

    private Account account(GotoneProviderAccountDO row) {
        return new Account(
                row.getAccountCode(),
                Channel.valueOf(row.getChannel()),
                row.getAdapter(),
                row.getEnabled(),
                row.getPriority(),
                row.getSecretRef(),
                row.getConnectTimeoutMs(),
                row.getRequestTimeoutMs(),
                row.getVersion(),
                fields(row.getSettings()));
    }

    private SmsBinding toSmsBinding(GotoneSmsTemplateBindingDO row) {
        return new SmsBinding(
                row.getServiceCode(),
                row.getRevision(),
                row.getAccountCode(),
                row.getRemoteTemplateId(),
                row.getRemoteSignature(),
                fields(row.getVariableMapping()));
    }

    private Map<String, String> fields(String encoded) {
        return json.readValue(encoded, FIELDS);
    }

    public record MappedTemplate(String id, Template template) {}

    public record Revision(int number, List<String> parameters, List<MappedTemplate> templates) {}

    public record Binding(String remoteTemplateId, String remoteSignature, Map<String, String> variables) {}

    private record Version(String serviceCode, int revision) {}
}
