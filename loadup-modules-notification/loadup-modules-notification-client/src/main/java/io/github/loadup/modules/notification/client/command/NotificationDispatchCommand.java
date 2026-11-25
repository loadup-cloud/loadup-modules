package io.github.loadup.modules.notification.client.command;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record NotificationDispatchCommand(
        String requestKey,
        String serviceCode,
        String title,
        String body,
        String actionUrl,
        LocalDateTime expiresAt,
        Map<String, List<String>> recipientsByChannel) {
    private static final Pattern SERVICE_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");

    public NotificationDispatchCommand {
        NotificationSubmitCommand.required(requestKey, 128);
        NotificationSubmitCommand.required(title, 200);
        NotificationSubmitCommand.required(body, 4000);
        NotificationSubmitCommand.validateActionUrl(actionUrl);
        validateServiceCode(serviceCode);
        if (recipientsByChannel == null || recipientsByChannel.isEmpty() || recipientsByChannel.size() > 4) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_CHANNEL_RECIPIENTS);
        }
        Map<String, List<String>> canonical = new TreeMap<>();
        recipientsByChannel.forEach((channel, recipients) -> canonical.put(channel, normalize(channel, recipients)));
        recipientsByChannel = Collections.unmodifiableMap(canonical);
    }

    public static void validateServiceCode(String code) {
        if (code == null || !SERVICE_CODE.matcher(code).matches()) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_SERVICE_CODE);
        }
    }

    private static List<String> normalize(String channel, List<String> recipients) {
        int maxLength = switch (channel) {
            case null -> throw new CommonException(NotificationResultCodeEnum.UNSUPPORTED_CHANNEL);
            case "IN_APP" -> 64;
            case "SMS" -> 16;
            case "EMAIL" -> 254;
            case "PUSH" -> 512;
            default -> throw new CommonException(NotificationResultCodeEnum.UNSUPPORTED_CHANNEL);
        };
        if (recipients == null || recipients.isEmpty() || recipients.size() > 100) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_CHANNEL_RECIPIENTS);
        }
        return recipients.stream().map(recipient -> normalizeRecipient(channel, recipient, maxLength))
                .distinct().sorted().toList();
    }

    private static String normalizeRecipient(String channel, String recipient, int maxLength) {
        if (recipient == null) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_CHANNEL_RECIPIENTS);
        }
        String value = recipient.strip();
        if (value.isEmpty() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)
                || ("SMS".equals(channel) && !value.matches("[+]?[0-9]{3,15}"))
                || ("EMAIL".equals(channel) && !value.matches("[^@ ]+@[^@ ]+"))
                || ("PUSH".equals(channel) && value.chars().anyMatch(Character::isWhitespace))) {
            throw new CommonException(NotificationResultCodeEnum.INVALID_CHANNEL_RECIPIENTS);
        }
        return value;
    }

    @Override
    public String toString() {
        return "NotificationDispatchCommand[serviceCode=" + serviceCode + "]";
    }
}
