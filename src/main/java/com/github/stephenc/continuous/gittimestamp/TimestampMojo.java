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
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.status.StatusScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.repository.ScmRepository;
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
public class TimestampMojo extends AbstractGitOpsMojo {
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
    /**
     * Set this property to {@code true} to inject the commit count prior to the {@link #snapshotText} in snapshot
     * versions. Normally, only the {@code SNAPSHOT} part of the project version is replaced by the timestamp, this is
     * because such a substitution will be idempotent under the Maven version number parsing rules (as {@code
     * 1-SNAPSHOT} is considered the same as {@code 1-20190322.100407-39}). Setting this property to {@code true} means
     * that the commit count will also be injected so that we would have either {@code 1.39-20190322.100407-39}
     * (no modified files in the workspace) or {@code 1.40-20190322.100407-39} (modified files in the workspace) as the
     * version. In other words, setting this property to {@code true} will mean that the version honours the expected
     * release version that would be produced by {@link ReleaseMojo} while also reflecting the fact that this version
     * is only a SNAPSHOT on the road to that release.
     * <br/>
     * <b>NOTE:</b> Maven will not recognise a version output as being equivalent to the project version when this
     * property is set to {@code true}, but if you are using this version in your own code it may be useful.
     *
     * @since 1.39
     */
    @Parameter(defaultValue = "false", property = "versionIncludesCommitCount")
    private boolean versionIncludesCommitCount;
    /**
     * If {@link #versionIncludesCommitCount} is {@code true} then this is the text in the version to be replaced by
     * the commit count. Normally you will want {@code -SNAPSHOT} but if you are using a version number that
     * indicates replacement such as {@code 1.x-SNAPSHOT} then you may want to use {@code x-SNAPSHOT} so
     * that the {@code x} is replaced.
     */
    @Parameter(defaultValue = "-SNAPSHOT", property = "snapshotText")
    private String snapshotText;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // first check that we are using git
            ScmRepository repository = getScmRepository();
            ScmProvider provider = getValidatedScmProvider(repository);

            // now get the last modified timestamp
            final long[] lastModified = new long[1];
            lastModified[0] = project.getFile().lastModified();
            Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "ls-files");
            GitCommandLineUtils.execute(cl, new StreamConsumer() {
                @Override
                public void consumeLine(String s) {
                    lastModified[0] = Math.max(lastModified[0], new File(basedir, s).lastModified());
                }
            }, logDebugConsumer(), new GitCommandLineLogger(this));
            StatusScmResult status = provider.status(repository, new ScmFileSet(basedir));
            for (ScmFile f: status.getChangedFiles()) {
                lastModified[0] = Math.max(lastModified[0], new File(basedir, f.getPath()).lastModified());
            }

            // now count how many commits on the current branch
            long count = getCurrentBranchCommitCount();

            // ok, let's create the timestamp
            String timestamp = TIMESTAMP_FORMAT.format(new Date(lastModified[0])) + "-" + count;
            String version = project.getVersion();
            Matcher matcher = SNAPSHOT_PATTERN.matcher(version);
            if (matcher.matches()) {
                if (versionTimestampSnapshots) {
                    String bareVersion;
                    if (versionIncludesCommitCount) {
                        String snapshotVersion = matcher.group(1) + "SNAPSHOT";
                        if (StringUtils.endsWith(snapshotVersion, snapshotText)) {
                            bareVersion = StringUtils.removeEnd(snapshotVersion, snapshotText);
                            if (!bareVersion.endsWith(".") && !bareVersion.endsWith("-")) {
                                // insert a separator if none present
                                bareVersion = bareVersion + ".";
                            }
                            bareVersion = bareVersion + (count + (status.getChangedFiles().isEmpty() ? 0 : 1)) + '-';
                        } else {
                            getLog().warn("Project version '" + version + "' normalized to '" + snapshotVersion
                                    + "' does not end with '" + snapshotText + "'");
                            bareVersion = matcher.group(1);
                        }
                    } else {
                        bareVersion = matcher.group(1);
                    }
                    version = bareVersion + timestamp;
                }
            } else {
                if (versionTimestampReleases) {
                    version = version + "-" + timestamp;
                }
            }
            getLog().info("Timestamp: " + timestamp);
            getLog().info("Version:   " + version);
            setProperty(timestampProperty, timestamp);
            writeFile(timestampFile, timestamp);
            setProperty(versionProperty, version);
            writeFile(versionFile, version);
        } catch (NoSuchScmProviderException e) {
            throw new MojoFailureException("Unknown SCM URL: " + scmUrl, e);
        } catch (ScmException | IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

}
