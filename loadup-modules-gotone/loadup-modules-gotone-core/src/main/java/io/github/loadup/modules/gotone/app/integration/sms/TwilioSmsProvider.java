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
package io.github.loadup.modules.gotone.app.integration.sms;

import io.github.loadup.modules.gotone.app.integration.GotoneHttp;
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class TwilioSmsProvider implements GotoneProvider {
    private final JsonMapper json;

    public TwilioSmsProvider(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String code() {
        return "twilio";
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public Set<String> settings() {
        return Set.of("accountSid", "from", "authUser");
    }

    @Override
    public boolean valid(Account account) {
        return account.settings().getOrDefault("accountSid", "").matches("AC[0-9a-fA-F]{32}")
                && account.settings().getOrDefault("from", "").matches("\\+[1-9][0-9]{2,14}")
                && account.settings().getOrDefault("authUser", "").matches("(?:|(?:AC|SK)[0-9a-fA-F]{32})");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        String sid = account.settings().get("accountSid");
        String user = account.settings().getOrDefault("authUser", "");
        if (user.isEmpty()) {
            user = sid;
        }
        String auth = Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
        URI endpoint = URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json");
        String body = GotoneHttp.form(Map.of(
                "To",
                work.snapshot().target().address(),
                "From",
                account.settings().get("from"),
                "Body",
                work.snapshot().fields().get("body")));
        try {
            var response = GotoneHttp.send(
                    account,
                    GotoneHttp.post(endpoint, account, "application/x-www-form-urlencoded", body, work.id())
                            .header("Authorization", "Basic " + auth)
                            .build());
            if (response.statusCode() == 201) {
                return Outcome.sent(json.readTree(response.body()).path("sid").asString(null));
            }
            return GotoneHttp.failure(response.statusCode());
        } catch (java.net.http.HttpConnectTimeoutException exception) {
            return Outcome.retry("CONNECT_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("INTERRUPTED");
        } catch (java.io.IOException | RuntimeException exception) {
            return Outcome.unknown("NO_PROVIDER_RESULT");
        }
    }
}
