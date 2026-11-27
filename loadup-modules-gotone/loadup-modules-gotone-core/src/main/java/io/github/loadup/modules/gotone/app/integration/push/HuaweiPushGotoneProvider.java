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
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class HuaweiPushGotoneProvider implements GotoneProvider {
    private static final URI TOKEN_ENDPOINT = URI.create("https://oauth-login.cloud.huawei.com/oauth2/v3/token");
    private final JsonMapper json;

    public HuaweiPushGotoneProvider(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String code() {
        return "huawei";
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public Set<String> settings() {
        return Set.of("appId", "platform", "clientId");
    }

    @Override
    public boolean valid(Account account) {
        var settings = account.settings();
        return "ANDROID".equals(settings.get("platform"))
                && settings.getOrDefault("appId", "").matches("[A-Za-z0-9_.:-]{1,128}")
                && settings.getOrDefault("clientId", "").matches("[0-9]{1,32}");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        String clientId = account.settings().get("clientId");
        String grant = GotoneHttp.form(
                Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
        String token;
        try {
            var response = GotoneHttp.send(
                    account,
                    GotoneHttp.post(TOKEN_ENDPOINT, account, "application/x-www-form-urlencoded", grant, null)
                            .build());
            int status = response.statusCode();
            if (status == 400 || status == 401 || status == 403) {
                return Outcome.failed("HUAWEI_AUTH_REJECTED");
            }
            if (status != 200) {
                return Outcome.retry("HUAWEI_AUTH_UNAVAILABLE");
            }
            token = json.readTree(response.body()).path("access_token").asString("");
            if (token.isBlank()) {
                return Outcome.retry("HUAWEI_AUTH_UNAVAILABLE");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.retry("HUAWEI_AUTH_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            return Outcome.retry("HUAWEI_AUTH_UNAVAILABLE");
        }

        var fields = work.snapshot().fields();
        int ttl = Math.min(1296000, Integer.parseInt(fields.getOrDefault("ttlSeconds", "3600")));
        var notification =
                Map.of("title", fields.get("title"), "body", fields.get("body"), "click_action", Map.of("type", 3));
        var message = Map.of(
                "android", Map.of("notification", notification, "ttl", ttl + "s"),
                "token", List.of(work.snapshot().target().address()));
        var endpoint = URI.create("https://push-api.cloud.huawei.com/v1/" + clientId + "/messages:send");
        var request = GotoneHttp.post(
                        endpoint,
                        account,
                        "application/json",
                        json.writeValueAsString(Map.of("message", message)),
                        null)
                .header("Authorization", "Bearer " + token)
                .build();
        return GotoneHttp.sendJson(account, request, json, HuaweiPushGotoneProvider::outcome);
    }

    static Outcome outcome(JsonNode body) {
        String code = body.path("code").asString("");
        return switch (code) {
            case "80000000" -> Outcome.sent(body.path("requestId").asString(null));
            case "80200001", "80200003" -> Outcome.retry("HUAWEI_AUTH_REJECTED");
            case "", "80100000", "81000001" -> Outcome.unknown("NO_HUAWEI_RESULT");
            default -> Outcome.failed("HUAWEI_REJECTED");
        };
    }
}
