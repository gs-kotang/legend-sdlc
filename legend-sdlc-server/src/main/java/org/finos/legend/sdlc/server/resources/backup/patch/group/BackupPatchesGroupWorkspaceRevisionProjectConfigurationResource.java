// Copyright 2023 Goldman Sachs
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

package org.finos.legend.sdlc.server.resources.backup.patch.group;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.finos.legend.sdlc.domain.model.project.configuration.ProjectConfiguration;
import org.finos.legend.sdlc.domain.model.version.VersionId;
import org.finos.legend.sdlc.server.domain.api.project.ProjectConfigurationApi;
import org.finos.legend.sdlc.server.domain.api.project.SourceSpecification;
import org.finos.legend.sdlc.server.error.LegendSDLCServerException;
import org.finos.legend.sdlc.server.resources.BaseResource;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/projects/{projectId}/patches/{patchReleaseVersionId}/groupWorkspaces/{workspaceId}/backup/revisions/{revisionId}/configuration")
@Api("Backup")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BackupPatchesGroupWorkspaceRevisionProjectConfigurationResource extends BaseResource
{
    private final ProjectConfigurationApi projectConfigurationApi;

    @Inject
    public BackupPatchesGroupWorkspaceRevisionProjectConfigurationResource(ProjectConfigurationApi projectConfigurationApi)
    {
        this.projectConfigurationApi = projectConfigurationApi;
    }

    @GET
    @ApiOperation("Get the configuration for a revision of a project in a backup group workspace at a revision for patch release version")
    public ProjectConfiguration getWorkspaceRevisionProjectConfiguration(@PathParam("projectId") String projectId,
                                                                         @PathParam("patchReleaseVersionId") String patchReleaseVersionId,
                                                                         @PathParam("workspaceId") String workspaceId,
                                                                         @PathParam("revisionId") @ApiParam("Including aliases: head, latest, current, base") String revisionId)
    {
        LegendSDLCServerException.validateNonNull(patchReleaseVersionId, "patchReleaseVersionId may not be null");
        VersionId versionId;
        try
        {
            versionId = VersionId.parseVersionId(patchReleaseVersionId);
        }
        catch (IllegalArgumentException e)
        {
            throw new LegendSDLCServerException(e.getMessage(), Response.Status.BAD_REQUEST, e);
        }
        return executeWithLogging(
                "getting project " + projectId + " configuration in backup group workspace " + workspaceId + " at revision " + revisionId + " for patch release version " + patchReleaseVersionId,
                () -> this.projectConfigurationApi.getBackupWorkspaceRevisionProjectConfiguration(projectId, SourceSpecification.newGroupWorkspaceSourceSpecification(workspaceId, versionId), revisionId)
        );
    }
}
