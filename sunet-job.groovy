// vim: ts=4 sts=4 sw=4 et
import java.io.IOException

// Used for merging .jenkins.yaml in to default env
def addNested(lhs, rhs) {
    rhs.each { k, v -> lhs[k] = lhs[k] in Map ? addNested(lhs[k], v) : v }
    return lhs
}

def _is_disabled(env) {
    return env.disabled.toBoolean();
}

def _build_in_docker(env) {
    if (_get_bool(env.build_in_docker.disable, false)) {
        echo("${env.full_name} not building in docker. build_in_docker.disable: ${env.build_in_docker.disable}")
        return false
    } else if (env.builders == ["docker"]) {
        echo("${env.full_name} not building in docker. \"docker\" is the only item in builders: ${env.builders}")
        return false
    } else if (env.build_in_docker.image == null && env.build_in_docker.dockerfile == null) {
        echo("${env.full_name} not building in docker. build_in_docker.mage: ${env.build_in_docker.image} and build_in_docker.dockerfile: ${env.build_in_docker.dockerfile}")
        return false
    }
    return true
}

def _slack_enabled(env) {
    if (env.slack.room != null || _get_bool(env.slack.disabled, false)) {
        return true
    }
    return false
}

def _managed_script_enabled(env, script_name) {
    if (env.managed_scripts != null && script_name in env.managed_scripts) {
        return true
    }
    return false
}

def _get_bool(value, default_value) {
    if (value != null) {
        return value.toBoolean()
    }
    return default_value.toBoolean()
}

def _get_int(value, default_value) {
    if (value != null) {
        return value.toInteger()
    }
    return default_value.toInteger()
}

// groovy.lang.MapWithDefault NotSerializable , so use it only in a helper function
@NonCPS
def repo_must_be_signed(full_name) {
    def repo_must_be_signed = [:].withDefault { false }
    repo_must_be_signed["SUNET/docker-jenkins"] = true
    repo_must_be_signed["SUNET/docker-jenkins-job"] = true

    return repo_must_be_signed[full_name]
}

