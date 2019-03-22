package com.github.stephenc.continuous.gittimestamp;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.scm.log.ScmLogger;

/**
 * Log adapter.
 */
class GitCommandLineLogger implements ScmLogger {

    private AbstractMojo mojo;

    public GitCommandLineLogger(AbstractMojo mojo) {
        this.mojo = mojo;
    }

    @Override
    public boolean isDebugEnabled() {
        return mojo.getLog().isDebugEnabled();
    }

    @Override
    public void debug(String content) {
        mojo.getLog().debug(content);
    }

    @Override
    public void debug(String content, Throwable error) {
        mojo.getLog().debug(content, error);
    }

    @Override
    public void debug(Throwable error) {
        mojo.getLog().debug(error);
    }

    @Override
    public boolean isInfoEnabled() {
        return mojo.getLog().isInfoEnabled();
    }

    @Override
    public void info(String content) {
        mojo.getLog().info(content);
    }

    @Override
    public void info(String content, Throwable error) {
        mojo.getLog().info(content, error);
    }

    @Override
    public void info(Throwable error) {
        mojo.getLog().info(error);
    }

    @Override
    public boolean isWarnEnabled() {
        return mojo.getLog().isWarnEnabled();
    }

    @Override
    public void warn(String content) {
        mojo.getLog().warn(content);
    }

    @Override
    public void warn(String content, Throwable error) {
        mojo.getLog().warn(content, error);
    }

    @Override
    public void warn(Throwable error) {
        mojo.getLog().warn(error);
    }

    @Override
    public boolean isErrorEnabled() {
        return mojo.getLog().isErrorEnabled();
    }

    @Override
    public void error(String content) {
        mojo.getLog().error(content);
    }

    @Override
    public void error(String content, Throwable error) {
        mojo.getLog().error(content, error);
    }

    @Override
    public void error(Throwable error) {
        mojo.getLog().error(error);
    }

}
