package com.github.stephenc.continuous.gittimestamp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedPropertiesValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Generates a release version based on the number of commits in the current Git branch and available tags. This mojo is
 * designed to be invoked before {@code release:prepare}.
 *
 * @since 1.39
 */
@Mojo(name = "setup-release",
      inheritByDefault = false,
      aggregator = true,
      defaultPhase = LifecyclePhase.INITIALIZE,
      requiresProject = true,
      threadSafe = true)
public class ReleaseMojo extends AbstractGitOpsMojo {
    private static final String REFS_TAGS = "refs/tags/";
    /**
     * The name of the property to populate with the release version.
     */
    @Parameter(defaultValue = "releaseVersion")
    private String releaseProperty;
    /**
     * The name of the property to populate with the follow-on development version.
     */
    @Parameter(defaultValue = "developmentVersion")
    private String developmentProperty;
    /**
     * The name of the property to populate with the tag name.
     *
     * @since 1.29
     */
    @Parameter(defaultValue = "tag")
    private String tagNameProperty;
    /**
     * When {@code true} will disable the check of whether setting {@code autoVersionSubmodules} makes sense.
     *
     * @since 1.29
     */
    @Parameter(property = "skipAutoVersionSubmodulesDetection")
    private boolean skipAutoVersionSubmodulesDetection;
    /**
     * By default, the first attempt at any revision version will have the {@code .0} implicit, only for repeats do we
     * add the {@code .1}, {@code .2}, etc in order to disambiguate, setting this property to {@code true} will disable
     * the special casing for {@code .0} and thus it will always be present. For example, if the project version is
     * {@code 1-SNAPSHOT} and there are 57 commits on the current branch:
     * <dl>
     * <dt>{@code skipAutoVersionSubmodulesDetection == false}</dt>
     * <dd>The candidate versions will be:
     * <ul><li>1.57</li><li>1.57.1</li><li>1.57.2</li><li>1.57.3</li><li>...</li></ul></dd>
     * <dt>{@code skipAutoVersionSubmodulesDetection == true}</dt>
     * <dd>The candidate versions will be:
     * <ul><li>1.57.0</li><li>1.57.1</li><li>1.57.2</li><li>1.57.3</li><li>...</li></ul></dd>
     * </dl>
     *
     * @since 1.39
     */
    @Parameter(property = "alwaysIncludeRepeatCount")
    private boolean alwaysIncludeRepeatCount;
    /**
     * The text in the version to be replaced. Normally you will want {@code -SNAPSHOT} but if you are using a version
     * number that indicates replacement such as {@code 1.x-SNAPSHOT} then you may want to use {@code x-SNAPSHOT} so
     * that the {@code x} is replaced.
     */
    @Parameter(defaultValue = "-SNAPSHOT", property = "snapshotText")
    private String snapshotText;
    /**
     * Disables querying the remote tags and instead only queries local tags.
     */
    @Parameter(property = "localTags")
    private boolean localTags;
    /**
     * Format to use when generating the tag name if none is specified. Mirrors {@code release:prepare}'s property.
     */
    @Parameter(defaultValue = "@{project.artifactId}-@{project.version}", property = "tagNameFormat")
    private String tagNameFormat;
    /**
     * If defined, the name of the file to populate with the project version followed by a newline.
     */
    @Parameter(property = "releaseVersionFile")
    private File releaseVersionFile;
    /**
     * If defined, the name of the file to populate with the suggested tag name followed by a newline.
     */
    @Parameter(property = "tagNameFile")
    private File tagNameFile;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!project.getVersion().endsWith(snapshotText)) {
            throw new MojoFailureException("The current project version is \'" + project.getVersion()
                    + "\' which does not end with the expected text to be replaced: \'" + snapshotText + "\'");
        }
        try {
            ScmRepository repository = getScmRepository();
            getValidatedScmProvider(repository);

            // now count how many commits on the current branch
            final long count = getCurrentBranchCommitCount();

            final Set<String> tags = new HashSet<>();
            Commandline cl;
            StreamConsumer consumer;
            if (!localTags && repository.getProviderRepository() instanceof GitScmProviderRepository) {
                cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "ls-remote");
                cl.createArg().setValue("--tags");
                cl.createArg().setValue("--quiet");
                cl.createArg().setValue(((GitScmProviderRepository) repository.getProviderRepository()).getFetchUrl());
                consumer = new LsRemoteTagsConsumer(tags);
            } else {
                cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "tag");
                cl.createArg().setValue("--list");
                consumer = new TagListConsumer(tags);
            }
            GitCommandLineUtils.execute(cl, consumer, logWarnConsumer(), new GitCommandLineLogger(this));

            String bareVersion = StringUtils.removeEnd(project.getVersion(), snapshotText);
            if (!bareVersion.endsWith(".") && !bareVersion.endsWith("-")) {
                // insert a separator if none present
                bareVersion = bareVersion + ".";
            }
            final String baseVersion = bareVersion + count;
            Iterator<String> suggestedVersion = new CandidateVersionsIterator(baseVersion, alwaysIncludeRepeatCount);
            String version;
            String suggestedTagName;
            while (true) {
                version = suggestedVersion.next();
                suggestedTagName = tagNameFromVersion(version);

                if (!tags.contains(suggestedTagName)) {
                    getLog().info(
                            "Could not find a tag called " + suggestedTagName + " recommending version " + version);
                    break;
                }
                getLog().debug("Skipping " + version + " as there is already a tag named " + suggestedTagName);
            }
            getLog().debug("Known tags: " + tags);

            // Ok let's set up the properties for release:prepare

            // The release version
            setProperty(releaseProperty, version);
            writeFile(this.releaseVersionFile, version);

            // The follow-on development version
            setProperty(developmentProperty, project.getVersion());

            // The tag
            setProperty(this.tagNameProperty, suggestedTagName);
            writeFile(this.tagNameFile, suggestedTagName);

            // Now can we help and set autoVersionSubmodules?
            if (skipAutoVersionSubmodulesDetection) {
                getLog().debug("autoVersionSubmodules detection disabled");
                return;
            }
            if (!project.isExecutionRoot()) {
                getLog().info("Project is not execution root, autoVersionSubmodules detection does not apply");
                return;
            }
            for (MavenProject p : project.getCollectedProjects()) {
                if (!project.getVersion().equals(p.getVersion())) {
                    getLog().warn("Reactor project " + p.getGroupId() + ":" + p.getArtifactId() + " has version "
                            + p.getVersion() + " which is not the same as " + project.getVersion()
                            + " thus autoVersionSubmodules cannot be assumed true");
                    return;
                }
            }
            getLog().info("All reactor projects share the same version: " + project.getVersion());
            setProperty("autoVersionSubmodules", "true");
        } catch (NoSuchScmProviderException e) {
            throw new MojoFailureException("Unknown SCM URL: " + scmUrl, e);
        } catch (ScmException | IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String tagNameFromVersion(String version) throws MojoExecutionException {
        Interpolator interpolator = new StringSearchInterpolator("@{", "}");
        List<String> possiblePrefixes = Arrays.asList("project", "pom");
        Properties values = new Properties();
        values.setProperty("artifactId", project.getArtifactId());
        values.setProperty("groupId", project.getGroupId());
        values.setProperty("version", version);
        interpolator.addValueSource(new PrefixedPropertiesValueSource(possiblePrefixes, values, true));
        RecursionInterceptor recursionInterceptor = new PrefixAwareRecursionInterceptor(possiblePrefixes);
        try {
            return interpolator.interpolate(tagNameFormat, recursionInterceptor);
        } catch (InterpolationException e) {
            throw new MojoExecutionException(
                    "Could not interpolate specified tag name format: " + tagNameFormat, e);
        }
    }


    private static class CandidateVersionsIterator implements Iterator<String> {

        private final String baseVersion;
        private final boolean alwaysIncludeRepeatCount;
        private long patch;

        public CandidateVersionsIterator(String baseVersion, boolean alwaysIncludeRepeatCount) {
            this.baseVersion = baseVersion;
            this.alwaysIncludeRepeatCount = alwaysIncludeRepeatCount;
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public String next() {
            try {
                return alwaysIncludeRepeatCount || patch > 0 ? baseVersion + "." + patch : baseVersion;
            } finally {
                patch++;
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static class TagListConsumer implements StreamConsumer {
        private final Set<String> tags;

        public TagListConsumer(Set<String> tags) {
            this.tags = tags;
        }

        @Override
        public void consumeLine(String line) {
            line.trim();
            if (!line.isEmpty()) {
                tags.add(line);
            }
        }
    }

    private static class LsRemoteTagsConsumer implements StreamConsumer {
        private final Set<String> tags;

        public LsRemoteTagsConsumer(Set<String> tags) {
            this.tags = tags;
        }

        @Override
        public void consumeLine(String line) {
            line.trim();
            if (!line.isEmpty()) {
                int index = line.indexOf(REFS_TAGS);
                if (index != -1 && line.matches("^[0-9a-fA-F]{40}\\s+refs/tags/.*$")) {
                    if (line.endsWith("{}")) {
                        line = line.substring(0, line.length() - 2);
                    }
                    tags.add(line.substring(index + REFS_TAGS.length()));
                }
            }
        }
    }
}
