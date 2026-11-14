package com.riverlifeline;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Hexagonal layering: domain ← application ← infrastructure. A violation fails the build. */
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
                .resideInAnyPackage("..monitoring.domain..", "..shared.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "org.springframework..",
                        "com.fasterxml..",
                        "jakarta..",
                        "org.slf4j..")
                .check(classes);
    }

    @Test
    void theApplicationDoesNotKnowTheAdaptersNorTheFramework() {
        noClasses()
                .that()
                .resideInAPackage("..monitoring.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..", "org.springframework..", "com.fasterxml..", "jakarta..")
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
}
