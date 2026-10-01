package io.github.andres.pigeon.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.github.andres.pigeon", importOptions = ImportOption.DoNotIncludeTests.class)
public class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_frameworks =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.fasterxml.jackson..",
                            "org.hibernate..",
                            "org.apache.rabbitmq..",
                            "org.springframework.amqp.."
                    )
                    .because("Domain must be pure Java (JDK only) without any framework dependencies (ADR-0001)");

    @ArchTest
    static final ArchRule application_must_only_depend_on_domain_and_allowed_annotations =
            classes().that().resideInAPackage("..application..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "io.github.andres.pigeon.domain..",
                            "io.github.andres.pigeon.application..",
                            "java..",
                            "org.slf4j..",
                            "org.springframework.stereotype..",
                            "org.springframework.transaction..",
                            "org.springframework.scheduling.."
                    )
                    .because("Application layer may only depend on Domain, JDK, and basic Spring service/transactional annotations");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_application_or_infrastructure =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..",
                            "..infrastructure.."
                    )
                    .because("Dependencies must point inward: domain cannot depend on outer layers");

    @ArchTest
    static final ArchRule application_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .because("Application layer cannot depend on infrastructure adapters");
}
