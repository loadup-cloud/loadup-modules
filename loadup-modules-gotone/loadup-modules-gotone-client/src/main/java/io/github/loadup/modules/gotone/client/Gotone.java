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
package io.github.loadup.modules.gotone.client;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Gotone {
    private Gotone() {}

    public enum Channel {
        SMS,
        EMAIL,
        PUSH,
        IN_APP,
        WEBHOOK
    }

    @Schema(name = "GotoneTarget", description = "Recipient address and optional push delivery identifiers")
    public record Target(
            @Schema(description = "Channel-specific address, such as an email address, push token or inbox user ID")
            String address,

            String platform,
            String appId) {
        public static Target address(String address) {
            return new Target(address, null, null);
        }
    }

    @Schema(name = "GotoneSubmit", description = "Request for asynchronous notification delivery")
    public record Submit(
            String serviceCode,

            @Schema(description = "Idempotency key unique to the calling source")
            String requestKey,

            @Schema(description = "Values keyed by the published route template parameter names")
            Map<String, String> parameters,

            @Schema(description = "Recipients for each configured channel that requires caller-supplied recipients")
            Map<Channel, List<Target>> recipientsByChannel,

            @Schema(description = "Delivery deadline. Defaults to 24 hours after acceptance.")
            Instant expiresAt) {}

    @Schema(name = "GotoneReceipt", description = "Receipt for an accepted notification request")
    public record Receipt(
            String requestId,
            String serviceCode,

            @Schema(description = "Published route revision used to plan the deliveries")
            int revision,

            Instant acceptedAt,
            int targetCount) {}

    @Schema(name = "GotoneAttempt", description = "Result and timestamps of one delivery attempt")
    public record Attempt(
            int number,
            String accountCode,
            Integer configVersion,
            String status,
            String reason,
            String remoteId,
            Instant startedAt,
            Instant finishedAt) {}

    @Schema(name = "GotoneDelivery", description = "Channel delivery status and attempt history for a target")
    public record Delivery(String id, Channel channel, String target, String status, List<Attempt> attempts) {}

    @Schema(name = "GotoneRequest", description = "Accepted notification request and its deliveries")
    public record Request(Receipt receipt, List<Delivery> deliveries) {}

    @Schema(name = "GotoneTemplate", description = "Channel-specific template fields")
    public record Template(Channel channel, Map<String, String> fields) {}

    @Schema(name = "GotoneRoute", description = "Versioned notification service configuration and channel templates")
    public record Route(
            String serviceCode,
            String name,
            String description,
            boolean enabled,

            @Schema(description = "Current configuration version. Use zero for the first publication.")
            int version,

            List<String> requiredParameters,
            List<Template> channels) {}

    @Schema(name = "GotoneAccount", description = "Versioned delivery provider configuration")
    public record Account(
            String accountCode,
            Channel channel,
            String adapter,
            boolean enabled,
            int priority,

            @Schema(description = "Reference to stored provider credentials")
            String secretRef,

            int connectTimeoutMs,
            int requestTimeoutMs,

            @Schema(description = "Current configuration version. Use zero to create an account.")
            int version,

            Map<String, String> settings) {}

    @Schema(name = "GotoneSmsBinding", description = "Provider template binding for a published SMS route revision")
    public record SmsBinding(
            String serviceCode,
            int revision,
            String accountCode,
            String remoteTemplateId,
            String remoteSignature,
            Map<String, String> variableMapping) {}

    @Schema(name = "GotoneRetry", description = "Request to retry a confirmed delivery failure")
    public record Retry(String deliveryId, String reason) {}

    @Schema(name = "GotoneResolution", description = "Recorded provider outcome for a delivery with uncertain status")
    public record Resolution(
            String deliveryId,

            @Schema(description = "Whether the provider confirmed acceptance of the delivery")
            Boolean accepted,

            String reason) {}
}
