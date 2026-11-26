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
import static io.github.loadup.modules.gotone.infrastructure.table.Tables.GOTONE_SERVICE;

import com.mybatisflex.core.query.QueryWrapper;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import io.github.loadup.modules.gotone.client.Gotone.Template;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneProviderAccountDO;
import io.github.loadup.modules.gotone.infrastructure.dataobject.GotoneServiceDO;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneProviderAccountMapper;
import io.github.loadup.modules.gotone.infrastructure.mapper.GotoneServiceMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneConfigurationStore {
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
    private static final TypeReference<List<String>> PARAMETERS = new TypeReference<>() {};
    private static final String ACCOUNT_SETTINGS = """
            SELECT a.*, COALESCE(s.settings, e.settings, p.settings, w.settings)::text AS settings
            FROM gotone_provider_account a
            LEFT JOIN gotone_sms_account s USING (account_code)
            LEFT JOIN gotone_email_account e USING (account_code)
            LEFT JOIN gotone_push_account p USING (account_code)
            LEFT JOIN gotone_webhook_endpoint w USING (account_code)
            """;

    public record MappedTemplate(UUID id, Template template) {}
    public record Revision(int number, List<String> parameters, List<MappedTemplate> templates) {}
    public record Binding(String remoteTemplateId, String remoteSignature, Map<String, String> variables) {}

    private final JdbcClient jdbc;
    private final GotoneServiceMapper services;
    private final GotoneProviderAccountMapper accounts;
    private final JsonMapper json;

    public GotoneConfigurationStore(JdbcClient jdbc, GotoneServiceMapper services,
                                    GotoneProviderAccountMapper accounts, JsonMapper json) {
        this.jdbc = jdbc;
        this.services = services;
        this.accounts = accounts;
        this.json = json;
    }

    public List<Route> routes() {
        return services.selectListByQuery(QueryWrapper.create().orderBy(GOTONE_SERVICE.SERVICE_CODE.asc()))
                .stream().map(this::route).toList();
    }

    public Optional<Revision> active(String code) {
        return Optional.ofNullable(services.selectOneById(code))
                .filter(service -> Boolean.TRUE.equals(service.getEnabled()))
                .map(GotoneServiceDO::getActiveRevision)
                .map(revision -> revision(code, revision));
    }

    public List<Route> drafts(String actor) {
        return jdbc.sql("SELECT config::text FROM gotone_service_draft WHERE actor = ? ORDER BY updated_at DESC")
                .param(actor).query(String.class).list().stream().map(value -> json.readValue(value, Route.class)).toList();
    }

    public void saveDraft(Route route, String actor) {
        jdbc.sql("""
                INSERT INTO gotone_service_draft (service_code, actor, config) VALUES (?, ?, CAST(? AS jsonb))
                ON CONFLICT (service_code, actor) DO UPDATE SET config = EXCLUDED.config, updated_at = now()
                """).params(route.serviceCode(), actor, json.writeValueAsString(route)).update();
    }

    public Route publish(Route route, String actor) {
        String code = route.serviceCode();
        jdbc.sql("""
                INSERT INTO gotone_service (service_code, name, description, enabled)
                VALUES (?, ?, ?, false) ON CONFLICT (service_code) DO NOTHING
                """).params(code, route.name(), route.description()).update();
        GotoneServiceDO current = lockService(code, route.version());
        int version = current.getVersion() + 1;
        jdbc.sql("""
                INSERT INTO gotone_service_revision (service_code, revision, required_parameters, published_by)
                VALUES (?, ?, CAST(? AS jsonb), ?)
                """).params(code, version, json.writeValueAsString(route.requiredParameters()), actor).update();
        for (Template template : route.channels()) {
            insertTemplate(code, version, template);
        }
        current.setName(route.name());
        current.setDescription(route.description());
        current.setEnabled(route.enabled());
        current.setVersion(version);
        current.setActiveRevision(version);
        services.update(current, false);
        jdbc.sql("DELETE FROM gotone_service_draft WHERE service_code = ? AND actor = ?")
                .params(code, actor).update();
        return route(current);
    }

    private void insertTemplate(String code, int revision, Template template) {
        UUID id = UUID.randomUUID();
        Channel channel = template.channel();
        jdbc.sql("INSERT INTO gotone_service_channel_map (id, service_code, revision, channel) VALUES (?, ?, ?, ?)")
                .params(id, code, revision, channel.name()).update();
        String fields = json.writeValueAsString(template.fields());
        if (channel == Channel.WEBHOOK) {
            String endpoint = template.fields().get("endpointCode");
            if (account(endpoint).filter(value -> value.channel() == Channel.WEBHOOK).isEmpty()) {
                throw new CommonException(INVALID_SUBMISSION);
            }
            jdbc.sql("INSERT INTO gotone_webhook_config (map_id, fields, endpoint_code) VALUES (?, CAST(? AS jsonb), ?)")
                    .params(id, fields, endpoint).update();
            return;
        }
        String table = switch (channel) {
            case SMS -> "gotone_sms_template";
            case EMAIL -> "gotone_email_template";
            case PUSH -> "gotone_push_config";
            case IN_APP -> "gotone_inbox_config";
            case WEBHOOK -> throw new IllegalStateException("Webhook configuration requires an endpoint");
        };
        jdbc.sql("INSERT INTO " + table + " (map_id, fields) VALUES (?, CAST(? AS jsonb))").params(id, fields).update();
    }

    public Route restore(String code, int revision, int expectedVersion, String actor) {
        GotoneServiceDO current = lockService(code, expectedVersion);
        Revision old = revision(code, revision);
        Route restored = publish(new Route(code, current.getName(), current.getDescription(), current.getEnabled(),
                expectedVersion, old.parameters(), old.templates().stream().map(MappedTemplate::template).toList()), actor);
        jdbc.sql("""
                INSERT INTO gotone_sms_template_binding
                SELECT target.id, b.account_code, b.remote_template_id, b.remote_signature, b.variable_mapping
                FROM gotone_sms_template_binding b JOIN gotone_service_channel_map original ON original.id = b.map_id
                JOIN gotone_service_channel_map target ON target.service_code = original.service_code
                    AND target.channel = original.channel
                WHERE original.service_code = ? AND original.revision = ? AND target.revision = ?
                """).params(code, revision, restored.version()).update();
        return restored;
    }

    public List<Account> accounts() {
        return jdbc.sql(ACCOUNT_SETTINGS + " ORDER BY a.priority, a.account_code").query(this::accountRow).list();
    }

    public Optional<Account> account(String code) {
        return jdbc.sql(ACCOUNT_SETTINGS + " WHERE a.account_code = ?").param(code).query(this::accountRow).optional();
    }

    public Account saveAccount(Account account) {
        jdbc.sql("""
                INSERT INTO gotone_provider_account
                    (account_code, channel, adapter, enabled, priority, secret_ref, connect_timeout_ms, request_timeout_ms, version)
                VALUES (?, ?, ?, false, ?, ?, ?, ?, 0) ON CONFLICT (account_code) DO NOTHING
                """).params(account.accountCode(), account.channel().name(), account.adapter(), account.priority(),
                account.secretRef(), account.connectTimeoutMs(), account.requestTimeoutMs()).update();
        GotoneProviderAccountDO current = jdbc.sql("SELECT * FROM gotone_provider_account WHERE account_code = ? FOR UPDATE")
                .param(account.accountCode()).query(BeanPropertyRowMapper.newInstance(GotoneProviderAccountDO.class)).single();
        if (!current.getChannel().equals(account.channel().name()) || !current.getAdapter().equals(account.adapter())
                || current.getVersion() != account.version()) throw new CommonException(REQUEST_CONFLICT);
        if (current.getVersion() != 0 && account.channel() == Channel.WEBHOOK
                && !account(account.accountCode()).orElseThrow().settings().equals(account.settings())) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        current.setEnabled(account.enabled());
        current.setPriority(account.priority());
        current.setSecretRef(account.secretRef());
        current.setConnectTimeoutMs(account.connectTimeoutMs());
        current.setRequestTimeoutMs(account.requestTimeoutMs());
        current.setVersion(current.getVersion() + 1);
        accounts.update(current, false);
        String table = switch (account.channel()) {
            case SMS -> "gotone_sms_account";
            case EMAIL -> "gotone_email_account";
            case PUSH -> "gotone_push_account";
            case WEBHOOK -> "gotone_webhook_endpoint";
            case IN_APP -> throw new CommonException(INVALID_SUBMISSION);
        };
        jdbc.sql("INSERT INTO " + table + " (account_code, settings) VALUES (?, CAST(? AS jsonb))"
                + " ON CONFLICT (account_code) DO UPDATE SET settings = EXCLUDED.settings")
                .params(account.accountCode(), json.writeValueAsString(account.settings())).update();
        return account(account.accountCode()).orElseThrow();
    }

    public List<SmsBinding> smsBindings() {
        return jdbc.sql("""
                SELECT m.service_code, m.revision, b.* FROM gotone_sms_template_binding b
                JOIN gotone_service_channel_map m ON m.id = b.map_id ORDER BY m.service_code, m.revision, b.account_code
                """).query((rs, row) -> new SmsBinding(rs.getString("service_code"), rs.getInt("revision"),
                rs.getString("account_code"), rs.getString("remote_template_id"), rs.getString("remote_signature"),
                fields(rs.getString("variable_mapping")))).list();
    }

    public void saveSmsBinding(SmsBinding binding) {
        Revision revision = revision(binding.serviceCode(), binding.revision());
        if (!revision.parameters().containsAll(binding.variableMapping().values())) {
            throw new CommonException(INVALID_SUBMISSION);
        }
        UUID mapId = revision.templates().stream()
                .filter(template -> template.template().channel() == Channel.SMS).findFirst()
                .orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND)).id();
        if (account(binding.accountCode()).filter(account -> account.channel() == Channel.SMS).isEmpty()) {
            throw new CommonException(GOTONE_NOT_FOUND);
        }
        jdbc.sql("""
                INSERT INTO gotone_sms_template_binding (map_id, account_code, remote_template_id, remote_signature, variable_mapping)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (map_id, account_code) DO UPDATE SET remote_template_id = EXCLUDED.remote_template_id,
                    remote_signature = EXCLUDED.remote_signature, variable_mapping = EXCLUDED.variable_mapping
                """).params(mapId, binding.accountCode(), binding.remoteTemplateId(), binding.remoteSignature(),
                json.writeValueAsString(binding.variableMapping())).update();
    }

    public Optional<Binding> binding(UUID mapId, String account) {
        return jdbc.sql("""
                SELECT remote_template_id, remote_signature, variable_mapping FROM gotone_sms_template_binding
                WHERE map_id = ? AND account_code = ?
                """).params(mapId, account).query((rs, row) -> new Binding(rs.getString("remote_template_id"),
                rs.getString("remote_signature"), fields(rs.getString("variable_mapping")))).optional();
    }

    private GotoneServiceDO lockService(String code, int version) {
        GotoneServiceDO current = jdbc.sql("SELECT * FROM gotone_service WHERE service_code = ? FOR UPDATE")
                .param(code).query(BeanPropertyRowMapper.newInstance(GotoneServiceDO.class)).optional()
                .orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
        if (current.getVersion() != version) throw new CommonException(REQUEST_CONFLICT);
        return current;
    }

    private Route route(GotoneServiceDO current) {
        Revision revision = revision(current.getServiceCode(), current.getActiveRevision());
        return new Route(current.getServiceCode(), current.getName(), current.getDescription(), current.getEnabled(),
                current.getVersion(), revision.parameters(), revision.templates().stream().map(MappedTemplate::template).toList());
    }

    private Revision revision(String code, int number) {
        String parameters = jdbc.sql("""
                SELECT required_parameters::text FROM gotone_service_revision WHERE service_code = ? AND revision = ?
                """).params(code, number).query(String.class).optional().orElseThrow(() -> new CommonException(GOTONE_NOT_FOUND));
        List<MappedTemplate> channels = jdbc.sql("""
                SELECT m.id, m.channel, COALESCE(s.fields, e.fields, p.fields, i.fields, w.fields) AS fields
                FROM gotone_service_channel_map m
                LEFT JOIN gotone_sms_template s ON s.map_id = m.id
                LEFT JOIN gotone_email_template e ON e.map_id = m.id
                LEFT JOIN gotone_push_config p ON p.map_id = m.id
                LEFT JOIN gotone_inbox_config i ON i.map_id = m.id
                LEFT JOIN gotone_webhook_config w ON w.map_id = m.id
                WHERE m.service_code = ? AND m.revision = ? ORDER BY m.channel
                """).params(code, number).query((rs, row) -> new MappedTemplate(rs.getObject("id", UUID.class),
                new Template(Channel.valueOf(rs.getString("channel")), fields(rs.getString("fields"))))).list();
        return new Revision(number, json.readValue(parameters, PARAMETERS), channels);
    }

    private Account accountRow(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getString("account_code"), Channel.valueOf(rs.getString("channel")), rs.getString("adapter"),
                rs.getBoolean("enabled"), rs.getInt("priority"), rs.getString("secret_ref"), rs.getInt("connect_timeout_ms"),
                rs.getInt("request_timeout_ms"), rs.getInt("version"), fields(rs.getString("settings")));
    }

    private Map<String, String> fields(String encoded) {
        return json.readValue(encoded, FIELDS);
    }
}
