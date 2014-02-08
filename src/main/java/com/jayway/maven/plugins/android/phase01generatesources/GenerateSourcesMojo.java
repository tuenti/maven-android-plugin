/*
 * Copyright (C) 2009 Jayway AB
 * Copyright (C) 2007-2008 JVending Masa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jayway.maven.plugins.android.phase01generatesources;

import com.android.manifmerger.ManifestMerger;
import com.android.manifmerger.MergerLog;
import com.android.utils.StdLogger;
import com.jayway.maven.plugins.android.AbstractAndroidMojo;
import com.jayway.maven.plugins.android.CommandExecutor;
import com.jayway.maven.plugins.android.ExecutionException;
import com.jayway.maven.plugins.android.common.AetherHelper;

import com.jayway.maven.plugins.android.configuration.BuildConfigConstant;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.AbstractScanner;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jayway.maven.plugins.android.common.AndroidExtension.APK;
import static com.jayway.maven.plugins.android.common.AndroidExtension.APKLIB;
import static com.jayway.maven.plugins.android.common.AndroidExtension.AAR;
import static com.jayway.maven.plugins.android.common.AndroidExtension.APKSOURCES;

/**
 * Generates <code>R.java</code> based on resources specified by the <code>resources</code> configuration parameter.
 * Generates java files based on aidl files.
 *
 * @author hugo.josefson@jayway.com
 * @author Manfred Moser <manfred@simpligility.com>
 *
 * @goal generate-sources
 * @phase generate-sources
 * @requiresProject true
 * @requiresDependencyResolution compile
 */
public class GenerateSourcesMojo extends AbstractAndroidMojo
{

    /**
     * <p>
     * Override default merging. You must have SDK Tools r20+
     * </p>
     * 
     * <p>
     * <b>IMPORTANT:</b> The resource plugin needs to be disabled for the
     * <code>process-resources</code> phase, so the "default-resources"
     * execution must be added. Without this the non-merged manifest will get
     * re-copied to the build directory.
     * </p>
     * 
     * <p>
     * The <code>androidManifestFile</code> should also be configured to pull
     * from the build directory so that later phases will pull the merged
     * manifest file.
     * </p>
     * <p>
     * Example POM Setup:
     * </p>
     * 
     * <pre>
     * &lt;build&gt;
     *     ...
     *     &lt;plugins&gt;
     *         ...
     *         &lt;plugin&gt;
     *             &lt;artifactId&gt;maven-resources-plugin&lt;/artifactId&gt;
     *             &lt;version&gt;2.6&lt;/version&gt;
     *             &lt;executions&gt;
     *                 &lt;execution&gt;
     *                     &lt;phase&gt;initialize&lt;/phase&gt;
     *                     &lt;goals&gt;
     *                         &lt;goal&gt;resources&lt;/goal&gt;
     *                     &lt;/goals&gt;
     *                 &lt;/execution&gt;
     *                 <b>&lt;execution&gt;
     *                     &lt;id&gt;default-resources&lt;/id&gt;
     *                     &lt;phase&gt;DISABLED&lt;/phase&gt;
     *                 &lt;/execution&gt;</b>
     *             &lt;/executions&gt;
     *         &lt;/plugin&gt;
     *         &lt;plugin&gt;
     *             &lt;groupId&gt;com.jayway.maven.plugins.android.generation2&lt;/groupId&gt;
     *             &lt;artifactId&gt;android-maven-plugin&lt;/artifactId&gt;
     *             &lt;configuration&gt;
     *                 <b>&lt;androidManifestFile&gt;
     *                     ${project.build.directory}/AndroidManifest.xml
     *                 &lt;/androidManifestFile&gt;
     *                 &lt;mergeManifests&gt;true&lt;/mergeManifests&gt;</b>
     *             &lt;/configuration&gt;
     *             &lt;extensions&gt;true&lt;/extensions&gt;
     *         &lt;/plugin&gt;
     *         ...
     *     &lt;/plugins&gt;
     *     ...
     * &lt;/build&gt;
     * </pre>
     * <p>
     * You can filter the pre-merged APK manifest. One important note about Eclipse, Eclipse will
     * replace the merged manifest with a filtered pre-merged version when the project is refreshed.
     * If you want to review the filtered merged version then you will need to open it outside Eclipse
     * without refreshing the project in Eclipse. 
     * </p>
     * <pre>
     * &lt;resources&gt;
     *     &lt;resource&gt;
     *         &lt;targetPath&gt;${project.build.directory}&lt;/targetPath&gt;
     *         &lt;filtering&gt;true&lt;/filtering&gt;
     *         &lt;directory&gt;${basedir}&lt;/directory&gt;
     *         &lt;includes&gt;
     *             &lt;include&gt;AndroidManifest.xml&lt;/include&gt;
     *         &lt;/includes&gt;
     *     &lt;/resource&gt;
     * &lt;/resources&gt;
     * </pre>
     * 
     * @parameter expression="${android.mergeManifests}" default-value="false"
     */
    protected boolean mergeManifests;

