import com.productmadness.CommonScripts
import com.productmadness.SecretManager

def pipelineParams = [:]
def environmentFile
def secretManager

def call(body) {
    pipelineParams = processParams(body)
    secretManager = new SecretManager(this)

    pipeline {
        agent {
            label EXECUTOR_LABEL()
        }
        options {
            timestamps()
            skipDefaultCheckout true
        }
        environment {
            ONE_PASSWORD_CONNECT_URL = "http://onepassword-connect.apptech.alabs.aristocrat.com:8080"
            ONE_PASSWORD_CONNECT_TOKEN = credentials('one-password-connect-token')
        }
        parameters {
            booleanParam(name: 'should_deploy_sample_app', defaultValue: true, description: 'Should deploy sample app built apk/ipa?')
            booleanParam(name: 'should_check_snapshots', defaultValue: true, description: 'Should check snapshot dependencies?')
        }
        stages {
            stage('set-build-info') {
                steps {
                    script {
                        String environmentFileName = "inject-all-java17.groovy"
                        String gsutilCommand = "gsutil copy gs://mg-ci-installers/env-properties/${environmentFileName} ${environmentFileName}"
                        sh (gsutilCommand)
                        environmentFile = load "${environmentFileName}"
                        environmentFile.setGlobal()
                    }
                }
            }
            stage('log-pipeline-params') {
                steps {
                    println '> Pipeline Params:\n' + pipelineParams
                }
            }
            stage("check-snapshot-dependencies") {
                agent {
                    label WINDOWS_LABEL()
                }
                when {
                    beforeAgent true
                    expression { return shouldCheckSnapshots() }
                }
                environment {
                    ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                    ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                    OS_TYPE = WINDOWS_OS_TYPE()
                }
                steps {
                    script {
                        withEnv(environmentFile.getWindows("jenkins")) {
                            checkIfUsingSnapshotDependencies()
                        }
                    }
                }
            }
            stage('build-intermediate-native-libs') {
                parallel {
                    stage('intermediate-android-aar') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.androidProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.androidProjects, this.&buildAar, true)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('intermediate-amazon-aar') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.amazonProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.amazonProjects, this.&buildAar, true)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('.net-project-intermediate') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.dotNetProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.dotNetProjects, this.&buildDotNetSolution, true)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('intermediate-ios-library') {
                        agent {
                            label OSX_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.iosProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = OSX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.iosProjects, this.&buildIosLib, true)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                    }
                }
            }
            stage('build-intermediate-unity-plugin') {
                agent {
                    label WINDOWS_LABEL()
                }
                when {
                    beforeAgent true
                    expression { return pipelineParams.unityPluginProject != null }
                }
                environment {
                    ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                    ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                    OS_TYPE = WINDOWS_OS_TYPE()
                }
                steps {
                    script {
                        withEnv(environmentFile.getWindows("jenkins")) {
                            buildPlugin(pipelineParams.unityPluginProject, true)
                            publishPlugin(pipelineParams.unityPluginProject, true)
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: '**/*.log, **/test*Unity*.xml, **/playTest*Unity*.xml', allowEmptyArchive: true
                    }
                    success {
                        archiveArtifacts '**/*.zip'
                    }
                }
            }
            stage('build-sample-app') {
                parallel {
                    stage('build-android-samples') {
                        agent {
                            label WINDOWS_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.sampleAppAndroidProject != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = WINDOWS_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getWindows("jenkins")) {
                                    buildGooglePlaySampleApp(pipelineParams.sampleAppAndroidProject)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('build-amazon-sample') {
                        agent {
                            label WINDOWS_LABEL()
                        }
                        when {
                            beforeAgent true
                            expression { return pipelineParams.sampleAppAmazonProject != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = WINDOWS_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getWindows("jenkins")) {
                                    buildAmazonSampleApp(pipelineParams.sampleAppAmazonProject)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('build-ios-sample') {
                        stages {
                            stage('export-xcode-sample-project') {
                                agent {
                                    label WINDOWS_LABEL()
                                }
                                when {
                                    beforeAgent true
                                    expression { return pipelineParams.sampleAppIosProject != null }
                                }
                                environment {
                                    ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                    ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                    OS_TYPE = WINDOWS_OS_TYPE()
                                }
                                steps {
                                    withEnv(environmentFile.getWindows("jenkins")) {
                                        buildSampleAppXcodeProject(pipelineParams.sampleAppIosProject)
                                    }
                                }
                                post {
                                    always {
                                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                                    }
                                }
                            }
                            stage('build-ipa-file') {
                                agent {
                                    label OSX_LABEL()
                                }
                                when {
                                    beforeAgent true
                                    expression { return pipelineParams.sampleAppIosProject != null }
                                }
                                environment {
                                    ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                    ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                    OS_TYPE = OSX_OS_TYPE()
                                }
                                steps {
                                    script {
                                        withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                            buildSampleAppIpa(pipelineParams.sampleAppIosProject)
                                        }
                                    }
                                }
                                post {
                                    always {
                                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                                    }
                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        script {
                            if (pipelineParams.finalisationTasks?.success?.trim()) {
                                gradleExec(pipelineParams.finalisationTasks.success)
                            }
                            if (pipelineParams.slackChannel?.trim()) {
                                slackSend channel: pipelineParams.slackChannel, color: 'good', message: "Build Succeeded : - (<${env.BUILD_URL.replace("%2F", "/")} |${env.JOB_NAME} #${env.BUILD_NUMBER}>)"
                            }
                        }
                    }
                    failure {
                        script {
                            if (pipelineParams.finalisationTasks?.failure?.trim()) {
                                gradleExec(pipelineParams.finalisationTasks.failure)
                            }
                            if (pipelineParams.slackChannel?.trim()) {
                                slackSend channel: pipelineParams.slackChannel, color: 'bad', message: "Build Failed : - (<${env.BUILD_URL.replace("%2F", "/")} |${env.JOB_NAME} #${env.BUILD_NUMBER}>)"
                            }
                        }
                    }
                    always {
                        script {
                            if (pipelineParams.finalisationTasks?.always?.trim()) {
                                gradleExec(pipelineParams.finalisationTasks.always)
                            }
                        }
                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                    }
                }
            }
            stage('build-native-libs') {
                parallel {
                    stage('android-aar') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            expression { return shouldRebuildForRelease() }
                            beforeAgent true
                            expression { return pipelineParams.androidProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.androidProjects, this.&buildAar, false)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('amazon-aar') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            expression { return shouldRebuildForRelease() }
                            beforeAgent true
                            expression { return pipelineParams.amazonProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.amazonProjects, this.&buildAar, false)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('.net-project') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            expression { return shouldRebuildForRelease() }
                            beforeAgent true
                            expression { return pipelineParams.dotNetProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = LINUX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getLinux("jenkins")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.dotNetProjects, this.&buildDotNetSolution, false)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('ios-library') {
                        agent {
                            label OSX_LABEL()
                        }
                        when {
                            expression { return shouldRebuildForRelease() }
                            beforeAgent true
                            expression { return pipelineParams.iosProjects != null }
                        }
                        environment {
                            ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                            ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                            OS_TYPE = OSX_OS_TYPE()
                        }
                        steps {
                            script {
                                withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                    cleanWs()
                                    applyToAllProjects(pipelineParams.iosProjects, this.&buildIosLib, false)
                                }
                            }
                        }
                        post {
                            always {
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                    }
                }
            }
            stage('build-unity-plugin') {
                agent {
                    label WINDOWS_LABEL()
                }
                when {
                    expression { return shouldRebuildForRelease() }
                    beforeAgent true
                    expression { return pipelineParams.unityPluginProject != null }
                }
                environment {
                    ARTIFACTORY_USER = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                    ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                    OS_TYPE = WINDOWS_OS_TYPE()
                }
                steps {
                    script {
                        withEnv(environmentFile.getWindows("jenkins")) {
                            buildPlugin(pipelineParams.unityPluginProject, false)
                            publishPlugin(pipelineParams.unityPluginProject, false)
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: '**/*.log, **/test*Unity*.xml, **/playTest*Unity*.xml', allowEmptyArchive: true
                    }
                    success {
                        archiveArtifacts '**/*.zip'
                    }
                }
            }
        }
    }
}

String EXECUTOR_LABEL() { return "alabs-mas-build-executor" }
String WINDOWS_LABEL()  { return "alabs-mas-windows" }
String LINUX_LABEL()    { return "alabs-mas-linux" }
String OSX_LABEL()      { return "coretech-osx" }

String WINDOWS_OS_TYPE() { return "Windows" }
String LINUX_OS_TYPE()   { return "Linux" }
String OSX_OS_TYPE()     { return "Osx" }

def shouldRebuildForRelease() {
    def masterBranch = 'master'
    def backportBranch = 'backport'
    return env.BRANCH_NAME == masterBranch || env.BRANCH_NAME.startsWith(backportBranch)
}

def shouldCheckSnapshots() {
    println "should_check_snapshots = ${params.should_check_snapshots}"
    if (!params.should_check_snapshots) {
        return false
    }

    return shouldRebuildForRelease()
}

def processParams(body) {
    def params = createDefaultPipelineParams()
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    return params
}

def createDefaultPipelineParams() {
    def paramMap = [:]
    paramMap.androidProjects = null
    paramMap.amazonProjects = null
    paramMap.iosProjects = null
    paramMap.dotNetProjects = null
    paramMap.unityPluginProject = null
    paramMap.unityPluginProjects = null
    paramMap.sampleAppAndroidProject = null
    paramMap.sampleAppAmazonProject = null
    paramMap.sampleAppIosProject = null
    paramMap.slackChannel = 'app-core-builds'
    paramMap.enableTnTVersioning = false
    paramMap.gradlewPath = '.'
    paramMap.finalisationTasks = null
    paramMap.vaultArtifactoryTeam = 'coretech'
    return paramMap
}

void convertToList(def argument) {
    return (argument instanceof List) ? argument : [argument]
}

void applyToAllProjects(def projects, Closure closure, boolean isStaging) {
    convertToList(projects).each { project ->
        closure(project, isStaging)
    }
}

def executeCommand(String command) {
    println("[" + (isUnix() ? "unix" : "windows") + "]: " + command)

    if (isUnix()) {
        sh(script: """#!/bin/bash -l
          ${command}
        """, returnStdout: true)?.trim()
    } else {
        powershell(script: command, returnStdout: true)?.trim()
    }
}

String getArtifactoryUser(String vaultArtifactoryTeam) {
    println "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "username")} - getArtifactoryUser"
    return "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "username")}"
}

String getArtifactoryPassword(String vaultArtifactoryTeam) {
    println "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "password")} - getArtifactoryPassword"
    return "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "password")}"
}

def checkIfUsingSnapshotDependencies() {
    gitClone("plugin", pipelineParams.scmUrl)
    stage ('check-snapshots') {
        println "Checking process - Start"
        gradleExec("checkIfUsingSnapshots -Pstaging=${false}")
        println "Checking process - Finish"
    }
}

def buildAar(HashMap project, boolean isStaging) {
    def projectName = project.name

    gitClone(projectName, pipelineParams.scmUrl)

    if (project.runTests) {
        stage("${projectName}-test") {
            gradleExec("${projectName}:test")
        }
        stage("${projectName}-testReport") {
            gradleExec("${projectName}:testReport")
        }
        stage("${projectName}-junit") {
            junit '**/testResults*.xml'
        }
    } else {
        println "${projectName} tests - SKIPPED"
    }
    stage("${projectName}-assemble") {
        gradleExec("${projectName}:assemble -Pstaging=${isStaging}")
    }
    stage("${projectName}-publish") {
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    if (project.noArtifacts) {
        println "SKIPPED - build has no artifacts"
    } else {
        stage("${projectName}-attach-artifacts") {
            archiveArtifacts '**/*release*.aar'
        }
    }
}

def buildIosLib(HashMap project, boolean isStaging) {
    def projectName = project.name
    def scheme = project.scheme ?: ''

    gitClone(projectName, pipelineParams.scmUrl)

    if (project.runTests) {
        stage("${projectName}-test") {
            gradleExec("${projectName}:xctest -Pscheme=${scheme}")
            def xmlFileName = "testResults.xml"
            generateTestResultXml("testResults.xcresult", xmlFileName, scheme)
            junit "${xmlFileName}"
            archiveArtifacts artifacts: "${xmlFileName}", allowEmptyArchive: true
        }
    } else {
        println "${projectName} tests - SKIPPED"
    }
    stage("${projectName}-build") {
        gradleExec("${projectName}:xcbuild -Pstaging=${isStaging} -Pscheme=${scheme}")
    }
    stage("${projectName}-publish") {
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    if (project.noArtifacts) {
        println "SKIPPED - build has no artifacts"
    } else {
        stage("${projectName}-attach-artifacts") {
            archiveArtifacts artifacts: '**/*.a', allowEmptyArchive: true
        }
    }
}

def buildDotNetSolution(HashMap project, boolean isStaging) {
    def projectName = project.name

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-build") {
        gradleExec("${projectName}:dotnetBuild -Pstaging=${isStaging}")
    }
    if (project.runTests) {
        stage("${projectName}-test") {
            gradleExec("${projectName}:dotnetTest -Pstaging=${isStaging}")
            nunit testResultsPattern: '**/test*Dotnet*.xml'
        }
    } else {
        println "${projectName} dotNet solution tests - SKIPPED"
    }
    stage("${projectName}-publish") {
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
}

void buildPlugin(HashMap project, boolean isStaging) {
    def projectName = project.name
    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-installDependencies") {
        gradleExec("${projectName}:installDependencies -Pstaging=${isStaging} --refresh-dependencies")
    }
    if (project.runEditModeTests) {
        stage("${projectName}-editModeUnityTests") {
            runUnityTaskOnProject(projectName, "editModeUnityTest", "-Pstaging=true")
            nunit testResultsPattern: '**/test*Unity*.xml'
        }
    } else {
        println "${projectName} editmode tests - SKIPPED"
    }
    if (project.runPlayModeTests) {
        stage("${projectName}-playModeUnityTests") {
            runUnityTaskOnProject(projectName, "playModeUnityTest", "-Pstaging=true")
            nunit testResultsPattern: '**/playTest*Unity*.xml'
        }
    } else {
        println "${projectName} playmode tests - SKIPPED"
    }
    if (pipelineParams.enableTnTVersioning) {
        stage("${projectName}-TntVersioning") {
            gradleExec("${projectName}:incrementVersion")
        }
    }
    stage("${projectName}-generateDependencies") {
        gradleExec("${projectName}:generateDependencies -Pstaging=${isStaging}")
    }
}

void publishPlugin(HashMap project, boolean isStaging) {
    def projectName = project.name

    stage("${projectName}-publish") {
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
}

void buildGooglePlaySampleApp(HashMap project) {
    println "should_deploy_sample_app = ${params.should_deploy_sample_app}"
    def projectName = project.name
    buildAndroidSampleApp(project)
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployGooglePlayBuild") {
            gradleExec("${projectName}:deployAndroidBuild")
        }
    } else {
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
}

void buildAmazonSampleApp(HashMap project) {
    println "should_deploy_sample_app = ${params.should_deploy_sample_app}"
    def projectName = project.name
    buildAndroidSampleApp(project)
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployAmazonBuild") {
            gradleExec("${projectName}:deployAmazonBuild")
        }
    } else {
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
}

void buildAndroidSampleApp(HashMap project) {
    def projectName = project.name
    def task = project.task
    def artifactPath = project.artifactPath

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-run-${task}") {
        runUnityTaskOnProject(projectName, task, "-Pstaging=true")
    }
    if (artifactPath?.trim()) {
        stage("${projectName}-attach-artifacts") {
            archiveArtifacts "${artifactPath}"
        }
    } else {
        println "No artifact path defined for ${projectName}. SKIPPED"
    }
}

void buildSampleAppXcodeProject(HashMap project) {
    def projectName = project.name
    def unityTask = project.unityTask

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-${unityTask}") {
        runUnityTaskOnProject(projectName, unityTask, "-Pstaging=true")
    }
    stage("${projectName}-zipXcodeProject") {
        gradleExec("${projectName}:zipXcodeProject")
    }
    stage("${projectName}-syncXcodeProjectToCloudStorage") {
        gradleExec("${projectName}:syncXcodeProjectToCloudStorage")
    }
}

void runUnityTaskOnProject(String projectName, String task, String params = "") {
    try {
        gradleExec("${projectName}:getLicense")
        gradleExec("${projectName}:${task} ${params}")
    } finally {
        withEnv(environmentFile.getWindows("jenkins")) {
            gradleExec("${projectName}:revokeLicense --info")
        }
    }
}

void buildSampleAppIpa(HashMap project) {
    def projectName = project.name
    def artifactPath = project.artifactPath
    def xcodeTask = project.xcodeTask

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-syncXcodeProjectFromCloudStorage") {
        gradleExec("${projectName}:syncXcodeProjectFromCloudStorage")
    }
    stage("${projectName}-unzipXcodeProject") {
        gradleExec("${projectName}:unzipXcodeProject")
    }
    stage("${projectName}-${xcodeTask}") {
        gradleExec("${projectName}:${xcodeTask}")
    }
    if (artifactPath?.trim()) {
        stage("${projectName}-attach-artifacts") {
            archiveArtifacts "${artifactPath}"
        }
    } else {
        println "No artifact path defined for ${projectName}. SKIPPED"
    }
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployIosBuild") {
            gradleExec("${projectName}:deployIosBuild")
        }
    } else {
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
}

void gitClone(String projectName, String scmUrl) {
    cleanWs()
    
    def osType = env.OS_TYPE ? env.OS_TYPE : OSX_OS_TYPE()
    if (osType.equals(WINDOWS_OS_TYPE()) || osType.equals(LINUX_OS_TYPE())) {
        stage("${projectName}-instance-ready-check") {
            println "Wait For Instance To Become Ready. OS Type: ${osType}"
            def commonScripts = new CommonScripts(this)
            commonScripts.waitForInstanceToBecomeReady()
            println "Instance is ready."
        }
    } else {
        println "Don't need to wait for Instance to be ready. OS Type: ${osType}"
    }

    stage("${projectName}-checkout") {
        executeCommand("git clone ${scmUrl} . --branch ${env.BRANCH_NAME}")
    }
}

void gradleExec(String command) {
    println(executeCommand("${pipelineParams.gradlewPath}/gradlew ${command} --stacktrace"))
}

void generateTestResultXml(String xcResultFileName, String xmlFileName, String scheme) {
    String fileShouldBeCreatedMaxMinutesAgo = "3"
    String filePath = executeCommand("find ~/Library/Developer/Xcode/DerivedData -name \"Run-${scheme}*.xcresult\" -mmin -${fileShouldBeCreatedMaxMinutesAgo} | sort -Vr | head -1")
    if (filePath == null) {
        println("error: xcresult was not found")
        return
    }
    executeCommand("cp -r ${filePath} ./${xcResultFileName}")
    println(executeCommand("trainer -p ./ -o ./ -f ${xmlFileName}"))
    executeCommand("rm -rf ${xcResultFileName}")
}
