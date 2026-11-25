package io.github.loadup.modules.notification.web;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.components.gotone.config.NotificationRouting;
import io.github.loadup.components.gotone.config.NotificationRouting.ServiceRoute;
import io.github.loadup.modules.notification.client.command.NotificationDispatchCommand;
import io.github.loadup.modules.notification.client.dto.NotificationAcceptedDTO;
import io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum;
import io.github.loadup.modules.notification.client.facade.NotificationFacade;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class NotificationOperationsController {
    private final NotificationFacade notifications;
    private final NotificationRouting routing;

    public NotificationOperationsController(NotificationFacade notifications, NotificationRouting routing) {
        this.notifications = notifications;
        this.routing = routing;
    }

    @GetMapping("/routes")
    public SuccessResponse<List<ServiceRoute>> routes() {
        return SuccessResponse.of(routing.list());
    }

    @PutMapping("/routes")
    public SuccessResponse<Void> saveRoute(@RequestBody ServiceRoute route) {
        NotificationDispatchCommand.validateServiceCode(route.serviceCode());
        if (route.channels() == null || route.channels().size() > NotificationRouting.MANAGED_CHANNELS.size()
                || route.channels().stream().anyMatch(channel -> channel == null
                        || !NotificationRouting.MANAGED_CHANNELS.contains(channel))) {
            throw new CommonException(NotificationResultCodeEnum.UNSUPPORTED_CHANNEL);
        }
        routing.save(new ServiceRoute(route.serviceCode(), route.channels().stream().distinct().toList()));
        return SuccessResponse.success();
    }

    @PostMapping("/publish")
    public SuccessResponse<NotificationAcceptedDTO> publish(
            @RequestBody NotificationDispatchCommand command, Authentication authentication) {
        String actor = NotificationController.actor(authentication);
        return SuccessResponse.of(notifications.dispatch("operator:" + actor, actor, command));
    }
}
