package org.apache.maven.plugin.deploy;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.deployer.ArtifactDeploymentException;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deploys an artifact to remote repository.
 *
 * @author <a href="mailto:evenisse@apache.org">Emmanuel Venisse</a>
 * @author <a href="mailto:jdcasey@apache.org">John Casey (refactoring only)</a>
 * @version $Id$
 */
@Mojo( name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true )
public class DeployMojo
    extends AbstractDeployMojo
{

    private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile( "(.+)::(.+)::(.+)" );

    /**
     */
    @Component
    private MavenProject project;
    
    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    private List<MavenProject> reactorProjects;
    
    /**
     * Whether every project should be deployed during its own deploy-phase or at the end of the multimodule build.
     * If set to {@code true} and the build fails, none of the reactor projects is deployed 
     * 
     * @since 2.8
     */
    @Parameter( defaultValue = "false", property = "deployAtEnd" )
    private boolean deployAtEnd;

    /**
     * @deprecated either use project.getArtifact() or reactorProjects.get(i).getArtifact()
     */
    @Parameter( defaultValue = "${project.artifact}", required = true, readonly = true )
    private Artifact artifact;

    /**
     * @deprecated either use project.getPackaging() or reactorProjects.get(i).getPackaging()
     */
    @Parameter( defaultValue = "${project.packaging}", required = true, readonly = true )
    private String packaging;

    /**
     * @deprecated either use project.getFile() or reactorProjects.get(i).getFile()
     */
    @Parameter( defaultValue = "${project.file}", required = true, readonly = true )
    private File pomFile;

    /**
     * Specifies an alternative repository to which the project artifacts should be deployed ( other
     * than those specified in &lt;distributionManagement&gt; ).
     * <br/>
     * Format: id::layout::url
     */
    @Parameter( property = "altDeploymentRepository" )
    private String altDeploymentRepository;

    /**
     * @deprecated either use project.getAttachedArtifacts() or reactorProjects.get(i).getAttachedArtifacts()
     */
    @Parameter( defaultValue = "${project.attachedArtifacts}", required = true, readonly = true )
    private List attachedArtifacts;

    /**
     * Set this to 'true' to bypass artifact deploy
     *
     * @since 2.4
     */
    @Parameter( property = "maven.deploy.skip", defaultValue = "false" )
    private boolean skip;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( skip )
        {
            getLog().info( "Skipping artifact deployment" );
            return;
        }

        failIfOffline();
        
        if( !deployAtEnd )
        {
            deployProject( project );
        }
        else
        {
            MavenProject lastProject = reactorProjects.get( reactorProjects.size() - 1 );
            if( lastProject.equals( project ) )
            {
                for( MavenProject reactorProject : reactorProjects )
                {
                    deployProject( reactorProject );
                }
            }
            else
            {
                getLog().info( "Deploying " + project.getGroupId() + ":" + project.getArtifactId() + 
                               ":" + project.getVersion() + " at end" );
            }
        }
    }

    private void deployProject( MavenProject project )
        throws MojoExecutionException, MojoFailureException
    {
        Artifact artifact = project.getArtifact();
        String packaging = project.getPackaging();
        File pomFile = project.getFile();
        
        @SuppressWarnings( "unchecked" )
        List<Artifact> attachedArtifacts = project.getAttachedArtifacts();
        
        ArtifactRepository repo = getDeploymentRepository( project );

        String protocol = repo.getProtocol();

        if ( protocol.equalsIgnoreCase( "scp" ) )
        {
            File sshFile = new File( System.getProperty( "user.home" ), ".ssh" );

            if ( !sshFile.exists() )
            {
                sshFile.mkdirs();
            }
        }
        

        // Deploy the POM
        boolean isPomArtifact = "pom".equals( packaging );
        if ( !isPomArtifact )
        {
            ArtifactMetadata metadata = new ProjectArtifactMetadata( artifact, pomFile );
            artifact.addMetadata( metadata );
        }

        if ( updateReleaseInfo )
        {
            artifact.setRelease( true );
        }

        try
        {
            if ( isPomArtifact )
            {
                deploy( pomFile, artifact, repo, getLocalRepository() );
            }
            else
            {
                File file = artifact.getFile();

                if ( file != null && file.isFile() )
                {
                    deploy( file, artifact, repo, getLocalRepository() );
                }
                else if ( !attachedArtifacts.isEmpty() )
                {
                    getLog().info( "No primary artifact to deploy, deploying attached artifacts instead." );

                    Artifact pomArtifact =
                        artifactFactory.createProjectArtifact( artifact.getGroupId(), artifact.getArtifactId(),
                                                               artifact.getBaseVersion() );
                    pomArtifact.setFile( pomFile );
                    if ( updateReleaseInfo )
                    {
                        pomArtifact.setRelease( true );
                    }

                    deploy( pomFile, pomArtifact, repo, getLocalRepository() );

                    // propagate the timestamped version to the main artifact for the attached artifacts to pick it up
                    artifact.setResolvedVersion( pomArtifact.getVersion() );
                }
                else
                {
                    String message = "The packaging for this project did not assign a file to the build artifact";
                    throw new MojoExecutionException( message );
                }
            }

            for ( Artifact attached : attachedArtifacts )
            {
                deploy( attached.getFile(), attached, repo, getLocalRepository() );
            }
        }
        catch ( ArtifactDeploymentException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    private ArtifactRepository getDeploymentRepository( MavenProject project )
        throws MojoExecutionException, MojoFailureException
    {
        ArtifactRepository repo = null;

        if ( altDeploymentRepository != null )
        {
            getLog().info( "Using alternate deployment repository " + altDeploymentRepository );

            Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher( altDeploymentRepository );

            if ( !matcher.matches() )
            {
                throw new MojoFailureException( altDeploymentRepository, "Invalid syntax for repository.",
                                                "Invalid syntax for alternative repository. Use \"id::layout::url\"." );
            }
            else
            {
                String id = matcher.group( 1 ).trim();
                String layout = matcher.group( 2 ).trim();
                String url = matcher.group( 3 ).trim();

                ArtifactRepositoryLayout repoLayout = getLayout( layout );

                repo = repositoryFactory.createDeploymentArtifactRepository( id, url, repoLayout, true );
            }
        }

        if ( repo == null )
        {
            repo = project.getDistributionManagementArtifactRepository();
        }

        if ( repo == null )
        {
            String msg = "Deployment failed: repository element was not specified in the POM inside"
                + " distributionManagement element or in -DaltDeploymentRepository=id::layout::url parameter";

            throw new MojoExecutionException( msg );
        }

        return repo;
    }

}
