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
import java.util.Set;
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
    private final int initialEventsPerRepository;

    /** GitHub event IDs already emitted by this process. */
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> commitMessagesBySha = new ConcurrentHashMap<>();

    public GitHubPollingService(
            ObjectMapper objectMapper,
            ActivityStore activityStore,
            ActivityStream activityStream,
            @Value("${github.api-url:https://api.github.com}") String apiUrl,
            @Value("${github.token:}") String token,
            @Value("${github.repositories:}") String repositoryConfig,
            @Value("${github.initial-events-per-repository:30}") int initialEventsPerRepository) {

        this.objectMapper = objectMapper;
        this.activityStore = activityStore;
        this.activityStream = activityStream;
        this.initialEventsPerRepository = initialEventsPerRepository;
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

    @Scheduled(fixedDelayString = "${github.polling-interval-ms:10000}", initialDelay = 2000)
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

            String response = client.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/repos/{owner}/{repo}/events")
                    .queryParam("per_page", initialEventsPerRepository)
                    .build(owner, repo))
                .retrieve()
                .body(String.class);

            JsonNode events = objectMapper.readTree(response);

            if (!events.isArray()) {
                return;
            }

            List<JsonNode> ordered = new ArrayList<>();
            events.forEach(ordered::add);

            ordered.sort(Comparator.comparing(
                this::eventTime,
                Comparator.nullsLast(Comparator.naturalOrder())
            ));

            for (JsonNode event : ordered) {
                processEvent(event, repository);
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

    private void processEvent(JsonNode event, String configuredRepository) {
        String eventId = text(event, "id");
        if (eventId == null || !seenEventIds.add(eventId)) {
            return;
        }

        Activity activity = mapEvent(event, configuredRepository);
        if (activity != null) {
            activityStore.add(activity);
            activityStream.publish(activity);
        }
    }

    private Activity mapEvent(JsonNode root, String configuredRepository) {
        String type = text(root, "type");
        JsonNode payload = root.path("payload");
        JsonNode repo = root.path("repo");
        String fullName = text(repo, "name");
        String repository = fullName != null && fullName.contains("/")
                ? fullName.substring(fullName.indexOf('/') + 1)
                : configuredRepository.substring(configuredRepository.lastIndexOf('/') + 1);
        String organization = fullName != null && fullName.contains("/")
                ? fullName.substring(0, fullName.indexOf('/'))
                : configuredRepository.substring(0, configuredRepository.lastIndexOf('/'));
        String actor = text(root.path("actor"), "login");
        Instant occurredAt = eventTime(root);

        return switch (type) {
            case "PullRequestEvent" -> pullRequest(root, payload, repository, organization, actor, occurredAt);
            default -> null;
        };
    }

    private Activity pullRequest(JsonNode root, JsonNode payload, String repository,
                                 String organization, String actor, Instant occurredAt) {
        JsonNode pr = payload.path("pull_request");
        String action = text(payload, "action");
        String headSha = text(pr.path("head"), "sha");
        if ("closed".equals(action) && pr.path("merged").asBoolean(false)) {
            action = "merged";
        }

        if (!"opened".equals(action) && !"merged".equals(action)) {
            return null;
        }

        String commitMessage = fetchCommitMessage(organization, repository, headSha);

        return new Activity(
                eventId(root),
                com.blackbox.activity.ActivityType.PULL_REQUEST,
                action,
                repository,
                organization,
                text(pr, "title"),
                text(pr.path("user"), "login"),
                null,
                pr.path("number").asInt(),
                text(pr.path("head"), "ref"),
                text(pr.path("base"), "ref"),
                text(pr, "html_url"),
                commitMessage,
                null,
                "Pull request " + action,
                occurredAt);
    }

    private Activity pullRequestReview(JsonNode root, JsonNode payload, String repository,
                                       String organization, String actor, Instant occurredAt) {
        JsonNode pr = payload.path("pull_request");
        JsonNode review = payload.path("review");
        String action = text(payload, "action");

        return new Activity(
                eventId(root),
                com.blackbox.activity.ActivityType.REVIEW,
                action,
                repository,
                organization,
                text(pr, "title"),
                text(pr.path("user"), "login"),
                text(review.path("user"), "login"),
                pr.path("number").asInt(),
                text(pr.path("head"), "ref"),
                text(pr.path("base"), "ref"),
                text(pr, "html_url"),
                null,
                null,
                "Pull request review " + action,
                occurredAt);
    }

    private Activity release(JsonNode root, JsonNode payload, String repository,
                             String organization, String actor, Instant occurredAt) {
        JsonNode release = payload.path("release");
        String action = text(payload, "action");

        return new Activity(
                eventId(root),
                com.blackbox.activity.ActivityType.RELEASE,
                action,
                repository,
                organization,
                text(release, "name"),
                text(release.path("author"), "login"),
                null,
                null,
                null,
                null,
                text(release, "html_url"),
                null,
                text(release, "tag_name"),
                "Release " + text(release, "tag_name"),
                occurredAt);
    }

    private Activity push(JsonNode root, JsonNode payload, String repository,
                          String organization, String actor, Instant occurredAt) {
        String ref = text(payload, "ref");
        String branch = ref != null && ref.startsWith("refs/heads/")
                ? ref.substring("refs/heads/".length())
                : ref;
        int commits = payload.path("commits").size();
        String compareUrl = text(payload, "compare");

        return new Activity(
                eventId(root),
                com.blackbox.activity.ActivityType.PUSH,
                "pushed",
                repository,
                organization,
                null,
                actor,
                null,
                null,
                branch,
                null,
                compareUrl,
                null,
                null,
                commits + " commit(s) pushed",
                occurredAt);
    }

    private Activity workflow(JsonNode root, JsonNode payload, String repository,
                              String organization, String actor, Instant occurredAt) {
        JsonNode workflow = payload.path("workflow_run");
        String action = text(payload, "action");

        return new Activity(
                eventId(root),
                com.blackbox.activity.ActivityType.WORKFLOW,
                action,
                repository,
                organization,
                text(workflow, "name"),
                text(workflow.path("actor"), "login"),
                null,
                null,
                null,
                null,
                text(workflow, "html_url"),
                null,
                null,
                "Workflow " + action,
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

    private String eventId(JsonNode root) {
        return text(root, "id");
    }

    private Instant eventTime(JsonNode node) {
        String value = text(node, "created_at");
        if (value == null) {
            value = text(node.path("payload").path("pull_request"), "updated_at");
        }
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
