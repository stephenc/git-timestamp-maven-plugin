/*
 * Copyright 2018 Stephen Connolly.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.stephenc.continuous.gittimestamp;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.status.StatusScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Generates a timestamp version based on the number of commits in the current Git branch and the last modified
 * timestamp of all the repository files
 */
@Mojo(name = "timestamp",
      aggregator = false,
      defaultPhase = LifecyclePhase.INITIALIZE,
      requiresProject = true,
      threadSafe = true)
public class TimestampMojo extends AbstractMojo {
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMdd.HHmmss");
    private static final Pattern SNAPSHOT_PATTERN = Pattern.compile(
            "^(.*-)?((?:SNAPSHOT)|(?:\\d{4}[0-1]\\d[0-3]\\d\\.[0-2]\\d[0-6]\\d[0-6]\\d-\\d+))$"
    );
    /**
     * If defined, the name of the property to populate with the raw timestamp, which will be in the format
     * {@code yyyyMMdd.HHmmss-NNNN}
     */
    @Parameter
    private String timestampProperty;
    /**
     * If defined, the name of the property to populate with the project version.
     * <ul>
     * <li>
     * If the project version is a release, the value is determined by {@link #versionTimestampReleases}:
     * <ul>
     * <li>when {@code false} the project version will be passed through unmodified</li>
     * <li>when {@code true} the timestamp will be appended to the project version</li>
     * </ul>
     * </li>
     * <li>
     * If the project version is a snapshot, the value is determined by {@link #versionTimestampSnapshots}:
     * <ul>
     * <li>when {@code false} the project version will be passed through unmodified</li>
     * <li>when {@code true} the {@code -SNAPSHOT} will be replaced with the timestamp</li>
     * </ul>
     * </li>
     * </ul>
     */
    @Parameter
    private String versionProperty;
    @Parameter(defaultValue = "false")
    private boolean versionTimestampReleases;
    @Parameter(defaultValue = "true")
    private boolean versionTimestampSnapshots;
    /**
     * If defined, the name of the file to populate with the raw timestamp, which will be in the format
     * {@code yyyyMMdd.HHmmss-NNNN} followed by a newline.
     */
    @Parameter
    private File timestampFile;
    /**
     * If defined, the name of the file to populate with the project version followed by a newline.
     * <ul>
     * <li>
     * If the project version is a release, the value is determined by {@link #versionTimestampReleases}:
     * <ul>
     * <li>when {@code false} the project version will be passed through unmodified</li>
     * <li>when {@code true} the timestamp will be appended to the project version</li>
     * </ul>
     * </li>
     * <li>
     * If the project version is a snapshot, the value is determined by {@link #versionTimestampSnapshots}:
     * <ul>
     * <li>when {@code false} the project version will be passed through unmodified</li>
     * <li>when {@code true} the {@code -SNAPSHOT} will be replaced with the timestamp</li>
     * </ul>
     * </li>
     * </ul>
     */
    @Parameter
    private File versionFile;
    @Parameter(defaultValue = "${project.scm.developerConnection}", readonly = true)
    private String scmDeveloperUrl;
    /**
     * The character encoding scheme to be applied when writing files.
     */
    @Parameter(defaultValue = "${project.build.outputEncoding}")
    protected String encoding;
    @Parameter(defaultValue = "${basedir}", readonly = true)
    private File basedir;
    @Component
    private ScmManager scmManager;
    @Parameter(defaultValue = "${project.scm.connection}", readonly = true)
    private String scmUrl;
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String scmUrl = scmDeveloperUrl == null || scmDeveloperUrl.isEmpty() ? this.scmUrl : scmDeveloperUrl;
        ScmRepository repository;
        try {
            // first check that we are using git
            repository = scmManager.makeScmRepository(scmUrl);
            ScmProvider provider = scmManager.getProviderByRepository(repository);
            if (!GitScmProviderRepository.PROTOCOL_GIT.equals(provider.getScmType())) {
                throw new MojoFailureException("Only Git SCM type is supported");
            }

            // now get the last modified timestamp
            final long[] lastModified = new long[1];
            lastModified[0] = project.getFile().lastModified();
            Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "ls-files");
            CommandLineUtils.StringStreamConsumer stderr = new CommandLineUtils.StringStreamConsumer();
            GitCommandLineUtils.execute(cl, new StreamConsumer() {
                @Override
                public void consumeLine(String s) {
                    lastModified[0] = Math.max(lastModified[0], new File(basedir, s).lastModified());
                }
            }, new CommandLineUtils.StringStreamConsumer(), new ScmLoggerImpl(this));
            StatusScmResult status = provider.status(repository, new ScmFileSet(basedir));
            for (ScmFile f: status.getChangedFiles()) {
                lastModified[0] = Math.max(lastModified[0], new File(basedir, f.getPath()).lastModified());
            }

            // now count how many commits on the current branch
            cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "rev-list");
            cl.createArg().setValue("--count");
            cl.createArg().setValue("HEAD");
            CommandLineUtils.StringStreamConsumer countOutput = new CommandLineUtils.StringStreamConsumer();
            GitCommandLineUtils.execute(cl, countOutput, stderr, new ScmLoggerImpl(this));
            long count;
            try {
                count = Long.parseLong(StringUtils.defaultIfBlank(countOutput.getOutput().trim(), "0"));
            } catch (NumberFormatException e) {
                throw new MojoExecutionException(
                        "Could not parse revision count from 'rev-list --count' output: " + countOutput.getOutput(),
                        e
                );
            }

            // ok, let's create the timestamp
            String timestamp = TIMESTAMP_FORMAT.format(new Date(lastModified[0])) + "-" + count;
            String version = project.getVersion();
            Matcher matcher = SNAPSHOT_PATTERN.matcher(version);
            if (matcher.matches()) {
                if (versionTimestampSnapshots) {
                    version = matcher.group(1) + timestamp;
                }
            } else {
                if (versionTimestampReleases) {
                    version = version + "-" + timestamp;
                }
            }
            getLog().info("Timestamp: " + timestamp);
            getLog().info("Version:   " + version);
            if (StringUtils.isNotBlank(timestampProperty)) {
                getLog().info("Setting property '" + timestampProperty + "' to '" + timestamp + "'");
                project.getProperties().setProperty(timestampProperty, timestamp);
            }
            if (StringUtils.isNotBlank(versionProperty)) {
                getLog().info("Setting property '" + versionProperty + "' to '" + version + "'");
                project.getProperties().setProperty(versionProperty, version);
            }
            if (timestampFile != null) {
                timestampFile.getParentFile().mkdirs();
                getLog().info("Writing '" + timestamp + "' to " + timestampFile);
                FileUtils.write(timestampFile, timestamp + "\n", encoding);
            }
            if (versionFile != null) {
                versionFile.getParentFile().mkdirs();
                getLog().info("Writing '" + version + "' to " + versionFile);
                FileUtils.write(versionFile, version + "\n", encoding);
            }
        } catch (NoSuchScmProviderException e) {
            throw new MojoFailureException("Unknown SCM URL: " + scmUrl, e);
        } catch (ScmException | IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }


    }

}
