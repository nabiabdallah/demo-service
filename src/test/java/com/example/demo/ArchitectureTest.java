package com.example.demo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture as executable rules. Agents cannot edit this file (see .claude/settings.json);
 * changing a rule is a human decision recorded in an ADR.
 *
 * <p>{@code src/test/resources/archunit.properties} lets a rule pass while its layer is still empty
 * (a new project has no use cases yet); each rule applies as soon as the first class lands in its
 * layer. {@link #guard_classes_are_imported} keeps that from hiding a broken setup: if no classes are
 * imported at all (e.g. a wrong package after a rename), it fails.
 */
@AnalyzeClasses(packages = "com.example.demo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.example.demo";
    private static final String DOMAIN = "com.example.demo.domain..";
    private static final String APPLICATION = "com.example.demo.application..";
    private static final String ADAPTER = "com.example.demo.adapter..";
    private static final String CONFIG = "com.example.demo.config..";

    @ArchTest
    static final ArchRule guard_classes_are_imported = classes()
            .that()
            .haveSimpleName("Application")
            .should()
            .resideInAPackage(BASE)
            .allowEmptyShould(false)
            .because("ArchUnit must import this service's classes, or every other rule passes vacuously");

    @ArchTest
    static final ArchRule domain_is_pure_java = classes()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(DOMAIN, "java..")
            .because("the domain must not know about frameworks, persistence or the web");

    @ArchTest
    static final ArchRule application_does_not_use_spring_or_adapters = noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ADAPTER, CONFIG, "org.springframework..", "jakarta.persistence..")
            .because("use cases talk to the outside world only through ports");

    @ArchTest
    static final ArchRule inbound_adapters_use_only_inbound_ports = noClasses()
            .that()
            .resideInAPackage("com.example.demo.adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.example.demo.application.port.out..", "com.example.demo.adapter.out..")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Service")
            .because("controllers call use-case interfaces, never implementations or repositories");

    @ArchTest
    static final ArchRule outbound_adapters_do_not_call_inbound_side = noClasses()
            .that()
            .resideInAPackage("com.example.demo.adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.demo.adapter.in..");

    @ArchTest
    static final ArchRule sql_only_in_persistence_adapters_and_config = noClasses()
            .that()
            .resideOutsideOfPackages("com.example.demo.adapter.out.persistence..", "com.example.demo.config..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.flywaydb..")
            .because("SQL, connections and migrations stay behind outbound persistence adapters");

    @ArchTest
    static final ArchRule adapters_are_independent = slices().matching("com.example.demo.adapter.(*).(*)..")
            .should()
            .notDependOnEachOther()
            .because("each integration is isolated behind its own port");

    @ArchTest
    static final ArchRule no_floating_point_fields = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(DOMAIN, APPLICATION)
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .because("amounts use BigDecimal value objects, never floating point");

    @ArchTest
    static final ArchRule no_floating_point_return_types = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(DOMAIN, APPLICATION)
            .should()
            .haveRawReturnType(double.class)
            .orShould()
            .haveRawReturnType(float.class)
            .orShould()
            .haveRawReturnType(Double.class)
            .orShould()
            .haveRawReturnType(Float.class)
            .because("amounts use BigDecimal value objects, never floating point");
}
