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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loadup.modules.gotone.app.integration.GotoneProvider.Result;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TencentSmsGotoneProviderTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signsTheExactRequestBodyWithTc3() throws Exception {
        String body = """
            {"PhoneNumberSet":["+8613800000000"],"SmsSdkAppId":"1400000000","TemplateId":"123"}\
            """;
        String authorization = TencentSmsGotoneProvider.authorization(
                "fixtureId", "fixtureSecret", Instant.parse("2026-11-27T00:00:00Z"), body);
        assertThat(authorization)
                .isEqualTo("TC3-HMAC-SHA256 Credential=fixtureId/2026-11-27/sms/tc3_request, "
                        + "SignedHeaders=content-type;host;x-tc-action, "
                        + "Signature=2ee9b9fc7f358c0405dbfd0a42093395a106398d4dfddc077156457a824ccf44");
    }

    @Test
    void distinguishesThePerRecipientStatusFromAnUncertainResponse() {
        var accepted = TencentSmsGotoneProvider.outcome(json.readTree("""
            {"Response":{"SendStatusSet":[{"Code":"Ok","SerialNo":"sms-1"}]}}\
            """));
        assertThat(accepted.result()).isEqualTo(Result.SENT);
        assertThat(accepted.remoteId()).isEqualTo("sms-1");
        assertThat(TencentSmsGotoneProvider.outcome(json.readTree("""
            {"Response":{"SendStatusSet":[{"Code":"FailedOperation.TemplateParamSetNotMatchApprovedTemplate"}]}}\
            """)).result())
                .isEqualTo(Result.FAILED);
        assertThat(TencentSmsGotoneProvider.outcome(json.readTree("""
            {"Response":{"Error":{"Code":"InternalError.SendAndRecvFail"}}}\
            """)).result())
                .isEqualTo(Result.UNKNOWN);
    }
}
