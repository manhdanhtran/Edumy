/**
 * 02 Catalog module. See docs/ai/01-architecture.md §1.5 and §1.8.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "02 Catalog",
        allowedDependencies = {"shared", "authoring :: api", "learning :: api"})
package com.edumy.catalog;
