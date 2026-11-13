package com.edumy.authoring.service;

import com.edumy.authoring.api.ChapterOutline;
import com.edumy.authoring.api.LessonContent;
import com.edumy.authoring.api.LessonQueryApi;
import com.edumy.authoring.entity.Lesson;
import com.edumy.authoring.repository.ChapterRepository;
import com.edumy.authoring.repository.LessonRepository;
import com.edumy.authoring.repository.LessonRepository.LessonCountView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class LessonQueryApiImpl implements LessonQueryApi {

    private final LessonRepository lessons;
    private final ChapterRepository chapters;

    LessonQueryApiImpl(LessonRepository lessons, ChapterRepository chapters) {
        this.lessons = lessons;
        this.chapters = chapters;
    }

    @Override
    public Optional<Long> findCourseId(Long lessonId) {
        return lessonId == null ? Optional.empty() : lessons.findCourseIdById(lessonId);
    }

    @Override
    public Map<Long, Integer> countLessons(Collection<Long> courseIds) {
        List<Long> ids = courseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return lessons.countByCourseIdIn(ids).stream()
                .collect(Collectors.toUnmodifiableMap(LessonCountView::getCourseId, c -> (int) c.getLessonCount()));
    }

    @Override
    public List<ChapterOutline> findOutline(Long courseId) {
        if (courseId == null) {
            return List.of();
        }
        Map<Long, List<ChapterOutline.LessonOutline>> byChapter = new LinkedHashMap<>();
        lessons.findByCourseIdOrdered(courseId).forEach(l -> byChapter
                .computeIfAbsent(l.getChapter().getId(), k -> new ArrayList<>())
                .add(new ChapterOutline.LessonOutline(l.getId(), l.getTitle(), l.getContentType().code(),
                        l.getDurationSeconds(), l.isPreview())));
        return chapters.findByCourseIdOrderBySortOrderAsc(courseId).stream()
                .map(ch -> new ChapterOutline(ch.getId(), ch.getTitle(),
                        List.copyOf(byChapter.getOrDefault(ch.getId(), List.of()))))
                .toList();
    }

    @Override
    public Optional<LessonContent> findContent(Long lessonId) {
        if (lessonId == null) {
            return Optional.empty();
        }
        return lessons.findWithChapterById(lessonId).map(this::toContent);
    }

    private LessonContent toContent(Lesson l) {
        return new LessonContent(l.getId(), l.getChapter().getCourse().getId(), l.getChapter().getId(), l.getTitle(),
                l.getContentType().code(), l.getVideoUrl(), l.getArticleContent(), l.getDurationSeconds());
    }
}
