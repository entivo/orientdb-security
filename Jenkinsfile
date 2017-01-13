#!groovy
node("master") {
    def mvnHome = tool 'mvn'
    def mvnJdk8Image = "orientdb/mvn-gradle-zulu-jdk-8"

    stage('Source checkout') {

        checkout scm
    }

    stage('Tests on Java8') {
        docker.image("${mvnJdk8Image}").inside("${env.VOLUMES}") {
            try {

                sh "${mvnHome}/bin/mvn  --batch-mode -V -U  clean install  -Dsurefire.useFile=false"

            } catch (e) {
                currentBuild.result = 'FAILURE'

                slackSend(color: 'bad', message: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
            } finally {
                junit allowEmptyResults: true, testResults: '**/target/surefire-reports/TEST-*.xml'

            }
        }
    }

}


