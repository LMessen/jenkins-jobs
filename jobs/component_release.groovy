evaluate(new File("${WORKSPACE}/common.groovy"))

repos.each { Map repo ->
  name = "${repo.name}-release"

  job(name) {
    description """
      <ol>
        <li>Watches the ${repo.name} repo for a git tag push. (It can also be triggered manually, supplying a value for TAG.)</li>
        <li>Unless TAG is set (manual trigger), this job only runs off the latest tag.</li>
        <li>The commit at HEAD of tag is then used to locate the release candidate image(s).</li>
        <li>Kicks off downstream e2e job to vet candidate image(s).</li>
        <li>Provided e2e tests pass, retags release candidate(s) with official semver tag in the 'deis' registry orgs.</li>
      </ol>
    """.stripIndent().trim()

    scm {
      git {
        remote {
          github("deis/${repo.name}")
          credentials('597819a0-b0b9-4974-a79b-3a5c2322606d')
          refspec('+refs/tags/*:refs/remotes/origin/tags/*')
        }
        branch('*/tags/*')
      }
    }

    logRotator {
      daysToKeep defaults.daysToKeep
    }

    publishers {
      slackNotifications {
        notifyFailure()
        notifyRepeatedFailure()
       }
     }

    parameters {
      stringParam('TAG', '', 'Specific tag to release')
    }

    triggers {
      githubPush()
    }

    wrappers {
      buildName('${GIT_BRANCH} ${TAG} #${BUILD_NUMBER}')
      timestamps()
      colorizeOutput 'xterm'
    }

    steps {
      main = [
        new File("${WORKSPACE}/bash/scripts/get_latest_tag.sh").text,
        new File("${WORKSPACE}/bash/scripts/locate_release_candidate.sh").text,
      ].join('\n')

      repo.components.each{ Map component ->
        main += """
          tag="\$(get-latest-tag)"
          commit="\$(git rev-parse HEAD)"

          locate-release-candidate ${component.name} "\${commit}" "\${tag}" "${component.envFile}"

        """.stripIndent()
      }

      shell main

      if (repo.runE2e) {
        conditionalSteps {
          condition {
            not {
              shell 'cat "${WORKSPACE}/env.properties" | grep -q SKIP_RELEASE'
            }
          }
          steps {
            repo.components.each{ Map component ->
              downstreamParameterized {
                trigger('release-candidate-e2e') {
                  block {
                    buildStepFailure('FAILURE')
                    failure('FAILURE')
                    unstable('UNSTABLE')
                  }
                  parameters {
                    propertiesFile(component.envFile)
                  }
                }
              }
            }
          }
        }
      }

      // If e2e job results in `SUCCESS`, promote release candidate
      conditionalSteps {
        condition {
          and {
            status('SUCCESS', 'SUCCESS')
          } {
            not {
              shell 'cat "${WORKSPACE}/env.properties" | grep -q SKIP_RELEASE'
            }
          }
        }
        steps {
          repo.components.each{ Map component ->
            downstreamParameterized {
              trigger('release-candidate-promote') {
                block {
                  buildStepFailure('FAILURE')
                  failure('FAILURE')
                  unstable('UNSTABLE')
                }
                parameters {
                  propertiesFile(component.envFile)
                }
              }
            }
          }
        }
      }
    }
  }
}
