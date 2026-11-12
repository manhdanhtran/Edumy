/**
 * Shared kernel (OPEN module): errors, security principal, text utils, JPA converters, web helpers.
 * Organised by topic, no layers. No business data. See docs/ai/01-architecture.md §1.5.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Shared",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.edumy.shared;
