package io.codearte.gradle.nexus.functional

import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.github.tomakehurst.wiremock.stubbing.Scenario
import groovy.json.JsonOutput
import io.codearte.gradle.nexus.logic.FetcherResponseTrait
import org.junit.Rule
import spock.lang.Unroll

import static com.github.tomakehurst.wiremock.client.WireMock.*

class MockedFunctionalSpec extends BaseNexusStagingFunctionalSpec implements FetcherResponseTrait {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8089);

    protected static final String stagingProfileId = "93c08fdebde1ff"

    @Unroll
    def "should not do request for staging profile when provided in configuration on #testedTaskName task"() {
        given:
            stubGetOneRepositoryWithProfileIdAndContent(stagingProfileId,
                    createResponseMapWithGivenRepos([aRepoInStateAndId(repoTypeToReturn, "ignored")]))
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                nexusStaging {
                    stagingProfileId = "$stagingProfileId"
                }
            """.stripIndent()
        when:
            def result = runTasksSuccessfully(testedTaskName)
        then:
            result.wasExecuted(testedTaskName)
            result.standardOutput.contains("Using configured staging profile id: $stagingProfileId")
            !result.standardOutput.contains("Getting staging profile for package group")
        and:
            verify(0, getRequestedFor(urlEqualTo("/staging/profiles")))
        where:
            testedTaskName      | repoTypeToReturn
            "closeRepository"   | "open"
            "promoteRepository" | "closed"
    }

    @Unroll
    def "should send request for staging profile when not provided in configuration on #testedTaskName task"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            stubGetOneRepositoryWithProfileIdAndContent(stagingProfileId,
                    createResponseMapWithGivenRepos([aRepoInStateAndId(repoTypeToReturn, "ignored")]))
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        when:
            def result = runTasksSuccessfully(testedTaskName)
        then:
            result.wasExecuted(testedTaskName)
            !result.standardOutput.contains("Using configured staging profile id: $stagingProfileId")
            result.standardOutput.contains("Getting staging profile for package group")
        and:
            verify(1, getRequestedFor(urlEqualTo("/staging/profiles")))
        where:
            testedTaskName      | repoTypeToReturn
            "closeRepository"   | "open"
            "promoteRepository" | "closed"
    }

    def "should reuse stagingProfileId from closeRepository in promoteRepository when called together"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            stubGetOneOpenRepositoryInFirstCallAndOneClosedIntheNext(stagingProfileId)
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
            """.stripIndent()
        when:
            def result = runTasksSuccessfully("closeRepository", "promoteRepository")
        then:
            result.wasExecuted("closeRepository")
            result.wasExecuted("promoteRepository")
        and:
            verify(2, getRequestedFor(urlEqualTo("/staging/profile_repositories/$stagingProfileId")))
            verify(1, getRequestedFor(urlEqualTo("/staging/profiles")))
    }

    def "should pass parameter to other task"() {
        given:
            stubGetStagingProfilesWithJson(this.getClass().getResource("/io/codearte/gradle/nexus/logic/2stagingProfilesShrunkResponse.json").text)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                task getValue << {
                    assert getStagingProfile.stagingProfileId == "$stagingProfileId"
                }
            """.stripIndent()
        expect:
            runTasksSuccessfully('getStagingProfile', 'getValue')
    }

    def "should retry promotion when repository has not been already closed"() {
        given:
            stubGetOneOpenRepositoryInFirstCallAndOneClosedIntheNext(stagingProfileId)
        and:
            stubSuccessfulCloseRepositoryWithProfileId(stagingProfileId)
        and:
            stubSuccessfulPromoteRepositoryWithProfileId(stagingProfileId)
        and:
            buildFile << """
                ${getApplyPluginBlock()}
                ${getDefaultConfigurationClosure()}
                nexusStaging {
                    stagingProfileId = "$stagingProfileId"
                    delayBetweenRetriesInMillis = 50
                    numberOfRetries = 2
                }
            """.stripIndent()
        when:
            def result = runTasksSuccessfully("promoteRepository")
        then:
            result.wasExecuted("promoteRepository")
            result.standardOutput.contains("Attempt 1/3 failed.")
            !result.standardOutput.contains("Attempt 2/3 failed.")
    }

    @Override
    protected String getDefaultConfigurationClosure() {
        return """
                nexusStaging {
                    username = "codearte"
                    packageGroup = "io.codearte"
                    serverUrl = "http://localhost:8089/"
                }
        """
    }

    private void stubGetStagingProfilesWithJson(String responseAsJson) {
        stubFor(get(urlEqualTo("/staging/profiles"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseAsJson)));
    }

    private void stubGetOneRepositoryWithProfileIdAndContent(String stagingProfileId, Map response) {
        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(JsonOutput.toJson(response))));
    }

    private void stubSuccessfulCloseRepositoryWithProfileId(String stagingProfileId) {
        stubFor(post(urlEqualTo("/staging/profiles/$stagingProfileId/finish"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")));
    }

    private void stubSuccessfulPromoteRepositoryWithProfileId(String stagingProfileId) {
        stubFor(post(urlEqualTo("/staging/profiles/$stagingProfileId/promote"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")));
    }

    private void stubGetOneOpenRepositoryInFirstCallAndOneClosedIntheNext(String stagingProfileId) {
        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId")).inScenario("State")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(JsonOutput.toJson(createResponseMapWithGivenRepos([aRepoInStateAndId("open", "ignored")])))
                )
                .willSetStateTo("CLOSED"));

        stubFor(get(urlEqualTo("/staging/profile_repositories/$stagingProfileId")).inScenario("State")
                .whenScenarioStateIs("CLOSED")
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Accept", containing("application/json"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(JsonOutput.toJson(createResponseMapWithGivenRepos([aRepoInStateAndId("closed", "ignored")])))
                ));
    }
}