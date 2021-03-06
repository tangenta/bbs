package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.error.Error;
import com.gaufoo.bbs.application.error.ErrorCode;
import com.gaufoo.bbs.application.error.Ok;
import com.gaufoo.bbs.application.types.Content;
import com.gaufoo.bbs.application.types.Lecture;
import com.gaufoo.bbs.application.util.LazyVal;
import com.gaufoo.bbs.components.authenticator.common.UserToken;
import com.gaufoo.bbs.components.content.common.ContentId;
import com.gaufoo.bbs.components.lecture.common.LectureId;
import com.gaufoo.bbs.components.lecture.common.LectureInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;
import static com.gaufoo.bbs.util.TaskChain.*;

public class AppLecture {
    private static Logger log = LoggerFactory.getLogger(AppLecture.class);

    public static Lecture.AllLecturesResult allLectures(Long skip, Long first) {
        final long fSkip = skip == null ? 0L : skip;
        final long fFirst = first == null ? Long.MAX_VALUE : first;

        Supplier<List<Lecture.LectureInfo>> lecInfos = () -> componentFactory.lecture.allPostsTimeOrder()
                .map(lectureId -> consLectureInfo(lectureId,
                        LazyVal.of(() -> componentFactory.lecture.postInfo(lectureId).orElse(null))))
                .skip(fSkip).limit(fFirst)
                .collect(Collectors.toList());

        return consMultiLectures(lecInfos);
    }

    public static Lecture.LectureInfoResult lectureInfo(String lectureIdStr) {
        LectureId lectureId = LectureId.of(lectureIdStr);

        return componentFactory.lecture.postInfo(lectureId)
                .map(lectureInfo -> (Lecture.LectureInfoResult)consLectureInfo(lectureId, LazyVal.with(lectureInfo)))
                .orElse(Error.of(ErrorCode.LectureNotfound));
    }

    public static Lecture.CreateLectureResult createLecture(Lecture.LectureInput lectureInput, String userToken) {
        return Commons.ensureAdmin(UserToken.of(userToken))
                .then(__ -> consLectureInfo(lectureInput))
                .then(lectureInfo -> Procedure.fromOptional(componentFactory.lecture.publishPost(lectureInfo), ErrorCode.PublishLectureFailed)
                        .mapR(lectureId -> consLectureInfo(lectureId, lectureInput, ContentId.of(lectureInfo.contentId))))
                .reduce(Error::of, i -> i);
    }

    public static Lecture.EditLectureResult editLecture(String lectureIdStr, Lecture.LectureOptionalInput lectureInput, String userToken) {
        LectureId lectureId = LectureId.of(lectureIdStr);
        return Commons.ensureAdmin(UserToken.of(userToken))
                .then(__ -> Procedure.fromOptional(componentFactory.lecture.postInfo(lectureId), ErrorCode.LectureNotfound))
                .then(lectureInfo -> modLectureInfo(lectureInfo, lectureInput))
                .then(newLectureInfo -> Result.<ErrorCode, Boolean>of(componentFactory.lecture.changePost(lectureId, newLectureInfo))
                        .then(ok -> ok ? Result.of(newLectureInfo) : Fail.of(ErrorCode.UpdateLectureFailed)))
                .then(newLectureInfo -> Result.of(consLectureInfo(lectureId, LazyVal.with(newLectureInfo))))
                .reduce(Error::of, i -> i);
    }

    public static Lecture.DeleteLectureResult deleteLecture(String lectureId, String userToken) {
        return Commons.ensureAdmin(UserToken.of(userToken))
                .then(__ -> Result.of(componentFactory.lecture.removePost(LectureId.of(lectureId))))
                .then(success -> success ? Result.of(true) : Fail.of(ErrorCode.DeleteLectureFailed))
                .reduce(Error::of, ok -> Ok.build());
    }

    public static void reset() {
        componentFactory.lecture.allPosts().forEach(lectureId -> {
            componentFactory.lecture.postInfo(lectureId)
                    .map(lectureInfo -> lectureInfo.contentId)
                    .map(contentId -> componentFactory.content.remove(ContentId.of(contentId)));
            componentFactory.lecture.removePost(lectureId);
        });
    }