    /**
     * Whether or not to reuse existing processed resources for apklibs instead of generating them.
     *
     * @parameter expression="${android.reuseApkLibs}" default-value="false"
     */
    protected boolean reuseApkLibs;

    /**
     * Override default generated folder containing R.java
     *
     * @parameter expression="${android.genDirectory}" default-value="${project.build.directory}/generated-sources/r"
     */
    protected File genDirectory;

    /**
     * Override default generated folder containing aidl classes
     *
     * @parameter expression="${android.genDirectoryAidl}"
     * default-value="${project.build.directory}/generated-sources/aidl"
     */
    protected File genDirectoryAidl;

    /**
     * <p>Parameter designed to generate custom BuildConfig constants
     *
     * @parameter expression="${android.buildConfigConstants}"
     * @readonly
     */
    protected BuildConfigConstant[] buildConfigConstants;

    public void execute() throws MojoExecutionException, MojoFailureException
    {

        // If the current POM isn't an Android-related POM, then don't do
        // anything.  This helps work with multi-module projects.
        if ( ! isCurrentProjectAndroid() )
        {
            return;
        }

        try
        {
            extractSourceDependencies();
            extractApkLibDependencies();
            extractAarDependencies();
            
            final String[] relativeAidlFileNames1 = findRelativeAidlFileNames( sourceDirectory );
            final String[] relativeAidlFileNames2 = findRelativeAidlFileNames( extractedDependenciesJavaSources );
            final Map<String, String[]> relativeApklibAidlFileNames = new HashMap<String, String[]>();
            String[] apklibAidlFiles;
            for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
            {
                if ( artifact.getType().equals( APKLIB ) )
                {
                    apklibAidlFiles = findRelativeAidlFileNames(
                            new File( getLibraryUnpackDirectory( artifact ) + "/src" ) );
                    relativeApklibAidlFileNames.put( artifact.getId(), apklibAidlFiles );
                }
            }

            mergeManifests();
            generateR();
            generateApklibR();
            generateBuildConfig();

            // When compiling AIDL for this project,
            // make sure we compile AIDL for dependencies as well.
            // This is so project A, which depends on project B, can
            // use AIDL info from project B in its own AIDL
            Map<File, String[]> files = new HashMap<File, String[]>();
            files.put( sourceDirectory, relativeAidlFileNames1 );
            files.put( extractedDependenciesJavaSources, relativeAidlFileNames2 );
            for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
            {
                if ( artifact.getType().equals( APKLIB ) )
                {
                    files.put( new File( getLibraryUnpackDirectory( artifact ) + "/src" ),
                            relativeApklibAidlFileNames.get( artifact.getId() ) );
                }
            }
            generateAidlFiles( files );
        }
        catch ( MojoExecutionException e )
        {
            getLog().error( "Error when generating sources.", e );
            throw e;
        }
    }

    protected void extractSourceDependencies() throws MojoExecutionException
    {
        for ( Artifact artifact : getRelevantDependencyArtifacts() )
        {
            String type = artifact.getType();
            if ( type.equals( APKSOURCES ) )
            {
                getLog().debug( "Detected apksources dependency " + artifact + " with file " + artifact.getFile()
                        + ". Will resolve and extract..." );

                Artifact resolvedArtifact = AetherHelper
                        .resolveArtifact( artifact, repoSystem, repoSession, projectRepos );

                File apksourcesFile = resolvedArtifact.getFile();

                // When the artifact is not installed in local repository, but rather part of the current reactor,
                // resolve from within the reactor. (i.e. ../someothermodule/target/*)
                if ( ! apksourcesFile.exists() )
                {
                    apksourcesFile = resolveArtifactToFile( artifact );
                }

                // When using maven under eclipse the artifact will by default point to a directory, which isn't
                // correct. To work around this we'll first try to get the archive from the local repo, and only if it
                // isn't found there we'll do a normal resolve.

                if ( apksourcesFile.isDirectory() )
                {
                    apksourcesFile = resolveArtifactToFile( artifact );
                }
                getLog().debug( "Extracting " + apksourcesFile + "..." );
                extractApksources( apksourcesFile );
            }
        }
        projectHelper.addResource( project, extractedDependenciesJavaResources.getAbsolutePath(), null, null );
        project.addCompileSourceRoot( extractedDependenciesJavaSources.getAbsolutePath() );
    }

