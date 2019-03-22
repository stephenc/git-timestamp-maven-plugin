pipeline {
    agent any
    options {
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '1', daysToKeepStr: '', numToKeepStr: '10')
        disableConcurrentBuilds()
    }
    stages {
        stage('Build') {
            when {
                not { branch 'master' }
            }
            steps {
                withMaven(maven:'maven-3', jdk:'java-8', mavenLocalRepo: '.repository') {
                    sh 'mvn verify'
                }
            }
        }
        stage('Release') {
            when {
                branch 'master'
            }
            environment {
                // We need to put the .gnupg homedir somewhere, the workspace is too long a path name
                // for the sockets, so we instead use a subdirectory of the user home (typically /home/jenkins).
                // By using the executor number as part of that name, we ensure nobody else will concurrently
                // use this directory
                GNUPGHOME = "${HOME}/.e${EXECUTOR_NUMBER}/.gnupg"
            }
            steps {
                withCredentials([
                        file(credentialsId: '.gnupg', variable: 'GNUPGHOME_ZIP'),
                        string(credentialsId: 'gpg.passphrase', variable: 'GPG_PASSPHRASE')
                ]) {
                    // Install the .gnupg directory
                    sh '''
                        gpgconf --kill gpg-agent
                        rm -rf "$(dirname "${GNUPGHOME}")"
                        mkdir -p "${GNUPGHOME}"
                        chmod 700 "${GNUPGHOME}"
                        unzip "${GNUPGHOME_ZIP}" -d "${GNUPGHOME}"
                        gpgconf --launch gpg-agent
                    '''

                    // Create and stage release
                    withMaven(maven:'maven-3', jdk:'java-8', mavenLocalRepo: '.repository', mavenSettingsConfig: 'oss-sonatype-publish') {
                        sh 'mvn release:clean git-timestamp:setup-release release:prepare release:perform'
                    }
                }
            }
            post {
                always {
                    // Uninstall .gnupg directory
                    sh 'gpgconf --kill gpg-agent || true ; rm -rf "$(dirname "${GNUPGHOME}")"'
                }
                success {
                    // Publish the tag
                    sshagent(['github-ssh']) {
                        // using the full url so that we do not care if https checkout used in Jenkins
                        sh 'git push git@github.com:stephenc/git-timestamp-maven-plugin.git $(cat TAG_NAME.txt)'
                    }
                    // Release the artifacts
                    withMaven(mavenLocalRepo: '.repository', mavenSettingsConfig: 'oss-sonatype-publish', maven:'maven-3', jdk:'java-8') {
                        sh 'mvn -f target/checkout/pom.xml nexus-staging:release'
                    }

                    // Set the display name to the version so it is easier to see in the UI
                    script { currentBuild.displayName = readFile('VERSION.txt').trim() }
                }
                failure {
                    // Remove the local tag as there is no matching remote tag
                    sh 'test -f TAG_NAME.txt && git tag -d $(cat TAG_NAME.txt) && rm -f TAG_NAME.txt || true'

                    // Drop staging repo
                    withMaven(mavenLocalRepo: '.repository', mavenSettingsConfig: 'oss-sonatype-publish', maven:'maven-3', jdk:'java-8') {
                        sh 'mvn -f target/checkout/pom.xml nexus-staging:drop || true'
                    }

                }
            }
        }
    }
}
