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
import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.app.integration.GotoneSecrets;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

abstract class RobotWebhookGotoneProvider implements GotoneProvider {
    protected final JsonMapper json;
    private final String host;

    RobotWebhookGotoneProvider(JsonMapper json, String host) {
        this.json = json;
        this.host = host;
    }

    @Override
    public final Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public final Set<String> settings() {
        return Set.of("destinationRef");
    }

    @Override
    public boolean valid(Account account) {
        return GotoneSecrets.valid(account.settings().get("destinationRef"));
    }

    @Override
    public final String webhookBodyField() {
        return "text";
    }

    @Override
    public final Outcome send(Account account, String secret, Work work, Binding binding) {
        URI uri;
        try {
            uri = URI.create(account.settings().getOrDefault("url", ""));
        } catch (IllegalArgumentException exception) {
            return Outcome.failed("WEBHOOK_ENDPOINT_FORBIDDEN");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !host.equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || !validEndpoint(uri)) {
            return Outcome.failed("WEBHOOK_ENDPOINT_FORBIDDEN");
        }
        String text = work.snapshot().fields().get("text");
        if (text == null || text.isBlank()) {
            return Outcome.failed("WEBHOOK_TEXT_MISSING");
        }
        try {
            return GotoneHttp.sendJson(account, request(uri, account, secret, text), json, this::outcome);
        } catch (GeneralSecurityException exception) {
            return Outcome.failed("WEBHOOK_SIGNING_UNAVAILABLE");
        }
    }

    protected abstract boolean validEndpoint(URI uri);

    protected abstract HttpRequest request(URI uri, Account account, String secret, String text)
            throws GeneralSecurityException;

    protected abstract Outcome outcome(JsonNode response);

    protected static boolean queryToken(URI uri, String path, String key) {
        String query = uri.getRawQuery();
        return path.equals(uri.getRawPath()) && query != null && query.matches(key + "=[A-Za-z0-9_-]+");
    }

    protected static String signature(String key, String message) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
