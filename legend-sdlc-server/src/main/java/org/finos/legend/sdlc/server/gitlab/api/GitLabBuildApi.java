// Copyright 2020 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.sdlc.server.gitlab.api;

import org.finos.legend.sdlc.domain.model.build.Build;
import org.finos.legend.sdlc.domain.model.build.BuildStatus;
import org.finos.legend.sdlc.domain.model.project.workspace.WorkspaceType;
import org.finos.legend.sdlc.domain.model.revision.RevisionAlias;
import org.finos.legend.sdlc.domain.model.version.VersionId;
import org.finos.legend.sdlc.server.domain.api.build.BuildAccessContext;
import org.finos.legend.sdlc.server.domain.api.build.BuildApi;
import org.finos.legend.sdlc.server.domain.api.project.SourceSpecification;
import org.finos.legend.sdlc.server.error.LegendSDLCServerException;
import org.finos.legend.sdlc.server.gitlab.GitLabConfiguration;
import org.finos.legend.sdlc.server.gitlab.GitLabProjectId;
import org.finos.legend.sdlc.server.gitlab.auth.GitLabUserContext;
import org.finos.legend.sdlc.server.gitlab.tools.PagerTools;
import org.finos.legend.sdlc.server.project.ProjectFileAccessProvider;
import org.finos.legend.sdlc.server.tools.BackgroundTaskProcessor;
import org.gitlab4j.api.Pager;
import org.gitlab4j.api.PipelineApi;
import org.gitlab4j.api.models.Pipeline;
import org.gitlab4j.api.models.PipelineStatus;

