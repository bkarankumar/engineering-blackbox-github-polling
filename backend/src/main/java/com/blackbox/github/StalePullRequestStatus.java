package com.blackbox.github;

import java.time.Instant;

public record StalePullRequestStatus(
        String organization,
        String repository,
        int pullRequestNumber,
        String title,
        String author,
        String url,
        Instant openedAt,
        long openDays
) {
}

