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
    void keepsOnlyLatestThreeOpenedAndMergedPullRequests() {
        ActivityStore store = new ActivityStore(500);

        for (int index = 1; index <= 4; index++) {
            store.add(pullRequest("opened-" + index, "opened", index));
        }

        for (int index = 1; index <= 4; index++) {
            store.add(pullRequest("merged-" + index, "merged", 100 + index));
        }

        List<Activity> latest = store.getLatest(20);

        assertEquals(6, latest.size());
        assertEquals(3, latest.stream().filter(activity -> "opened".equals(activity.action())).count());
        assertEquals(3, latest.stream().filter(activity -> "merged".equals(activity.action())).count());
        assertTrue(latest.stream().anyMatch(activity -> "opened-4".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "opened-3".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "opened-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "opened-1".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "merged-4".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "merged-3".equals(activity.id())));
        assertTrue(latest.stream().anyMatch(activity -> "merged-2".equals(activity.id())));
        assertFalse(latest.stream().anyMatch(activity -> "merged-1".equals(activity.id())));
    }

    private Activity pullRequest(String id, String action, int prNumber) {
        return new Activity(
                id,
                ActivityType.PULL_REQUEST,
                action,
                "repo",
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
                "Pull request " + action,
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(prNumber)
        );
    }
}
