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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

public final class AliyunSmsGotoneProvider implements GotoneProvider {
    private static final URI ENDPOINT = URI.create("https://dysmsapi.aliyuncs.com/");
    private final JsonMapper json;
    private final Clock clock;

    public AliyunSmsGotoneProvider(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    @Override
    public String code() {
        return "aliyun";
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public Set<String> settings() {
        return Set.of("accessKeyId", "signature");
    }

    @Override
    public boolean requiresSmsBinding() {
        return true;
    }

    @Override
    public boolean valid(Account account) {
        return account.settings().getOrDefault("accessKeyId", "").matches("[A-Za-z0-9]{8,128}");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        String phone = work.snapshot().target().address();
        if (!phone.matches("\\+861[0-9]{10}")) {
            return Outcome.retry("ALIYUN_REGION_UNSUPPORTED");
        }
        String signature =
                binding.remoteSignature() == null ? account.settings().get("signature") : binding.remoteSignature();
        if (signature == null || signature.isBlank()) {
            return Outcome.retry("ALIYUN_SIGNATURE_MISSING");
        }
        Map<String, String> variables = new TreeMap<>();
        for (var entry : binding.variables().entrySet()) {
            String value = work.snapshot().parameters().get(entry.getValue());
            if (value == null) {
                return Outcome.retry("ALIYUN_TEMPLATE_PARAMETER_MISSING");
            }
            variables.put(entry.getKey(), value);
        }
        Map<String, String> fields = new TreeMap<>();
        fields.put("Action", "SendSms");
        fields.put("Version", "2017-05-25");
        fields.put("Format", "JSON");
        fields.put("AccessKeyId", account.settings().get("accessKeyId"));
        fields.put("SignatureMethod", "HMAC-SHA1");
        fields.put("SignatureVersion", "1.0");
        fields.put("SignatureNonce", UUID.randomUUID().toString());
        fields.put("Timestamp", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
        fields.put("PhoneNumbers", phone.substring(3));
        fields.put("SignName", signature);
        fields.put("TemplateCode", binding.remoteTemplateId());
        fields.put("TemplateParam", json.writeValueAsString(variables));
        fields.put("OutId", work.id());
        String canonical = fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String toSign = "POST&%2F&" + encode(canonical);
            String signed = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
            var response = GotoneHttp.send(
                    account,
                    GotoneHttp.post(
                                    ENDPOINT,
                                    account,
                                    "application/x-www-form-urlencoded",
                                    canonical + "&Signature=" + encode(signed),
                                    work.id())
                            .build());
            if (response.statusCode() != 200) {
                return GotoneHttp.failure(response.statusCode());
            }
            var result = json.readTree(response.body());
            String code = result.path("Code").asString();
            if (code.equals("OK")) {
                return Outcome.sent(result.path("BizId").asString(null));
            }
            if (code.equals("isv.BUSINESS_LIMIT_CONTROL") || code.equals("isv.AMOUNT_NOT_ENOUGH")) {
                return Outcome.retry("ALIYUN_ACCOUNT_LIMIT");
            }
            return code.isBlank() || code.equals("isp.SYSTEM_ERROR") || code.equals("isp.GATEWAY_ERROR")
                    ? Outcome.unknown("NO_ALIYUN_RESULT")
                    : Outcome.failed("ALIYUN_REJECTED");
        } catch (GeneralSecurityException exception) {
            return Outcome.failed("ALIYUN_SIGNING_UNAVAILABLE");
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
