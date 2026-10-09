package com.blackbox.activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class ActivityStore {

    private static final int MAX_PULL_REQUESTS_PER_ACTION = 3;

    private final ConcurrentLinkedDeque<Activity> activities =
            new ConcurrentLinkedDeque<>();

    private final int maxEvents;

    public ActivityStore(
            @Value("${blackbox.max-events:500}") int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public void add(Activity activity) {
        activities.addFirst(activity);
        trimPullRequests("opened", MAX_PULL_REQUESTS_PER_ACTION);
        trimPullRequests("merged", MAX_PULL_REQUESTS_PER_ACTION);

        while (activities.size() > maxEvents) {
            activities.pollLast();
        }
    }

    private void trimPullRequests(String action, int limit) {
        Set<String> idsToRemove = new HashSet<>();
        int kept = 0;

        for (Activity candidate : activities) {
            if (candidate.type() != ActivityType.PULL_REQUEST || !action.equals(candidate.action())) {
                continue;
            }

            kept++;
            if (kept > limit) {
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
}