def load_env() {
    node {
        stage("load_env") {
            def args = [
                    $class                           : 'GitSCM',
                    userRemoteConfigs                : [[url: "https://github.com/${FULL_NAME}.git"]],
                    branches                         : [[name: "*/${DEFAULT_BRANCH}"]],
                    extensions                       : [
                            [$class: 'CheckoutOption'],
                            [$class   : 'CloneOption',
                             depth    : 1,
                             noTags   : true,
                             reference: '',
                             shallow  : true
                            ],
                            // FIXME: Is this worth it? to keep these in sync?
                            [$class: 'SparseCheckoutPaths', sparseCheckoutPaths: [
                                    [path: '.jenkins.yaml'],
                                    [path: 'Dockerfile'],
                                    [path: 'setup.py'],
                                    [path: 'CMakeLists.txt'],
                                    [path: 'Dockerfile.jenkins'],
                                    [path: 'Jenkinsfile']
                            ]
                            ]
                    ],
                    doGenerateSubmoduleConfigurations: false,
                    submoduleCfg                     : [],
            ]
            try {
                checkout(changelog: false, poll: false, scm: args)
            } catch (hudson.plugins.git.GitException ex) {
                // hudson.plugins.git.GitException: Command "git checkout -f 5306295ed1f2ca866cd108516aaedb3b2f055e43" returned status code 128:
                // stderr: error: Sparse checkout leaves no entry on working directory

                // No config found, nor no things to guess config from.
                // We let things have its path, and we will return NOT_BUILT a tad later
            }

            // Default environment
            def env = [
                    'name'                   : JOB_BASE_NAME,
                    'full_name'              : FULL_NAME.toLowerCase(),
                    'repo_full_name'         : FULL_NAME, // Jenkins is not case insensitive with push notifications
                    'repo_must_be_signed'    : repo_must_be_signed(FULL_NAME),
                    'disabled'               : false,
                    'git'                    : [:],
                    'default_branch'         : DEFAULT_BRANCH,
                    'environment_variables'  : [:],
                    'python_source_directory': 'src',
                    'slack'                  : ['room': 'devops-builds', 'disabled': false],
                    'triggers'               : [:],
                    'builders'               : [],
                    'docker_name'            : FULL_NAME.toLowerCase(),
                    'build_in_docker'        : [
                            'disabled'     : false,
                            'dockerfile'   : null,
                            'image'        : null,
                            'start_command': "/run.sh"
                    ]
            ]

            def signature_verified = false

            // Check signature in git before reading from it.
            if (repo_must_be_signed(FULL_NAME)) {
                echo "Verifying signature before reading anything from the repo"
                configFileProvider([configFile(fileId: 'GPG_WRAPPER', variable: 'GPG_WRAPPER')]) {
                    withCredentials([file(credentialsId: 'GNUPG_KEYRING', variable: 'GNUPG_KEYRING')]) {
                        sh('chmod +x "$GPG_WRAPPER" && git -c "gpg.program=$GPG_WRAPPER" verify-commit HEAD')
                        signature_verified = true
                    }
                }
            }

            if (fileExists('Jenkinsfile')) {
                if (signature_verified) {
                    // Only load Jenkinsfile's from verified repos
                    load 'Jenkinsfile'
                } else {
                    echo "Repo not verified. Add it to repo_must_be_signed"
                    currentBuild.result = "ABORTED"
                }
                return 'Jenkinsfile'
            }

            // Load enviroment variables from repo yaml file
            try {
                def yaml_text = readFile(".jenkins.yaml")
                // Mangle broken yaml into something a propper yaml parser stands
                def fixed_yaml_text = yaml_text.replaceAll('cron: (@\\w+)', 'cron: "$1"')
                if (yaml_text != fixed_yaml_text)
                    echo("FIXME: This repo contains non compliant yaml")
                def repo_env = readYaml(text: fixed_yaml_text)
                env = addNested(env, repo_env)
            } catch (IOException ex) {
                echo("No .jenkins.yaml for ${env.full_name}... will use defaults")
            }

            // detecting builders
            if (env.builder != null && env.builders.size() == 0) {
                echo("DEPRECATION WARNING. Use builders.")
                echo("Builder ${env.builder} found for ${env.full_name}, added to builders")
                env.builders += env.builder
            }

            // If builder or builders is empty try to guess
            if (env.builders == null || env.builders.size() == 0) {
                echo("No builders found for ${env.full_name}... trying to guess")
                env.builders = []

                if (fileExists("Dockerfile")) {
                    if (readFile("Dockerfile").contains("FROM")) {
                        echo("Found Dockerfile for ${env.full_name}. Adding \"docker\" to builders.")
                        env.builders += "docker"
                    } else {
                        echo("FIXME: This repo contains a Dockerfile without a FROM!")
                    }
                }

                if (fileExists("setup.py")) {
                    echo("Found setup.py for ${env.full_name}. Adding \"python\" to builders.")
                    env.builders += "python"
                }

                if (env.script != null) {
                    echo("script set for ${env.full_name}. Adding \"script\" to builders.")
                    env.builders += "script"
                }

                if (fileExists("CMakeLists.txt")) {
                    echo("Found CMakeLists.txt for ${env.full_name}. Adding \"cmake\" to builders.")
                    env.builders += "cmake"
                }
            }

            // detecting wrappers
            if (fileExists("Dockerfile.jenkins")) {
                echo("Found Dockerfile.jenkins for ${env.full_name}. Will be used for build.")
                env.build_in_docker.dockerfile = "Dockerfile.jenkins"
            }

            if (env.build_in_docker.dockerfile == null && env.build_in_docker.image == null) {
                echo("No explicit build in docker settings found for ${env.full_name}. Will use docker.sunet.se/sunet/docker-jenkins-job.")
                env.build_in_docker.image = "docker.sunet.se/sunet/docker-jenkins-job"
            } else {
                if (env.build_in_docker.dockerfile != null) {
                    echo("Using dockerfile ${env.build_in_docker.dockerfile} to build ${env.full_name}.")
                } else {
                    echo("Using image ${env.build_in_docker.image} to build ${env.full_name}.")
                }
            }

            // Run the extra-job bits if You're one of those
            if (env.extra_jobs != null) {
                for (def job in env.extra_jobs) {
                    if (job.name == JOB_BASE_NAME) {
                        echo "I'm a extra-job"
                        // Merge everything in the extra job over the current job
                        env << job
                        // And remove the extra_jobs bit, becase we're the extra job here,
                        // And we shouldn't generate ourselfs.
                        env.remove("extra_jobs")
                        break;
                    }
                }
            }

            // We need another if block, because env might have bin modified
            if (env.extra_jobs != null) {
                // Generate our extra_jobs by running some job-dsl
                stage("extra_jobs") {
                    // Write extra_jobs structure as json, so we can read it in job-dsl
                    // This way we don't need to pass variables as strings and so on.
                    writeJSON(file: "extra_jobs.json", json: env.extra_jobs)
                    // Provision the pipeline groovy
                    configFileProvider([configFile(fileId: 'sunet-job.groovy', targetLocation: 'sunet-job.groovy')]) {
                        jobDsl(
                                failOnMissingPlugin: true,
                                failOnSeedCollision: true,
                                lookupStrategy: 'SEED_JOB',
                                removedConfigFilesAction: 'DELETE',
                                removedJobAction: 'DELETE',
                                removedViewAction: 'DELETE',
                                unstableOnDeprecation: true,
                                scriptText: """
import groovy.json.JsonSlurper
import jenkins.model.Jenkins
def extra_jobs = new JsonSlurper().parseText(readFileFromWorkspace("extra_jobs.json"))

for (job in extra_jobs) {
    // Keep this in sync between github_docker_repos.groovy and sunet-job.groovy
    // We need this magic dance so job-dsl doesn't overwrite
    // any triggers or other properties created in pipeline
    def existing_job = Jenkins.instance.getItem(job.name)
    def pipeline_job = pipelineJob(job.name)

    // Copy over anything we have generated
    if (existing_job) {
        // Get the existing job xml
        def xml = existing_job.getConfigFile().asString()
        // Parse it in groovy
        def existing_job_conf = new XmlParser().parseText(xml)
        // And inject the existing properties into the new job
        pipeline_job.with {
            configure { project ->
                project.div(existing_job_conf.properties)
            }
        }
    }

    pipeline_job.with {
        environmentVariables {
            env("FULL_NAME", "${FULL_NAME}")
            env("DEFAULT_BRANCH", "${DEFAULT_BRANCH}"
            env("DEV_MODE", "${DEV_MODE}")
        }
        definition {
            cps {
                script(readFileFromWorkspace('sunet-job.groovy'))
                sandbox()
            }
        }
    }
}
""",
                        )
                    }
                }
            }

            return env
        }
    }
}

