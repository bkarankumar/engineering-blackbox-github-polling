import React, { useEffect, useMemo, useState } from "react";
import PropTypes from "prop-types";
import { createRoot } from "react-dom/client";
import {
    GitPullRequest,
    GitMerge,
    MessageSquare,
    Rocket,
    PlayCircle,
    RefreshCw,
    Search,
    ExternalLink,
    Radio
} from "lucide-react";
import "./styles.css";

const MAX_PULL_REQUESTS_PER_ACTION = 3;

function App() {
    const [activities, setActivities] = useState([]);
    const [repository, setRepository] = useState("");
    const [actionFilter, setActionFilter] = useState("");
    const [connected, setConnected] = useState(false);
    const [status, setStatus] = useState(null);

    useEffect(() => {
        loadInitialActivities();

        const source = new EventSource("/api/stream/activities");

        source.addEventListener("connected", () => {
            setConnected(true);
        });

        source.addEventListener("activity", event => {
            const activity = JSON.parse(event.data);
            setActivities(previous => mergeActivity(previous, activity));
        });

        source.onerror = () => {
            setConnected(false);
        };

        return () => source.close();
    }, []);

    async function loadInitialActivities() {
        const [activitiesResponse, statusResponse] = await Promise.all([
            fetch("/api/activities?limit=6"),
            fetch("/api/status")
        ]);

        if (activitiesResponse.ok) {
            setActivities(limitPullRequestActivities(await activitiesResponse.json()));
        }

        if (statusResponse.ok) {
            setStatus(await statusResponse.json());
        }
    }

    const configuredRepositories = status?.configuredRepositories ?? [];

    const repositories = useMemo(
        () => [...new Set([
            ...configuredRepositories,
            ...activities.map(getRepositoryFullName).filter(Boolean)
        ])]
            .sort((left, right) => left.localeCompare(right)),
        [activities, configuredRepositories]
    );

    const filtered = activities.filter(activity => {
        const repositoryMatch =
            !repository || getRepositoryFullName(activity) === repository;

        const actionMatch =
            !actionFilter || activity.action === actionFilter;

        return repositoryMatch && actionMatch;
    });

    const openedCount = activities.filter(
        a => a.type === "PULL_REQUEST" && a.action === "opened"
    ).length;

    const mergedCount = activities.filter(
        a => a.type === "PULL_REQUEST" && a.action === "merged"
    ).length;

    return (
        <div className="app">
            <header className="header">
                <div>
                    <div className="eyebrow">ENGINEERING OBSERVABILITY</div>

                    <h1>
                        <span className="logo">◉</span>{" "}
                        Engineering Blackbox
                    </h1>

                    <p>
                        A live window into what is changing across your repositories.
                    </p>
                </div>

                <div className={`connection ${connected ? "online" : ""}`}>
                    <Radio size={15} />
                    {connected ? "LIVE" : "CONNECTING"}
                </div>
            </header>

            <section className="hero">
                <div>
                    <span className="hero-label">LIVE EVENTS</span>
                    <strong>{activities.length}</strong>
                    <span>events in memory</span>
                </div>

                <div>
                    <span className="hero-label">REPOSITORIES</span>
                    <strong>{configuredRepositories.length || repositories.length}</strong>
                    <span>configured repositories</span>
                </div>

                <div>
                    <span className="hero-label">PR ACTIVITY</span>
                    <strong>{openedCount} / {mergedCount}</strong>
                    <span>opened vs merged PRs</span>
                </div>

                <div>
                    <span className="hero-label">PR WINDOW</span>
                    <strong>3 / 3</strong>
                    <span>latest opened and merged PRs</span>
                </div>
            </section>

            <section className="filters">
                <div className="search-box">
                    <Search size={16} />
                    <select
                        value={repository}
                        onChange={event => setRepository(event.target.value)}
                    >
                        <option value="">All repositories</option>
                        {repositories.map(repo => (
                            <option key={repo} value={repo}>
                                {repo}
                            </option>
                        ))}
                    </select>
                </div>

                <select
                    value={actionFilter}
                    onChange={event => setActionFilter(event.target.value)}
                >
                    <option value="">All PRs</option>
                    <option value="opened">Opened PRs</option>
                    <option value="merged">Merged PRs</option>
                </select>

                <button onClick={loadInitialActivities}>
                    <RefreshCw size={15} />
                    Refresh
                </button>
            </section>

            <main>
                <div className="section-heading">
                    <div>
                        <h2>Live activity</h2>
                        <span>GitHub is polled automatically every 15 minutes</span>
                    </div>
                </div>

                <div className="timeline">
                    {filtered.map(activity => (
                        <ActivityCard
                            key={activity.id}
                            activity={activity}
                        />
                    ))}

                    {!filtered.length && (
                        <div className="empty">
                            <Radio size={35} />
                            <h3>Waiting for activity</h3>
                            <p>
                                Open or merge a pull request in a configured repository and the latest
                                three events for each will appear here.
                            </p>
                        </div>
                    )}
                </div>
            </main>
        </div>
    );
}

