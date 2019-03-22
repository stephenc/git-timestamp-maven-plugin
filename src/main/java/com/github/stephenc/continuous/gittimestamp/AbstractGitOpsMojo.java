/*
 * Copyright 2019 Stephen Connolly
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
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.git.gitexe.command.GitCommandLineUtils;
import org.apache.maven.scm.provider.git.repository.GitScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Base class for the GitOps mojos.
 */
public abstract class AbstractGitOpsMojo extends AbstractMojo {
    /**
     * Controls which SCM URL to prefer for querying, the {@code xpath:/project/scm/developerConnection} or the {@code
     * xpath:/project/scm/connection}.
     *
     * @since 1.29
     */
    @Parameter(property = "preferDeveloperconnection", defaultValue = "true")
    protected boolean preferDeveloperConnection;
    /**
     * The character encoding scheme to be applied when writing files.
     */
    @Parameter(defaultValue = "${project.build.outputEncoding}")
    protected String encoding;
    @Parameter(defaultValue = "${basedir}", readonly = true)
    protected File basedir;
    @Component
    protected ScmManager scmManager;
    @Parameter(defaultValue = "${project.scm.connection}", readonly = true)
    protected String scmUrl;
    @Parameter(defaultValue = "${project.scm.developerConnection}", readonly = true)
    protected String scmDeveloperUrl;
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    protected void writeFile(File fileName, String value) throws IOException {
        if (fileName != null) {
            fileName.getParentFile().mkdirs();
            getLog().info("Writing '" + value + "' to " + fileName);
            FileUtils.write(fileName, value + "\n", encoding);
        }
    }

    protected void setProperty(String propertyName, String propertyValue) {
        if (StringUtils.isNotBlank(propertyName)) {
            getLog().info("Setting property '" + propertyName + "' to '" + propertyValue + "'");
            project.getProperties().setProperty(propertyName, propertyValue);
        }
    }

    protected long getCurrentBranchCommitCount()
            throws ScmException, MojoExecutionException {
        Commandline cl = GitCommandLineUtils.getBaseGitCommandLine(basedir, "rev-list");
        cl.createArg().setValue("--count");
        cl.createArg().setValue("HEAD");
        CommandLineUtils.StringStreamConsumer countOutput = new CommandLineUtils.StringStreamConsumer();
        GitCommandLineUtils.execute(cl, countOutput, logWarnConsumer(), new GitCommandLineLogger(this));
        try {
            return Long.parseLong(StringUtils.defaultIfBlank(countOutput.getOutput().trim(), "0"));
        } catch (NumberFormatException e) {
            throw new MojoExecutionException(
                    "Could not parse revision count from 'rev-list --count' output: " + countOutput.getOutput(),
                    e
            );
        }
    }

    protected ScmRepository getScmRepository() throws ScmRepositoryException, NoSuchScmProviderException {
        String scmUrl = preferDeveloperConnection
                ? (scmDeveloperUrl == null || scmDeveloperUrl.isEmpty() ? this.scmUrl : scmDeveloperUrl)
                : (this.scmUrl == null || this.scmUrl.isEmpty() ? scmDeveloperUrl : this.scmUrl);
        return scmManager.makeScmRepository(scmUrl);
    }

    protected ScmProvider getValidatedScmProvider(ScmRepository repository)
            throws NoSuchScmProviderException, MojoFailureException {
        ScmProvider provider = scmManager.getProviderByRepository(repository);
        if (!GitScmProviderRepository.PROTOCOL_GIT.equals(provider.getScmType())) {
            throw new MojoFailureException("Only Git SCM type is supported");
        }
        return provider;
    }

    protected CommandLineUtils.StringStreamConsumer logWarnConsumer() {
        return new CommandLineUtils.StringStreamConsumer() {
            @Override
            public void consumeLine(String line) {
                super.consumeLine(line);
                getLog().warn(line);
            }
        };
    }
    protected CommandLineUtils.StringStreamConsumer logInfoConsumer() {
        return new CommandLineUtils.StringStreamConsumer() {
            @Override
            public void consumeLine(String line) {
                super.consumeLine(line);
                getLog().info(line);
            }
        };
    }
    protected CommandLineUtils.StringStreamConsumer logDebugConsumer() {
        return new CommandLineUtils.StringStreamConsumer() {
            @Override
            public void consumeLine(String line) {
                super.consumeLine(line);
                getLog().debug(line);
            }
        };
    }
}
