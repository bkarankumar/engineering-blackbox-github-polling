package com.blackbox.api;

import com.blackbox.activity.ActivityStore;
import com.blackbox.activity.ActivityStream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final ActivityStore store;
    private final ActivityStream stream;

    @Value("${github.repositories:}")
    private String repositories;

    @Value("${github.polling-interval-ms:10000}")
    private long pollingIntervalMs;

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<String> configuredRepositories = repositories == null || repositories.isBlank()
                ? List.of()
                : Arrays.stream(repositories.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        return Map.of(
                "status", "UP",
                "mode", "github-api-polling",
                "storedEvents", store.count(),
                "connectedDashboards", stream.connectedClients(),
                "configuredRepositories", configuredRepositories,
                "pollingIntervalMs", pollingIntervalMs
        );
    }
}
