package com.cosium.spring.data.jpa.entity.graph.repository.support;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.QueryHints;

/**
 * Created on 24/11/16.
 *
 * @author Reda.Housni-Alaoui
 */
class RepositoryQueryEntityGraphInjector implements MethodInterceptor {

  private static final Logger LOG =
      LoggerFactory.getLogger(RepositoryQueryEntityGraphInjector.class);

  private static final List<String> EXECUTE_QUERY_METHODS =
      Arrays.asList("getResultList", "getSingleResult", "scroll");
  private static final String UNWRAP_METHOD = "unwrap";

  private final EntityManager entityManager;
  private final EntityGraphBean entityGraphCandidate;

  private RepositoryQueryEntityGraphInjector(
      EntityManager entityManager, EntityGraphBean entityGraphCandidate) {
    this.entityManager = requireNonNull(entityManager);
    this.entityGraphCandidate = requireNonNull(entityGraphCandidate);
  }

  static Query proxy(
      Query query, EntityManager entityManager, EntityGraphBean entityGraphCandidate) {
    ProxyFactory proxyFactory = new ProxyFactory(query);
    proxyFactory.addAdvice(
        new RepositoryQueryEntityGraphInjector(entityManager, entityGraphCandidate));
    return (Query) proxyFactory.getProxy();
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    String invokedMethodName = invocation.getMethod().getName();
    Object[] invokedMethodArguments = invocation.getArguments();
    if (UNWRAP_METHOD.equals(invocation.getMethod().getName())
        && invokedMethodArguments.length == 1
        && invokedMethodArguments[0] == null) {
      // Since
      // https://github.com/spring-projects/spring-data-jpa/commit/74ff5b3a65b4a6d8df391be656c2bbb3373e3fae#diff-3f3f77fedc45cbd28380d53c7d7e481cR301, when Spring
      // Data JPA finds a proxied Query it calls javax.persistence.Query.unwrap(null). Because of
      // the
      // passed null argument, Hibernate target fails with a NullPointerException.
      //
      // It seems that Spring Data JPA does this to compensate a bug in EclipseLink. Take a look at
      // https://github.com/spring-projects/spring-data-jpa/commit/74ff5b3a65b4a6d8df391be656c2bbb3373e3fae#diff-3f3f77fedc45cbd28380d53c7d7e481cR224.
      // To avoid the null pointer exception, we return the unwrapped query. According to the
      // existing automated tests, Spring Data JPA does not execute query from the unwrapped query,
      // so there should be no missing EntityGraph. If the future proves we were wrong, the
      // alternative is to return a proxied
      // query eliminating compatibility between this library and EclipseLink :(
      return invocation.getThis();
    }
    if (EXECUTE_QUERY_METHODS.contains(invokedMethodName)) {
      addEntityGraphToQuery((Query) invocation.getThis());
    }
    return invocation.proceed();
  }

  private void addEntityGraphToQuery(Query query) {
    if (CountQueryDetector.isCountQuery()) {
      LOG.trace("CountQuery detected.");
      return;
    }
    if (!entityGraphCandidate.isPrimary()
        && QueryHintsUtils.containsEntityGraph(query.getHints())) {
      LOG.trace(
          "The query hints passed with the find method already hold an entity graph. Overriding aborted because the candidate EntityGraph is optional.");
      return;
    }

    QueryHintsUtils.removeEntityGraphs(query.getHints());
    QueryHints hints = QueryHintsUtils.buildQueryHints(entityManager, entityGraphCandidate);

    hints.forEach(query::setHint);
  }
}
