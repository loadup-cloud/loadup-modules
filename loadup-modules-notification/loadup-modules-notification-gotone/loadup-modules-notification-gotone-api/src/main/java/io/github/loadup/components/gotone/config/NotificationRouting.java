package io.github.loadup.components.gotone.config;

import java.util.List;
import java.util.Set;

public interface NotificationRouting extends ChannelConfigProvider {
    Set<String> MANAGED_CHANNELS = Set.of("IN_APP", "EMAIL", "SMS", "PUSH");

    List<ServiceRoute> list();

    void save(ServiceRoute route);

    record ServiceRoute(String serviceCode, List<String> channels) {}
}
