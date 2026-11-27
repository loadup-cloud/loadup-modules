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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TencentSmsGotoneProvider implements GotoneProvider {
    private static final URI ENDPOINT = URI.create("https://sms.tencentcloudapi.com/");
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private final JsonMapper json;
    private final Clock clock;

    public TencentSmsGotoneProvider(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String code() {
        return "tencent";
    }

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public Set<String> settings() {
        return Set.of("secretId", "sdkAppId", "region", "signature");
    }

    @Override
    public boolean valid(Account account) {
        return account.settings().getOrDefault("secretId", "").matches("[A-Za-z0-9]{8,128}")
                && account.settings().getOrDefault("sdkAppId", "").matches("[0-9]{1,20}")
                && account.settings().getOrDefault("region", "").matches("[a-z][a-z0-9-]{1,31}");
    }

    @Override
    public boolean requiresSmsBinding() {
        return true;
    }

    @Override
    public boolean validSmsBinding(String templateId, Set<String> variables) {
        if (!templateId.matches("[0-9]{1,20}")) {
            return false;
        }
        for (int index = 1; index <= variables.size(); index++) {
            if (!variables.contains(Integer.toString(index))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        if (binding == null
                || !validSmsBinding(
                        binding.remoteTemplateId(), binding.variables().keySet())) {
            return Outcome.failed("TENCENT_TEMPLATE_INVALID");
        }
        var settings = account.settings();
        String phone = work.snapshot().target().address();
        String signature = binding.remoteSignature() == null ? settings.get("signature") : binding.remoteSignature();
        if (phone.startsWith("+86") && (signature == null || signature.isBlank())) {
            return Outcome.failed("TENCENT_SIGNATURE_MISSING");
        }
        var parameters = new ArrayList<String>();
        for (int index = 1; index <= binding.variables().size(); index++) {
            String value = work.snapshot().parameters().get(binding.variables().get(Integer.toString(index)));
            if (value == null) {
                return Outcome.failed("TENCENT_TEMPLATE_PARAMETER_MISSING");
            }
            parameters.add(value);
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("PhoneNumberSet", List.of(phone));
        fields.put("SmsSdkAppId", settings.get("sdkAppId"));
        fields.put("TemplateId", binding.remoteTemplateId());
        if (signature != null && !signature.isBlank()) {
            fields.put("SignName", signature);
        }
        fields.put("TemplateParamSet", parameters);
        fields.put("SessionContext", work.id());
        String body = json.writeValueAsString(fields);
        Instant now = clock.instant();
        try {
            var request = GotoneHttp.post(ENDPOINT, account, CONTENT_TYPE, body, null)
                    .header("Authorization", authorization(settings.get("secretId"), secret, now, body))
                    .header("X-TC-Action", "SendSms")
                    .header("X-TC-Version", "2021-01-11")
                    .header("X-TC-Region", settings.get("region"))
                    .header("X-TC-Timestamp", Long.toString(now.getEpochSecond()))
                    .build();
            return GotoneHttp.sendJson(account, request, json, TencentSmsGotoneProvider::outcome);
        } catch (GeneralSecurityException exception) {
            return Outcome.failed("TENCENT_SIGNING_UNAVAILABLE");
        }
    }

    static Outcome outcome(JsonNode body) {
        var response = body.path("Response");
        var status = response.path("SendStatusSet").path(0);
        String code = status.path("Code").asString("");
        if ("Ok".equals(code)) {
            return Outcome.sent(status.path("SerialNo").asString(null));
        }
        if (code.isBlank()) {
            code = response.path("Error").path("Code").asString("");
        }
        if (code.isBlank() || code.startsWith("InternalError") || code.equals("RequestTimeout")) {
            return Outcome.unknown("NO_TENCENT_RESULT");
        }
        return code.startsWith("RequestLimitExceeded") || code.startsWith("LimitExceeded")
                ? Outcome.retry("TENCENT_THROTTLED")
                : Outcome.failed("TENCENT_REJECTED");
    }

    static String authorization(String secretId, String secret, Instant now, String body)
            throws GeneralSecurityException {
        String date = now.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        String signedHeaders = "content-type;host;x-tc-action";
        String canonical = "POST\n/\n\ncontent-type:"
                + CONTENT_TYPE
                + "\nhost:"
                + ENDPOINT.getHost()
                + "\nx-tc-action:sendsms\n\n"
                + signedHeaders
                + "\n"
                + sha256(body);
        String scope = date + "/sms/tc3_request";
        String toSign = ALGORITHM + "\n" + now.getEpochSecond() + "\n" + scope + "\n" + sha256(canonical);
        byte[] key = hmac(("TC3" + secret).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(hmac(key, "sms"), "tc3_request");
        return ALGORITHM
                + " Credential="
                + secretId
                + "/"
                + scope
                + ", SignedHeaders="
                + signedHeaders
                + ", Signature="
                + HexFormat.of().formatHex(hmac(key, toSign));
    }

    private static String sha256(String value) throws GeneralSecurityException {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
