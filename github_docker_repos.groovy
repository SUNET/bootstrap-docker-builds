// vim: ts=4 sts=4 sw=4 et
import groovy.json.JsonSlurper
import java.io.IOException

import jenkins.model.Jenkins


def orgs = ['SUNET','TheIdentitySelector']
def api = "https://api.github.com"

if (binding.hasVariable("ORGS") && "${ORGS}" != "") {
    orgs = new JsonSlurper().parseText("${ORGS}")
    out.println("orgs overridden by env: ${ORGS}")
}
def ONLY_REPOS = null
if (binding.hasVariable("REPOS") && "${REPOS}" != "") {
    ONLY_REPOS = new JsonSlurper().parseText("${REPOS}")
    out.println("repos overridden by env: ${REPOS}")
}
def is_dev_mode = false
if (binding.hasVariable("DEV_MODE") && "${DEV_MODE}" != "" && DEV_MODE.toBoolean()) {
    out.println("DEV_MODE detected, will act accordingly")
    is_dev_mode = true
}

for (org in orgs) {
    //TODO: Should we set a per_page=100 (100 is max) to decrese the number of api calls,
    // So we don't get ratelimited as easy?
    def next_path = "/orgs/${org}/repos"
    def c = new URL(api + "/orgs/${org}").openConnection()
    // Is this a org?
    // If not, try the users endpoint
    if (c.getResponseCode() == 404) {
        out.println("${org} is not a org, guessing its a user")
        next_path = "/users/${org}/repos"
    }
    try {
        while (next_path != null) {
            // No, we can't use try_get_file here, because we need headers for links
            def url = new URL(api + next_path)
            def conn = url.openConnection()
            // This way you can add your own github auth token via https://github.com/settings/tokens
            // So you don't run into the request api request limit as quickly...
            if (binding.hasVariable("GITHUB_TOKEN") && "${GITHUB_TOKEN}" != "")
                conn.addRequestProperty("Authorization", "token ${GITHUB_TOKEN}")

            def repos = new JsonSlurper().parse(conn.getInputStream())

            // Terminate loop if we can't find a next link
            next_path = null
            Map<String, List<String>> headers = conn.getHeaderFields()
            if ('Link' in headers) {
                // The list isn't the Link's in the Link header,
                // its that it can be multiple Link headers in a response.
                def links = headers['Link'][0]
                //links.split(",").each { link ->
                for (link in links.split(",")) {
                    link = link.trim()
                    def m = (link =~ /<https:\/\/api.github.com([^>]+)>; rel="next"/)
                    if (m.matches()) {
                        next_path = m[0][1]
                        break;
                    }
                }
            }

            for (repo in repos) {
                if (repo.name.equals("bootstrap-docker-builds"))
                    continue
                if (ONLY_REPOS && !ONLY_REPOS.contains(repo.name))
                    continue
                out.println("repo: ${repo.name}")

                if (repo.archived) {
                    out.println("Skipping archived repo")
                    continue
                }

                // Keep this in sync between github_docker_repos.groovy and sunet-job.groovy
                // We need this magic dance so job-dsl doesn't overwrite
                // any triggers or other properties created in pipeline
                def existing_job = Jenkins.instance.getItem(repo.name)
                def pipeline_job = pipelineJob(repo.name)

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

                // But force these things to be as they should.
                pipeline_job.with {
                    environmentVariables {
                        env("FULL_NAME", repo.full_name)
                        env("DEV_MODE", is_dev_mode.toString())
                    }
                    definition {
                        cps {
                            script(readFileFromWorkspace('sunet-job.groovy'))
                            sandbox()
                        }
                    }
                }
            }
        }
    } catch (IOException ex) {
        out.println("---- Bad response from: ----")
        out.println("Path: ${next_path}")
        out.println(ex.toString());
        out.println(ex.getMessage());
        throw ex
    }
}

configFiles {
    // Used in some jobs as a prepp step
    scriptConfig {
        id("docker_build_prep.sh")
        name("docker_build_prep.sh")
        comment("Script managed from job-dsl, don't edit in jenkins.")
        content(readFileFromWorkspace("managed_scripts/docker_build_prep.sh"))
    }
    // Used to build extra-jobs
    customConfig {
        id("sunet-job.groovy")
        name("sunet-job.groovy")
        comment("Script managed from job-dsl, don't edit in jenkins.")
        content(readFileFromWorkspace("sunet-job.groovy"))
    }
}
removedConfigFilesAction('DELETE')

listView("cnaas") {
    jobs {
        regex(/.*cnaas.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("comanage") {
    jobs {
        regex(/^comanage.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("eduid") {
    jobs {
        name("pysmscom")
        name("python-vccs_client")
        name("VCCS")
        regex(/.*eduid.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("jenkins") {
    jobs {
        name("bootstrap-docker-builds")
        regex(/.*jenkins.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("se-leg") {
    jobs {
        regex(/.*se-leg.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

freeStyleJob('promote-image') {
    disabled()
    description("A job to manually promote a docker image from one name to another")
    if (jenkins.model.Jenkins.instance.getPluginManager().getPlugin('build-user-vars-plugin')) {
        wrappers {
            buildUserVars()
        }
    }
    parameters {
        stringParam('IMAGE_NAME', null, 'The image name to promote, ex: docker.sunet.se/sunet/docker-jenkins-job')
        stringParam('SOURCE_TAG', 'latest', 'The current tag of the image to promote')
        stringParam('TARGET_TAG', 'stable', 'The target tag to promote the image to, ex: stable')
        booleanParam('TAG_PREVIOUS', true, 'Create a previous-$TARGET_TAG of the old $TARGET_TAG')
    }
    steps {
        shell(readFileFromWorkspace("promote-image.sh"))
    }
}
