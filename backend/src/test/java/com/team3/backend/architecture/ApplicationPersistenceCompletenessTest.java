package com.team3.backend.architecture;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "yon.normalization.ingest.enabled=false",
        "yon.normalization.condition-set-backfill.enabled=false"
})
class ApplicationPersistenceCompletenessTest {

    private static final String ROOT = "com.team3.backend";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory factory;

    @Test
    void allApplicationEntitiesAreManagedByDefaultScanning() {
        var scanner = scanner();
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> expected = names(scanner);
        assertThat(expected).isNotEmpty();
        Set<String> actual = factory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void allSpringDataRepositoriesAreRegisteredIncludingThoseWithoutOwnEntities() {
        var scanner = scanner();
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RepositoryDefinition.class));
        scanner.addExcludeFilter(new AnnotationTypeFilter(NoRepositoryBean.class));
        Set<String> repositories = names(scanner);
        assertThat(repositories).isNotEmpty();
        for (String name : repositories) {
            Class<?> type = ClassUtils.resolveClassName(name, context.getClassLoader());
            if (type.isInterface()) {
                assertThat(context.getBeanNamesForType(type)).as("Repository: %s", name).hasSize(1);
            }
        }
    }

    @Test
    void notificationServiceClassesAreAbsentFromTheClasspath() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver(context.getClassLoader());
        assertThat(resolver.getResources("classpath*:com/team3/notification/**/*.class")).isEmpty();
        assertThat(resolver.getResources("classpath*:com/team3/backend/notification/**/*.class")).isEmpty();
    }

    private Set<String> names(ClassPathScanningCandidateComponentProvider scanner) {
        return scanner.findCandidateComponents(ROOT).stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private ClassPathScanningCandidateComponentProvider scanner() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false, context.getEnvironment()) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.setResourceLoader(context);
        return scanner;
    }
}
