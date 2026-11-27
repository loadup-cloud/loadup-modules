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
package io.github.loadup.modules.gotone.app.integration.webhook;

import io.github.loadup.modules.gotone.app.integration.GotoneHttp;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class WecomWebhookGotoneProvider extends RobotWebhookGotoneProvider {
    public WecomWebhookGotoneProvider(JsonMapper json) {
        super(json, "qyapi.weixin.qq.com");
    }

    @Override
    public String code() {
        return "wecom";
    }

    @Override
    public boolean valid(Account account) {
        return account.secretRef() == null && super.valid(account);
    }

    @Override
    protected boolean validEndpoint(URI uri) {
        return queryToken(uri, "/cgi-bin/webhook/send", "key");
    }

    @Override
    protected HttpRequest request(URI uri, Account account, String secret, String text) {
        String body = json.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
        return GotoneHttp.post(uri, account, "application/json", body, null).build();
    }

    @Override
    protected Outcome outcome(JsonNode response) {
        int code = response.path("errcode").asInt(Integer.MIN_VALUE);
        return switch (code) {
            case 0 -> Outcome.sent(null);
            case 45009 -> Outcome.retry("WECOM_THROTTLED");
            case -1, Integer.MIN_VALUE -> Outcome.unknown("NO_WECOM_RESULT");
            default -> Outcome.failed("WECOM_REJECTED");
        };
    }
}