import javax.inject.Inject;
import javax.ws.rs.core.Response.Status;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GitLabBuildApi extends GitLabApiWithFileAccess implements BuildApi
{
    @Inject
    public GitLabBuildApi(GitLabConfiguration gitLabConfiguration, GitLabUserContext userContext, BackgroundTaskProcessor backgroundTaskProcessor)
    {
        super(gitLabConfiguration, userContext, backgroundTaskProcessor);
    }

    @Override
    public BuildAccessContext getProjectBuildAccessContext(String projectId)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        return new GitLabBuildAccessContext(projectId)
        {
            @Override
            protected String getRef()
            {
                return getDefaultBranch(gitLabProjectId);
            }

            @Override
            protected String getRefInfoForException()
            {
                return "project " + projectId;
            }

            @Override
            protected ProjectFileAccessProvider.RevisionAccessContext getRevisionAccessContext()
            {
                return getProjectFileAccessProvider().getProjectRevisionAccessContext(projectId);
            }
        };
    }

    @Override
    public BuildAccessContext getWorkspaceBuildAccessContext(String projectId, String workspaceId, WorkspaceType workspaceType, ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(workspaceId, "workspaceId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        return new GitLabBuildAccessContext(projectId)
        {
            @Override
            protected String getRef()
            {
                return getBranchName(gitLabProjectId, SourceSpecification.newSourceSpecification(workspaceId, workspaceType, workspaceAccessType));
            }

            @Override
            protected String getRefInfoForException()
            {
                return "workspace " + workspaceId + " in project " + projectId;
            }

            @Override
            protected ProjectFileAccessProvider.RevisionAccessContext getRevisionAccessContext()
            {
                return getProjectFileAccessProvider().getWorkspaceRevisionAccessContext(projectId, workspaceId, workspaceType, workspaceAccessType);
            }
        };
    }

    @Override
    public BuildAccessContext getVersionBuildAccessContext(String projectId, VersionId versionId)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(versionId, "versionId may not be null");
        return new GitLabBuildAccessContext(projectId)
        {
            @Override
            protected String getRef()
            {
                return buildVersionTagName(versionId);
            }

            @Override
            protected String getRefInfoForException()
            {
                return "version " + versionId.toVersionIdString() + " of project " + projectId;
            }

            @Override
            protected ProjectFileAccessProvider.RevisionAccessContext getRevisionAccessContext()
            {
                return getProjectFileAccessProvider().getVersionRevisionAccessContext(projectId, versionId);
            }
        };
    }

    private abstract class GitLabBuildAccessContext implements BuildAccessContext
    {
        private final GitLabProjectId projectId;

        private GitLabBuildAccessContext(GitLabProjectId projectId)
        {
            this.projectId = projectId;
        }

        private GitLabBuildAccessContext(String projectId)
        {
            this(parseProjectId(projectId));
        }

        @Override
        public Build getBuild(String buildId)
        {
            int pipelineId = parseIntegerIdIfNotNull(buildId);
            Pipeline pipeline;
            try
            {
                pipeline = withRetries(() -> getGitLabApi().getPipelineApi().getPipeline(this.projectId.getGitLabId(), pipelineId));
            }
            catch (Exception e)
            {
                throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access build " + buildId + " in " + getRefInfoForException(),
                    () -> "Unknown build in " + getRefInfoForException() + ": " + buildId,
                    () -> "Error getting build " + buildId + " in " + getRefInfoForException());
            }

            if (!getRef().equals(pipeline.getRef()))
            {
                throw new LegendSDLCServerException("Unknown build in " + getRefInfoForException() + ": " + buildId, Status.NOT_FOUND);
            }

            return fromGitLabPipeline(this.projectId, pipeline);
        }

        @Override
        public List<Build> getBuilds(Iterable<String> revisionIds, Iterable<BuildStatus> statuses, Integer limit)
        {
            try
            {
                boolean limited = false;
                if (limit != null)
                {
                    if (limit == 0)
                    {
                        return Collections.emptyList();
                    }
                    if (limit < 0)
                    {
                        throw new LegendSDLCServerException("Invalid limit: " + limit, Status.BAD_REQUEST);
                    }
                    limited = true;
                }
                PipelineApi pipelineApi = getGitLabApi().getPipelineApi();
                Pager<Pipeline> pager = withRetries(() -> pipelineApi.getPipelines(this.projectId.getGitLabId(), null, null, getRef(), false, null, null, null, null, ITEMS_PER_PAGE));
                Stream<Pipeline> pipelineStream = PagerTools.stream(pager);
                Set<String> revisionIdSet = (revisionIds == null)
                    ? Collections.emptySet()
                    : StreamSupport.stream(revisionIds.spliterator(), false)
                        // make sure various synonymous aliases are grouped together properly
                        .filter(Objects::nonNull)
                        .map(revisionId ->
                        {
                            RevisionAlias alias = getRevisionAlias(revisionId);
                            return (alias == RevisionAlias.REVISION_ID) ? revisionId : alias.getValue();
                        })
                        .distinct()
                        // resolve revision alias when possible
                        .map(revisionId -> resolveRevisionId(revisionId, getRevisionAccessContext()))
                        .collect(Collectors.toSet());
                if (!revisionIdSet.isEmpty())
                {
                    pipelineStream = pipelineStream.filter(pipeline -> revisionIdSet.contains(pipeline.getSha()));
                }

                Set<BuildStatus> statusSet = (statuses == null) ? Collections.emptySet() : ((statuses instanceof Set) ? (Set<BuildStatus>) statuses : StreamSupport.stream(statuses.spliterator(), false).collect(Collectors.toCollection(() -> EnumSet.noneOf(BuildStatus.class))));
                if (!statusSet.isEmpty())
                {
                    pipelineStream = pipelineStream.filter(pipeline -> statusSet.contains(fromGitLabPipelineStatus(pipeline.getStatus())));
                }

                if (limited)
                {
                    pipelineStream = pipelineStream.limit(limit);
                }

                return pipelineStream.map(p ->
                {
                    if (p.getCreatedAt() == null)
                    {
                        try
                        {
                            return withRetries(() -> pipelineApi.getPipeline(this.projectId.getGitLabId(), p.getId()));
                        }
                        catch (Exception ignore)
                        {
                            // ignore exception
                        }
                    }
                    return p;
                })
                .map(p -> fromGitLabPipeline(this.projectId, p))
                .collect(PagerTools.listCollector(pager, limit));
            }
            catch (Exception e)
            {
                throw buildException(e,
                    () -> "User " + getCurrentUser() + " is not allowed to access builds for " + getRefInfoForException(),
                    () -> "Unknown " + getRefInfoForException(),
                    () -> "Error getting builds for " + getRefInfoForException());
            }
        }

        protected abstract String getRef();

        protected abstract String getRefInfoForException();

        protected abstract ProjectFileAccessProvider.RevisionAccessContext getRevisionAccessContext();
    }

    private Build fromGitLabPipeline(GitLabProjectId projectId, Pipeline pipeline)
    {
        return fromGitLabPipeline(projectId.toString(), pipeline, pipeline.getWebUrl());
    }

    private static Build fromGitLabPipeline(String projectId, Pipeline pipeline, String webURL)
    {
        String id = toStringIfNotNull(pipeline.getId());
        String revisionId = pipeline.getSha();
        BuildStatus status = fromGitLabPipelineStatus(pipeline.getStatus());
        Instant createdAt = toInstantIfNotNull(pipeline.getCreatedAt());
        Instant startedAt = toInstantIfNotNull(pipeline.getStartedAt());
        Instant finishedAt = toInstantIfNotNull(pipeline.getFinishedAt());
        return new Build()
        {
            @Override
            public String getId()
            {
                return id;
            }

            @Override
            public String getProjectId()
            {
                return projectId;
            }

            @Override
            public String getRevisionId()
            {
                return revisionId;
            }

            @Override
            public BuildStatus getStatus()
            {
                return status;
            }

            @Override
            public Instant getCreatedAt()
            {
                return createdAt;
            }

            @Override
            public Instant getStartedAt()
            {
                return startedAt;
            }

            @Override
            public Instant getFinishedAt()
            {
                return finishedAt;
            }

            @Override
            public String getWebURL()
            {
                return webURL;
            }
        };
    }

    private static BuildStatus fromGitLabPipelineStatus(PipelineStatus pipelineStatus)
    {
        if (pipelineStatus == null)
        {
            return null;
        }
        switch (pipelineStatus)
        {
            case PENDING:
            {
                return BuildStatus.PENDING;
            }
            case RUNNING:
            {
                return BuildStatus.IN_PROGRESS;
            }
            case SUCCESS:
            {
                return BuildStatus.SUCCEEDED;
            }
            case FAILED:
            {
                return BuildStatus.FAILED;
            }
            case CANCELED:
            {
                return BuildStatus.CANCELED;
            }
            default:
            {
                return BuildStatus.UNKNOWN;
            }
        }
    }
}
