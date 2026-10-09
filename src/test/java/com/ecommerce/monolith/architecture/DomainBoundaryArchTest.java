package com.ecommerce.monolith.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

@AnalyzeClasses(
        packages = "com.ecommerce.monolith",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class DomainBoundaryArchTest {

    private static final Set<String> DOMAIN_MODULES = Set.of(
            "cart",
            "coupon",
            "order",
            "outbox",
            "payment",
            "product",
            "review",
            "user"
    );

    @ArchTest
    static final ArchRule repositories_are_internal_to_their_owning_module =
            classes()
                    .that().resideInAPackage("com.ecommerce.monolith..")
                    .should(onlyAccessRepositoriesInTheSameModule());

    @ArchTest
    static final ArchRule cross_module_dependencies_use_public_api_or_documented_exceptions =
            classes()
                    .that().resideInAPackage("com.ecommerce.monolith..")
                    .should(onlyUsePublicCrossModuleApi());

    private static ArchCondition<JavaClass> onlyAccessRepositoriesInTheSameModule() {
        return new ArchCondition<>("only access repositories in the same domain module") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                moduleOf(origin).ifPresent(originModule -> origin.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> isDomainRepository(dependency.getTargetClass()))
                        .filter(dependency -> !moduleOf(dependency.getTargetClass()).orElse("").equals(originModule))
                        .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                origin,
                                "%s must not access another module repository: %s".formatted(
                                        origin.getName(),
                                        dependency.getDescription()
                                )
                        ))));
            }
        };
    }

    private static ArchCondition<JavaClass> onlyUsePublicCrossModuleApi() {
        return new ArchCondition<>("use service/dto packages for cross-module dependencies") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originModule = moduleOf(origin);
                if (originModule.isEmpty()) {
                    return;
                }

                origin.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> moduleOf(dependency.getTargetClass()).isPresent())
                        .filter(dependency -> !moduleOf(dependency.getTargetClass()).orElseThrow().equals(originModule.get()))
                        .filter(dependency -> !isPublicCrossModuleDependency(dependency))
                        .filter(dependency -> !isDocumentedException(dependency))
                        .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                origin,
                                "%s must depend on public service/dto APIs across modules: %s".formatted(
                                        origin.getName(),
                                        dependency.getDescription()
                                )
                        )));
            }
        };
    }

    private static boolean isDomainRepository(JavaClass javaClass) {
        return moduleOf(javaClass).isPresent() && javaClass.getPackageName().contains(".repository");
    }

    private static boolean isPublicCrossModuleDependency(Dependency dependency) {
        String targetPackage = dependency.getTargetClass().getPackageName();
        return targetPackage.contains(".dto") || targetPackage.contains(".service");
    }

    private static boolean isDocumentedException(Dependency dependency) {
        JavaClass origin = dependency.getOriginClass();
        JavaClass target = dependency.getTargetClass();
        String originPackage = origin.getPackageName();
        String targetName = target.getName();

        if (originPackage.equals("com.ecommerce.monolith.outbox.service")
                && (targetName.equals("com.ecommerce.monolith.order.entity.Order")
                || targetName.equals("com.ecommerce.monolith.payment.entity.Payment")
                || targetName.equals("com.ecommerce.monolith.payment.entity.PaymentReconciliationTask"))) {
            return true;
        }

        return (originPackage.equals("com.ecommerce.monolith.payment.service")
                || originPackage.equals("com.ecommerce.monolith.review.service"))
                && (targetName.equals("com.ecommerce.monolith.order.entity.Order")
                || targetName.equals("com.ecommerce.monolith.order.entity.Order$OrderStatus"));
    }

    private static Optional<String> moduleOf(JavaClass javaClass) {
        return moduleOf(javaClass.getPackageName());
    }

    private static Optional<String> moduleOf(String packageName) {
        String prefix = "com.ecommerce.monolith.";
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }

        String remainder = packageName.substring(prefix.length());
        int dotIndex = remainder.indexOf('.');
        String module = dotIndex >= 0 ? remainder.substring(0, dotIndex) : remainder;
        return DOMAIN_MODULES.contains(module) ? Optional.of(module) : Optional.empty();
    }
}