// No def, global
/*
docker_cloud_name = null
for (def cloud : jenkins.model.Jenkins.getInstance().clouds) {
    // job-dsl can't access plugin classes, so just compare it as a string
    // instead of using instanceof
    if (cloud.getClass().toString() == 'class com.nirima.jenkins.plugins.docker.DockerCloud') {
        docker_cloud_name = cloud.name
        break;
    }
}
if (!docker_cloud_name)
    throw new Exception("Can't find a docker cloud to run containers on!")
*/

// load and parse .jenkins.yaml
def job_env = load_env()

if (job_env == 'Jenkinsfile') {
    // load_env() detected and ran a Jenkinsfile
    // end job here with whatever status that might have produced.
    return
}

if (job_env.builders.size() == 0 || _is_disabled(job_env)) {
    echo("No builder for ${job_env.full_name}...")
    currentBuild.result = "NOT_BUILT"
    return
}

echo("running job for ${job_env.full_name} using builders: ${job_env.builders}")

// Rotate builds
def log_rotator = [
        $class        : "LogRotator",
        "numToKeepStr": '10',
]
// Rotate archived artifacts
if (job_env.archive_artifacts != null) {
    log_rotator["artifactNumToKeepStr"] = job_env.archive_artifacts.num_to_keep?.toString() ?: "1"
}
if (env.DEV_MODE?.toBoolean())
    echo "DEV_MODE detected"

