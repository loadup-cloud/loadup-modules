package io.github.loadup.modules.notification.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RoutedNotificationContent(
        String serviceCode,
        String senderId,
        String title,
        String body,
        String actionUrl,
        LocalDateTime expiresAt,
        Map<String, List<String>> recipientsByChannel) {
    @Override
    public String toString() {
        return "RoutedNotificationContent[serviceCode=" + serviceCode + "]";
    }
}
