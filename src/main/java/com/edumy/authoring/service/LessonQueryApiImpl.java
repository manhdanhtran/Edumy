package com.edumy.authoring.service;

import java.util.Optional;

import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.authoring.repository.LessonRepository;
import org.springframework.stereotype.Service;

@Service
class LessonQueryApiImpl implements LessonQueryApi {

    private final LessonRepository lessons;

    LessonQueryApiImpl(LessonRepository lessons) {
        this.lessons = lessons;
    }

    @Override
    public Optional<Long> findCourseId(Long lessonId) {
        return lessonId == null ? Optional.empty() : lessons.findCourseIdById(lessonId);
    }
}
