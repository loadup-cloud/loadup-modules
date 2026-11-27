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
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class DingtalkWebhookGotoneProvider extends RobotWebhookGotoneProvider {
    private final Clock clock;

    public DingtalkWebhookGotoneProvider(JsonMapper json, Clock clock) {
        super(json, "oapi.dingtalk.com");
        this.clock = clock;
    }

    @Override
    public String code() {
        return "dingtalk";
    }

    @Override
    protected boolean validEndpoint(URI uri) {
        return queryToken(uri, "/robot/send", "access_token");
    }

    @Override
    protected HttpRequest request(URI uri, Account account, String secret, String text)
            throws GeneralSecurityException {
        if (secret != null) {
            String timestamp = Long.toString(clock.millis());
            String query = GotoneHttp.form(
                    Map.of("timestamp", timestamp, "sign", signature(secret, timestamp + "\n" + secret)));
            uri = URI.create(uri.toASCIIString() + "&" + query);
        }
        String body = json.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", text)));
        return GotoneHttp.post(uri, account, "application/json", body, null).build();
    }

    @Override
    protected Outcome outcome(JsonNode response) {
        int code = response.path("errcode").asInt(-1);
        return switch (code) {
            case 0 -> Outcome.sent(null);
            case 130101 -> Outcome.retry("DINGTALK_THROTTLED");
            case -1 -> Outcome.unknown("NO_DINGTALK_RESULT");
            default -> Outcome.failed("DINGTALK_REJECTED");
        };
    }
}
