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

import org.finos.legend.sdlc.server.application.entity.PerformChangesCommand;
import org.finos.legend.sdlc.server.domain.api.conflictResolution.ConflictResolutionApi;
import org.finos.legend.sdlc.server.domain.api.entity.EntityApi;
import org.finos.legend.sdlc.server.domain.api.project.SourceSpecification;
import org.finos.legend.sdlc.server.error.LegendSDLCServerException;
import org.finos.legend.sdlc.server.gitlab.GitLabConfiguration;
import org.finos.legend.sdlc.server.gitlab.GitLabProjectId;
import org.finos.legend.sdlc.server.gitlab.auth.GitLabUserContext;
import org.finos.legend.sdlc.server.gitlab.tools.GitLabApiTools;
import org.finos.legend.sdlc.server.project.ProjectFileAccessProvider;
import org.finos.legend.sdlc.server.tools.BackgroundTaskProcessor;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.RepositoryApi;
import org.gitlab4j.api.models.Branch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

public class GitLabConflictResolutionApi extends GitLabApiWithFileAccess implements ConflictResolutionApi
{
    private static final Logger LOGGER = LoggerFactory.getLogger(GitLabConflictResolutionApi.class);

    private final EntityApi entityApi;

    @Inject
    public GitLabConflictResolutionApi(GitLabConfiguration gitLabConfiguration, GitLabUserContext userContext, EntityApi entityApi, BackgroundTaskProcessor backgroundTaskProcessor)
    {
        super(gitLabConfiguration, userContext, backgroundTaskProcessor);
        this.entityApi = entityApi;
    }