    private void extractApksources( File apksourcesFile ) throws MojoExecutionException
    {
        if ( apksourcesFile.isDirectory() )
        {
            getLog().warn( "The apksources artifact points to '" + apksourcesFile
                    + "' which is a directory; skipping unpacking it." );
            return;
        }
        final UnArchiver unArchiver = new ZipUnArchiver( apksourcesFile )
        {
            @Override
            protected Logger getLogger()
            {
                return new ConsoleLogger( Logger.LEVEL_DEBUG, "dependencies-unarchiver" );
            }
        };
        extractedDependenciesDirectory.mkdirs();
        unArchiver.setDestDirectory( extractedDependenciesDirectory );
        try
        {
            unArchiver.extract();
        }
        catch ( ArchiverException e )
        {
            throw new MojoExecutionException( "ArchiverException while extracting " + apksourcesFile.getAbsolutePath()
                    + ". Message: " + e.getLocalizedMessage(), e );
        }
    }

    private void extractApkLibDependencies() throws MojoExecutionException
    {
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            String type = artifact.getType();
            if ( type.equals( APKLIB ) )
            {
                getLog().debug( "Extracting apklib " + artifact.getArtifactId() + "..." );
                extractApklib( artifact );
            }
        }
    }

    private void extractApklib( Artifact apklibArtifact ) throws MojoExecutionException
    {

        final Artifact resolvedArtifact = AetherHelper
                .resolveArtifact( apklibArtifact, repoSystem, repoSession, projectRepos );

        File apkLibFile = resolvedArtifact.getFile();

        // When the artifact is not installed in local repository, but rather part of the current reactor,
        // resolve from within the reactor. (i.e. ../someothermodule/target/*)
        if ( ! apkLibFile.exists() )
        {
            apkLibFile = resolveArtifactToFile( apklibArtifact );
        }

        //When using maven under eclipse the artifact will by default point to a directory, which isn't correct.
        //To work around this we'll first try to get the archive from the local repo, and only if it isn't found there
        // we'll do a normal resolve.
        if ( apkLibFile.isDirectory() )
        {
            apkLibFile = resolveArtifactToFile( apklibArtifact );
        }

        if ( apkLibFile.isDirectory() )
        {
            getLog().warn(
                    "The apklib artifact points to '" + apkLibFile + "' which is a directory; skipping unpacking it." );
            return;
        }

        final UnArchiver unArchiver = new ZipUnArchiver( apkLibFile )
        {
            @Override
            protected Logger getLogger()
            {
                return new ConsoleLogger( Logger.LEVEL_DEBUG, "dependencies-unarchiver" );
            }
        };
        File apklibDirectory = new File( getLibraryUnpackDirectory( apklibArtifact ) );
        apklibDirectory.mkdirs();
        unArchiver.setDestDirectory( apklibDirectory );
        try
        {
            unArchiver.extract();
        }
        catch ( ArchiverException e )
        {
            throw new MojoExecutionException( "ArchiverException while extracting " + apklibDirectory.getAbsolutePath()
                    + ". Message: " + e.getLocalizedMessage(), e );
        }

        projectHelper.addResource( project, apklibDirectory.getAbsolutePath() + "/src", null,
                Arrays.asList( "**/*.java", "**/*.aidl" ) );
        project.addCompileSourceRoot( apklibDirectory.getAbsolutePath() + "/src" );

    }

    private void extractAarDependencies() throws MojoExecutionException
    {
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            String type = artifact.getType();
            if ( type.equals( AAR ) )
            {
                getLog().debug( "Extracting aar " + artifact.getArtifactId() + "..." );
                extractAarlib( artifact );
            }
        }
    }

    private void extractAarlib( Artifact aarArtifact ) throws MojoExecutionException
    {

        final Artifact resolvedArtifact = AetherHelper
                .resolveArtifact( aarArtifact, repoSystem, repoSession, projectRepos );

        File aarFile = resolvedArtifact.getFile();

        // When the artifact is not installed in local repository, but rather part of the current reactor,
        // resolve from within the reactor. (i.e. ../someothermodule/target/*)
        if ( ! aarFile.exists() )
        {
            aarFile = resolveArtifactToFile( aarArtifact );
        }

        //When using maven under eclipse the artifact will by default point to a directory, which isn't correct.
        //To work around this we'll first try to get the archive from the local repo, and only if it isn't found there
        // we'll do a normal resolve.
        if ( aarFile.isDirectory() )
        {
            aarFile = resolveArtifactToFile( aarArtifact );
        }

        if ( aarFile.isDirectory() )
        {
            getLog().warn(
                    "The aar artifact points to '" + aarFile + "' which is a directory; skipping unpacking it." );
            return;
        }

        final UnArchiver unArchiver = new ZipUnArchiver( aarFile )
        {
            @Override
            protected Logger getLogger()
            {
                return new ConsoleLogger( Logger.LEVEL_DEBUG, "dependencies-unarchiver" );
            }
        };
        File aarDirectory = new File( getLibraryUnpackDirectory( aarArtifact ) );
        aarDirectory.mkdirs();
        unArchiver.setDestDirectory( aarDirectory );
        try
        {
            unArchiver.extract();
        }
        catch ( ArchiverException e )
        {
            throw new MojoExecutionException( "ArchiverException while extracting " + aarDirectory.getAbsolutePath()
                    + ". Message: " + e.getLocalizedMessage(), e );
        }

        projectHelper.addResource( project, aarDirectory.getAbsolutePath() + "/src", null,
                Arrays.asList( "**/*.aidl" ) );
        //project.addCompileSourceRoot( aarDirectory.getAbsolutePath() + "/src" );

    }

    private void generateR() throws MojoExecutionException
    {
        getLog().debug( "Generating R file for " + project.getPackaging() );

        genDirectory.mkdirs();

        File[] overlayDirectories = getResourceOverlayDirectories();

        if ( extractedDependenciesRes.exists() )
        {
            try
            {
                getLog().info( "Copying dependency resource files to combined resource directory." );
                if ( ! combinedRes.exists() )
                {
                    if ( ! combinedRes.mkdirs() )
                    {
                        throw new MojoExecutionException(
                                "Could not create directory for combined resources at " + combinedRes
                                        .getAbsolutePath() );
                    }
                }
                org.apache.commons.io.FileUtils.copyDirectory( extractedDependenciesRes, combinedRes );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "", e );
            }
        }
        if ( resourceDirectory.exists() && combinedRes.exists() )
        {
            try
            {
                getLog().info( "Copying local resource files to combined resource directory." );

                org.apache.commons.io.FileUtils.copyDirectory( resourceDirectory, combinedRes, new FileFilter()
                {

                    /**
                     * Excludes files matching one of the common file to exclude.
                     * The default excludes pattern are the ones from
                     * {org.codehaus.plexus.util.AbstractScanner#DEFAULTEXCLUDES}
                     * @see java.io.FileFilter#accept(java.io.File)
                     */
                    public boolean accept( File file )
                    {
                        for ( String pattern : AbstractScanner.DEFAULTEXCLUDES )
                        {
                            if ( AbstractScanner.match( pattern, file.getAbsolutePath() ) )
                            {
                                getLog().debug(
                                        "Excluding " + file.getName() + " from resource copy : matching " + pattern );
                                return false;
                            }
                        }
                        return true;
                    }
                } );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "", e );
            }
        }


        CommandExecutor executor = CommandExecutor.Factory.createDefaultCommmandExecutor();
        executor.setLogger( this.getLog() );

        List<String> commands = new ArrayList<String>();
        commands.add( "package" );
        if ( APKLIB.equals( project.getArtifact().getType() ) )
        {
            commands.add( "--non-constant-id" );
        }
        commands.add( "-m" );
        commands.add( "-J" );
        commands.add( genDirectory.getAbsolutePath() );
        commands.add( "-M" );
        commands.add( androidManifestFile.getAbsolutePath() );
        if ( StringUtils.isNotBlank( customPackage ) )
        {
            commands.add( "--custom-package" );
            commands.add( customPackage );
        }

        addResourcesDirectories( commands, overlayDirectories );

        commands.add( "--auto-add-overlay" );
        if ( assetsDirectory.exists() )
        {
            commands.add( "-A" );
            commands.add( assetsDirectory.getAbsolutePath() );
        }
        if ( extractedDependenciesAssets.exists() )
        {
            commands.add( "-A" );
            commands.add( extractedDependenciesAssets.getAbsolutePath() );
        }
        commands.add( "-I" );
        commands.add( getAndroidSdk().getAndroidJar().getAbsolutePath() );
        if ( StringUtils.isNotBlank( configurations ) )
        {
            commands.add( "-c" );
            commands.add( configurations );
        }

        for ( String aaptExtraArg : aaptExtraArgs )
        {
            commands.add( aaptExtraArg );
        }

        if ( proguardFile != null )
        {
            File parentFolder = proguardFile.getParentFile();
            if ( parentFolder != null )
            {
                parentFolder.mkdirs();
            }
            commands.add( "-G" );
            commands.add( proguardFile.getAbsolutePath() );
        }
        getLog().info( getAndroidSdk().getAaptPath() + " " + commands.toString() );
        try
        {
            executor.executeCommand( getAndroidSdk().getAaptPath(), commands, project.getBasedir(), false );
        }
        catch ( ExecutionException e )
        {
            throw new MojoExecutionException( "", e );
        }

        project.addCompileSourceRoot( genDirectory.getAbsolutePath() );
    }

    private void addResourcesDirectories( List<String> commands, File[] overlayDirectories )
    {
        for ( File resOverlayDir : overlayDirectories )
        {
            if ( resOverlayDir != null && resOverlayDir.exists() )
            {
                commands.add( "-S" );
                commands.add( resOverlayDir.getAbsolutePath() );
            }
        }
        if ( combinedRes.exists() )
        {
            commands.add( "-S" );
            commands.add( combinedRes.getAbsolutePath() );
        }
        else
        {
            if ( resourceDirectory.exists() )
            {
                commands.add( "-S" );
                commands.add( resourceDirectory.getAbsolutePath() );
            }
        }

        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) || artifact.getType().equals( AAR ) )
            {
                String apklibResDirectory = getLibraryUnpackDirectory( artifact ) + "/res";
                if ( new File( apklibResDirectory ).exists() )
                {
                    commands.add( "-S" );
                    commands.add( apklibResDirectory );
                }
            }
        }        
    }
    
    private void mergeManifests() throws MojoExecutionException
    {
        getLog().debug( "mergeManifests: " + mergeManifests );

        if ( !mergeManifests )
        {
            getLog().info( "Manifest merging disabled. Using project manifest only" );
            return;
        }

        getLog().info( "Getting manifests of dependent apklibs" );
        List<File> libManifests = new ArrayList<File>();
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) || artifact.getType().equals( AAR ) )
            {
                File apklibManifeset = new File( getLibraryUnpackDirectory( artifact ), "AndroidManifest.xml" );
                if ( !apklibManifeset.exists() )
                {
                    throw new MojoExecutionException( artifact.getArtifactId() + " is missing AndroidManifest.xml" );
                }

                libManifests.add( apklibManifeset );
            }
        }

        if ( !libManifests.isEmpty() )
        {
            final File mergedManifest = new File( androidManifestFile.getParent(), "AndroidManifest-merged.xml" );
            final StdLogger stdLogger = new StdLogger( StdLogger.Level.VERBOSE );
            final ManifestMerger merger = new ManifestMerger( MergerLog.wrapSdkLog( stdLogger ), null );

            getLog().info( "Merging manifests of dependent apklibs" );

            final boolean mergeSuccess = merger.process( mergedManifest, androidManifestFile,
                libManifests.toArray( new File[libManifests.size()] ),  null, null );

            if ( mergeSuccess )
            {
                // Replace the original manifest file with the merged one so that
                // the rest of the build will pick it up.
                androidManifestFile.delete();
                mergedManifest.renameTo( androidManifestFile );
                getLog().info( "Done Merging Manifests of APKLIBs" );
            }
            else
            {
                getLog().error( "Manifests were not merged!" );
                throw new MojoExecutionException( "Manifests were not merged!" );
            }
        }
        else
        {
            getLog().info( "No APKLIB manifests found. Using project manifest only." );
        }
    }

    private void generateApklibR() throws MojoExecutionException
    {
        getLog().debug( "Generating R file for projects dependent on apklibs" );

        genDirectory.mkdirs();

        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) && shouldGenerateRFor( artifact ) )
            {
                generateRForApkLibDependency( artifact );
            }
        }

        project.addCompileSourceRoot( genDirectory.getAbsolutePath() );
    }

    private boolean shouldGenerateRFor( Artifact apklibArtifact )
    {
        if ( !reuseApkLibs )
        {
            return true;
        }

        if ( classFileRExistsFor( apklibArtifact ) )
        {
            getLog().info( "R found for " + apklibArtifact.getId() + ", so it won't be regenerated" );
            return false;
        }

        return true;
    }

    private boolean classFileRExistsFor( Artifact apklibArtifact )
    {
        String libPackage;
        try
        {
            String libraryUnpackDirectory = getLibraryUnpackDirectory( apklibArtifact );
            libPackage = extractPackageNameFromAndroidManifest( new File( libraryUnpackDirectory
                    + File.separator + "AndroidManifest.xml" ) );
        }
        catch ( MojoExecutionException e )
        {
            return false;
        }

        return new File( project.getBuild().getOutputDirectory() + File.separator
                + libPackage.replace( ".", File.separator ) + File.separator + "R.class" ).exists();
    }

    private void generateRForApkLibDependency( Artifact apklibArtifact ) throws MojoExecutionException
    {
        final String unpackDir = getLibraryUnpackDirectory( apklibArtifact );
        getLog().debug( "Generating R file for apklibrary: " + apklibArtifact.getGroupId() );

        CommandExecutor executor = CommandExecutor.Factory.createDefaultCommmandExecutor();
        executor.setLogger( this.getLog() );

        List<String> commands = new ArrayList<String>();
        commands.add( "package" );
        commands.add( "--non-constant-id" );
        commands.add( "-m" );
        commands.add( "-J" );
        commands.add( genDirectory.getAbsolutePath() );
        commands.add( "--custom-package" );
        commands.add( extractPackageNameFromAndroidManifest( new File( unpackDir + "/" + "AndroidManifest.xml" ) ) );
        commands.add( "-M" );
        commands.add( androidManifestFile.getAbsolutePath() );
        if ( resourceDirectory.exists() )
        {
            commands.add( "-S" );
            commands.add( resourceDirectory.getAbsolutePath() );
        }
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) || artifact.getType().equals( AAR ) )
            {
                final String apkLibResDir = getLibraryUnpackDirectory( artifact ) + "/res";
                if ( new File( apkLibResDir ).exists() )
                {
                    commands.add( "-S" );
                    commands.add( apkLibResDir );
                }
            }
        }
        commands.add( "--auto-add-overlay" );
        if ( assetsDirectory.exists() )
        {
            commands.add( "-A" );
            commands.add( assetsDirectory.getAbsolutePath() );
        }
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) )
            {
                final String apkLibAssetsDir = getLibraryUnpackDirectory( artifact ) + "/assets";
                if ( new File( apkLibAssetsDir ).exists() )
                {
                    commands.add( "-A" );
                    commands.add( apkLibAssetsDir );
                }
            }
        }
        commands.add( "-I" );
        commands.add( getAndroidSdk().getAndroidJar().getAbsolutePath() );
        if ( StringUtils.isNotBlank( configurations ) )
        {
            commands.add( "-c" );
            commands.add( configurations );
        }

        for ( String aaptExtraArg : aaptExtraArgs )
        {
            commands.add( aaptExtraArg );
        }

        getLog().info( getAndroidSdk().getAaptPath() + " " + commands.toString() );
        try
        {
            executor.executeCommand( getAndroidSdk().getAaptPath(), commands, project.getBasedir(), false );
        }
        catch ( ExecutionException e )
        {
            throw new MojoExecutionException( "", e );
        }
    }

    private void generateBuildConfig() throws MojoExecutionException
    {
        getLog().debug( "Generating BuildConfig file" );

        // Create the BuildConfig for our package.
        String packageName = extractPackageNameFromAndroidManifest( androidManifestFile );
        if ( StringUtils.isNotBlank( customPackage ) )
        {
            packageName = customPackage;
        }
        generateBuildConfigForPackage( packageName );

        // Generate the BuildConfig for any apklib dependencies.
        for ( Artifact artifact : getAllRelevantDependencyArtifacts() )
        {
            if ( artifact.getType().equals( APKLIB ) )
            {
                File apklibManifeset = new File( getLibraryUnpackDirectory( artifact ), "AndroidManifest.xml" );
                String apklibPackageName = extractPackageNameFromAndroidManifest( apklibManifeset );
                generateBuildConfigForPackage( apklibPackageName );
            }
        }
    }

    private void generateBuildConfigForPackage( String packageName ) throws MojoExecutionException
    {
        File outputFolder = new File( genDirectory, packageName.replace( ".", File.separator ) );
        outputFolder.mkdirs();

        StringBuilder buildConfig = new StringBuilder();
        buildConfig.append( "package " ).append( packageName ).append( ";\n\n" );
        buildConfig.append( "public final class BuildConfig {\n" );
        buildConfig.append( "  public static final boolean DEBUG = " ).append( !release ).append( ";\n" );
        for ( BuildConfigConstant constant : buildConfigConstants )
        {
            String value = constant.getValue();
            if ( "String".equals( constant.getType() ) )
            {
                value = "\"" + value + "\"";
            }

            buildConfig.append( "  public static final " )
                       .append( constant.getType() )
                       .append( " " )
                       .append( constant.getName() )
                       .append( " = " )
                       .append( value )
                       .append( ";\n" );
        }
        buildConfig.append( "}\n" );


        File outputFile = new File( outputFolder, "BuildConfig.java" );
        try
        {
            FileUtils.writeStringToFile( outputFile, buildConfig.toString() );
        }
        catch ( IOException e )
        {
            getLog().error( "Error generating BuildConfig ", e );
            throw new MojoExecutionException( "Error generating BuildConfig", e );
        }
    }

    /**
     * Given a map of source directories to list of AIDL (relative) filenames within each,
     * runs the AIDL compiler for each, such that all source directories are available to
     * the AIDL compiler.
     *
     * @param files Map of source directory File instances to the relative paths to all AIDL files within
     * @throws MojoExecutionException If the AIDL compiler fails
     */
    private void generateAidlFiles( Map<File /*sourceDirectory*/, String[] /*relativeAidlFileNames*/> files )
            throws MojoExecutionException
    {
        List<String> protoCommands = new ArrayList<String>();
        protoCommands.add( "-p" + getAndroidSdk().getPathForFrameworkAidl() );

        genDirectoryAidl.mkdirs();
        project.addCompileSourceRoot( genDirectoryAidl.getPath() );
        Set<File> sourceDirs = files.keySet();
        for ( File sourceDir : sourceDirs )
        {
            protoCommands.add( "-I" + sourceDir );
        }
        for ( File sourceDir : sourceDirs )
        {
            for ( String relativeAidlFileName : files.get( sourceDir ) )
            {
                File targetDirectory = new File( genDirectoryAidl, new File( relativeAidlFileName ).getParent() );
                targetDirectory.mkdirs();

                final String shortAidlFileName = new File( relativeAidlFileName ).getName();
                final String shortJavaFileName = shortAidlFileName.substring( 0, shortAidlFileName.lastIndexOf( "." ) )
                                                 + ".java";
                final File aidlFileInSourceDirectory = new File( sourceDir, relativeAidlFileName );

                List<String> commands = new ArrayList<String>( protoCommands );
                commands.add( aidlFileInSourceDirectory.getAbsolutePath() );
                commands.add( new File( targetDirectory, shortJavaFileName ).getAbsolutePath() );
                try
                {
                    CommandExecutor executor = CommandExecutor.Factory.createDefaultCommmandExecutor();
                    executor.setLogger( this.getLog() );

                    executor.executeCommand( getAndroidSdk().getAidlPath(), commands, project.getBasedir(),
                            false );
                }
                catch ( ExecutionException e )
                {
                    throw new MojoExecutionException( "", e );
                }
            }
        }
    }

    private String[] findRelativeAidlFileNames( File sourceDirectory )
    {
        String[] relativeAidlFileNames = findFilesInDirectory( sourceDirectory, "**/*.aidl" );
        getLog().info( "ANDROID-904-002: Found aidl files: Count = " + relativeAidlFileNames.length );
        return relativeAidlFileNames;
    }

    /**
     * @return true if the pom type is APK, APKLIB, or APKSOURCES
     */
    private boolean isCurrentProjectAndroid()
    {
        Set<String> androidArtifacts = new HashSet<String>()
        {
            {
                addAll( Arrays.asList( APK, APKLIB, APKSOURCES, AAR ) );
            }
        };
        return androidArtifacts.contains( project.getArtifact().getType() );
    }

}
