package com.blackbox.api;

import com.blackbox.activity.ActivityStore;
import com.blackbox.activity.ActivityStream;
import com.blackbox.github.GitHubPollingService;
import com.blackbox.github.RepositoryBuildStatus;
import com.blackbox.github.StalePullRequestStatus;
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
    private final GitHubPollingService gitHubPollingService;

    @Value("${github.repositories:}")
    private String repositories;

    @Value("${github.polling-interval-ms:180000}")
    private long pollingIntervalMs;

    @Value("${github.pull-requests-per-repository:30}")
    private int pullRequestsPerRepository;

    @Value("${blackbox.stale-pr-days:3}")
    private int stalePullRequestDays;

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<String> configuredRepositories = repositories == null || repositories.isBlank()
                ? List.of()
                : Arrays.stream(repositories.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        List<RepositoryBuildStatus> repositoryBuildStatuses = gitHubPollingService.getRepositoryBuildStatuses();
        List<RepositoryBuildStatus> failingBuildStatuses = repositoryBuildStatuses.stream()
                .filter(RepositoryBuildStatus::failing)
                .toList();
        List<StalePullRequestStatus> stalePullRequests = gitHubPollingService.getStalePullRequests();

        return Map.ofEntries(
                Map.entry("status", "UP"),
                Map.entry("mode", "github-pr-polling"),
                Map.entry("storedEvents", store.count()),
                Map.entry("connectedDashboards", stream.connectedClients()),
                Map.entry("configuredRepositories", configuredRepositories),
                Map.entry("pollingIntervalMs", pollingIntervalMs),
                Map.entry("pullRequestsPerRepository", pullRequestsPerRepository),
                Map.entry("stalePullRequestDays", stalePullRequestDays),
                Map.entry("repositoryBuildStatuses", repositoryBuildStatuses),
                Map.entry("failingBuildStatuses", failingBuildStatuses),
                Map.entry("stalePullRequests", stalePullRequests)
        );
    }
}
