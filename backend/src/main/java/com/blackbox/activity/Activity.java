package com.blackbox.activity;

import java.time.Instant;

public record Activity(
        String id,
        ActivityType type,
        String action,
        String repository,
        String organization,
        String title,
        String author,
        String reviewer,
        Integer pullRequestNumber,
        String branch,
        String targetBranch,
        String url,
        String commitMessage,
        String buildStatus,
        String buildConclusion,
        String buildName,
        String buildUrl,
        Instant buildUpdatedAt,
        String releaseTag,
        String summary,
        Instant occurredAt
) {
}
