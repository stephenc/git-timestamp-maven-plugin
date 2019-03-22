pipeline {
    agent any
    stages {
        stage('Build') {
            when {
                not { branch 'master' }
            }
            steps {
                withMaven(mavenLocalRepo: '.repository', maven:'maven-3', jdk:'java-8') {
                    sh 'mvn verify'
                }
            }
        }
        stage('Release') {
            when {
                branch 'master'
            }
            steps {
                withMaven(mavenLocalRepo: '.repository', mavenSettingsConfig: 'oss-sonatype-publish', maven:'maven-3', jdk:'java-8') {
                    sh '''
                    mvn -U release:clean git-timestamp:setup-release release:prepare release:perform
                    '''
                }
            }
            post {
                success {
                    sshagent(['github-ssh']) {
                        // using the full url so that we do not care if https checkout used in Jenkins
                        sh 'git push git@github.com:cloudbeers/maven-continuous.git $(cat TAG_NAME.txt)'
                    }
                    // (If using a repository manager with staging support) Close staging repo
                }
                failure {
                    sh 'test -f TAG_NAME.txt && git tag -d $(cat TAG_NAME.txt) || true'
                    // (If using a repository manager with staging support) Drop staging repo
                }
            }
        }
    }
}
