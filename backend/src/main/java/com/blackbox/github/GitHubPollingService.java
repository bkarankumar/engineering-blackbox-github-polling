package com.blackbox.github;

import com.blackbox.activity.Activity;
import com.blackbox.activity.ActivityStream;
import com.blackbox.activity.ActivityStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GitHubPollingService {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final ActivityStore activityStore;
    private final ActivityStream activityStream;
    private final List<String> repositories;
    private final int pullRequestsPerRepository;
    private final int stalePullRequestDays;

    /** Last emitted PR snapshot keyed by owner/repo#number. */
    private final Map<String, String> lastPublishedPullRequestState = new ConcurrentHashMap<>();
    private final Map<String, String> commitMessagesBySha = new ConcurrentHashMap<>();
    private final Map<String, String> defaultBranchByRepository = new ConcurrentHashMap<>();
    private final Map<String, RepositoryBuildStatus> repositoryBuildStatuses = new ConcurrentHashMap<>();
    private final Map<String, List<StalePullRequestStatus>> stalePullRequestsByRepository = new ConcurrentHashMap<>();

    public GitHubPollingService(
            ObjectMapper objectMapper,
            ActivityStore activityStore,
            ActivityStream activityStream,
            @Value("${github.api-url:https://api.github.com}") String apiUrl,
            @Value("${github.token:}") String token,
            @Value("${github.repositories:}") String repositoryConfig,
            @Value("${github.pull-requests-per-repository:30}") int pullRequestsPerRepository,
            @Value("${blackbox.stale-pr-days:3}") int stalePullRequestDays) {

        this.objectMapper = objectMapper;
        this.activityStore = activityStore;
        this.activityStream = activityStream;
        this.pullRequestsPerRepository = pullRequestsPerRepository;
        this.stalePullRequestDays = stalePullRequestDays;
        this.repositories = parseRepositories(repositoryConfig);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(apiUrl.trim())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }

        this.client = builder.build();
    }

    @Scheduled(fixedDelayString = "${github.polling-interval-ms:180000}", initialDelay = 2000)
    public void pollGitHub() {
        if (repositories.isEmpty()) {
            log.warn("GitHub polling is disabled: GITHUB_REPOSITORIES is empty");
            return;
        }

        for (String repository : repositories) {
            pollRepository(repository);
        }
    }

    private void pollRepository(String repository) {
        try {
            String[] parts = repository.split("/", 2);

            if (parts.length != 2) {
                log.warn("Invalid GitHub repository configuration: {}", repository);
                return;
            }

            String owner = parts[0];
            String repo = parts[1];

            List<Activity> openPullRequests = fetchPullRequests(owner, repo, "open");
            updateStalePullRequests(owner, repo, openPullRequests);

            List<Activity> activities = new ArrayList<>();
            activities.addAll(openPullRequests);
            activities.addAll(fetchPullRequests(owner, repo, "closed"));
            refreshRepositoryBuildStatus(owner, repo);

            activities.sort(Comparator.comparing(
                    Activity::occurredAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));

            for (Activity activity : activities) {
                processPullRequest(activity);
            }

        } catch (RestClientResponseException ex) {
            log.warn(
                "GitHub polling failed for {}: HTTP {} {}",
                repository,
                ex.getStatusCode().value(),
                ex.getStatusText()
            );
        } catch (Exception ex) {
            log.warn(
                "GitHub polling failed for {}: {}",
                repository,
                ex.getMessage()
            );
        }
    }

    private List<Activity> fetchPullRequests(String owner, String repository, String state) throws Exception {
        String response = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/pulls")
                        .queryParam("state", state)
                        .queryParam("sort", "open".equals(state) ? "created" : "updated")
                        .queryParam("direction", "desc")
                        .queryParam("per_page", pullRequestsPerRepository)
                        .build(owner, repository))
                .retrieve()
                .body(String.class);

        JsonNode pullRequests = objectMapper.readTree(response);
        if (!pullRequests.isArray()) {
            return List.of();
        }

        List<Activity> activities = new ArrayList<>();
        for (JsonNode pullRequest : pullRequests) {
            Activity activity = mapPullRequest(owner, repository, pullRequest, state);
            if (activity != null) {
                activities.add(activity);
            }
        }

        return activities;
    }

    public List<RepositoryBuildStatus> getRepositoryBuildStatuses() {
        return repositoryBuildStatuses.values().stream()
                .sorted(Comparator.comparing(status -> status.organization() + "/" + status.repository()))
                .toList();
    }

    public List<StalePullRequestStatus> getStalePullRequests() {
        return stalePullRequestsByRepository.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator
                        .comparingLong(StalePullRequestStatus::openDays).reversed()
                        .thenComparing(status -> status.organization() + "/" + status.repository())
                        .thenComparingInt(StalePullRequestStatus::pullRequestNumber))
                .toList();
    }

    private void processPullRequest(Activity activity) {
        if (activity == null) {
            return;
        }

        String signature = activitySignature(activity);
        String previous = lastPublishedPullRequestState.put(activity.id(), signature);
        if (signature.equals(previous)) {
            return;
        }

        activityStore.add(activity);
        activityStream.publish(activity);
    }

    private Activity mapPullRequest(String organization, String repository, JsonNode pullRequest, String state) {
        Instant occurredAt;
        String action;

        if ("open".equals(state)) {
            occurredAt = instant(text(pullRequest, "created_at"));
            action = "opened";
        } else {
            String mergedAt = text(pullRequest, "merged_at");
            if (mergedAt == null) {
                return null;
            }

            occurredAt = instant(mergedAt);
            action = "merged";
        }

        if (occurredAt == null) {
            return null;
        }

        int pullRequestNumber = pullRequest.path("number").asInt();
        String headSha = text(pullRequest.path("head"), "sha");
        String commitMessage = fetchCommitMessage(organization, repository, headSha);
        PullRequestBuildStatus buildStatus = fetchPullRequestBuildStatus(organization, repository, headSha);

        return new Activity(
                pullRequestId(organization, repository, pullRequestNumber),
                com.blackbox.activity.ActivityType.PULL_REQUEST,
                action,
                repository,
                organization,
                text(pullRequest, "title"),
                text(pullRequest.path("user"), "login"),
                null,
                pullRequestNumber,
                text(pullRequest.path("head"), "ref"),
                text(pullRequest.path("base"), "ref"),
                text(pullRequest, "html_url"),
                commitMessage,
                buildStatus.status(),
                buildStatus.conclusion(),
                buildStatus.name(),
                buildStatus.url(),
                buildStatus.updatedAt(),
                null,
                "Pull request " + action,
                occurredAt);
    }

    private PullRequestBuildStatus fetchPullRequestBuildStatus(String owner, String repository, String sha) {
        if (sha == null || sha.isBlank()) {
            return PullRequestBuildStatus.unknown();
        }

        PullRequestBuildStatus fromChecks = fetchPullRequestBuildStatusFromChecks(owner, repository, sha);
        if (fromChecks != null) {
            return fromChecks;
        }

        PullRequestBuildStatus fromStatuses = fetchPullRequestBuildStatusFromCommitStatus(owner, repository, sha);
        return fromStatuses != null ? fromStatuses : PullRequestBuildStatus.unknown();
    }

    private PullRequestBuildStatus fetchPullRequestBuildStatusFromChecks(String owner, String repository, String sha) {
        try {
            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
                            .queryParam("per_page", 100)
                            .build(owner, repository, sha))
                    .retrieve()
                    .body(String.class);

            JsonNode checkRuns = objectMapper.readTree(response).path("check_runs");
            if (!checkRuns.isArray() || checkRuns.isEmpty()) {
                return null;
            }

            return aggregateBuildStatusFromCheckRuns(checkRuns);
        } catch (RestClientResponseException ex) {
            log.debug(
                    "Unable to fetch check runs for {}/{}@{}: HTTP {} {}",
                    owner,
                    repository,
                    sha,
                    ex.getStatusCode().value(),
                    ex.getStatusText()
            );
            return null;
        } catch (Exception ex) {
            log.debug(
                    "Unable to fetch check runs for {}/{}@{}: {}",
                    owner,
                    repository,
                    sha,
                    ex.getMessage()
            );
            return null;
        }
    }

    private PullRequestBuildStatus aggregateBuildStatusFromCheckRuns(JsonNode checkRuns) {
        JsonNode latestRun = null;
        JsonNode latestFailingRun = null;
        JsonNode latestRunningRun = null;
        Instant latestRunUpdatedAt = null;
        Instant latestFailingRunUpdatedAt = null;
        Instant latestRunningRunUpdatedAt = null;
        boolean hasFailure = false;
        boolean hasRunning = false;
        boolean hasSuccess = false;

        for (JsonNode checkRun : checkRuns) {
            Instant updatedAt = instant(text(checkRun, "updated_at"));
            if (isNewerThan(updatedAt, latestRunUpdatedAt)) {
                latestRun = checkRun;
                latestRunUpdatedAt = updatedAt;
            }

            String status = text(checkRun, "status");
            String conclusion = text(checkRun, "conclusion");

            if (isRunningStatus(status)) {
                hasRunning = true;
                if (isNewerThan(updatedAt, latestRunningRunUpdatedAt)) {
                    latestRunningRun = checkRun;
                    latestRunningRunUpdatedAt = updatedAt;
                }
            }

            if (isFailingConclusion(conclusion)) {
                hasFailure = true;
                if (isNewerThan(updatedAt, latestFailingRunUpdatedAt)) {
                    latestFailingRun = checkRun;
                    latestFailingRunUpdatedAt = updatedAt;
                }
            }

            if (isPassingConclusion(conclusion)) {
                hasSuccess = true;
            }
        }

        JsonNode representative = hasFailure
                ? latestFailingRun != null ? latestFailingRun : latestRun
                : hasRunning
                ? latestRunningRun != null ? latestRunningRun : latestRun
                : latestRun;

        String status = hasFailure
                ? "failing"
                : hasRunning
                ? "running"
                : hasSuccess
                ? "passing"
                : "unknown";

        String conclusion = hasFailure
                ? text(representative, "conclusion")
                : hasRunning
                ? "pending"
                : hasSuccess
                ? text(representative, "conclusion")
                : null;

        return new PullRequestBuildStatus(
                status,
                conclusion,
                text(representative, "name"),
                text(representative, "html_url"),
                instant(text(representative, "updated_at"))
        );
    }

    private boolean isRunningStatus(String status) {
        return "queued".equalsIgnoreCase(status)
                || "in_progress".equalsIgnoreCase(status)
                || "requested".equalsIgnoreCase(status)
                || "waiting".equalsIgnoreCase(status)
                || "pending".equalsIgnoreCase(status);
    }

    private boolean isPassingConclusion(String conclusion) {
        return "success".equalsIgnoreCase(conclusion)
                || "neutral".equalsIgnoreCase(conclusion)
                || "skipped".equalsIgnoreCase(conclusion);
    }

    private PullRequestBuildStatus fetchPullRequestBuildStatusFromCommitStatus(String owner, String repository, String sha) {
        try {
            String response = client.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}/status", owner, repository, sha)
                    .retrieve()
                    .body(String.class);

            JsonNode commitStatus = objectMapper.readTree(response);
            String state = text(commitStatus, "state");
            if (state == null || state.isBlank()) {
                return null;
            }

            JsonNode statuses = commitStatus.path("statuses");
            JsonNode latestStatus = null;
            Instant latestStatusUpdatedAt = null;
            if (statuses.isArray()) {
                for (JsonNode statusEntry : statuses) {
                    Instant updatedAt = instant(text(statusEntry, "updated_at"));
                    if (isNewerThan(updatedAt, latestStatusUpdatedAt)) {
                        latestStatus = statusEntry;
                        latestStatusUpdatedAt = updatedAt;
                    }
                }
            }

            String buildState = mapCommitStateToBuildState(state);
            return new PullRequestBuildStatus(
                    buildState,
                    state,
                    latestStatus != null ? text(latestStatus, "context") : text(commitStatus, "state"),
                    latestStatus != null && text(latestStatus, "target_url") != null
                            ? text(latestStatus, "target_url")
                            : text(commitStatus, "commit_url"),
                    latestStatus != null ? instant(text(latestStatus, "updated_at")) : null
            );
        } catch (RestClientResponseException ex) {
            log.debug(
                    "Unable to fetch commit status for {}/{}@{}: HTTP {} {}",
                    owner,
                    repository,
                    sha,
                    ex.getStatusCode().value(),
                    ex.getStatusText()
            );
            return null;
        } catch (Exception ex) {
            log.debug(
                    "Unable to fetch commit status for {}/{}@{}: {}",
                    owner,
                    repository,
                    sha,
                    ex.getMessage()
            );
            return null;
        }
    }

    private String mapCommitStateToBuildState(String state) {
        if (state == null) {
            return "unknown";
        }

        return switch (state.toLowerCase()) {
            case "success" -> "passing";
            case "failure", "error" -> "failing";
            case "pending" -> "running";
            default -> "unknown";
        };
    }

    private boolean isNewerThan(Instant candidate, Instant current) {
        if (candidate == null) {
            return false;
        }

        return current == null || candidate.isAfter(current);
    }

    private void refreshRepositoryBuildStatus(String owner, String repository) {
        String repositoryKey = owner + "/" + repository;

        try {
            String defaultBranch = defaultBranchByRepository.computeIfAbsent(
                    repositoryKey,
                    ignored -> fetchDefaultBranch(owner, repository)
            );

            if (defaultBranch == null || defaultBranch.isBlank()) {
                return;
            }

            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/actions/runs")
                            .queryParam("branch", defaultBranch)
                            .queryParam("status", "completed")
                            .queryParam("per_page", 1)
                            .build(owner, repository))
                    .retrieve()
                    .body(String.class);

            JsonNode workflowRuns = objectMapper.readTree(response).path("workflow_runs");
            RepositoryBuildStatus buildStatus = workflowRuns.isArray() && !workflowRuns.isEmpty()
                    ? mapBuildStatus(owner, repository, defaultBranch, workflowRuns.get(0))
                    : new RepositoryBuildStatus(
                            owner,
                            repository,
                            defaultBranch,
                            null,
                            "completed",
                            "unknown",
                            null,
                            null,
                            false
                    );

            repositoryBuildStatuses.put(repositoryKey, buildStatus);
        } catch (RestClientResponseException ex) {
            log.debug(
                    "Unable to fetch workflow status for {}/{}: HTTP {} {}",
                    owner,
                    repository,
                    ex.getStatusCode().value(),
                    ex.getStatusText()
            );
        } catch (Exception ex) {
            log.debug(
                    "Unable to fetch workflow status for {}/{}: {}",
                    owner,
                    repository,
                    ex.getMessage()
            );
        }
    }

    private void updateStalePullRequests(String owner, String repository, List<Activity> openPullRequests) {
        String repositoryKey = owner + "/" + repository;
        Instant now = Instant.now();

        List<StalePullRequestStatus> stalePullRequests = openPullRequests.stream()
                .filter(activity -> activity.occurredAt() != null)
                .map(activity -> toStalePullRequestStatus(activity, now))
                .filter(status -> status.openDays() >= stalePullRequestDays)
                .sorted(Comparator.comparingLong(StalePullRequestStatus::openDays).reversed())
                .toList();

        stalePullRequestsByRepository.put(repositoryKey, stalePullRequests);
    }

    private StalePullRequestStatus toStalePullRequestStatus(Activity activity, Instant now) {
        long openDays = Math.max(0, ChronoUnit.DAYS.between(activity.occurredAt(), now));

        return new StalePullRequestStatus(
                activity.organization(),
                activity.repository(),
                activity.pullRequestNumber() == null ? 0 : activity.pullRequestNumber(),
                activity.title(),
                activity.author(),
                activity.url(),
                activity.occurredAt(),
                openDays
        );
    }

    private String fetchDefaultBranch(String owner, String repository) {
        try {
            String response = client.get()
                    .uri("/repos/{owner}/{repo}", owner, repository)
                    .retrieve()
                    .body(String.class);

            JsonNode repo = objectMapper.readTree(response);
            return text(repo, "default_branch");
        } catch (Exception ex) {
            log.debug("Unable to fetch default branch for {}/{}: {}", owner, repository, ex.getMessage());
            return null;
        }
    }

    private RepositoryBuildStatus mapBuildStatus(String owner, String repository, String defaultBranch, JsonNode workflowRun) {
        String status = text(workflowRun, "status");
        String conclusion = text(workflowRun, "conclusion");

        return new RepositoryBuildStatus(
                owner,
                repository,
                defaultBranch,
                text(workflowRun, "name"),
                status,
                conclusion,
                text(workflowRun, "html_url"),
                instant(text(workflowRun, "updated_at")),
                isFailingConclusion(conclusion)
        );
    }

    private boolean isFailingConclusion(String conclusion) {
        return "failure".equalsIgnoreCase(conclusion)
                || "startup_failure".equalsIgnoreCase(conclusion)
                || "timed_out".equalsIgnoreCase(conclusion)
                || "action_required".equalsIgnoreCase(conclusion);
    }

    private String fetchCommitMessage(String owner, String repository, String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }

        String cacheKey = owner + "/" + repository + "#" + sha;
        if (commitMessagesBySha.containsKey(cacheKey)) {
            return commitMessagesBySha.get(cacheKey);
        }

        try {
            String response = client.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repository, sha)
                    .retrieve()
                    .body(String.class);

            JsonNode commit = objectMapper.readTree(response);
            String message = summarizeCommitMessage(text(commit.path("commit"), "message"));
            if (message != null) {
                commitMessagesBySha.put(cacheKey, message);
            }
            return message;
        } catch (RestClientResponseException ex) {
            log.debug(
                    "Unable to fetch commit message for {}/{}@{}: HTTP {} {}",
                    owner,
                    repository,
                    sha,
                    ex.getStatusCode().value(),
                    ex.getStatusText()
            );
            return null;
        } catch (Exception ex) {
            log.debug(
                    "Unable to fetch commit message for {}/{}@{}: {}",
                    owner,
                    repository,
                    sha,
                    ex.getMessage()
            );
            return null;
        }
    }

    private String summarizeCommitMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String headline = message.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);

        return headline == null || headline.isBlank() ? null : headline;
    }

    private List<String> parseRepositories(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.replaceFirst("^/+", "").replaceFirst("/+$", ""))
                .filter(item -> item.contains("/"))
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private String pullRequestId(String organization, String repository, int pullRequestNumber) {
        return organization + "/" + repository + "#" + pullRequestNumber;
    }

    private String activitySignature(Activity activity) {
        return String.join("|",
                activity.action(),
                nullSafe(activity.title()),
                nullSafe(activity.author()),
                String.valueOf(activity.pullRequestNumber()),
                nullSafe(activity.branch()),
                nullSafe(activity.targetBranch()),
                nullSafe(activity.url()),
                nullSafe(activity.commitMessage()),
                nullSafe(activity.buildStatus()),
                nullSafe(activity.buildConclusion()),
                nullSafe(activity.buildName()),
                nullSafe(activity.buildUrl()),
                activity.buildUpdatedAt() == null ? "" : activity.buildUpdatedAt().toString(),
                activity.occurredAt() == null ? "" : activity.occurredAt().toString());
    }

    private Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
