Bootstrap job for automatically creating docker jobs from github for SUNET

### Available settings
```groovy
// Default environment
def env = [
        'name'                   : name from github ex. eduid-am,
        'full_name'              : full name from github ex. sunet/eduid-am,
        'disabled'               : false,
        'git'                    : [:],
        'python_source_directory': 'src',
        'slack'                  : ['room': 'devops', 'enable': true],
        'triggers'               : [:],
        'builders'               : []
        'build_in_docker'        : [
                'disabled': false,
                'dockerfile': null,
                'image': null,
                'start_command': "/run.sh"
        ]
    ]
```

#### .jenkins.yaml with default values
```yaml
# Does not add a job if set to true
disabled: false

git:
  # Branch to checkout, master if omitted
  branch: master
  # Multiple branches can be built by providing branch names as a list of strings to branches
  branches: []
  extensions:
    # Will make a shallow clone of the repo
    shallow_clone: false
    # Check out a local branch named as remote branch for use with "${GIT_LOCAL_BRANCH}"
    checkout_local_branch: false

# Settings for building in a docker container
build_in_docker:
  # Using docker as only builder will disable build in docker
  # Does not try to build in a docker container if set to true
  disable: false
  # Docker image to build inside of
  image: docker.sunet.se/sunet/docker-jenkins-job
  # First build a docker image to build inside of
  dockerfile: ~
  # Verbose output if set to true
  verbose: false
  # Update the source image before build
  force_pull: true
  # Command used to start container for build
  start_command: /run.sh

# Se below for available builders
builders: []

# If script is not empty the builder "script"  will be added
# Every list item is a line
script: []

# Set environment variables that will be available during build
# Default [environment variables](https://wiki.jenkins.io/display/JENKINS/Building+a+software+project#Buildingasoftwareproject-belowJenkinsSetEnvironmentVariables)
environment_variables:
  VAR1: "something"
  VAR2: "something else"

# Will set items in the list as upstream
upstream: []
# Will set items in the list as downstream
downstream: []

triggers:
  # Build when push received to the master branch
  github_push: true
  # Use cron syntax to periodically build project, eg. "@daily"
  cron: ~

slack:
  # Default to send errors and back to normal to the devops channel
  disabled: false
  room: "devops"
  custom_message: ~
  sendas: ~

# Save artifacts from successful builds
archive_artifacts:
  # You can use wildcards like "module/dist/**/*.zip"
  include: "module/dist/**/*.zip"
  # Optionally specify the 'excludes' pattern, such as "foo/bar/**/*"
  exclude: "foo/bar/**/*"
  # Number of archives to keep
  num_to_keep: 1
# Use artifacts from another projects last successful build
copy_artifacts:
  # Name of project to copy artifacts from
  project_name: name_of_project
  # Directory to copy artifacts to. Artifact source dir will be used if omitted
  target_dir: ~
  # Relative path to artifacts to include, eg. module/dist/**/*.zip. All artifacts will be included if omitted
  include
    - **/*.zip
  # Relative path to artifacts to exclude
  exclude
    - **/*.xml
  # Ignores the directory structure of the artifacts
  flatten: false
  # Allows this build to continue even if no last successful build of the artifact project can be found
  optional: false

# Set to true if workspace should be removed before build
clean_workspace: false

# Script to run before build
pre_build_script: []
Script to run after build
post_build_script: []

# Settings for builder python
# Module name to be used in python builder script, defaults to project name
python_module: ~
# Source directory for use in python builder script
python_source_directory: src
# The only supported value is pypi.sunet.se that uploads *.egg and *.tar.gz from ./dist
# Will check that builder python is used
publish_over_ssh: []

# Settings for builder docker
# Name that built docker image should have
docker_name: sunet/name_of_repo
# Tags that should be used
# "git-\${GIT_REVISION,length=8}", "ci-${env.name}-\${BUILD_NUMBER}" and "latest" will always be set
docker_tags: []
# Set docker context directory if different from repo root
docker_context_dir: ~
# Force rebuild
docker_no_cache: true
# Update the source image before build
docker_force_pull: true
# Force tag replacement when tag already exist
docker_force_tag: false
# Create fingerprints for the image
docker_create_fingerprints: true
# Does not tag the build as latest
docker_skip_tag_as_latest: false

# Use managed script in the list where applicable
managed_scripts: []

# Define extra jobs derived from this projects settings
extra_jobs: []
```

#### Available builders
```yaml
builders:
  # Runs "make clean && make && make test" in the default job container
  - make
  # Runs the script /opt/builders/cmake in the default job container
  - cmake
  # Runs the script /opt/builders/python in the default job container
  # Arguments are env.name|env.python_module env.python_source_directory
  - python
  # Will build and push a docker image to docker.sunet.se
  # Expects the Dockerfile to be in the project root, can be change by setting docker_context_dir
  # If managed_scripts docker_build_prep.sh and/or docker_tag.sh is set those will be used
  - docker
  # Builder script will just the items in the script list as lines of commands
  - script
```

#### Extra jobs
```yaml
# An example of an extra job
# eduid-am is Python project repo and we want a docker image built after
# python code succefully finishes
# The options from outside extra_jobs file will be used unless explicitly set in the extra job
extra_jobs:
  - name: eduid-am-docker
    builders:
      - docker
    docker_name: eduid/eduid-am
    managed_scripts:
      - docker_build_prep.sh
      - docker_tag.sh
    triggers:
      github_push: false
      cron: ~
    upstream:
      - eduid-am
```

### Tips

#### String transformations
https://wiki.jenkins.io/display/JENKINS/Token+Macro+Plugin

### And how do this thingie work?
This is now a new generation of jenkins jobs for the sunet infrastructure.

`github_docker_repos.groovy` is now a tiny peice of job-dsl-code which
generates pipeline jobs for all the repos in the configured ORG's on github,
but contrary to previous iterations it doesn't configure those jobs.
In jenkins pipeline, the jobs decide themself what to do.
The pipeline job used is the one in `sunet-job.groovy` and that checks out
the master branch of the repo, reads `.jenkins.yml` and goes from there.

Right now, due to that the logic is that .jenkins.yml is read from master,
and might contain configuration to build another branch, there are two
checkouts done. One for reading the configuration, and one for executing
the build.

Also, if configured to do so, currently by a opt-in-list, gpg signatures in
git are verified before reading `.jenkins.yaml` and before building the repo.
This is so we can ensure that we have a trust chain from developer to binaries.

For future migrations to a more standardized `Jenkinsfile` flow, one can
add a `Jenkinsfile` to a repo, and turn on repo signature verification, and
then use a standard flow like any other pipeline based jenkins machine.
Just steal the relevant code for your project from `sunet-job.groovy` and
customize your own `Jenkinsfile` to fit your project.
