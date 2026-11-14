package com.riverlifeline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hexagonal layering of every bounded context ({@code monitoring}, {@code pipeline}): domain ← application ←
 * infrastructure. A violation fails the build.
 */
class ArchitectureTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.riverlifeline");
    }

    @Test
    void theDomainDependsOnNothingButItselfAndTheSharedKernel() {
        noClasses()
                .that()
                .resideInAnyPackage("..monitoring.domain..", "..pipeline.domain..", "..shared.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "org.springframework..",
                        "com.fasterxml..",
                        "jakarta..",
                        "org.slf4j..",
                        "io.jhdf..",
                        "org.locationtech..")
                .check(classes);
    }

    @Test
    void theApplicationDoesNotKnowTheAdaptersNorTheFramework() {
        noClasses()
                .that()
                .resideInAnyPackage("..monitoring.application..", "..pipeline.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure..",
                        "org.springframework..",
                        "com.fasterxml..",
                        "jakarta..",
                        "io.jhdf..",
                        "org.locationtech..")
                .check(classes);
    }

    @Test
    void adaptersDoNotCallEachOther() {
        noClasses()
                .that()
                .resideInAPackage("..infrastructure.adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure.adapter.out..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..infrastructure.adapter.out..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure.adapter.in..")
                .check(classes);
    }

    @Test
    void theContextsDoNotReachIntoEachOther() {
        noClasses()
                .that()
                .resideInAPackage("com.riverlifeline.pipeline..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.riverlifeline.monitoring..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("com.riverlifeline.monitoring..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.riverlifeline.pipeline..")
                .check(classes);
    }
}
