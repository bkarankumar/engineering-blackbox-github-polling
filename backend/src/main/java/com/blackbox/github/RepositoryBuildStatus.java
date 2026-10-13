package com.blackbox.github;

import java.time.Instant;

public record RepositoryBuildStatus(
        String organization,
        String repository,
        String defaultBranch,
        String workflowName,
        String status,
        String conclusion,
        String url,
        Instant updatedAt,
        boolean failing
) {
}

