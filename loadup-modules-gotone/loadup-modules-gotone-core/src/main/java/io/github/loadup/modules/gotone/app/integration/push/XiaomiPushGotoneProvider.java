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
package io.github.loadup.modules.gotone.app.integration.push;

import io.github.loadup.modules.gotone.app.integration.GotoneHttp;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class XiaomiPushGotoneProvider implements GotoneProvider {
    private static final URI ENDPOINT = URI.create("https://api.xmpush.xiaomi.com/v3/message/regid");
    private final JsonMapper json;

    public XiaomiPushGotoneProvider(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String code() {
        return "xiaomi";
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public Set<String> settings() {
        return Set.of("appId", "platform", "packageName", "channelId");
    }

    @Override
    public boolean valid(Account account) {
        var settings = account.settings();
        return "ANDROID".equals(settings.get("platform"))
                && settings.getOrDefault("appId", "").matches("[A-Za-z0-9_.:-]{1,128}")
                && settings.getOrDefault("packageName", "").matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                && settings.getOrDefault("channelId", "").matches("[A-Za-z0-9_-]{0,128}");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        String registrationId = work.snapshot().target().address();
        if (!registrationId.matches("[A-Za-z0-9=_+/-]+")) {
            return Outcome.failed("XIAOMI_REGISTRATION_ID_INVALID");
        }
        var fields = work.snapshot().fields();
        String body = fields.get("body");
        var form = new LinkedHashMap<String, String>();
        form.put("registration_id", registrationId);
        form.put("restricted_package_name", account.settings().get("packageName"));
        form.put("title", fields.get("title"));
        form.put("description", body);
        form.put("payload", body);
        form.put("pass_through", "0");
        long ttlMillis = Integer.parseInt(fields.getOrDefault("ttlSeconds", "3600")) * 1000L;
        form.put("time_to_live", Long.toString(Math.min(1209600000L, ttlMillis)));
        String channelId = account.settings().get("channelId");
        if (channelId != null && !channelId.isBlank()) {
            form.put("extra.channel_id", channelId);
        }
        var request = GotoneHttp.post(
                        ENDPOINT, account, "application/x-www-form-urlencoded", GotoneHttp.form(form), null)
                .header("Authorization", "key=" + secret)
                .build();
        return GotoneHttp.sendJson(account, request, json, XiaomiPushGotoneProvider::outcome);
    }

    static Outcome outcome(JsonNode body) {
        int code = body.path("code").asInt(-1);
        if (code == 0 && "ok".equals(body.path("result").asText(""))) {
            return Outcome.sent(body.path("data").path("id").asText(null));
        }
        if (code == 200002) {
            return Outcome.retry("XIAOMI_THROTTLED");
        }
        return "error".equals(body.path("result").asText(""))
                ? Outcome.failed("XIAOMI_REJECTED")
                : Outcome.unknown("NO_XIAOMI_RESULT");
    }
}