    @Override
    public void discardConflictResolution(String projectId, SourceSpecification sourceSpecification)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(sourceSpecification.getWorkspaceId(), "workspaceId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        boolean conflictResolutionBranchDeleted;
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        try
        {
            conflictResolutionBranchDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!conflictResolutionBranchDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
    }

    /**
     * This method will discard all changes in conflict resolution. Assume we have workspace branch `w1`, this method will:
     * 1. Remove conflict resolution branch of `w1`
     * 2. Create backup branch for `w1`
     * 3. Remove `w1`
     * 4. Create `w1` from project head
     * 5. Remove backup branch for `w1`
     */
    @Override
    public void discardChangesConflictResolution(String projectId, SourceSpecification sourceSpecification)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(sourceSpecification.getWorkspaceId(), "workspaceId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        // Verify conflict resolution is happening
        try
        {
            withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId()))));
        }
        catch (Exception e)
        {
            if (GitLabApiTools.isNotFoundGitLabApiException(e))
            {
                LOGGER.error("Conflict resolution is not happening on {} {} in project {}, so discard changes is not actionable", sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel(), sourceSpecification.getWorkspaceId(), projectId);
            }
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to get " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + "). " + "This implies that conflict resolution is not taking place, hence discard changes is not actionable",
                () -> "Error getting " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete backup branch if already exists
        boolean backupWorkspaceDeleted;
        ProjectFileAccessProvider.WorkspaceAccessType backupWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.BACKUP;
        try
        {
            backupWorkspaceDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            // If we fail to delete the residual backup workspace, we cannot proceed anyway, so we will throw the error here
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown project: " + projectId,
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!backupWorkspaceDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Create backup branch
        Branch workspaceBranch;
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        try
        {
            workspaceBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown project: " + projectId,
                () -> "Error creating " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (workspaceBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete original branch
        boolean originalBranchDeleted;
        try
        {
            originalBranchDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!originalBranchDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Create new workspace branch off the project HEAD
        Branch newWorkspaceBranch;
        try
        {
            newWorkspaceBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), getSourceBranch(gitLabProjectId, sourceSpecification.getPatchReleaseVersionId()), 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown project: " + projectId,
                () -> "Error creating " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (newWorkspaceBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete conflict resolution branch
        boolean conflictResolutionWorkspaceDeleted;
        try
        {
            conflictResolutionWorkspaceDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!conflictResolutionWorkspaceDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete backup branch
        try
        {
            boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
            if (!deleted)
            {
                LOGGER.error("Failed to delete {} {} in project {}", sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel(), sourceSpecification.getWorkspaceId(), projectId);
            }
        }
        catch (Exception e)
        {
            // unfortunate, but this should not throw error
            LOGGER.error("Error deleting {} {} in project {}", sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel(), sourceSpecification.getWorkspaceId(), projectId, e);
        }
    }

    /**
     * This method will apply conflict resolution changes and mark a conflict resolution as done.
     * Assume we have workspace branch `w1`, this method will:
     * 1. Perform entity changes to resolve conflicts
     * 2. Remove backup branch for `w1` if exists
     * 3. Create backup branch for `w1`
     * 4. Remove workspace branch `w1`
     * 5. Create new workspace branch `w1` from conflict resolution branch `w1`
     * 6. Remove conflict resolution branch `w1`
     * 7. Remove backup branch `w1`
     */
    @Override
    public void acceptConflictResolution(String projectId, SourceSpecification sourceSpecification, PerformChangesCommand command)
    {
        LegendSDLCServerException.validateNonNull(projectId, "projectId may not be null");
        LegendSDLCServerException.validateNonNull(sourceSpecification.getWorkspaceId(), "workspaceId may not be null");
        GitLabProjectId gitLabProjectId = parseProjectId(projectId);
        RepositoryApi repositoryApi = getGitLabApi().getRepositoryApi();
        ProjectFileAccessProvider.WorkspaceAccessType conflictResolutionWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.CONFLICT_RESOLUTION;
        // Verify conflict resolution is happening
        try
        {
            withRetries(() -> repositoryApi.getBranch(gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId()))));
        }
        catch (Exception e)
        {
            if (e instanceof GitLabApiException && GitLabApiTools.isNotFoundGitLabApiException((GitLabApiException) e))
            {
                LOGGER.error("Conflict resolution is not happening on workspace {} in project {}, so accepting conflict resolution is not actionable", sourceSpecification.getWorkspaceId(), projectId);
            }
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to get " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + "). " + "This implies that conflict resolution is not taking place, hence accepting conflict resolution is not actionable",
                () -> "Error getting " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Perform entity changes to resolve conflicts
        try
        {
            this.entityApi.getWorkspaceWithConflictResolutionEntityModificationContext(projectId, sourceSpecification).performChanges(command.getEntityChanges(), command.getRevisionId(), command.getMessage());
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to apply conflict resolution changes in " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + "). " + "This implies that conflict resolution is not taking place, hence accept is not actionable",
                () -> "Error applying conflict resolution changes in " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete backup branch if already exists
        boolean backupWorkspaceDeleted;
        ProjectFileAccessProvider.WorkspaceAccessType backupWorkspaceType = ProjectFileAccessProvider.WorkspaceAccessType.BACKUP;
        try
        {
            backupWorkspaceDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            // If we fail to delete the residual backup workspace, we cannot proceed anyway, so we will throw the error here
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!backupWorkspaceDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Create backup branch from original branch
        Branch newBackupBranch;
        ProjectFileAccessProvider.WorkspaceAccessType workspaceAccessType = ProjectFileAccessProvider.WorkspaceAccessType.WORKSPACE;
        try
        {
            Thread.sleep(1000); // Wait to allow nodes to synchronize that backup branch is already deleted
        }
        catch (InterruptedException e)
        {
            LOGGER.warn("Interrupted while waiting for nodes to synchronize that backup branch was deleted.", e);
            Thread.currentThread().interrupt();
        }
        try
        {
            newBackupBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown project: " + projectId,
                () -> "Error creating " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (newBackupBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " from " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete original branch
        boolean originalBranchDeleted;
        try
        {
            originalBranchDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!originalBranchDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Create new workspace branch off the conflict workspace head
        Branch newWorkspaceBranch;
        try
        {
            Thread.sleep(1000); // Wait to allow nodes to synchronize that original branch is already deleted.
        }
        catch (InterruptedException e)
        {
            LOGGER.warn("Interrupted while waiting for nodes to synchronize that original branch was deleted.", e);
            Thread.currentThread().interrupt();
        }
        try
        {
            newWorkspaceBranch = GitLabApiTools.createBranchFromSourceBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), workspaceAccessType, sourceSpecification.getPatchReleaseVersionId())), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 30, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error creating " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (newWorkspaceBranch == null)
        {
            throw new LegendSDLCServerException("Failed to create " + sourceSpecification.getWorkspaceType().getLabel() + " " + workspaceAccessType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " from " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete conflict resolution branch
        boolean conflictResolutionWorkspaceDeleted;
        try
        {
            // No need to waste wait time here since conflict resolution branch was long created during update
            conflictResolutionWorkspaceDeleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), conflictResolutionWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
        }
        catch (Exception e)
        {
            throw buildException(e,
                () -> "User " + getCurrentUser() + " is not allowed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId,
                () -> "Unknown " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " (" + sourceSpecification.getWorkspaceId() + ") or project (" + projectId + ")",
                () -> "Error deleting " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        if (!conflictResolutionWorkspaceDeleted)
        {
            throw new LegendSDLCServerException("Failed to delete " + sourceSpecification.getWorkspaceType().getLabel() + " " + conflictResolutionWorkspaceType.getLabel() + " " + sourceSpecification.getWorkspaceId() + " in project " + projectId);
        }
        // Delete backup branch
        try
        {
            Thread.sleep(500); // Wait extra 500 ms to allow nodes to synchronize that backup branch was recreated
        }
        catch (InterruptedException e)
        {
            LOGGER.warn("Interrupted while waiting for nodes to synchronize that backup branch was recreated.", e);
            Thread.currentThread().interrupt();
        }
        try
        {
            boolean deleted = GitLabApiTools.deleteBranchAndVerify(repositoryApi, gitLabProjectId.getGitLabId(), getWorkspaceBranchName(SourceSpecification.newSourceSpecification(sourceSpecification.getWorkspaceId(), sourceSpecification.getWorkspaceType(), backupWorkspaceType, sourceSpecification.getPatchReleaseVersionId())), 20, 1_000);
            if (!deleted)
            {
                LOGGER.error("Failed to delete {} {} in project {}", sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel(), sourceSpecification.getWorkspaceId(), projectId);
            }
        }
        catch (Exception e)
        {
            // unfortunate, but this should not throw error
            LOGGER.error("Error deleting {} {} in project {}", sourceSpecification.getWorkspaceType().getLabel() + " " + backupWorkspaceType.getLabel(), sourceSpecification.getWorkspaceId(), projectId, e);
        }
    }
}
