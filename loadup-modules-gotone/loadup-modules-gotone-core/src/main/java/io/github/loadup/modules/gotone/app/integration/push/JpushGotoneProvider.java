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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class JpushGotoneProvider implements GotoneProvider {
    private static final URI ENDPOINT = URI.create("https://api.jpush.cn/v3/push");
    private final JsonMapper json;

    public JpushGotoneProvider(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String code() {
        return "jpush";
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public Set<String> settings() {
        return Set.of("appId", "platform", "appKey", "apnsProduction");
    }

    @Override
    public boolean valid(Account account) {
        var settings = account.settings();
        return settings.getOrDefault("appId", "").matches("[A-Za-z0-9_.:-]{1,128}")
                && Set.of("ANDROID", "IOS").contains(settings.getOrDefault("platform", ""))
                && settings.getOrDefault("appKey", "").matches("[A-Za-z0-9]{8,128}")
                && Set.of("true", "false").contains(settings.getOrDefault("apnsProduction", "true"));
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        var fields = work.snapshot().fields();
        var target = work.snapshot().target();
        String platform = target.platform().toLowerCase(Locale.ROOT);
        String alert = fields.get("body");
        String title = fields.get("title");
        Map<String, ?> notification = platform.equals("ios")
                ? Map.of("alert", Map.of("title", title, "body", alert))
                : Map.of("alert", alert, "title", title);
        int ttl = Math.min(259200, Integer.parseInt(fields.getOrDefault("ttlSeconds", "3600")));
        String body = json.writeValueAsString(Map.of(
                "platform", List.of(platform),
                "audience", Map.of("registration_id", List.of(target.address())),
                "notification", Map.of("alert", alert, platform, notification),
                "options",
                        Map.of(
                                "time_to_live",
                                ttl,
                                "apns_production",
                                Boolean.parseBoolean(account.settings().getOrDefault("apnsProduction", "true")))));
        String credentials = account.settings().get("appKey") + ":" + secret;
        var request = GotoneHttp.post(ENDPOINT, account, "application/json", body, null)
                .header(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build();
        return GotoneHttp.sendJson(account, request, json, JpushGotoneProvider::outcome);
    }

    static Outcome outcome(JsonNode body) {
        String messageId = body.path("msg_id").asString("");
        return messageId.isBlank() ? Outcome.unknown("NO_JPUSH_RESULT") : Outcome.sent(messageId);
    }
}
