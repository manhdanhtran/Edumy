package com.edumy.shared.jpa;

import org.hibernate.proxy.HibernateProxy;

/**
 * Helpers for the id-based, Hibernate-proxy-safe {@code equals}/{@code hashCode} every entity implements
 * (.cursor/rules/20-entity-repository.mdc):
 * <pre>{@code
 * public final boolean equals(Object o) {
 *     if (this == o) return true;
 *     if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) return false;
 *     Long id = getId();
 *     return id != null && id.equals(((Course) o).getId());
 * }
 * public final int hashCode() { return JpaEntities.effectiveClass(this).hashCode(); }
 * }</pre>
 */
public final class JpaEntities {

    private JpaEntities() {
    }

    /** The entity class behind {@code o}: the persistent class of a Hibernate proxy, else {@code o.getClass()}. */
    public static Class<?> effectiveClass(Object o) {
        return o instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
    }
}
