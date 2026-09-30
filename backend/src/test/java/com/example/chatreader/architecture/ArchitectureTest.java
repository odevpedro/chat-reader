package com.example.chatreader.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Regras de arquitetura do ADR-001. Falha o build se a estrutura de modulos violar
 * a regra de dependencia infrastructure -&gt; application -&gt; domain.
 */
@DisplayName("Arquitetura — modulos isolados e dominio puro (ADR-001)")
class ArchitectureTest {

    private static final String BASE = "com.example.chatreader";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("o dominio nao depende de Spring, JPA ou Jackson")
    void domainIsPure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml..")
                .because("o dominio nao conhece a infraestrutura (ADR-001, secao 6)");

        rule.check(classes);
    }

    @Test
    @DisplayName("application nao depende de infrastructure nem de controllers")
    void applicationDoesNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(BASE + "..infrastructure..")
                .because("a regra de dependencia e infrastructure -> application -> domain");

        rule.check(classes);
    }

    @Test
    @DisplayName("o dominio do chat nao conhece o modulo importer")
    void chatDomainDoesNotKnowImporter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".chat..")
                .should().dependOnClassesThat()
                .resideInAPackage(BASE + ".importer..")
                .because("modulos nao se referenciam diretamente; a traducao acontece "
                        + "em NormalizedChat (ADR-004)");

        rule.check(classes);
    }

    @Test
    @DisplayName("cada classe de infrastructure e um adapter, controller, mapper ou DTO")
    void infrastructureHoldsOnlyAdaptersAndControllers() {
        ArchRule rule = classes().that()
                .resideInAPackage(BASE + "..infrastructure")
                .and(new DescribedPredicate<JavaClass>("nao e classe aninhada") {
                    @Override
                    public boolean test(JavaClass input) {
                        return input.getEnclosingClass().isEmpty();
                    }
                })
                .should().haveSimpleNameEndingWith("Controller")
                .orShould().haveSimpleNameEndingWith("Adapter")
                .orShould().haveSimpleNameEndingWith("Mapper")
                .orShould().haveSimpleNameEndingWith("Entity")
                .orShould().haveSimpleNameEndingWith("Importer")
                .orShould().haveSimpleNameEndingWith("Repository")
                .orShould().haveSimpleNameEndingWith("Dtos")
                .because("infrastructure so existe onde ha adapter, controller, mapper ou DTO");

        rule.check(classes);
    }

    @Test
    @DisplayName("o modulo chat nao depende de importer, metrics, security nem config")
    void chatDependsOnlyOnItselfAndShared() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".chat..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        BASE + ".importer..",
                        BASE + ".metrics..",
                        BASE + ".security..",
                        BASE + ".config..")
                .because("modulos sao independentes; shared e transversal (ADR-001)");

        rule.check(classes);
    }

    @Test
    @DisplayName("domain e application nao dependem de modulos transversal")
    void innerLayersDoNotDependOnCrossCutting() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + "..domain..")
                .or().resideInAPackage(BASE + "..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(BASE + ".security..", BASE + ".config..", BASE + ".metrics..")
                .because("seguranca, config e metricas sao concernimentos da borda");

        rule.check(classes);
    }

    @Test
    @DisplayName("nenhum pacote do dominio declara campo de credencial")
    void domainHasNoCredentialFields() {
        ArchRule rule = noFields()
                .that().haveNameMatching("(?i).*(password|senha|secret|token|apikey|api_key).*")
                .should().beDeclaredInClassesThat().resideInAPackage(BASE + "..domain..")
                .because("senhas e segredos vivem em variaveis de ambiente, nunca no dominio");

        rule.check(classes);
    }
}
