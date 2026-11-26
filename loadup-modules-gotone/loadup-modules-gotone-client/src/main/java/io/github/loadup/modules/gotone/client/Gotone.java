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

    public record Target(String address, String platform, String appId) {
        public static Target address(String address) {
            return new Target(address, null, null);
        }
    }

    public record Submit(
            String serviceCode,
            String requestKey,
            Map<String, String> parameters,
            Map<Channel, List<Target>> recipientsByChannel,
            Instant expiresAt) {}

    public record Receipt(String requestId, String serviceCode, int revision, Instant acceptedAt, int targetCount) {}

    public record Attempt(
            int number,
            String accountCode,
            Integer configVersion,
            String status,
            String reason,
            String remoteId,
            Instant startedAt,
            Instant finishedAt) {}

    public record Delivery(String id, Channel channel, String target, String status, List<Attempt> attempts) {}

    public record Request(Receipt receipt, List<Delivery> deliveries) {}

    public record Template(Channel channel, Map<String, String> fields) {}

    public record Route(
            String serviceCode,
            String name,
            String description,
            boolean enabled,
            int version,
            List<String> requiredParameters,
            List<Template> channels) {}

    public record Account(
            String accountCode,
            Channel channel,
            String adapter,
            boolean enabled,
            int priority,
            String secretRef,
            int connectTimeoutMs,
            int requestTimeoutMs,
            int version,
            Map<String, String> settings) {}

    public record SmsBinding(
            String serviceCode,
            int revision,
            String accountCode,
            String remoteTemplateId,
            String remoteSignature,
            Map<String, String> variableMapping) {}

    public record Retry(String deliveryId, String reason) {}

    public record Resolution(String deliveryId, Boolean accepted, String reason) {}
}
