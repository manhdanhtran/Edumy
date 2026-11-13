package com.edumy.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** UC-G03 {@code GET /api/v1/catalog/categories} on the dev seed: categories 1–7 active, 8 (Nhiếp ảnh) inactive. */
@MySqlSeedSpringBootTest
class CatalogCategoryControllerIT {

    static final String CATEGORIES = "/api/v1/catalog/categories";

    @Autowired
    MockMvc mvc;
    @Autowired
    SqlStatementCounter sql;

    @Test
    void list_anonymous_activeCategoriesByName_UCG03() throws Exception {
        mvc.perform(get(CATEGORIES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(3, 5, 2, 1, 7, 6, 4)))
                .andExpect(jsonPath("$[0].name").value("Khoa học dữ liệu"))
                .andExpect(jsonPath("$[0].slug").value("khoa-hoc-du-lieu"))
                .andExpect(jsonPath("$[3].name").value("Lập trình web"))
                .andExpect(jsonPath("$[?(@.id == 8)]").isEmpty());
    }

    @Test
    void list_oneStatement() throws Exception {
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk());
        sql.reset();
        mvc.perform(get(CATEGORIES)).andExpect(status().isOk());
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(1);
    }
}
