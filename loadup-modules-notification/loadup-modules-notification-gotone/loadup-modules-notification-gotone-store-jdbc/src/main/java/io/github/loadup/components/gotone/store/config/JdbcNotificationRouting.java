package io.github.loadup.components.gotone.store.config;

import io.github.loadup.components.gotone.config.NotificationRouting;
import io.github.loadup.components.gotone.store.mapper.ServiceChannelRowMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

public class JdbcNotificationRouting extends JdbcChannelConfigProvider implements NotificationRouting {
    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcNotificationRouting(
            ServiceChannelRowMapper channels, JsonMapper mapper, JdbcClient jdbc, Clock clock) {
        super(channels, mapper);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<ServiceRoute> list() {
        Map<String, List<String>> routes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT service_code, NULL AS channel FROM gotone_notification_service WHERE deleted = FALSE
                UNION ALL
                SELECT service_code, CASE WHEN enabled THEN channel END FROM gotone_service_channel
                WHERE deleted = FALSE AND channel IN (:managed)
                ORDER BY service_code, channel
                """)
                .param("managed", MANAGED_CHANNELS)
                .query((row, index) -> new RouteRow(row.getString("service_code"), row.getString("channel")))
                .list()
                .forEach(row -> {
                    var channels = routes.computeIfAbsent(row.serviceCode(), ignored -> new ArrayList<>());
                    if (row.channel() != null) {
                        channels.add(row.channel());
                    }
                });
        return routes.entrySet().stream()
                .map(route -> new ServiceRoute(route.getKey(), List.copyOf(route.getValue())))
                .toList();
    }

    @Override
    @Transactional
    public void save(ServiceRoute route) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.sql("""
                INSERT INTO gotone_notification_service (id, service_code, created_at, updated_at)
                VALUES (:id, :code, :now, :now)
                ON CONFLICT (service_code) WHERE deleted = FALSE
                DO UPDATE SET updated_at = EXCLUDED.updated_at
                """)
                .param("id", UUID.randomUUID().toString())
                .param("code", route.serviceCode())
                .param("now", now)
                .update();
        jdbc.sql("""
                UPDATE gotone_service_channel SET enabled = FALSE, updated_at = :now
                WHERE service_code = :code AND deleted = FALSE AND channel IN (:managed)
                """)
                .param("code", route.serviceCode())
                .param("managed", MANAGED_CHANNELS)
                .param("now", now)
                .update();
        for (String channel : route.channels()) {
            jdbc.sql("""
                    INSERT INTO gotone_service_channel (id, service_code, channel, created_at, updated_at)
                    VALUES (:id, :code, :channel, :now, :now)
                    ON CONFLICT (service_code, channel) WHERE deleted = FALSE
                    DO UPDATE SET enabled = TRUE, updated_at = EXCLUDED.updated_at
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("code", route.serviceCode())
                    .param("channel", channel)
                    .param("now", now)
                    .update();
        }
    }

    private record RouteRow(String serviceCode, String channel) {}
}
