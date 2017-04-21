package io.codearte.gradle.nexus

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.invoke.MethodHandles

@CompileStatic
trait FunctionalTestHelperTrait implements FunctionalTestConstants {

    private static final Logger logT = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String NEXUS_USERNAME_AT_ENVIRONMENT_VARIABLE_NAME = 'nexusUsernameAT'
    private static final String NEXUS_PASSWORD_AT_ENVIRONMENT_VARIABLE_NAME = 'nexusPasswordAT'

    String getNexusUsernameAT() {
        return System.getenv(NEXUS_USERNAME_AT_ENVIRONMENT_VARIABLE_NAME) ?:
            tryToReadPropertyFromGradlePropertiesFile(NEXUS_USERNAME_AT_ENVIRONMENT_VARIABLE_NAME) ?:
                'nexus-at'
    }

    //Temporary hack to read nexus password in e2e tests
    String tryToReadNexusPasswordAT() {
        //Will not work with empty password. However, support for it would complicate '?;' statement
        return System.getenv(NEXUS_PASSWORD_AT_ENVIRONMENT_VARIABLE_NAME) ?:
            tryToReadPropertyFromGradlePropertiesFile(NEXUS_PASSWORD_AT_ENVIRONMENT_VARIABLE_NAME) ?:
                { throw new RuntimeException("Nexus password for AT tests is not set in 'gradle.properties' nor system variable " +
                    "'$NEXUS_PASSWORD_AT_ENVIRONMENT_VARIABLE_NAME'") }()
    }

    private String tryToReadPropertyFromGradlePropertiesFile(String propertyName) {
        Properties props = new Properties()
        File gradlePropertiesFile = new File(new File(System.getProperty('user.home'), '.gradle'), 'gradle.properties')
        if (!gradlePropertiesFile.exists()) {
            logT.warn("$gradlePropertiesFile does not exist while reading '$propertyName' value")
            return null
        }
        gradlePropertiesFile.withInputStream { props.load(it) }
        return props.getProperty(propertyName)
    }
}
