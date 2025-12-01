@Library('plugins-jenkins-pipeline@6.1.0-RED-606-SNAPSHOT') _
@Library('ci-client-pipeline-library@0.11.0') ciPipelineLibrary

pluginsPipeline {
  scmUrl = 'git@github.com:ProductMadness/madkit-events-dotnet.git'
  dotNetProjects = [
    [
      'name' : 'dotnet-sources',
      'runTests': true
    ]
  ]
  slackChannel = 'app-core-builds'
}