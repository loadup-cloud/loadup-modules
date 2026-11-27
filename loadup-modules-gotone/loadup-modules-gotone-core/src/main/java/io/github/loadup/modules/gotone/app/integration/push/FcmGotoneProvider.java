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
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class FcmGotoneProvider implements GotoneProvider {
    private static final URI OAUTH = URI.create("https://oauth2.googleapis.com/token");
    private final JsonMapper json;
    private final Clock clock;

    public FcmGotoneProvider(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String code() {
        return "fcm";
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public Set<String> settings() {
        return Set.of("projectId", "platform", "appId");
    }

    @Override
    public boolean valid(Account account) {
        return account.settings().getOrDefault("projectId", "").matches("[a-z][a-z0-9-]{0,62}")
                && Set.of("ANDROID", "IOS", "WEB").contains(account.settings().getOrDefault("platform", ""))
                && account.settings().getOrDefault("appId", "").matches("[A-Za-z0-9_.:-]{1,128}");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        String token;
        try {
            token = accessToken(account, secret);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.retry("FCM_AUTH_INTERRUPTED");
        } catch (java.io.IOException | java.security.GeneralSecurityException | RuntimeException exception) {
            return Outcome.retry("FCM_AUTH_UNAVAILABLE");
        }
        if (token == null || token.isBlank()) {
            return Outcome.retry("FCM_AUTH_UNAVAILABLE");
        }
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(work.expiresAt())) {
            return Outcome.retry("FCM_SEND_WINDOW_CLOSED");
        }
        var content = work.snapshot().fields();
        var message = new LinkedHashMap<String, Object>();
        message.put("token", work.snapshot().target().address());
        message.put("notification", Map.of("title", content.get("title"), "body", content.get("body")));
        long ttl = Math.max(
                0,
                Math.min(
                        Long.parseLong(content.getOrDefault("ttlSeconds", "3600")),
                        work.expiresAt().getEpochSecond() - clock.instant().getEpochSecond()));
        switch (work.snapshot().target().platform()) {
            case "ANDROID" -> message.put("android", Map.of("ttl", ttl + "s"));
            case "IOS" ->
                message.put(
                        "apns",
                        Map.of(
                                "headers",
                                Map.of(
                                        "apns-expiration",
                                        Long.toString(clock.instant().getEpochSecond() + ttl))));
            case "WEB" -> message.put("webpush", Map.of("headers", Map.of("TTL", Long.toString(ttl))));
            default -> {
                return Outcome.failed("UNSUPPORTED_PLATFORM");
            }
        }
        URI endpoint = URI.create(
                "https://fcm.googleapis.com/v1/projects/" + account.settings().get("projectId") + "/messages:send");
        try {
            var response = GotoneHttp.send(
                    account,
                    GotoneHttp.post(
                                    endpoint,
                                    account,
                                    "application/json",
                                    json.writeValueAsString(Map.of("message", message)),
                                    work.id())
                            .header("Authorization", "Bearer " + token)
                            .build());
            if (response.statusCode() == 200) {
                return Outcome.sent(json.readTree(response.body()).path("name").asString(null));
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

    private String accessToken(Account account, String secret)
            throws java.io.IOException, InterruptedException, java.security.GeneralSecurityException {
        var credential = json.readTree(secret);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        long now = clock.instant().getEpochSecond();
        var claims = encoder.encodeToString(json.writeValueAsBytes(Map.of(
                "iss",
                credential.path("client_email").asString(),
                "scope",
                "https://www.googleapis.com/auth/firebase.messaging",
                "aud",
                OAUTH.toString(),
                "iat",
                now,
                "exp",
                now + 300)));
        String pem = credential
                .path("private_key")
                .asString()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        String assertion = header + "." + claims;
        signer.update(assertion.getBytes(StandardCharsets.US_ASCII));
        assertion += "." + encoder.encodeToString(signer.sign());
        var request = GotoneHttp.post(
                        OAUTH,
                        account,
                        "application/x-www-form-urlencoded",
                        GotoneHttp.form(Map.of(
                                "grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion", assertion)),
                        null)
                .build();
        var response = GotoneHttp.send(account, request);
        return response.statusCode() == 200
                ? json.readTree(response.body()).path("access_token").asString(null)
                : null;
    }
}
