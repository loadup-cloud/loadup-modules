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
package io.github.loadup.modules.gotone.app.integration;

import io.github.loadup.modules.gotone.client.Gotone.Account;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

final class GotoneHttp {
    private GotoneHttp() {}

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static HttpRequest.Builder post(URI uri, Account account, String contentType, String body, String deliveryId) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(account.requestTimeoutMs()))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
        if (deliveryId != null) builder.header("Idempotency-Key", deliveryId);
        return builder;
    }

    static HttpResponse<String> send(Account account, HttpRequest request) throws IOException, InterruptedException {
        return send(account, request, HttpResponse.BodyHandlers.ofString());
    }

    static <T> HttpResponse<T> send(Account account, HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        // The caller enforces an outer deadline, including response-body reads.
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(account.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(request, bodyHandler);
        }
    }

    static GotoneProvider.Outcome failure(int status) {
        if (status == 429 || status == 401 || status == 403) {
            return GotoneProvider.Outcome.retry("HTTP_" + status);
        }
        if (status == 408 || status >= 500) return GotoneProvider.Outcome.unknown("HTTP_" + status);
        return GotoneProvider.Outcome.failed("HTTP_" + status);
    }
}
