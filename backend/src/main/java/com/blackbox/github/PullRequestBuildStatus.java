package com.blackbox.github;

import java.time.Instant;

public record PullRequestBuildStatus(
        String status,
        String conclusion,
        String name,
        String url,
        Instant updatedAt
) {
    public static PullRequestBuildStatus unknown() {
        return new PullRequestBuildStatus("unknown", null, null, null, null);
    }
}

