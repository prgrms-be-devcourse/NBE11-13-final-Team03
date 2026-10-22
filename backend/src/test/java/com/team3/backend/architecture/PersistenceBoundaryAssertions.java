package com.team3.backend.architecture;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.util.ClassUtils;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class PersistenceBoundaryAssertions {

    private static final String ROOT = "com.team3.backend";
    private static final String NOTIFICATION = ROOT + ".notification.";

    private PersistenceBoundaryAssertions() {
    }

    static void assertComplete(ApplicationContext context, EntityManagerFactory factory,
                               boolean notificationRole) {
        var entityScanner = scanner(context);
        entityScanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> allEntities = names(entityScanner);
        assertThat(allEntities).as("Entity discovery must not be empty").isNotEmpty();

        Set<String> expectedEntities = allEntities.stream()
                .filter(name -> belongsToRole(name, notificationRole))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> actualEntities = factory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actualEntities).as("Managed entities must match the complete role boundary")
                .containsExactlyInAnyOrderElementsOf(expectedEntities);

        var repositoryScanner = scanner(context);
        repositoryScanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        repositoryScanner.addIncludeFilter(new AnnotationTypeFilter(RepositoryDefinition.class));
        repositoryScanner.addExcludeFilter(new AnnotationTypeFilter(NoRepositoryBean.class));
        Set<String> repositories = names(repositoryScanner);
        assertThat(repositories).as("Repository discovery must not be empty").isNotEmpty();
        for (String name : repositories) {
            Class<?> type = ClassUtils.resolveClassName(name, context.getClassLoader());
            if (!type.isInterface()) {
                continue;
            }
            assertThat(context.getBeanNamesForType(type))
                    .as("Repository registration: %s", name)
                    .hasSize(belongsToRole(name, notificationRole) ? 1 : 0);
        }
    }

    private static boolean belongsToRole(String name, boolean notificationRole) {
        return name.startsWith(NOTIFICATION) == notificationRole;
    }

    private static Set<String> names(ClassPathScanningCandidateComponentProvider scanner) {
        return scanner.findCandidateComponents(ROOT).stream()
                .map(definition -> definition.getBeanClassName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static ClassPathScanningCandidateComponentProvider scanner(ApplicationContext context) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false, context.getEnvironment()) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                // Spring's default component scanner excludes interfaces; repositories are interfaces.
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.setResourceLoader(context);
        return scanner;
    }
}
