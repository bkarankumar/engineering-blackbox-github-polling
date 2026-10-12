package com.blackbox.activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class ActivityStore {

    private static final int MAX_PULL_REQUESTS_PER_ACTION = 1;

    private final ConcurrentLinkedDeque<Activity> activities =
            new ConcurrentLinkedDeque<>();

    private final int maxEvents;

    public ActivityStore(
            @Value("${blackbox.max-events:500}") int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public void add(Activity activity) {
        if (activity.type() == ActivityType.PULL_REQUEST) {
            activities.removeIf(existing -> isSamePullRequest(existing, activity));
        }

        activities.addFirst(activity);
        trimPullRequestsPerRepository(MAX_PULL_REQUESTS_PER_ACTION);

        while (activities.size() > maxEvents) {
            activities.pollLast();
        }
    }

    private void trimPullRequestsPerRepository(int limit) {
        Set<String> idsToRemove = new HashSet<>();
        Map<String, Integer> countsByRepositoryAndAction = new HashMap<>();

        for (Activity candidate : activities) {
            if (candidate.type() != ActivityType.PULL_REQUEST) {
                continue;
            }

            String key = pullRequestBucketKey(candidate);
            int nextCount = countsByRepositoryAndAction.getOrDefault(key, 0) + 1;
            countsByRepositoryAndAction.put(key, nextCount);

            if (nextCount > limit) {
                idsToRemove.add(candidate.id());
            }
        }

        if (!idsToRemove.isEmpty()) {
            activities.removeIf(activity -> idsToRemove.contains(activity.id()));
        }
    }

    public List<Activity> getAll() {
        return new ArrayList<>(activities);
    }

    public List<Activity> getLatest(int limit) {
        return activities.stream()
                .limit(Math.min(limit, maxEvents))
                .toList();
    }

    public long count() {
        return activities.size();
    }

    public void clear() {
        activities.clear();
    }

    private boolean isSamePullRequest(Activity left, Activity right) {
        return left.type() == ActivityType.PULL_REQUEST
                && right.type() == ActivityType.PULL_REQUEST
                && left.pullRequestNumber() != null
                && left.pullRequestNumber().equals(right.pullRequestNumber())
                && same(left.organization(), right.organization())
                && same(left.repository(), right.repository());
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String pullRequestBucketKey(Activity activity) {
        return (activity.organization() == null ? "" : activity.organization())
                + "/"
                + (activity.repository() == null ? "" : activity.repository())
                + "#"
                + (activity.action() == null ? "" : activity.action());
    }
}
