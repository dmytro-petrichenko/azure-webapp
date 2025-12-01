import com.productmadness.CommonScripts
import com.productmadness.SecretManager

def pipelineParams = [:]
def environmentFile
def secretManager

def log(msg) {
    println "[LOG] ${new Date()} - ${msg}"
}

def call(body) {
    log("call() - pipeline start")
    pipelineParams = processParams(body)
    log("call() - pipelineParams after processParams: ${pipelineParams}")
    secretManager = new SecretManager(this)
    log("call() - SecretManager initialised")

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
                        log("Stage set-build-info - start")
                        String environmentFileName = "inject-all-java17.groovy"
                        String gsutilCommand = "gsutil copy gs://mg-ci-installers/env-properties/${environmentFileName} ${environmentFileName}"
                        log("set-build-info - running command: ${gsutilCommand}")
                        sh (gsutilCommand)
                        log("set-build-info - loading environment file: ${environmentFileName}")
                        environmentFile = load "${environmentFileName}"
                        environmentFile.setGlobal()
                        log("Stage set-build-info - end")
                    }
                }
            }
            stage('log-pipeline-params') {
                steps {
                    script {
                        log("Stage log-pipeline-params - pipelineParams: ${pipelineParams}")
                        println '> Pipeline Params:\n' + pipelineParams
                    }
                }
            }
            stage("check-snapshot-dependencies") {
                agent {
                    label WINDOWS_LABEL()
                }
                when {
                    beforeAgent true
                    expression { 
                        log("When check-snapshot-dependencies - evaluating shouldCheckSnapshots()")
                        return shouldCheckSnapshots() 
                    }
                }
                steps {
                    script {
                        log("Stage check-snapshot-dependencies - start")
                        env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                        env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                        env.OS_TYPE              = WINDOWS_OS_TYPE()
                        log("check-snapshot-dependencies - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                        withEnv(environmentFile.getWindows("jenkins")) {
                            log("check-snapshot-dependencies - calling checkIfUsingSnapshotDependencies()")
                            checkIfUsingSnapshotDependencies()
                        }
                        log("Stage check-snapshot-dependencies - end")
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
                            expression { 
                                log("When intermediate-android-aar - pipelineParams.androidProjects != null? ${pipelineParams.androidProjects != null}")
                                return pipelineParams.androidProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage intermediate-android-aar - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = LINUX_OS_TYPE()
                                log("intermediate-android-aar - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getLinux("jenkins")) {
                                    log("intermediate-android-aar - cleanWs()")
                                    cleanWs()
                                    log("intermediate-android-aar - applyToAllProjects(androidProjects, buildAar, true)")
                                    applyToAllProjects(pipelineParams.androidProjects, this.&buildAar, true)
                                }
                                log("Stage intermediate-android-aar - end")
                            }
                        }
                        post {
                            always {
                                log("Post intermediate-android-aar - archiving logs")
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
                            expression { 
                                log("When .net-project-intermediate - pipelineParams.dotNetProjects != null? ${pipelineParams.dotNetProjects != null}")
                                return pipelineParams.dotNetProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage .net-project-intermediate - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = LINUX_OS_TYPE()
                                log(".net-project-intermediate - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getLinux("jenkins")) {
                                    log(".net-project-intermediate - cleanWs()")
                                    cleanWs()
                                    log(".net-project-intermediate - applyToAllProjects(dotNetProjects, buildDotNetSolution, true)")
                                    applyToAllProjects(pipelineParams.dotNetProjects, this.&buildDotNetSolution, true)
                                }
                                log("Stage .net-project-intermediate - end")
                            }
                        }
                        post {
                            always {
                                log("Post .net-project-intermediate - archiving logs")
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
                            expression { 
                                log("When intermediate-ios-library - pipelineParams.iosProjects != null? ${pipelineParams.iosProjects != null}")
                                return pipelineParams.iosProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage intermediate-ios-library - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = OSX_OS_TYPE()
                                log("intermediate-ios-library - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                    log("intermediate-ios-library - cleanWs()")
                                    cleanWs()
                                    log("intermediate-ios-library - applyToAllProjects(iosProjects, buildIosLib, true)")
                                    applyToAllProjects(pipelineParams.iosProjects, this.&buildIosLib, true)
                                }
                                log("Stage intermediate-ios-library - end")
                            }
                        }
                        post {
                            always {
                                log("Post intermediate-ios-library - archiving logs")
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                }
                post {
                    always {
                        log("Post build-intermediate-native-libs - archiving logs")
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
                    expression { 
                        log("When build-intermediate-unity-plugin - pipelineParams.unityPluginProject != null? ${pipelineParams.unityPluginProject != null}")
                        return pipelineParams.unityPluginProject != null 
                    }
                }
                steps {
                    script {
                        log("Stage build-intermediate-unity-plugin - start")
                        env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                        env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                        env.OS_TYPE              = WINDOWS_OS_TYPE()
                        log("build-intermediate-unity-plugin - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                        withEnv(environmentFile.getWindows("jenkins")) {
                            log("build-intermediate-unity-plugin - buildPlugin()")
                            buildPlugin(pipelineParams.unityPluginProject, true)
                            log("build-intermediate-unity-plugin - publishPlugin()")
                            publishPlugin(pipelineParams.unityPluginProject, true)
                        }
                        log("Stage build-intermediate-unity-plugin - end")
                    }
                }
                post {
                    always {
                        log("Post build-intermediate-unity-plugin - archiving logs and test results")
                        archiveArtifacts artifacts: '**/*.log, **/test*Unity*.xml, **/playTest*Unity*.xml', allowEmptyArchive: true
                    }
                    success {
                        log("Post build-intermediate-unity-plugin - archiving zip artifacts")
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
                            expression { 
                                log("When build-android-samples - pipelineParams.sampleAppAndroidProject != null? ${pipelineParams.sampleAppAndroidProject != null}")
                                return pipelineParams.sampleAppAndroidProject != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage build-android-samples - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = WINDOWS_OS_TYPE()
                                log("build-android-samples - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getWindows("jenkins")) {
                                    log("build-android-samples - buildGooglePlaySampleApp()")
                                    buildGooglePlaySampleApp(pipelineParams.sampleAppAndroidProject)
                                }
                                log("Stage build-android-samples - end")
                            }
                        }
                        post {
                            always {
                                log("Post build-android-samples - archiving logs")
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
                            expression { 
                                log("When build-amazon-sample - pipelineParams.sampleAppAmazonProject != null? ${pipelineParams.sampleAppAmazonProject != null}")
                                return pipelineParams.sampleAppAmazonProject != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage build-amazon-sample - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = WINDOWS_OS_TYPE()
                                log("build-amazon-sample - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getWindows("jenkins")) {
                                    log("build-amazon-sample - buildAmazonSampleApp()")
                                    buildAmazonSampleApp(pipelineParams.sampleAppAmazonProject)
                                }
                                log("Stage build-amazon-sample - end")
                            }
                        }
                        post {
                            always {
                                log("Post build-amazon-sample - archiving logs")
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
                                    expression { 
                                        log("When export-xcode-sample-project - pipelineParams.sampleAppIosProject != null? ${pipelineParams.sampleAppIosProject != null}")
                                        return pipelineParams.sampleAppIosProject != null 
                                    }
                                }
                                steps {
                                    script {
                                        log("Stage export-xcode-sample-project - start")
                                        env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                        env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                        env.OS_TYPE              = WINDOWS_OS_TYPE()
                                        log("export-xcode-sample-project - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                        withEnv(environmentFile.getWindows("jenkins")) {
                                            log("export-xcode-sample-project - buildSampleAppXcodeProject()")
                                            buildSampleAppXcodeProject(pipelineParams.sampleAppIosProject)
                                        }
                                        log("Stage export-xcode-sample-project - end")
                                    }
                                }
                                post {
                                    always {
                                        log("Post export-xcode-sample-project - archiving logs")
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
                                    expression { 
                                        log("When build-ipa-file - pipelineParams.sampleAppIosProject != null? ${pipelineParams.sampleAppIosProject != null}")
                                        return pipelineParams.sampleAppIosProject != null 
                                    }
                                }
                                steps {
                                    script {
                                        log("Stage build-ipa-file - start")
                                        env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                        env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                        env.OS_TYPE              = OSX_OS_TYPE()
                                        log("build-ipa-file - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                        withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                            log("build-ipa-file - buildSampleAppIpa()")
                                            buildSampleAppIpa(pipelineParams.sampleAppIosProject)
                                        }
                                        log("Stage build-ipa-file - end")
                                    }
                                }
                                post {
                                    always {
                                        log("Post build-ipa-file - archiving logs")
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
                            log("Post build-sample-app SUCCESS - running finalisationTasks.success and slack notification if configured")
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
                            log("Post build-sample-app FAILURE - running finalisationTasks.failure and slack notification if configured")
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
                            log("Post build-sample-app ALWAYS - running finalisationTasks.always if configured")
                            if (pipelineParams.finalisationTasks?.always?.trim()) {
                                gradleExec(pipelineParams.finalisationTasks.always)
                            }
                        }
                        log("Post build-sample-app - archiving logs")
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
                            expression { 
                                log("When android-aar - shouldRebuildForRelease()? ${shouldRebuildForRelease()}")
                                return shouldRebuildForRelease() 
                            }
                            beforeAgent true
                            expression { 
                                log("When android-aar - pipelineParams.androidProjects != null? ${pipelineParams.androidProjects != null}")
                                return pipelineParams.androidProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage android-aar - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = LINUX_OS_TYPE()
                                log("android-aar - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getLinux("jenkins")) {
                                    log("android-aar - cleanWs()")
                                    cleanWs()
                                    log("android-aar - applyToAllProjects(androidProjects, buildAar, false)")
                                    applyToAllProjects(pipelineParams.androidProjects, this.&buildAar, false)
                                }
                                log("Stage android-aar - end")
                            }
                        }
                        post {
                            always {
                                log("Post android-aar - archiving logs")
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('amazon-aar') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            expression { 
                                log("When amazon-aar - shouldRebuildForRelease()? ${shouldRebuildForRelease()}")
                                return shouldRebuildForRelease() 
                            }
                            beforeAgent true
                            expression { 
                                log("When amazon-aar - pipelineParams.amazonProjects != null? ${pipelineParams.amazonProjects != null}")
                                return pipelineParams.amazonProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage amazon-aar - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = LINUX_OS_TYPE()
                                log("amazon-aar - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getLinux("jenkins")) {
                                    log("amazon-aar - cleanWs()")
                                    cleanWs()
                                    log("amazon-aar - applyToAllProjects(amazonProjects, buildAar, false)")
                                    applyToAllProjects(pipelineParams.amazonProjects, this.&buildAar, false)
                                }
                                log("Stage amazon-aar - end")
                            }
                        }
                        post {
                            always {
                                log("Post amazon-aar - archiving logs")
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('.net-project') {
                        agent {
                            label LINUX_LABEL()
                        }
                        when {
                            expression { 
                                log("When .net-project - shouldRebuildForRelease()? ${shouldRebuildForRelease()}")
                                return shouldRebuildForRelease() 
                            }
                            beforeAgent true
                            expression { 
                                log("When .net-project - pipelineParams.dotNetProjects != null? ${pipelineParams.dotNetProjects != null}")
                                return pipelineParams.dotNetProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage .net-project - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = LINUX_OS_TYPE()
                                log(".net-project - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getLinux("jenkins")) {
                                    log(".net-project - cleanWs()")
                                    cleanWs()
                                    log(".net-project - applyToAllProjects(dotNetProjects, buildDotNetSolution, false)")
                                    applyToAllProjects(pipelineParams.dotNetProjects, this.&buildDotNetSolution, false)
                                }
                                log("Stage .net-project - end")
                            }
                        }
                        post {
                            always {
                                log("Post .net-project - archiving logs")
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                    stage('ios-library') {
                        agent {
                            label OSX_LABEL()
                        }
                        when {
                            expression { 
                                log("When ios-library - shouldRebuildForRelease()? ${shouldRebuildForRelease()}")
                                return shouldRebuildForRelease() 
                            }
                            beforeAgent true
                            expression { 
                                log("When ios-library - pipelineParams.iosProjects != null? ${pipelineParams.iosProjects != null}")
                                return pipelineParams.iosProjects != null 
                            }
                        }
                        steps {
                            script {
                                log("Stage ios-library - start")
                                env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                                env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                                env.OS_TYPE              = OSX_OS_TYPE()
                                log("ios-library - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                                withEnv(environmentFile.getMacOsx("osxbuilduser")) {
                                    log("ios-library - cleanWs()")
                                    cleanWs()
                                    log("ios-library - applyToAllProjects(iosProjects, buildIosLib, false)")
                                    applyToAllProjects(pipelineParams.iosProjects, this.&buildIosLib, false)
                                }
                                log("Stage ios-library - end")
                            }
                        }
                        post {
                            always {
                                log("Post ios-library - archiving logs")
                                archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                            }
                        }
                    }
                }
                post {
                    always {
                        log("Post build-native-libs - archiving logs")
                        archiveArtifacts artifacts: '**/*.log', allowEmptyArchive: true
                    }
                }
            }
            stage('build-unity-plugin') {
                agent {
                    label WINDOWS_LABEL()
                }
                when {
                    expression { 
                        log("When build-unity-plugin - shouldRebuildForRelease()? ${shouldRebuildForRelease()}")
                        return shouldRebuildForRelease() 
                    }
                    beforeAgent true
                    expression { 
                        log("When build-unity-plugin - pipelineParams.unityPluginProject != null? ${pipelineParams.unityPluginProject != null}")
                        return pipelineParams.unityPluginProject != null 
                    }
                }
                steps {
                    script {
                        log("Stage build-unity-plugin - start")
                        env.ARTIFACTORY_USER     = getArtifactoryUser(pipelineParams.vaultArtifactoryTeam)
                        env.ARTIFACTORY_PASSWORD = getArtifactoryPassword(pipelineParams.vaultArtifactoryTeam)
                        env.OS_TYPE              = WINDOWS_OS_TYPE()
                        log("build-unity-plugin - env set ARTIFACTORY_USER and OS_TYPE=${env.OS_TYPE}")

                        withEnv(environmentFile.getWindows("jenkins")) {
                            log("build-unity-plugin - buildPlugin()")
                            buildPlugin(pipelineParams.unityPluginProject, false)
                            log("build-unity-plugin - publishPlugin()")
                            publishPlugin(pipelineParams.unityPluginProject, false)
                        }
                        log("Stage build-unity-plugin - end")
                    }
                }
                post {
                    always {
                        log("Post build-unity-plugin - archiving logs and test results")
                        archiveArtifacts artifacts: '**/*.log, **/test*Unity*.xml, **/playTest*Unity*.xml', allowEmptyArchive: true
                    }
                    success {
                        log("Post build-unity-plugin - archiving zip artifacts")
                        archiveArtifacts '**/*.zip'
                    }
                }
            }
        }
    }
    log("call() - pipeline definition end")
}

String EXECUTOR_LABEL() { 
    log("EXECUTOR_LABEL() called")
    return "alabs-mas-build-executor" 
}
String WINDOWS_LABEL()  { 
    log("WINDOWS_LABEL() called")
    return "alabs-mas-windows" 
}
String LINUX_LABEL()    { 
    log("LINUX_LABEL() called")
    return "alabs-mas-linux" 
}
String OSX_LABEL()      { 
    log("OSX_LABEL() called")
    return "coretech-osx" 
}

String WINDOWS_OS_TYPE() { 
    log("WINDOWS_OS_TYPE() called")
    return "Windows" 
}
String LINUX_OS_TYPE()   { 
    log("LINUX_OS_TYPE() called")
    return "Linux" 
}
String OSX_OS_TYPE()     { 
    log("OSX_OS_TYPE() called")
    return "Osx" 
}

def shouldRebuildForRelease() {
    log("shouldRebuildForRelease() - BRANCH_NAME=${env.BRANCH_NAME}")
    def masterBranch = 'master'
    def backportBranch = 'backport'
    def result = env.BRANCH_NAME == masterBranch || env.BRANCH_NAME.startsWith(backportBranch)
    log("shouldRebuildForRelease() - result=${result}")
    return result
}

def shouldCheckSnapshots() {
    println "should_check_snapshots = ${params.should_check_snapshots}"
    log("shouldCheckSnapshots() - should_check_snapshots=${params.should_check_snapshots}")
    if (!params.should_check_snapshots) {
        log("shouldCheckSnapshots() - returning false due to param")
        return false
    }
    def result = shouldRebuildForRelease()
    log("shouldCheckSnapshots() - result=${result}")
    return result
}

def processParams(body) {
    log("processParams() - start")
    def params = createDefaultPipelineParams()
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()
    log("processParams() - params after body() call: ${params}")
    return params
}

def createDefaultPipelineParams() {
    log("createDefaultPipelineParams() - start")
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
    log("createDefaultPipelineParams() - end with paramMap=${paramMap}")
    return paramMap
}

def convertToList(def argument) {
    log("convertToList() - argument=${argument}")
    def result = (argument instanceof List) ? argument : [argument]
    log("convertToList() - result=${result}")
    return result
}

void applyToAllProjects(def projects, Closure closure, boolean isStaging) {
    log("applyToAllProjects() - projects=${projects}, isStaging=${isStaging}")
    convertToList(projects).each { project ->
        log("applyToAllProjects() - processing project=${project}")
        closure(project, isStaging)
    }
}

def executeCommand(String command) {
    def os = isUnix() ? "unix" : "windows"
    log("executeCommand() - OS=${os}, command=${command}")
    try {
        if (isUnix()) {
            def result = sh(script: """#!/bin/bash -l
          ${command}
        """, returnStdout: true)?.trim()
            log("executeCommand() - command completed, result length=${result?.length()}")
            println("[${os}]: " + command)
            return result
        } else {
            def result = powershell(script: command, returnStdout: true)?.trim()
            log("executeCommand() - command completed, result length=${result?.length()}")
            println("[${os}]: " + command)
            return result
        }
    } catch (err) {
        log("executeCommand() - ERROR running command='${command}', error=${err}")
        throw err
    }
}

String getArtifactoryUser(String vaultArtifactoryTeam) {
    log("getArtifactoryUser() - vaultArtifactoryTeam=${vaultArtifactoryTeam}")
    def user = "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "username")}"
    println "${user} - getArtifactoryUser"
    log("getArtifactoryUser() - got user")
    return user
}

String getArtifactoryPassword(String vaultArtifactoryTeam) {
    log("getArtifactoryPassword() - vaultArtifactoryTeam=${vaultArtifactoryTeam}")
    def pwd = "${secretManager.getSecretFromOnePassword("CIJenkinsSecrets", "ci--jfrog", "password")}"
    println "${pwd} - getArtifactoryPassword"
    log("getArtifactoryPassword() - got password")
    return pwd
}

def checkIfUsingSnapshotDependencies() {
    log("checkIfUsingSnapshotDependencies() - start")
    gitClone("plugin", pipelineParams.scmUrl)
    stage ('check-snapshots') {
        log("checkIfUsingSnapshotDependencies() - inside stage check-snapshots")
        println "Checking process - Start"
        gradleExec("checkIfUsingSnapshots -Pstaging=${false}")
        println "Checking process - Finish"
    }
    log("checkIfUsingSnapshotDependencies() - end")
}

def buildAar(HashMap project, boolean isStaging) {
    log("buildAar() - start, project=${project}, isStaging=${isStaging}")
    def projectName = project.name

    gitClone(projectName, pipelineParams.scmUrl)

    if (project.runTests) {
        stage("${projectName}-test") {
            log("buildAar() - ${projectName}-test")
            gradleExec("${projectName}:test")
        }
        stage("${projectName}-testReport") {
            log("buildAar() - ${projectName}-testReport")
            gradleExec("${projectName}:testReport")
        }
        stage("${projectName}-junit") {
            log("buildAar() - ${projectName}-junit junit()")
            junit '**/testResults*.xml'
        }
    } else {
        log("buildAar() - ${projectName} tests - SKIPPED")
        println "${projectName} tests - SKIPPED"
    }
    stage("${projectName}-assemble") {
        log("buildAar() - ${projectName}-assemble, staging=${isStaging}")
        gradleExec("${projectName}:assemble -Pstaging=${isStaging}")
    }
    stage("${projectName}-publish") {
        log("buildAar() - ${projectName}-publish, staging=${isStaging}")
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    if (project.noArtifacts) {
        log("buildAar() - ${projectName} noArtifacts=true, skipping attach")
        println "SKIPPED - build has no artifacts"
    } else {
        stage("${projectName}-attach-artifacts") {
            log("buildAar() - ${projectName}-attach-artifacts archiving AAR")
            archiveArtifacts '**/*release*.aar'
        }
    }
    log("buildAar() - end for project=${projectName}")
}

def buildIosLib(HashMap project, boolean isStaging) {
    log("buildIosLib() - start, project=${project}, isStaging=${isStaging}")
    def projectName = project.name
    def scheme = project.scheme ?: ''

    gitClone(projectName, pipelineParams.scmUrl)

    if (project.runTests) {
        stage("${projectName}-test") {
            log("buildIosLib() - ${projectName}-test, scheme=${scheme}")
            gradleExec("${projectName}:xctest -Pscheme=${scheme}")
            def xmlFileName = "testResults.xml"
            generateTestResultXml("testResults.xcresult", xmlFileName, scheme)
            junit "${xmlFileName}"
            archiveArtifacts artifacts: "${xmlFileName}", allowEmptyArchive: true
        }
    } else {
        log("buildIosLib() - ${projectName} tests - SKIPPED")
        println "${projectName} tests - SKIPPED"
    }
    stage("${projectName}-build") {
        log("buildIosLib() - ${projectName}-build, staging=${isStaging}, scheme=${scheme}")
        gradleExec("${projectName}:xcbuild -Pstaging=${isStaging} -Pscheme=${scheme}")
    }
    stage("${projectName}-publish") {
        log("buildIosLib() - ${projectName}-publish, staging=${isStaging}")
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    if (project.noArtifacts) {
        log("buildIosLib() - ${projectName} noArtifacts=true, skipping attach")
        println "SKIPPED - build has no artifacts"
    } else {
        stage("${projectName}-attach-artifacts") {
            log("buildIosLib() - ${projectName}-attach-artifacts archiving .a files")
            archiveArtifacts artifacts: '**/*.a', allowEmptyArchive: true
        }
    }
    log("buildIosLib() - end for project=${projectName}")
}

def buildDotNetSolution(HashMap project, boolean isStaging) {
    log("buildDotNetSolution() - start, project=${project}, isStaging=${isStaging}")
    def projectName = project.name

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-build") {
        log("buildDotNetSolution() - ${projectName}-build")
        gradleExec("${projectName}:dotnetBuild -Pstaging=${isStaging}")
    }
    if (project.runTests) {
        stage("${projectName}-test") {
            log("buildDotNetSolution() - ${projectName}-test")
            gradleExec("${projectName}:dotnetTest -Pstaging=${isStaging}")
            log("buildDotNetSolution() - ${projectName}-test nunit()")
            nunit testResultsPattern: '**/test*Dotnet*.xml'
        }
    } else {
        log("buildDotNetSolution() - ${projectName} dotNet solution tests - SKIPPED")
        println "${projectName} dotNet solution tests - SKIPPED"
    }
    stage("${projectName}-publish") {
        log("buildDotNetSolution() - ${projectName}-publish")
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    log("buildDotNetSolution() - end for project=${projectName}")
}

void buildPlugin(HashMap project, boolean isStaging) {
    log("buildPlugin() - start, project=${project}, isStaging=${isStaging}")
    def projectName = project.name
    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-installDependencies") {
        log("buildPlugin() - ${projectName}-installDependencies, staging=${isStaging}")
        gradleExec("${projectName}:installDependencies -Pstaging=${isStaging} --refresh-dependencies")
    }
    if (project.runEditModeTests) {
        stage("${projectName}-editModeUnityTests") {
            log("buildPlugin() - ${projectName}-editModeUnityTests")
            runUnityTaskOnProject(projectName, "editModeUnityTest", "-Pstaging=true")
            nunit testResultsPattern: '**/test*Unity*.xml'
        }
    } else {
        log("buildPlugin() - ${projectName} editmode tests - SKIPPED")
        println "${projectName} editmode tests - SKIPPED"
    }
    if (project.runPlayModeTests) {
        stage("${projectName}-playModeUnityTests") {
            log("buildPlugin() - ${projectName}-playModeUnityTests")
            runUnityTaskOnProject(projectName, "playModeUnityTest", "-Pstaging=true")
            nunit testResultsPattern: '**/playTest*Unity*.xml'
        }
    } else {
        log("buildPlugin() - ${projectName} playmode tests - SKIPPED")
        println "${projectName} playmode tests - SKIPPED"
    }
    if (pipelineParams.enableTnTVersioning) {
        stage("${projectName}-TntVersioning") {
            log("buildPlugin() - ${projectName}-TntVersioning")
            gradleExec("${projectName}:incrementVersion")
        }
    }
    stage("${projectName}-generateDependencies") {
        log("buildPlugin() - ${projectName}-generateDependencies")
        gradleExec("${projectName}:generateDependencies -Pstaging=${isStaging}")
    }
    log("buildPlugin() - end for project=${projectName}")
}

void publishPlugin(HashMap project, boolean isStaging) {
    log("publishPlugin() - start, project=${project}, isStaging=${isStaging}")
    def projectName = project.name

    stage("${projectName}-publish") {
        log("publishPlugin() - ${projectName}-publish, staging=${isStaging}")
        gradleExec("${projectName}:publish -Pstaging=${isStaging}")
    }
    log("publishPlugin() - end for project=${projectName}")
}

void buildGooglePlaySampleApp(HashMap project) {
    log("buildGooglePlaySampleApp() - start, project=${project}")
    println "should_deploy_sample_app = ${params.should_deploy_sample_app}"
    def projectName = project.name
    buildAndroidSampleApp(project)
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployGooglePlayBuild") {
            log("buildGooglePlaySampleApp() - ${projectName}-deployGooglePlayBuild")
            gradleExec("${projectName}:deployAndroidBuild")
        }
    } else {
        log("buildGooglePlaySampleApp() - deployment not enabled for ${projectName}")
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
    log("buildGooglePlaySampleApp() - end for project=${projectName}")
}

void buildAmazonSampleApp(HashMap project) {
    log("buildAmazonSampleApp() - start, project=${project}")
    println "should_deploy_sample_app = ${params.should_deploy_sample_app}"
    def projectName = project.name
    buildAndroidSampleApp(project)
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployAmazonBuild") {
            log("buildAmazonSampleApp() - ${projectName}-deployAmazonBuild")
            gradleExec("${projectName}:deployAmazonBuild")
        }
    } else {
        log("buildAmazonSampleApp() - deployment not enabled for ${projectName}")
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
    log("buildAmazonSampleApp() - end for project=${projectName}")
}

void buildAndroidSampleApp(HashMap project) {
    log("buildAndroidSampleApp() - start, project=${project}")
    def projectName = project.name
    def task = project.task
    def artifactPath = project.artifactPath

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-run-${task}") {
        log("buildAndroidSampleApp() - ${projectName}-run-${task}")
        runUnityTaskOnProject(projectName, task, "-Pstaging=true")
    }
    if (artifactPath?.trim()) {
        stage("${projectName}-attach-artifacts") {
            log("buildAndroidSampleApp() - ${projectName}-attach-artifacts path=${artifactPath}")
            archiveArtifacts "${artifactPath}"
        }
    } else {
        log("buildAndroidSampleApp() - no artifact path defined for ${projectName}")
        println "No artifact path defined for ${projectName}. SKIPPED"
    }
    log("buildAndroidSampleApp() - end for project=${projectName}")
}

void buildSampleAppXcodeProject(HashMap project) {
    log("buildSampleAppXcodeProject() - start, project=${project}")
    def projectName = project.name
    def unityTask = project.unityTask

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-${unityTask}") {
        log("buildSampleAppXcodeProject() - ${projectName}-${unityTask}")
        runUnityTaskOnProject(projectName, unityTask, "-Pstaging=true")
    }
    stage("${projectName}-zipXcodeProject") {
        log("buildSampleAppXcodeProject() - ${projectName}-zipXcodeProject")
        gradleExec("${projectName}:zipXcodeProject")
    }
    stage("${projectName}-syncXcodeProjectToCloudStorage") {
        log("buildSampleAppXcodeProject() - ${projectName}-syncXcodeProjectToCloudStorage")
        gradleExec("${projectName}:syncXcodeProjectToCloudStorage")
    }
    log("buildSampleAppXcodeProject() - end for project=${projectName}")
}

void runUnityTaskOnProject(String projectName, String task, String params = "") {
    log("runUnityTaskOnProject() - start, projectName=${projectName}, task=${task}, params=${params}")
    try {
        log("runUnityTaskOnProject() - ${projectName}:getLicense")
        gradleExec("${projectName}:getLicense")
        log("runUnityTaskOnProject() - ${projectName}:${task} ${params}")
        gradleExec("${projectName}:${task} ${params}")
    } finally {
        log("runUnityTaskOnProject() - finally revokeLicense for projectName=${projectName}")
        withEnv(environmentFile.getWindows("jenkins")) {
            gradleExec("${projectName}:revokeLicense --info")
        }
    }
    log("runUnityTaskOnProject() - end, projectName=${projectName}")
}

void buildSampleAppIpa(HashMap project) {
    log("buildSampleAppIpa() - start, project=${project}")
    def projectName = project.name
    def artifactPath = project.artifactPath
    def xcodeTask = project.xcodeTask

    gitClone(projectName, pipelineParams.scmUrl)
    stage("${projectName}-syncXcodeProjectFromCloudStorage") {
        log("buildSampleAppIpa() - ${projectName}-syncXcodeProjectFromCloudStorage")
        gradleExec("${projectName}:syncXcodeProjectFromCloudStorage")
    }
    stage("${projectName}-unzipXcodeProject") {
        log("buildSampleAppIpa() - ${projectName}-unzipXcodeProject")
        gradleExec("${projectName}:unzipXcodeProject")
    }
    stage("${projectName}-${xcodeTask}") {
        log("buildSampleAppIpa() - ${projectName}-${xcodeTask}")
        gradleExec("${projectName}:${xcodeTask}")
    }
    if (artifactPath?.trim()) {
        stage("${projectName}-attach-artifacts") {
            log("buildSampleAppIpa() - ${projectName}-attach-artifacts path=${artifactPath}")
            archiveArtifacts "${artifactPath}"
        }
    } else {
        log("buildSampleAppIpa() - no artifact path defined for ${projectName}")
        println "No artifact path defined for ${projectName}. SKIPPED"
    }
    if (params.should_deploy_sample_app) {
        stage("${projectName}-deployIosBuild") {
            log("buildSampleAppIpa() - ${projectName}-deployIosBuild")
            gradleExec("${projectName}:deployIosBuild")
        }
    } else {
        log("buildSampleAppIpa() - deployment is not enabled for ${projectName}")
        println "Deployment is not enabled for ${projectName}. SKIPPED"
    }
    log("buildSampleAppIpa() - end for project=${projectName}")
}

void gitClone(String projectName, String scmUrl) {
    log("gitClone() - start, projectName=${projectName}, scmUrl=${scmUrl}")
    cleanWs()
    
    def osType = env.OS_TYPE ? env.OS_TYPE : OSX_OS_TYPE()
    log("gitClone() - OS_TYPE for gitClone=${osType}")
    if (osType.equals(WINDOWS_OS_TYPE()) || osType.equals(LINUX_OS_TYPE())) {
        stage("${projectName}-instance-ready-check") {
            println "Wait For Instance To Become Ready. OS Type: ${osType}"
            log("gitClone() - instance-ready-check, OS Type: ${osType}")
            def commonScripts = new CommonScripts(this)
            commonScripts.waitForInstanceToBecomeReady()
            println "Instance is ready."
            log("gitClone() - instance is ready")
        }
    } else {
        println "Don't need to wait for Instance to be ready. OS Type: ${osType}"
        log("gitClone() - no wait for instance, OS Type: ${osType}")
    }

    stage("${projectName}-checkout") {
        def cmd = "git clone ${scmUrl} . --branch ${env.BRANCH_NAME}"
        log("gitClone() - ${projectName}-checkout running: ${cmd}")
        executeCommand(cmd)
    }
    log("gitClone() - end for projectName=${projectName}")
}

void gradleExec(String command) {
    log("gradleExec() - start, command=${command}")
    def fullCommand = "${pipelineParams.gradlewPath}/gradlew ${command} --stacktrace"
    log("gradleExec() - full command: ${fullCommand}")
    println(executeCommand(fullCommand))
    log("gradleExec() - end, command=${command}")
}

void generateTestResultXml(String xcResultFileName, String xmlFileName, String scheme) {
    log("generateTestResultXml() - start, xcResultFileName=${xcResultFileName}, xmlFileName=${xmlFileName}, scheme=${scheme}")
    String fileShouldBeCreatedMaxMinutesAgo = "3"
    String findCommand = "find ~/Library/Developer/Xcode/DerivedData -name \"Run-${scheme}*.xcresult\" -mmin -${fileShouldBeCreatedMaxMinutesAgo} | sort -Vr | head -1"
    log("generateTestResultXml() - executing find command: ${findCommand}")
    String filePath = executeCommand(findCommand)
    log("generateTestResultXml() - filePath=${filePath}")
    if (filePath == null) {
        println("error: xcresult was not found")
        log("generateTestResultXml() - error: xcresult was not found")
        return
    }
    executeCommand("cp -r ${filePath} ./${xcResultFileName}")
    log("generateTestResultXml() - copied xcresult to ./ ${xcResultFileName}")
    println(executeCommand("trainer -p ./ -o ./ -f ${xmlFileName}"))
    log("generateTestResultXml() - trainer executed producing ${xmlFileName}")
    executeCommand("rm -rf ${xcResultFileName}")
    log("generateTestResultXml() - removed xcResultFileName=${xcResultFileName}")
    log("generateTestResultXml() - end")
}