def property_list = []
def docker_image = null
if (_build_in_docker(job_env)) {
    if (job_env.build_in_docker.image == "docker.sunet.se/sunet/docker-jenkins-job") {
        echo("Not specifically building in docker, because our image is docker.sunet.se/sunet/docker-jenkins-job")
    } else if (job_env.build_in_docker.image != null) {
        echo("${job_env.full_name} building in docker image ${job_env.build_in_docker.image}")
        docker_image = job_env.build_in_docker.image
    } else if (job_env.build_in_docker.dockerfile != null) {
        echo("${job_env.full_name} building in docker image from Dockerfile ${job_env.build_in_docker.dockerfile}")
        // FIXME!
        // pkcs11-proxy is the only one using this.
        // This can be done in pipeline, but in docker-cloud?
        //dockerfile('.', job_env.build_in_docker.dockerfile)
        echo("Doesn't support Dockerfile yet, so use the regular image for now")
        docker_image = "docker.sunet.se/sunet/docker-jenkins-job"
    }
    /* Buu, doesn't work. Use dockerNode() instead

    We might use patched docker-plugin so we can pass in a whole dockerTemplateBase
    to dockerNode()...
    https://github.com/glance-/docker-plugin/tree/DockerTemplateBase

    The only thing that we might need that for is if we need to run
    docker images from inside another image than our regular
    docker.sunet.se/sunet/docker-jenkins-job

    property_list += [
        $class: "DockerJobTemplateProperty",
        template: [
            pullStrategy: env.DEV_MODE?.toBoolean() ? "PULL_NEVER" : (job_env.build_in_docker.force_pull ? "PULL_ALWAYS" : "PULL_LATEST"),
            // Name the container after what we build in it
            name: "docker-${job_env.full_name}",
            connector: [
                attach: []
            ],
            labelString: '',
            instanceCapStr: '0',
            dockerTemplateBase: [
                $class: 'DockerTemplateBase',
                // Let global limit handle this
                // Enable docker in docker
                volumesString: \
                    '/usr/bin/docker:/usr/bin/docker:ro\n' +
                    '/var/run/docker.sock:/var/run/docker.sock',
                dockerCommand: job_env.build_in_docker.start_command,
                tty: true,
                image: docker_image,
            ]
        ]
    ]*/
}

// github_push is enabled by default
def trigger_list = []
if (_get_bool(job_env.triggers.github_push, true)) {
    echo("${job_env.full_name} using trigger github push")
    trigger_list += githubPush()
}
if (job_env.triggers.cron != null) {
    echo("${job_env.full_name} using trigger cron: ${job_env.triggers.cron}")
    trigger_list += cron(job_env.triggers.cron)
}
if (job_env.upstream != null && job_env.upstream.size() > 0) {
    echo("${job_env.full_name} using trigger upstream: ${job_env.upstream.join(', ')}")
    trigger_list += upstream(job_env.upstream.join(', '))
}

// If we have some triggers, add them to the property-list
if (trigger_list)
    property_list += [pipelineTriggers(trigger_list)]

// We always need to keep FULL_NAME, DEFAULT_BRANCH, and optionally DEV_MODE
property_list += [
        $class                    : 'EnvInjectJobProperty',
        info                      : [
                propertiesContent: "FULL_NAME=${FULL_NAME}\n" + "DEFAULT_BRANCH=${DEFAULT_BRANCH}\n" + (env.DEV_MODE != null ? "DEV_MODE=${DEV_MODE}\n" : "")
        ],
        keepBuildVariables        : true,
        keepJenkinsSystemVariables: true,
        on                        : true,
]

if (job_env.archive_artifacts != null) {
    if (job_env.archive_artifacts.allowed_projects != null) {
        String allowed_projects = job_env.archive_artifacts.allowed_projects.join(",")
        property_list += [copyArtifactPermission(allowed_projects)]
    } else {
        // Allow all projects to copy artifacts as a compatibility measure if archive_artifacts is set but
        // allowed_projects is missing
        property_list += [copyArtifactPermission('*')]
    }
}

properties([
        buildDiscarder(log_rotator),
        [$class: 'GithubProjectProperty', projectUrlStr: "${job_env.full_name}"],
] + property_list)


