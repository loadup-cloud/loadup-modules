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
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class FeishuWebhookGotoneProvider extends RobotWebhookGotoneProvider {
    private final Clock clock;

    public FeishuWebhookGotoneProvider(JsonMapper json, Clock clock) {
        super(json, "open.feishu.cn");
        this.clock = clock;
    }

    @Override
    public String code() {
        return "feishu";
    }

    @Override
    protected boolean validEndpoint(URI uri) {
        return uri.getRawQuery() == null && uri.getRawPath().matches("/open-apis/bot/v2/hook/[A-Za-z0-9_-]+");
    }

    @Override
    protected HttpRequest request(URI uri, Account account, String secret, String text)
            throws GeneralSecurityException {
        var fields = new LinkedHashMap<String, Object>();
        if (secret != null) {
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            fields.put("timestamp", timestamp);
            fields.put("sign", signature(timestamp + "\n" + secret, ""));
        }
        fields.put("msg_type", "text");
        fields.put("content", Map.of("text", text));
        return GotoneHttp.post(uri, account, "application/json", json.writeValueAsString(fields), null)
                .build();
    }

    @Override
    protected Outcome outcome(JsonNode response) {
        int code = response.path("code").asInt(response.path("StatusCode").asInt(-1));
        return switch (code) {
            case 0 -> Outcome.sent(null);
            case 11232 -> Outcome.retry("FEISHU_THROTTLED");
            case -1 -> Outcome.unknown("NO_FEISHU_RESULT");
            default -> Outcome.failed("FEISHU_REJECTED");
        };
    }
}
