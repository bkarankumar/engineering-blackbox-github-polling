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

    /** Last emitted PR snapshot keyed by owner/repo#number. */
    private final Map<String, String> lastPublishedPullRequestState = new ConcurrentHashMap<>();
    private final Map<String, String> commitMessagesBySha = new ConcurrentHashMap<>();

    public GitHubPollingService(
            ObjectMapper objectMapper,
            ActivityStore activityStore,
            ActivityStream activityStream,
            @Value("${github.api-url:https://api.github.com}") String apiUrl,
            @Value("${github.token:}") String token,
            @Value("${github.repositories:}") String repositoryConfig,
            @Value("${github.pull-requests-per-repository:30}") int pullRequestsPerRepository) {

        this.objectMapper = objectMapper;
        this.activityStore = activityStore;
        this.activityStream = activityStream;
        this.pullRequestsPerRepository = pullRequestsPerRepository;
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

            List<Activity> activities = new ArrayList<>();
            activities.addAll(fetchPullRequests(owner, repo, "open"));
            activities.addAll(fetchPullRequests(owner, repo, "closed"));

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
                null,
                "Pull request " + action,
                occurredAt);
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
