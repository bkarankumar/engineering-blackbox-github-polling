package com.blackbox;

import com.blackbox.activity.Activity;
import com.blackbox.activity.ActivityStore;
import com.blackbox.activity.ActivityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackboxApplicationTests {

    @Test
    void smokeTest() {
        // Add GitHub API integration tests in the target environment.
    }

    @Test
    void keepsOnlyLatestOpenedAndMergedPullRequests() {
        ActivityStore store = new ActivityStore(500);

        for (int index = 1; index <= 4; index++) {
            store.add(pullRequest("opened-" + index, "opened", index));
        }

        for (int index = 1; index <= 4; index++) {
            store.add(pullRequest("merged-" + index, "merged", 100 + index));
        }

        List<Activity> latest = store.getLatest(20);

        assertEquals(2, latest.size());
        assertEquals(1, latest.stream().filter(activity -> "opened".equals(activity.action())).count());
        assertEquals(1, latest.stream().filter(activity -> "merged".equals(activity.action())).count());
        assertTrue(latest.stream().anyMatch(activity -> "opened-4".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "opened-3".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "opened-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "opened-1".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "merged-4".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "merged-3".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "merged-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "merged-1".equals(activity.id())));
    }

    @Test
    void keepsSeparatePullRequestWindowsPerRepository() {
        ActivityStore store = new ActivityStore(500);

        for (int index = 1; index <= 4; index++) {
            store.add(pullRequest("repo-a-opened-" + index, "opened", index, "repo-a"));
            store.add(pullRequest("repo-b-opened-" + index, "opened", 100 + index, "repo-b"));
        }

        List<Activity> latest = store.getLatest(20);

        assertEquals(2, latest.stream().filter(activity -> "opened".equals(activity.action())).count());
        assertTrue(latest.stream().anyMatch(activity -> "repo-a-opened-4".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-a-opened-3".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-a-opened-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-a-opened-1".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "repo-b-opened-4".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-b-opened-3".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-b-opened-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "repo-b-opened-1".equals(activity.id())));
    }

    @Test
    void mergedPullRequestReplacesOpenedStateForSamePullRequest() {
        ActivityStore store = new ActivityStore(500);

        store.add(pullRequest("org/repo#162", "opened", 162));
        store.add(pullRequest("org/repo#162", "merged", 162));

        List<Activity> latest = store.getLatest(10);

        assertEquals(1, latest.size());
        assertEquals("merged", latest.get(0).action());
        assertEquals(162, latest.get(0).pullRequestNumber());
    }

    private Activity pullRequest(String id, String action, int prNumber) {
        return pullRequest(id, action, prNumber, "repo");
    }

    private Activity pullRequest(String id, String action, int prNumber, String repository) {
        return new Activity(
                id,
                ActivityType.PULL_REQUEST,
                action,
                repository,
                "org",
                "Title " + prNumber,
                "author",
                null,
                prNumber,
                "feature/" + prNumber,
                "main",
                "https://github.com/org/repo/pull/" + prNumber,
                "feat: add change " + prNumber,
                null,
                null,
                null,
                null,
                null,
                null,
                "Pull request " + action,
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(prNumber)
        );
    }
}