function ActivityCard(props) {
    const { activity } = props;
    const details = getDetails(activity);
    const pullRequestUrl = getPullRequestUrl(activity);
    const isNavigablePullRequest = Boolean(pullRequestUrl);
    const title = activity.title || activity.summary || "Activity";

    const CardTag = isNavigablePullRequest ? "a" : "article";
    const cardProps = isNavigablePullRequest
        ? {
            href: pullRequestUrl,
            target: "_blank",
            rel: "noopener noreferrer",
            title: "Open pull request in GitHub"
        }
        : {};

    return (
        <CardTag
            className={`activity ${details.className} ${isNavigablePullRequest ? "activity-clickable activity-card-link" : ""}`}
            {...cardProps}
        >
            <div className="activity-icon">
                {details.icon}
            </div>

            <div className="activity-content">
                <div className="activity-meta">
                    <span className="activity-type">
                        {details.label}
                    </span>

                    <time>
                        {formatTime(activity.occurredAt)}
                    </time>
                </div>

                <div className="repository">
                    {activity.organization
                        ? `${activity.organization}/`
                        : ""}
                    {activity.repository}
                </div>

                <div className="title-row">
                    <h3 className={isNavigablePullRequest ? "activity-title-link" : undefined}>
                        {title}
                    </h3>

                    {isNavigablePullRequest ? (
                        <span className="title-row-icon" title="Open pull request in GitHub">
                            <ExternalLink size={15} />
                        </span>
                    ) : activity.url && (
                        <a
                            href={activity.url}
                            target="_blank"
                            rel="noreferrer"
                            title="Open in GitHub"
                        >
                            <ExternalLink size={15} />
                        </a>
                    )}
                </div>

                {activity.commitMessage && (
                    <div className="details">
                        <span>💬 {activity.commitMessage}</span>
                    </div>
                )}

                <div className="details">
                    {activity.author && (
                        <span>👤 {activity.author}</span>
                    )}

                    {activity.reviewer && (
                        <span>🔎 {activity.reviewer}</span>
                    )}

                    {activity.pullRequestNumber && (
                        <span className={isNavigablePullRequest ? "pr-number-link" : undefined}>
                            PR #{activity.pullRequestNumber}
                        </span>
                    )}

                    {activity.branch && (
                        <span>
                            {activity.branch}
                            {activity.targetBranch
                                ? ` → ${activity.targetBranch}`
                                : ""}
                        </span>
                    )}

                    {activity.releaseTag && (
                        <span>🏷️ {activity.releaseTag}</span>
                    )}
                </div>

                {isNavigablePullRequest && (
                    <div className="activity-actions">
                        <span className="open-pr-link">
                            Open PR
                            <ExternalLink size={14} />
                        </span>
                    </div>
                )}
            </div>
        </CardTag>
    );
}

function getPullRequestUrl(activity) {
    if (activity.type !== "PULL_REQUEST") {
        return null;
    }

    if (activity.url) {
        return activity.url;
    }

    if (!activity.organization || !activity.repository || !activity.pullRequestNumber) {
        return null;
    }

    return `https://github.com/${activity.organization}/${activity.repository}/pull/${activity.pullRequestNumber}`;
}

function getRepositoryFullName(activity) {
    if (!activity?.repository) {
        return null;
    }

    if (activity.organization) {
        return `${activity.organization}/${activity.repository}`;
    }

    return activity.repository;
}

ActivityCard.propTypes = {
    activity: PropTypes.shape({
        id: PropTypes.string.isRequired,
        type: PropTypes.string.isRequired,
        action: PropTypes.string.isRequired,
        repository: PropTypes.string,
        organization: PropTypes.string,
        title: PropTypes.string,
        author: PropTypes.string,
        reviewer: PropTypes.string,
        pullRequestNumber: PropTypes.number,
        branch: PropTypes.string,
        targetBranch: PropTypes.string,
        url: PropTypes.string,
        commitMessage: PropTypes.string,
        releaseTag: PropTypes.string,
        summary: PropTypes.string,
        occurredAt: PropTypes.string.isRequired
    }).isRequired
};

function getDetails(activity) {
    if (activity.type === "PULL_REQUEST") {
        if (activity.action === "merged") {
            return {
                label: "PR MERGED",
                className: "merged",
                icon: <GitMerge size={17} />
            };
        }

        return {
            label: activity.action === "opened"
                ? "PR OPENED"
                : `PR ${activity.action.toUpperCase()}`,
            className: "pull-request",
            icon: <GitPullRequest size={17} />
        };
    }

    if (activity.type === "REVIEW") {
        return {
            label: `REVIEW ${activity.action.toUpperCase()}`,
            className: "review",
            icon: <MessageSquare size={17} />
        };
    }

    if (activity.type === "RELEASE") {
        return {
            label: `RELEASE ${activity.action.toUpperCase()}`,
            className: "release",
            icon: <Rocket size={17} />
        };
    }

    if (activity.type === "WORKFLOW") {
        return {
            label: `WORKFLOW ${activity.action.toUpperCase()}`,
            className: "workflow",
            icon: <PlayCircle size={17} />
        };
    }

    return {
        label: "PUSH",
        className: "push",
        icon: <GitPullRequest size={17} />
    };
}

function limitPullRequestActivities(items) {
    const counts = {
        opened: 0,
        merged: 0
    };

    return items.filter(activity => {
        if (activity.type !== "PULL_REQUEST") {
            return false;
        }

        if (activity.action !== "opened" && activity.action !== "merged") {
            return false;
        }

        if (counts[activity.action] >= MAX_PULL_REQUESTS_PER_ACTION) {
            return false;
        }

        counts[activity.action] += 1;
        return true;
    });
}

function mergeActivity(items, nextActivity) {
    const withoutDuplicate = items.filter(item => item.id !== nextActivity.id);
    return limitPullRequestActivities([nextActivity, ...withoutDuplicate]);
}

function formatTime(value) {
    return new Date(value).toLocaleString([], {
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}

createRoot(document.getElementById("root")).render(
    <React.StrictMode>
        <App />
    </React.StrictMode>
);
