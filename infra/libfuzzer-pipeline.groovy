// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def project = new groovy.json.JsonSlurperClassic().parseText(config["project_json"])

    // Project configuration.
    def projectName = project["name"] ?: env.JOB_BASE_NAME
    def sanitizers = project["sanitizers"] ?: ["address"]
    def coverageFlags = project["coverage_flags"]

    // Dockerfile config
    def dockerfileConfig = project["dockerfile"] ?: [
        "path": "projects/$projectName/Dockerfile",
        "git" : "https://github.com/google/oss-fuzz.git",
        "context" : "projects/$projectName/"
    ]
    def dockerfile = dockerfileConfig["path"]
    def dockerGit = dockerfileConfig["git"]
    def dockerContextDir = dockerfileConfig["context"] ?: ""
    def dockerTag = "ossfuzz/$projectName"
    def dockerUid = 0 // TODO: try to make $USER to work
    def dockerRunOptions = "--user $dockerUid --cap-add SYS_PTRACE"
    
    def date = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        .format(java.time.LocalDateTime.now())

    node {
        def workspace = pwd()
        // def uid = sh(returnStdout: true, script: 'id -u $USER').trim()
        echo "using uid $dockerUid"

        def srcmapFile = "$workspace/srcmap.json"
        echo "Building $dockerTag: $project"

        sh "docker run --rm $dockerRunOptions -v $workspace:/workspace ubuntu bash -c \"rm -rf /workspace/out\""
        sh "mkdir -p $workspace/out"

        stage("docker image") {
            def dockerfileRev

            dir('checkout') {
                git url: dockerGit
                dockerfileRev = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            }

            sh "docker build --no-cache -t $dockerTag -f checkout/$dockerfile checkout/$dockerContextDir"

            // obtain srcmap
            sh "docker run $dockerRunOptions --rm $dockerTag srcmap > $workspace/srcmap.json.tmp"
            // use classic slurper: http://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
            def srcmap = new groovy.json.JsonSlurperClassic().parse(
                new File("$workspace/srcmap.json.tmp"))
            srcmap['/src'] = [ type: 'git',
                               rev:  dockerfileRev,
                               url:  dockerGit,
                               path: "/" + dockerContextDir ]
            echo "srcmap: $srcmap"
            writeFile file: srcmapFile, text: groovy.json.JsonOutput.toJson(srcmap)
        } // stage("docker image")

        for (int i = 0; i < sanitizers.size(); i++) {
            def sanitizer = sanitizers[i]
            dir(sanitizer) {
                def out = "$workspace/out/$sanitizer"
                def junit_reports = "$workspace/junit_reports/$sanitizer"
                sh "mkdir -p $out"
                sh "mkdir -p $junit_reports"
                stage("$sanitizer sanitizer") {
                    // Run image to produce fuzzers
                    def env = "-e SANITIZER=\"${sanitizer}\" "
                    if (coverageFlags != null) {
                        env += "-e COVERAGE_FLAGS=\"${coverageFlags}\" "
                    }
                    sh "docker run --rm $dockerRunOptions -v $out:/out $env -t $dockerTag compile"
                    // Test all fuzzers
                    sh "docker run --rm $dockerRunOptions -v $out:/out -v $junit_reports:/junit_reports -e TEST_SUITE=\"${projectName}.${sanitizer}.\" -t ossfuzz/base-runner test_report"
                }
            }
        }

        stage("uploading") {
            step([$class: 'JUnitResultArchiver', testResults: 'junit_reports/**/*.xml'])
            dir('out') {
                for (int i = 0; i < sanitizers.size(); i++) {
                    def sanitizer = sanitizers[i]
                    dir (sanitizer) {
                        def zipFile = "$projectName-$sanitizer-${date}.zip"
                        sh "zip -j $zipFile *"
                        sh "gsutil cp $zipFile gs://clusterfuzz-builds/$projectName/"
                        def stampedSrcmap = "$projectName-$sanitizer-${date}.srcmap.json"
                        sh "cp $srcmapFile $stampedSrcmap"
                        sh "gsutil cp $stampedSrcmap gs://clusterfuzz-builds/$projectName/"
                    }
                }
            }
        } // stage("uploading")

        stage("pushing image") {
            docker.withRegistry('', 'docker-login') {
                docker.image(dockerTag).push()
            }
        } // stage("pushing image")
    } // node
}  // call

return this;