// This is broken out to a function, so it can be called either via a node() or a dockerNode()
def runJob(job_env) {
    def scmVars
    try {
        // Set the configured enviorment variables
        if (job_env.environment_variables != null) {
            // Set these variables in our current job environment
            echo("Configured environment variables:")
            job_env.environment_variables.each { item -> echo("${item}") }
            // Use groovy magic here to be able to iterate without serialising iterator
            // And use a shell to expand the string, so we don't have variables inside
            // our env-vars because a lot of things doesn't really like that.
            job_env.environment_variables.each { item -> env."${item.key}" = sh(returnStdout: true, script: """echo -n "${item.value}";""") }
        }
        stage("checkout") {
            def args = [
                    $class           : 'GitSCM',
                    userRemoteConfigs: [[url: "https://github.com/${job_env.repo_full_name}.git"]],
                    branches         : [],
                    extensions       : [],
            ]
            // Branch
            if (job_env.git.branch != null) {
                echo("${job_env.full_name} building branch ${job_env.git.branch}")
                args["branches"].add(["name": "*/${job_env.git.branch}"])
            } else if (job_env.git.branches != null) {
                echo("${job_env.full_name} building branches ${job_env.git.branches}")
                for (def branch in job_env.git.branches) {
                    args["branches"].add(["name": "*/${branch}"])
                }
            } else {
                echo("${job_env.full_name} building branch ${job_env.default_branch}")
                args["branches"].add(["name": "*/${job_env.default_branch}"])
            }
            shallow_clone = false
            if (job_env.git.extensions != null) {
                if (job_env.git.extensions.checkout_local_branch != null) {
                    echo("${job_env.full_name} checking out local branch")
                    args["extensions"].add([$class: 'PruneStaleBranch'])
                    args["extensions"].add([$class: 'LocalBranch', localBranch: '**'])
                }
                if (job_env.git.extensions.shallow_clone != null) {
                    shallow_clone = true
                    args["extensions"].add([$class: 'CloneOption', shallow: true])
                }
            }
            scmVars = checkout(args)
            echo('Making scmVars available')
            // The plugin seems borked, set what we need manually
            // https://issues.jenkins-ci.org/browse/JENKINS-45489
            FULL_PATH_BRANCH = "${sh(script:'git name-rev --name-only HEAD', returnStdout: true)}".replace("\n", "")
            COMMIT_SHA1 = "${sh(script: 'git rev-parse HEAD', returnStdout: true)}".replace("\n", "")
            // There is no previout commit to find if a shallow clone is checked out
            if (!shallow_clone) {
                PREVIOUS_COMMIT = "${sh(script: 'git rev-parse HEAD^1', returnStdout: true)}".replace("\n", "")
                scmVars.GIT_PREVIOUS_COMMIT = PREVIOUS_COMMIT
            }
            scmVars.GIT_BRANCH = FULL_PATH_BRANCH
            scmVars.GIT_COMMIT = COMMIT_SHA1
            scmVars.GIT_LOCAL_BRANCH = FULL_PATH_BRANCH.substring(FULL_PATH_BRANCH.lastIndexOf('/') + 1, FULL_PATH_BRANCH.length())
            scmVars.each { item -> env."${item.key}" = item.value }
            echo("GIT_BRANCH: ${GIT_BRANCH}")
            echo("GIT_COMMIT: ${GIT_COMMIT}")
            echo("GIT_PREVIOUS_COMMIT: ${GIT_PREVIOUS_COMMIT}")
            echo("GIT_LOCAL_BRANCH: ${GIT_LOCAL_BRANCH}")

            // ['GIT_BRANCH':'origin/main', 'GIT_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_SUCCESSFUL_COMMIT':'dcea3f3567b7f55bc7a1a2f3d6752c084cc9b694', 'GIT_URL':'https://github.com/glance-/docker-goofys.git']
        }
        if (repo_must_be_signed(FULL_NAME)) {
            // Only build code which we can validate
            stage("Verify signature") {
                configFileProvider([configFile(fileId: 'GPG_WRAPPER', variable: 'GPG_WRAPPER')]) {
                    withCredentials([file(credentialsId: 'GNUPG_KEYRING', variable: 'GNUPG_KEYRING')]) {
                        sh('chmod +x "$GPG_WRAPPER" && git -c "gpg.program=$GPG_WRAPPER" verify-commit HEAD')
                    }
                }
            }
        }
        if (job_env.copy_artifacts != null) {
            stage("Copy artifacts") {
                def args = [
                        projectName         : job_env.copy_artifacts.project_name,
                        selector            : lastSuccessful(),
                        fingerprintArtifacts: true,
                ]
                if (job_env.copy_artifacts.target_dir != null)
                    args["target"] = job_env.copy_artifacts.target_dir
                if (job_env.copy_artifacts.include != null)
                    args["filter"] = job_env.copy_artifacts.include
                if (job_env.copy_artifacts.exclude != null)
                    excludePatterns(job_env.copy_artifacts.exclude.join(', '))
                args["excludes"] = job_env.copy_artifacts.exclude
                if (job_env.copy_artifacts.flatten != null)
                    args["flatten"] = job_env.copy_artifacts.flatten
                if (job_env.copy_artifacts.optional != null)
                    args["optional"] = job_env.copy_artifacts.optional
                copyArtifacts(args)
            }
        }
        if (job_env.pre_build_script != null) {
            stage("Pre build script") {
                sh(job_env.pre_build_script.join('\n'))
            }
        }
        // Mutually exclusive builder steps
        if (job_env.builders.contains("script")) {
            stage("builder script") {
                // This is expected to be run in the same shell,
                // So job_env-modifications carry over between lines in yaml.
                sh(job_env.script.join('\n'))
            }
        } else if (job_env.builders.contains("make")) {
            stage("builder make") {
                sh("make clean && make && make test")
            }
        } else if (job_env.builders.contains("cmake")) {
            stage("builder cmake") {
                sh("/opt/builders/cmake")
            }
        } else if (job_env.builders.contains("python")) {
            stage("builder python") {
                def python_module = job_env.name
                if (job_env.python_module != null) {
                    python_module = job_env.python_module
                }
                sh("/opt/builders/python ${python_module} ${job_env.python_source_directory}")
            }
        }
        if (job_env.builders.contains("docker")) {
            stage("builder docker") {
                if (_managed_script_enabled(job_env, 'docker_build_prep.sh')) {
                    echo("Managed script docker_build_prep.sh enabled.")
                    configFileProvider([configFile(fileId: 'docker_build_prep.sh', variable: 'DOCKER_BUILD_PREP')]) {
                        sh 'chmod +x "$DOCKER_BUILD_PREP" ; "$DOCKER_BUILD_PREP"'
                    }
                }
                tags = ["git-${scmVars.GIT_COMMIT[0..8]}", "ci-${job_env.name}-${BUILD_NUMBER}"]
                if (job_env.docker_tags != null)
                    // Expand docker_tags
                    job_env.docker_tags.each { item ->
                        tag = env.getEnvironment().expand(item)
                        tags.add(tag)
                    }
                echo("Docker tags:")
                tags.each { tag ->
                    echo(tag)
                }

                if (_managed_script_enabled(job_env, 'docker_tag.sh')) {
                    echo("Managed script docker_tag.sh enabled.")
                    echo("Not using docker_tag.sh, having it done by dockerBuildAndPublish instead")
                    // docker_tag is buggy and trying to deterministically find a docker image
                    // based on a git sha. This detonates if it sees other images built on the same sha,
                    // so implement the same functionallity here.
                    // Due to git plugin having some issues we now always have a GIT_LOCAL_BRANCH.
                    tags.add("branch-${scmVars.GIT_LOCAL_BRANCH}")
                }
                if (!_get_bool(job_env.docker_skip_tag_as_latest, false))
                    tags.add("latest")

                // Expand docker_name
                def docker_name = env.getEnvironment().expand(job_env.docker_name)
                def full_names = []
                for (def tag in tags)
                    full_names.add("docker.sunet.se/${docker_name.replace("-/", "/")}:${tag}")
                // docker doesn't like glance-/repo, so mangle it to glance/repo

                echo("Docker image will be pushed as:")
                full_names.each { name ->
                    echo(name)
                }

                def docker_build_and_publish = [
                        $class             : 'DockerBuilderPublisher',
                        dockerFileDirectory: "",
                        tagsString         : full_names.join("\n"),
                        pushOnSuccess      : !env.DEV_MODE?.toBoolean(), // Don't push in dev mode
                        pull               : (env.DEV_MODE?.toBoolean() ? false : _get_bool(job_env.docker_force_pull, true)),
                        noCache            : (_get_bool(job_env.docker_no_cache, true)),
                ]
                if (job_env.docker_context_dir != null)
                    docker_build_and_publish["dockerFileDirectory"] = job_env.docker_context_dir
                /* No corresponding functionallity in docker-plugin
                dockerBuildAndPublish {
                    forceTag(_get_bool(job_env.docker_force_tag, false))
                    forcePull(false)
                    createFingerprints(_get_bool(job_env.docker_create_fingerprints, true))
                }*/
                step(docker_build_and_publish)
            }
        }
        if (job_env.post_build_script != null) {
            stage("Post build script") {
                sh(job_env.post_build_script.join('\n'))
            }
        }
        if (job_env.downstream != null && job_env.downstream.size() > 0) {
            stage("Triggering downstreams") {
                echo("${job_env.full_name} using downstream ${job_env.downstream.join(', ')}")
                for (def downstream in job_env.downstream) {
                    build(job: downstream, wait: false)
                }
            }
        }
        if (job_env.publish_over_ssh != null) {
            stage("Publishing over ssh") {
                for (def target in job_env.publish_over_ssh) {
                    if (target == 'pypi.sunet.se') {
                        if (job_env.builders.contains("python") || job_env.builders.contains("script")) {
                            echo("Publishing over ssh to ${target} enabled.")
                            sshPublisher(publishers: [sshPublisherDesc(
                                    configName: 'pypi.sunet.se',
                                    transfers: [sshTransfer(
                                            removePrefix: 'dist',
                                            sourceFiles: 'dist/*.egg,dist/*.tar.gz,dist/*.whl'
                                    )]
                            )])
                        }
                    } else {
                        echo("Don't know how to publish over ssh to ${target} for builders ${job_env.builders}.")
                    }
                }
            }
        }
        if (job_env.archive_artifacts != null) {
            // Save artifacts for use in another project
            stage("Archiving artifacts") {
                echo("${job_env.full_name} using artifact archiver for ${job_env.archive_artifacts.include}")
                def args = [
                        "artifacts": job_env.archive_artifacts.include
                ]
                if (job_env.archive_artifacts.exclude != null) {
                    args["excludes"] = job_env.archive_artifacts.exclude
                }
                archiveArtifacts(args)
            }
        }
    } catch (InterruptedException x) {
        currentBuild.result = 'ABORTED'
        throw x
    } catch (x) {
        currentBuild.result = 'FAILURE'
        throw x
    } finally {
        if (_slack_enabled(job_env) && !env.DEV_MODE?.toBoolean()) {
            def current_result = currentBuild.result ? currentBuild.result : "SUCCESS"
            def previous_result = currentBuild.getPreviousBuild()?.getResult()
            if (previous_result != "SUCCESS" || current_result != "SUCCESS") {
                def message_result
                def color
                if (previous_result != "SUCCESS" && current_result == "SUCCESS") {
                    message_result = "Back to normal"
                    color = 'good'
                } else if (previous_result == current_result) {
                    message_result = "Still ${current_result}"
                    color = 'danger'
                } else {
                    message_result = current_result
                    color = 'warning'
                }
                // TODO: Figure out how to sainly add custom parameters without having if en mass..
                // TODO: right now message: job_env.slack.custom_message, username: job_env.slack.sendas are unsupported
                // We're skipping after 2 min 33 sec, Back to normal after 22 min and so.
                slackSend(color: color, channel: job_env.slack.room, message: "${env.JOB_NAME} - #${env.BUILD_NUMBER} ${message_result} (<${env.BUILD_URL}|Open>)")
            }
        }
    }
}

ansiColor('xterm') {
    if (docker_image) {
        dockerNode(image: docker_image) {
            runJob(job_env)
        }
    } else {
        node() {
            runJob(job_env)
        }
    }
}
