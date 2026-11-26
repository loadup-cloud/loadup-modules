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
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_PROVIDER_ACCOUNT;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SERVICE;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SERVICE_CHANNEL_MAP;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SERVICE_DRAFT;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SERVICE_REVISION;
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SMS_TEMPLATE_BINDING;

import com.mybatisflex.core.query.QueryWrapper;
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
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneProviderAccountMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceChannelMapMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceDraftMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceRevisionMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneSmsTemplateBindingMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneConfigurationStore {
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
    private static final TypeReference<List<String>> PARAMETERS = new TypeReference<>() {};
    private final GotoneServiceMapper services;
    private final GotoneServiceDraftMapper drafts;
    private final GotoneServiceRevisionMapper revisions;
    private final GotoneServiceChannelMapMapper channels;
    private final GotoneProviderAccountMapper accounts;
    private final GotoneSmsTemplateBindingMapper bindings;
    private final GotoneRows rows;
    private final JsonMapper json;

    public GotoneConfigurationStore(
            GotoneServiceMapper services,
            GotoneServiceDraftMapper drafts,
            GotoneServiceRevisionMapper revisions,
            GotoneServiceChannelMapMapper channels,
            GotoneProviderAccountMapper accounts,
            GotoneSmsTemplateBindingMapper bindings,
            GotoneRows rows,
            JsonMapper json) {
        this.services = services;
        this.drafts = drafts;
        this.revisions = revisions;
        this.channels = channels;
        this.accounts = accounts;
        this.bindings = bindings;
        this.rows = rows;
        this.json = json;
    }

    private static QueryWrapper serviceQuery(String code) {
        return QueryWrapper.create().where(GOTONE_SERVICE.SERVICE_CODE.eq(code));
    }

    private static QueryWrapper channelQuery(String code, int revision) {
        return QueryWrapper.create()
                .where(GOTONE_SERVICE_CHANNEL_MAP.SERVICE_CODE.eq(code))
                .and(GOTONE_SERVICE_CHANNEL_MAP.REVISION.eq(revision));
    }

    private static QueryWrapper draftQuery(String code, String actor) {
        return QueryWrapper.create()
                .where(GOTONE_SERVICE_DRAFT.SERVICE_CODE.eq(code))
                .and(GOTONE_SERVICE_DRAFT.ACTOR.eq(actor));
    }

    private static QueryWrapper accountQuery(String code) {
        return QueryWrapper.create().where(GOTONE_PROVIDER_ACCOUNT.ACCOUNT_CODE.eq(code));
    }

    private static QueryWrapper bindingQuery(String mapId, String account) {
        return QueryWrapper.create()
                .where(GOTONE_SMS_TEMPLATE_BINDING.MAP_ID.eq(mapId))
                .and(GOTONE_SMS_TEMPLATE_BINDING.ACCOUNT_CODE.eq(account));
    }

    public List<Route> routes() {
        var published = services.selectListByQuery(QueryWrapper.create().orderBy(GOTONE_SERVICE.SERVICE_CODE.asc()));
        if (published.isEmpty()) {
            return List.of();
        }
        var codes = published.stream().map(GotoneServiceDO::getServiceCode).toList();
        var versions = published.stream()
                .map(GotoneServiceDO::getActiveRevision)
                .distinct()
                .toList();
        var history = revisions
                .selectListByQuery(QueryWrapper.create()
                        .where(GOTONE_SERVICE_REVISION.SERVICE_CODE.in(codes))
                        .and(GOTONE_SERVICE_REVISION.REVISION.in(versions)))
                .stream()
                .collect(Collectors.toMap(r -> new Version(r.getServiceCode(), r.getRevision()), r -> r));
        var templates = channels
                .selectListByQuery(QueryWrapper.create()
                        .where(GOTONE_SERVICE_CHANNEL_MAP.SERVICE_CODE.in(codes))
                        .and(GOTONE_SERVICE_CHANNEL_MAP.REVISION.in(versions))
                        .orderBy(GOTONE_SERVICE_CHANNEL_MAP.CHANNEL.asc()))
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
        return Optional.ofNullable(services.selectOneByQuery(serviceQuery(code)))
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .map(s -> revision(code, s.getActiveRevision()));
    }

    public List<Route> drafts(String actor) {
        return drafts
                .selectListByQuery(QueryWrapper.create()
                        .where(GOTONE_SERVICE_DRAFT.ACTOR.eq(actor))
                        .and(GOTONE_SERVICE_DRAFT.CONFIG.isNotNull())
                        .orderBy(GOTONE_SERVICE_DRAFT.UPDATED_AT.desc()))
                .stream()
                .map(d -> json.readValue(d.getConfig(), Route.class))
                .toList();
    }

    public void saveDraft(Route route, String actor) {
        String config = json.writeValueAsString(route);
        var patch = rows.touch(new GotoneServiceDraftDO());
        patch.setConfig(config);
        var key = draftQuery(route.serviceCode(), actor);
        if (drafts.updateByQuery(patch, key) == 0) {
            var draft = rows.create(new GotoneServiceDraftDO());
            draft.setActor(actor);
            draft.setServiceCode(route.serviceCode());
            draft.setConfig(config);
            drafts.insert(draft);
        }
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
                services.insert(current);
            } catch (DuplicateKeyException conflict) {
                throw new CommonException(REQUEST_CONFLICT, conflict);
            }
        } else {
            services.update(rows.touch(current), false);
        }
        var revision = rows.create(new GotoneServiceRevisionDO());
        revision.setServiceCode(code);
        revision.setRevision(version);
        revision.setPublishedBy(actor);
        revision.setPublishedAt(revision.getCreatedAt());
        revision.setRequiredParameters(json.writeValueAsString(route.requiredParameters()));
        revisions.insert(revision);
        for (Template template : route.channels()) {
            if (template.channel() == Channel.WEBHOOK
                    && account(template.fields().get("endpointCode"))
                            .filter(a -> a.channel() == Channel.WEBHOOK)
                            .isEmpty()) {
                throw new CommonException(INVALID_SUBMISSION);
            }
            var mapping = rows.create(new GotoneServiceChannelMapDO());
            mapping.setServiceCode(code);
            mapping.setRevision(version);
            mapping.setChannel(template.channel().name());
            mapping.setFields(json.writeValueAsString(template.fields()));
            channels.insert(mapping);
        }
        var draft = drafts.selectOneByQuery(draftQuery(code, actor).forUpdate());
        if (draft != null) {
            draft.setConfig(null);
            drafts.update(rows.touch(draft), false);
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
        for (var row : bindings.selectListByQuery(QueryWrapper.create()
                .where(GOTONE_SMS_TEMPLATE_BINDING.SERVICE_CODE.eq(code))
                .and(GOTONE_SMS_TEMPLATE_BINDING.REVISION.eq(number)))) {
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
        return accounts
                .selectListByQuery(QueryWrapper.create()
                        .orderBy(GOTONE_PROVIDER_ACCOUNT.PRIORITY.asc(), GOTONE_PROVIDER_ACCOUNT.ACCOUNT_CODE.asc()))
                .stream()
                .map(this::account)
                .toList();
    }

    public Optional<Account> account(String code) {
        return Optional.ofNullable(accounts.selectOneByQuery(accountQuery(code)))
                .map(this::account);
    }

    public Account saveAccount(Account account) {
        var fresh = account.version() == 0;
        var current = fresh
                ? rows.create(new GotoneProviderAccountDO())
                : accounts.selectOneByQuery(accountQuery(account.accountCode()).forUpdate());
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
                accounts.insert(current);
            } catch (DuplicateKeyException conflict) {
                throw new CommonException(REQUEST_CONFLICT, conflict);
            }
        } else {
            accounts.update(rows.touch(current), false);
        }
        return account(current);
    }

    public List<SmsBinding> smsBindings() {
        return bindings
                .selectListByQuery(QueryWrapper.create()
                        .orderBy(
                                GOTONE_SMS_TEMPLATE_BINDING.SERVICE_CODE.asc(),
                                GOTONE_SMS_TEMPLATE_BINDING.REVISION.asc(),
                                GOTONE_SMS_TEMPLATE_BINDING.ACCOUNT_CODE.asc()))
                .stream()
                .map(this::toSmsBinding)
                .toList();
    }

    public void saveSmsBinding(SmsBinding binding) {
        var mapping = channels.selectOneByQuery(channelQuery(binding.serviceCode(), binding.revision())
                .and(GOTONE_SERVICE_CHANNEL_MAP.CHANNEL.eq(Channel.SMS.name()))
                .forUpdate());
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
        var current = bindings.selectOneByQuery(
                bindingQuery(mapping.getId(), binding.accountCode()).forUpdate());
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
            bindings.insert(current);
        } else {
            bindings.update(rows.touch(current), false);
        }
    }

    public Optional<Binding> binding(String mapId, String account) {
        return Optional.ofNullable(bindings.selectOneByQuery(bindingQuery(mapId, account)))
                .map(b -> new Binding(b.getRemoteTemplateId(), b.getRemoteSignature(), fields(b.getVariableMapping())));
    }

    private GotoneServiceDO lockedService(String code, int version) {
        var row = services.selectOneByQuery(serviceQuery(code).forUpdate());
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
        return revision(
                revisionRow(code, number),
                channels.selectListByQuery(
                        channelQuery(code, number).orderBy(GOTONE_SERVICE_CHANNEL_MAP.CHANNEL.asc())));
    }

    private GotoneServiceRevisionDO revisionRow(String code, int number) {
        return Optional.ofNullable(revisions.selectOneByQuery(QueryWrapper.create()
                        .where(GOTONE_SERVICE_REVISION.SERVICE_CODE.eq(code))
                        .and(GOTONE_SERVICE_REVISION.REVISION.eq(number))))
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