    private static Lecture.LectureInfo consLectureInfo(LectureId lectureId, LazyVal<LectureInfo> lectureInfo) {
        return new Lecture.LectureInfo() {
            public String getId()       { return lectureId.value; }
            public String getTitle()    { return nilOrTr(lectureInfo.get(), x -> x.title); }
            public Content getContent() { return nilOrTr(lectureInfo.get(), x -> fromIdToContent(ContentId.of(x.contentId))); }
            public String getPosition() { return nilOrTr(lectureInfo.get(), x -> x.position); }
            public Long   getTime()     { return nilOrTr(lectureInfo.get(), x -> x.time.toEpochMilli()); }
            public String getLecturer() { return nilOrTr(lectureInfo.get(), x -> x.lecturer); }
            public String getNote()     { return nilOrTr(lectureInfo.get(), x -> x.note); }
        };
    }

    private static Lecture.MultiLectures consMultiLectures(Supplier<List<Lecture.LectureInfo>> lecSupplier) {
        return new Lecture.MultiLectures() {
            public Long getTotalCount()                     { return componentFactory.lecture.allPostsCount();}
            public List<Lecture.LectureInfo> getLectures()  { return lecSupplier.get(); }
        };
    }

    private static Procedure<ErrorCode, LectureInfo> consLectureInfo(Lecture.LectureInput in) {
        return AppContent.storeContentInput(in.content)
                .mapR(contentId -> LectureInfo.of(in.title, contentId.value, in.position, Instant.ofEpochMilli(in.time), in.lecturer, in.note));
    }

    private static Lecture.LectureInfo consLectureInfo(LectureId lectureId, Lecture.LectureInput in, ContentId contentId) {
        return new Lecture.LectureInfo() {
            public String  getId()       { return lectureId.value; }
            public String  getTitle()    { return in.title; }
            public Content getContent()  { return fromIdToContent(contentId); }
            public String  getPosition() { return in.position; }
            public Long    getTime()     { return in.time; }
            public String  getLecturer() { return in.lecturer; }
            public String  getNote()     { return in.note; }
        };
    }

    private static Procedure<ErrorCode, LectureInfo> modLectureInfo(LectureInfo old, Lecture.LectureOptionalInput in) {
        Procedure<ErrorCode, ContentId> result = Result.of(null);
        if (in.content != null) {
            result = result.then(ig -> AppContent.consContent(in.content))
                    .then(contentInfo -> Procedure.fromOptional(componentFactory.content.cons(contentInfo), ErrorCode.CreateContentFailed))
                    .then(contentId -> Result.of(contentId, () -> componentFactory.content.remove(contentId)));
            if (!result.isSuccessful()) return Fail.of(result.retrieveError().get());
        }
        return result.then(nullableContentId -> Result.of(LectureInfo.of(
                preferNew(in.title, old.title),
                preferNew(nilOrTr(nullableContentId, x -> x.value), old.contentId),
                preferNew(in.position, old.position),
                preferNew(nilOrTr(in.time, Instant::ofEpochMilli), old.time),
                preferNew(in.lecturer, old.lecturer),
                preferNew(in.note, old.note)
        )));
    }

    private static Content fromIdToContent(ContentId contentId) {
        return AppContent.fromContentId(contentId).reduce(AppLecture::warnNil, i -> i);
    }

    private static <T, E> T warnNil(E error) {
        log.warn("null warning: {}", error);
        return null;
    }

    private static <T, R> R nilOrTr(T obj, Function<T, R> tr) {
        if (obj == null) return null;
        return tr.apply(obj);
    }

    private static <T, U> T preferNew(T newItem, U oldItem, Function<U ,T> oldTransformer) {
        if (newItem == null) {
            if (oldItem == null) return null;
            else return oldTransformer.apply(oldItem);
        } else return newItem;
    }

    private static <T> T preferNew(T newItem, T oldItem) {
        return preferNew(newItem, oldItem, i -> i);
    }
}
