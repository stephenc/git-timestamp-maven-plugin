package com.github.stephenc.continuous.gittimestamp;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedPropertiesValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Generates a release version based on the number of commits in the current Git branch and available tags. This mojo is
 * designed to be invoked before {@code release:prepare}.
 *
 * @since 1.2
 */
@Mojo(name = "release",
      aggregator = true,
      defaultPhase = LifecyclePhase.INITIALIZE,
      requiresProject = true,
      threadSafe = true)
public class ReleaseMojo extends AbstractMojo {
    private static final Pattern SNAPSHOT_PATTERN = Pattern.compile(
            "^(.*-)?((?:SNAPSHOT)|(?:\\d{4}[0-1]\\d[0-3]\\d\\.[0-2]\\d[0-6]\\d[0-6]\\d-\\d+))$"
    );
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
     * The text in the version to be replaced. Normally you will want {@code -SNAPSHOT} but if you are using a version
     * number that indicates replacement such as {@code 1.x-SNAPSHOT} then you may wany to use {@code x-SNAPSHOT} so
     * that the {@code x} is replaced
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
        if (!project.getVersion().endsWith(snapshotText)) {
            throw new MojoFailureException("The current project version is \'" + project.getVersion()
                    + "\' which does not end with the expected text to be replaced: \'" + snapshotText + "\'");
        }
        String scmUrl = scmDeveloperUrl == null || scmDeveloperUrl.isEmpty() ? this.scmUrl : scmDeveloperUrl;
        ScmRepository repository;
        try {
            // first check that we are using git
            repository = scmManager.makeScmRepository(scmUrl);
            final ScmProvider provider = scmManager.getProviderByRepository(repository);
            if (!GitScmProviderRepository.PROTOCOL_GIT.equals(provider.getScmType())) {
                throw new MojoFailureException("Only Git SCM type is supported");
            }
            CommandLineUtils.StringStreamConsumer stderr = new CommandLineUtils.StringStreamConsumer();
            // now count how many commits on the current branch
            Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "rev-list");
            cl.createArg().setValue("--count");
            cl.createArg().setValue("HEAD");
            CommandLineUtils.StringStreamConsumer countOutput = new CommandLineUtils.StringStreamConsumer();
            GitCommandLineUtils.execute(cl, countOutput, stderr, new ScmLoggerImpl(this));
            final long count;
            try {
                count = Long.parseLong(StringUtils.defaultIfBlank(countOutput.getOutput().trim(), "0"));
            } catch (NumberFormatException e) {
                throw new MojoExecutionException(
                        "Could not parse revision count from 'rev-list --count' output: " + countOutput.getOutput(),
                        e
                );
            }

            final Set<String> tags = new HashSet<>();
            if (!localTags && repository.getProviderRepository() instanceof GitScmProviderRepository) {
                cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "ls-remote");
                cl.createArg().setValue("--tags");
                cl.createArg().setValue("--quiet");
                cl.createArg().setValue(((GitScmProviderRepository) repository.getProviderRepository()).getFetchUrl());
            } else {
                cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "tag");
                cl.createArg().setValue("--list");
            }
            StreamConsumer consumer = new StreamConsumer() {
                @Override
                public void consumeLine(String line) {
                    line.trim();
                    if (!line.isEmpty()) {
                        tags.add(line);
                    }
                }
            };
            GitCommandLineUtils.execute(cl, consumer, stderr, new ScmLoggerImpl(this));

            final String baseVersion = project.getVersion().replace(snapshotText, Long.toString(count));
            Iterator<String> suggestedVersion = new Iterator<String>() {

                private long patch;

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public String next() {
                    try {
                        if (patch == 0) {
                            return baseVersion;
                        }
                        return baseVersion + "." + patch;
                    } finally {
                        patch++;
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
            String version;
            while (true) {
                version = suggestedVersion.next();
                Interpolator interpolator = new StringSearchInterpolator("@{", "}");
                List<String> possiblePrefixes = java.util.Arrays.asList("project", "pom");
                Properties values = new Properties();
                values.setProperty("artifactId", project.getArtifactId());
                values.setProperty("groupId", project.getGroupId());
                values.setProperty("version", version);
                interpolator.addValueSource(new PrefixedPropertiesValueSource(possiblePrefixes, values, true));
                RecursionInterceptor recursionInterceptor = new PrefixAwareRecursionInterceptor(possiblePrefixes);
                String suggestedTagName;
                try {
                    suggestedTagName = interpolator.interpolate(tagNameFormat, recursionInterceptor);
                } catch (InterpolationException e) {
                    throw new MojoExecutionException(
                            "Could not interpolate specified tag name format: " + tagNameFormat, e);
                }

                if (!tags.contains(suggestedTagName)) {
                    getLog().info(
                            "Could not find a tag called " + suggestedTagName + " recommending version " + version);
                    break;
                }
                getLog().debug("Skipping " + version + " as there is already a tag named " + suggestedTagName);
            }
            getLog().debug("Known tags: " + tags);

            if (StringUtils.isNotBlank(releaseProperty)) {
                getLog().info("Setting property '" + releaseProperty + "' to '" + version + "'");
                project.getProperties().setProperty(releaseProperty, version);
            }
            if (StringUtils.isNotBlank(developmentProperty)) {
                getLog().info("Setting property '" + developmentProperty + "' to '" + project.getVersion() + "'");
                project.getProperties().setProperty(developmentProperty, project.getVersion());
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
