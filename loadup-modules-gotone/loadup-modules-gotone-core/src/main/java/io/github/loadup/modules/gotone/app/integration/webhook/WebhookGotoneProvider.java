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
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookGotoneProvider implements GotoneProvider {
    private final Set<String> allowedHosts;

    public WebhookGotoneProvider(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    @Override
    public String code() {
        return "https";
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public Set<String> settings() {
        return Set.of("url", "destinationRef");
    }

    @Override
    public boolean valid(Account account) {
        String ref = account.settings().get("destinationRef");
        return (ref != null && GotoneSecrets.valid(ref) && !account.settings().containsKey("url"))
                || (ref == null
                        && endpoint(account.settings().getOrDefault("url", "")) != null
                        && URI.create(account.settings().get("url")).getRawQuery() == null);
    }

    private URI endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        URI uri = endpoint(account.settings().getOrDefault("url", ""));
        if (uri == null) {
            return Outcome.failed("WEBHOOK_ENDPOINT_FORBIDDEN");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                        || (bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xc0) == 0x40)) {
                    return Outcome.failed("WEBHOOK_ENDPOINT_FORBIDDEN");
                }
            }
        } catch (java.net.UnknownHostException exception) {
            return Outcome.retry("WEBHOOK_DNS_UNAVAILABLE");
        }
        String body = work.snapshot().fields().get("payload");
        var request = GotoneHttp.post(uri, account, "application/json", body, work.id());
        if (secret != null) {
            try {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                request.header(
                        "X-Gotone-Signature",
                        HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))));
            } catch (GeneralSecurityException exception) {
                return Outcome.failed("WEBHOOK_SIGNING_UNAVAILABLE");
            }
        }
        try {
            var response =
                    GotoneHttp.send(account, request.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Outcome.sent(null);
            }
            // A generic endpoint's error does not prove it has not processed the request.
            return Outcome.unknown("HTTP_" + response.statusCode());
        } catch (java.net.http.HttpConnectTimeoutException exception) {
            return Outcome.retry("CONNECT_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("INTERRUPTED");
        } catch (java.io.IOException | RuntimeException exception) {
            return Outcome.unknown("NO_WEBHOOK_RESULT");
        }
    }
}
