package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.error.Error;
import com.gaufoo.bbs.application.error.ErrorCode;
import com.gaufoo.bbs.application.error.Ok;
import com.gaufoo.bbs.application.types.Comment;
import com.gaufoo.bbs.application.types.Content;
import com.gaufoo.bbs.application.types.LearningResource;
import com.gaufoo.bbs.application.types.PersonalInformation;
import com.gaufoo.bbs.application.util.LazyVal;
import com.gaufoo.bbs.application.util.StaticResourceConfig;
import com.gaufoo.bbs.components.active.common.ActiveInfo;
import com.gaufoo.bbs.components.authenticator.Authenticator;
import com.gaufoo.bbs.components.authenticator.common.Permission;
import com.gaufoo.bbs.components.authenticator.common.UserToken;
import com.gaufoo.bbs.components.commentGroup.comment.common.CommentId;
import com.gaufoo.bbs.components.commentGroup.comment.common.CommentInfo;
import com.gaufoo.bbs.components.commentGroup.common.CommentGroupId;
import com.gaufoo.bbs.components.content.common.ContentId;
import com.gaufoo.bbs.components.file.common.FileId;
import com.gaufoo.bbs.components.learningResource.common.LearningResourceId;
import com.gaufoo.bbs.components.learningResource.common.LearningResourceInfo;
import com.gaufoo.bbs.components.scutCourse.common.Course;
import com.gaufoo.bbs.components.scutCourse.common.CourseCode;
import com.gaufoo.bbs.components.user.common.UserId;
import com.gaufoo.bbs.util.Tuple;
import com.sun.istack.internal.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;
import static com.gaufoo.bbs.util.TaskChain.*;

public class AppLearningResource {
    private static Logger log = LoggerFactory.getLogger(AppLearningResource.class);
    private static Consumer<ErrorCode> warnNil = errorCode -> log.warn("null warning: {}", errorCode);
    private static final String postGroupId = Commons.getGroupId(Commons.PostType.LearningResource);

    public static LearningResource.AllLearningResourceResult allLearningResources(Long skip, Long first, String courseStr, Commons.SortedBy sortedBy) {
        final long fSkip = skip == null ? 0L : skip;
        final long fFrist = first == null ? Long.MAX_VALUE : first;
        final Commons.SortedBy fSortedBy = sortedBy == null ? Commons.SortedBy.ActiveTimeDes : sortedBy;

        Predicate<CourseCode> courseFilter;
        Procedure<ErrorCode, CourseCode> initResult;
        if (courseStr == null) {
            courseFilter = __ -> true;
            initResult = Result.of(null);
        } else {
            initResult = parseCourse(courseStr);
            courseFilter = (courseCode -> courseCode.equals(initResult.retrieveResult().get()));
        }


        return initResult.mapR(nullableCourseCode -> selectLearnResources(fSortedBy, nullableCourseCode))
                .mapR(resourceIdStream -> resourceIdStream
                        .map(resId -> Tuple.of(resId, LazyVal.of(() -> fetchLearningResourceInfoAndUnwrap(resId, warnNil))))
//                        .filter(idLearnInfoTuple -> idLearnInfoTuple.right.get() != null)
                        .filter(idLearnInfOTuple -> courseFilter.test(CourseCode.of(idLearnInfOTuple.right.get().courseCode)))
                        .map(tup -> consLearningResourceInfoRet(tup.left, tup.right))
                        .skip(fSkip).limit(fFrist))
                .reduce(Error::of, infoStream -> consMultiLearnResources(infoStream, fSkip, fFrist));

    }

    public static LearningResource.CreateLearningResourceResult createLearningResource(LearningResource.LearningResourceInput learningResourceInput, String userToken) {
        class Ctx {
            UserId userId;                            Void put(UserId userId)            { this.userId = userId;           return null; }
            LearningResourceInfo contructedLearnInfo; Void put(LearningResourceInfo lri) { this.contructedLearnInfo = lri; return null; }
            LearningResourceId learningResourceId;    Void put(LearningResourceId lri)   { this.learningResourceId = lri;  return null; }
        }
        Ctx ctx = new Ctx();

        return Commons.fetchUserId(UserToken.of(userToken)).mapR(ctx::put)
                .then(__ -> consLearningResourceInfo(ctx.userId, learningResourceInput)).mapR(ctx::put)
                .then(__ -> publishLearningResource(ctx.contructedLearnInfo)).mapR(ctx::put)
                .then(__ -> consActiveAndHeat(ctx.learningResourceId, ctx.userId))
                .reduce(Error::of, __ -> consLearningResourceInfoAfterCreate(ctx.userId, ctx.learningResourceId, learningResourceInput, ctx.contructedLearnInfo));
    }

    public static LearningResource.DeleteLearningResourceResult deleteLearningResource(String learningResourceId, String userToken) {
        LearningResourceId learnId = LearningResourceId.of(learningResourceId);
        class Ctx {
            LearningResourceInfo learnInfo; Void put(LearningResourceInfo info) { this.learnInfo = info; return null; }
        }
        Ctx ctx = new Ctx();

        return Commons.fetchPermission(UserToken.of(userToken))
                .then(permission -> ensurePermission(learnId, permission))
                .then(__ -> fetchLearningResourceInfo(learnId)).mapR(ctx::put)
                .then(__ -> deleteLearnResourceInfoRefs(ctx.learnInfo))
                .then(__ -> clearActiveAndHeat(learnId))
                .then(__ -> deleteLearnResourceInfo(learnId))
                .reduce(Error::of, __ -> Ok.build());
    }

    public static LearningResource.CreateLearningResourceCommentResult createLearningResourceComment(LearningResource.LearningResourceCommentInput input, String userToken) {
        class Ctx {
            UserId userId;                  Void put(UserId userId)             { this.userId = userId;    return null; }
            LearningResourceInfo learnInfo; Void put(LearningResourceInfo info) { this.learnInfo = info;   return null; }
            ContentId contentId;            Void put(ContentId id)              { this.contentId = id;     return null; }
            CommentId commentId;
            CommentInfo commentInfo;        Void put(Tuple<CommentId, CommentInfo> tup) { this.commentId = tup.left; this.commentInfo = tup.right; return null; }
        }
        Ctx ctx = new Ctx();

        LearningResourceId learnId = LearningResourceId.of(input.postIdCommenting);
        return Commons.fetchUserId(UserToken.of(userToken)).mapR(ctx::put)
                .then(__ -> fetchLearningResourceInfo(learnId)).mapR(ctx::put)
                .then(__ -> AppContent.storeContentInput(input.content)).mapR(ctx::put)
                .then(__ -> AppComment.postComment(CommentGroupId.of(ctx.learnInfo.commentGroupId), input.content, ctx.userId)).mapR(ctx::put)
                .reduce(Error::of, __ -> consCommentInfoRet(ctx.commentId, ctx.contentId, ctx.userId, ctx.commentInfo));
    }

    public static LearningResource.DeleteLearningResourceCommentResult deleteLearningResourceComment(String learningResourceId, String commentIdStr, String userToken) {
        LearningResourceId learnId = LearningResourceId.of(learningResourceId);
        CommentId commentId = CommentId.of(commentIdStr);

        class Ctx {
            LearningResourceInfo learningResourceInfo; Void put(LearningResourceInfo info) { this.learningResourceInfo = info; return null; }
            CommentGroupId commentGroupId;             Void put(CommentGroupId id)         { this.commentGroupId = id;         return null; }
            CommentInfo commentInfo;                   Void put(CommentInfo info)          { this.commentInfo = info;          return null; }
        }
        Ctx ctx = new Ctx();

        return Commons.fetchPermission(UserToken.of(userToken))
                .then(permission -> ensurePermission(commentId, permission))
                .then(__ -> fetchLearningResourceInfo(learnId)).mapR(ctx::put)
                .then(__ -> Result.of(CommentGroupId.of(ctx.learningResourceInfo.commentGroupId))).mapR(ctx::put)
                .then(__ -> AppComment.fetchCommentInfo(commentId)).mapR(ctx::put)
                .then(__ -> alterHeat(learnId, ctx.commentInfo.creationTime, -1))
                .then(__ -> AppComment.deleteComment(ctx.commentGroupId, commentId))
                .reduce(Error::of, __ -> Ok.build());
    }

    private static Stream<LearningResourceId> selectLearnResources(Commons.SortedBy sortedBy, @Nullable CourseCode courseCode) {
        Function<Boolean, Stream<LearningResourceId>> natrual = courseCode == null ?
                (isDes) -> componentFactory.learningResource.allPosts(isDes) :
                (isDes) -> componentFactory.learningResource.allPostsOfCourse(courseCode.value, isDes);

        Stream<LearningResourceId> dataSource;
        switch (sortedBy) {
            case ActiveTimeAsc: dataSource = componentFactory.active.getAllAsc(postGroupId).map(LearningResourceId::of); break;
            case ActiveTimeDes: dataSource = componentFactory.active.getAllDes(postGroupId).map(LearningResourceId::of); break;
            case HeatAsc: dataSource = componentFactory.heat.getAllAsc(postGroupId).map(LearningResourceId::of); break;
            case HeatDes: dataSource = componentFactory.heat.getAllDes(postGroupId).map(LearningResourceId::of); break;
            case NatureAsc: dataSource = natrual.apply(false); break;
            case NatureDes: dataSource = natrual.apply(true); break;
            default: dataSource = null; // impossible to reach
        }

        return dataSource;
    }

    private static Stream<LearningResourceInfo> filterMatchedCourses(Stream<LearningResourceInfo> infos, @Nullable CourseCode courseCode) {
        Predicate<LearningResourceInfo> courseFilter = courseCode == null ?
                (__) -> true :
                (info) -> info.courseCode.equals(courseCode.value);

        return infos.filter(courseFilter);
    }

    private static Procedure<ErrorCode, LearningResourceId> publishLearningResource(LearningResourceInfo learningResourceInfo) {
        return Procedure.fromOptional(componentFactory.learningResource.publishPost(learningResourceInfo), ErrorCode.PublishLearningResourceFailed)
                .then(learningResourceId -> Result.of(learningResourceId, () -> componentFactory.learningResource.removePost(learningResourceId)));
    }

    private static Procedure<ErrorCode, LearningResourceInfo> consLearningResourceInfo(UserId userId, LearningResource.LearningResourceInput input) {
        class Ctx {
            CourseCode courseCode;         Void put(CourseCode courseCode)  { this.courseCode = courseCode; return null; }
            ContentId contentId;           Void put(ContentId   contentId)  { this.contentId = contentId;   return null; }
            FileId attachedFileId;         Void put(FileId         fileId)  { this.attachedFileId = fileId; return null; }
            CommentGroupId commentGroupId; Void put(CommentGroupId  cmgId)  { this.commentGroupId = cmgId;  return null; }
        }
        Ctx ctx = new Ctx();

        return parseCourse(input.course).mapR(ctx::put)
                .then(__ -> AppContent.storeContentInput(input.content)).mapR(ctx::put)
                .then(__ -> Commons.storeBase64File(componentFactory.learningResourceAttachFiles, input.base64AttachedFile)).mapR(ctx::put)
                .then(__ -> AppComment.createCommentGroup()).mapR(ctx::put)
                .then(__ -> Result.of(LearningResourceInfo.of(userId.value, input.title, ctx.contentId.value, ctx.courseCode.value, ctx.attachedFileId.value, ctx.commentGroupId.value)));
    }

    private static Procedure<ErrorCode, Void> consActiveAndHeat(LearningResourceId learningResourceId, UserId userId) {
        String currentActiveTimeWin = Commons.currentActiveTimeWindow();
        String learnResourceGroupId = Commons.getGroupId(Commons.PostType.LearningResource);

        Optional<ActiveInfo> activeInfo = componentFactory.active.touch(Commons.getGroupId(Commons.PostType.LearningResource), learningResourceId.value, userId.value);
        Optional<ActiveInfo> mostActiveInfo = componentFactory.active.touch(currentActiveTimeWin, learnResourceGroupId + learningResourceId.value, userId.value);
        Optional<Long> heat = componentFactory.heat.increase(learnResourceGroupId, learningResourceId.value, 1);
        Optional<Long> hottest = componentFactory.heat.increase(currentActiveTimeWin, learnResourceGroupId + learningResourceId.value, 1);

        boolean success = activeInfo.isPresent() && mostActiveInfo.isPresent() && heat.isPresent() && hottest.isPresent();
        return success ? Result.of(null, () -> clearActiveAndHeat(learningResourceId)) : Fail.of(ErrorCode.CreateActiveAndHeatFailed);
    }

    private static Procedure<ErrorCode, Void> clearActiveAndHeat(LearningResourceId learningResourceId) {
        String currentActiveTimeWin = Commons.currentActiveTimeWindow();
        String lastActiveTimeWin = Commons.lastActiveTimeWindow();

        boolean rmActive = componentFactory.active.remove(postGroupId, learningResourceId.value);
        componentFactory.active.remove(currentActiveTimeWin, postGroupId + learningResourceId.value);
        componentFactory.active.remove(lastActiveTimeWin, postGroupId + learningResourceId.value);
        boolean rmHeat = componentFactory.heat.remove(postGroupId, learningResourceId.value);
        componentFactory.heat.remove(currentActiveTimeWin, postGroupId + learningResourceId.value);
        componentFactory.heat.remove(lastActiveTimeWin, postGroupId + learningResourceId.value);

        boolean success = rmActive && rmHeat;
        return success ? Result.of(null) : Fail.of(ErrorCode.ClearActiveAndHeatFailed);
    }

    private static Procedure<ErrorCode, Void> alterHeat(LearningResourceId learningResourceId, Instant creationTime, long delta) {
        String learnId = learningResourceId.value;

        String creatTimeWin = Commons.heatTimeWindow(creationTime);
        String curHeatTimeWin = Commons.currentHeatTimeWindow();
        String lastHeatTimeWin = Commons.lastHeatTimeWindow();

        return Procedure.fromOptional(componentFactory.heat.increase(postGroupId, learnId, delta),
                    ErrorCode.AlterHeatFailed, () -> componentFactory.heat.increase(postGroupId, learnId, -delta))
                .then(__ -> Result.of(creatTimeWin.equals(curHeatTimeWin) || creatTimeWin.equals(lastHeatTimeWin)))
                .then(needAlterHottest -> !needAlterHottest ? Result.of(null) :
                    Procedure.fromOptional(componentFactory.heat.increase(creatTimeWin, postGroupId + learnId, delta),
                            ErrorCode.AlterHeatFailed, () -> componentFactory.heat.increase(creatTimeWin, postGroupId + learnId, -delta))
                        .then(__ -> Result.of(null))
                );
    }

    private static Procedure<ErrorCode, Void> deleteLearnResourceInfoRefs(LearningResourceInfo info) {
        componentFactory.learningResourceAttachFiles.Remove(FileId.of(info.attachedFileId));
        boolean rmContent = componentFactory.content.remove(ContentId.of(info.contentId));
        boolean rmComment = componentFactory.commentGroup.removeComments(CommentGroupId.of(info.commentGroupId));

        boolean success = rmContent && rmComment;
        return success ? Result.of(null) : Fail.of(ErrorCode.DeleteLearningResourceFailed);
    }

    private static Procedure<ErrorCode, Void> deleteLearnResourceInfo(LearningResourceId id) {
        return componentFactory.learningResource.removePost(id) ? Result.of(null) : Fail.of(ErrorCode.DeleteLearningResourceFailed);
    }

    private static Comment.CommentInfo consCommentInfoRet(CommentId commentId, ContentId contentId, UserId authorId, CommentInfo commentInfo) {
        return new Comment.CommentInfo() {
            public String getId() { return commentId.value; }
            public Content getContent() {
                return AppContent.fromContentId(contentId).reduce(AppLearningResource::warnNil, i -> i);
            }
            public PersonalInformation.PersonalInfo getAuthor() {
                return Commons.fetchPersonalInfo(authorId).reduce(AppLearningResource::warnNil, i -> i);
            }
            public Comment.AllReplies getAllReplies(Long skip, Long first) {
                return new Comment.AllReplies() {
                    public Long getTotalCount()                 { return 0L;                 }
                    public List<Comment.ReplyInfo> getReplies() { return new LinkedList<>(); }
                };
            }
            public Long getCreationTime() {
                return commentInfo.creationTime.toEpochMilli();
            }
        };
    }

    private static LearningResource.MultiLearningResources consMultiLearnResources(Stream<LearningResource.LearningResourceInfo> infos, long skip, long first) {
        return new LearningResource.MultiLearningResources() {
            public Long getTotalCount() { return componentFactory.learningResource.allPostsCount(); }
            public List<LearningResource.LearningResourceInfo> getLearningResources() {
                return infos.skip(skip).limit(first).collect(Collectors.toList());
            }
        };
    }

    private static LearningResource.LearningResourceInfo consLearningResourceInfoRet(LearningResourceId resourceId, LazyVal<LearningResourceInfo> info) {
        return new LearningResource.LearningResourceInfo() {
            LazyVal<ActiveInfo> latestActiveInfo = LazyVal.of(() ->
                    componentFactory.active.getLatestActiveInfo(info.get().commentGroupId, resourceId.value)
                            .orElse(warnNil(ErrorCode.LatestActiveNotFound)));

            public String getId() { return resourceId.value; }
            public PersonalInformation.PersonalInfo getAuthor() {
                return Commons.fetchPersonalInfoAndUnwrap(UserId.of(info.get().authorId), warnNil);
            }
            public String getTitle()    { return info.get().title; }
            public Long getCreateTime() { return info.get().createTime.toEpochMilli(); }
            public Content getContent() {
                return AppContent.fromContentId(ContentId.of(info.get().contentId)).reduce(AppLearningResource::warnNil, i -> i);
            }
            public String getCourse() {
                return componentFactory.course.getCourseFromCode(CourseCode.of(info.get().courseCode)).map(Enum::toString).orElse(null);
            }
            public String getAttachedFileURL() {
                return Commons.fetchFileUrlAndUnwrap(componentFactory.learningResourceAttachFiles, StaticResourceConfig.FileType.AttachFiles, FileId.of(info.get().attachedFileId), warnNil);
            }
            public PersonalInformation.PersonalInfo getLatestCommenter() {
                return nilOrTr(latestActiveInfo.get(), activeInfo ->
                    Commons.fetchPersonalInfoAndUnwrap(UserId.of(activeInfo.toucherId), warnNil)
                );
            }
            public Long getLatestActiveTime() {
                return nilOrTr(latestActiveInfo.get(), x -> x.time.toEpochMilli());
            }
            public Comment.AllComments getAllComments(Long skip, Long first) {
                skip = skip == null ? 0L : skip;
                first = first == null ? Long.MAX_VALUE : first;
                return AppComment.consAllComments(CommentGroupId.of(info.get().commentGroupId), skip, first);
            }
        };
    }

    private static LearningResource.LearningResourceInfo consLearningResourceInfoAfterCreate(UserId userId, LearningResourceId resourceId, LearningResource.LearningResourceInput input, LearningResourceInfo commonInfo) {
        return new LearningResource.LearningResourceInfo() {
            public String getId()               { return resourceId.value; }
            public String getTitle()            { return input.title; }
            public Content getContent()         { return fromIdToContent(ContentId.of(commonInfo.contentId)); }
            public String getCourse()           { return input.course; }
            public Long getLatestActiveTime()   { return Instant.now().toEpochMilli(); }
            public Long getCreateTime()         { return Instant.now().toEpochMilli(); }
            public PersonalInformation.PersonalInfo getLatestCommenter() { return null; }
            public PersonalInformation.PersonalInfo getAuthor() {
                return Commons.fetchPersonalInfo(userId).reduce(AppLearningResource::warnNil, i -> i);
            }
            public String getAttachedFileURL() {
                return Commons.fetchFileUrl(componentFactory.learningResourceAttachFiles, StaticResourceConfig.FileType.AttachFiles, FileId.of(commonInfo.attachedFileId))
                        .reduce(AppLearningResource::warnNil, url -> url);
            }
            public Comment.AllComments getAllComments(Long skip, Long first) {
                return new Comment.AllComments() {
                    public Long getTotalCount() { return 0L; }
                    public List<Comment.CommentInfo> getComments() { return new LinkedList<>(); }
                };
            }
        };
    }

    private static Procedure<ErrorCode, Void> ensurePermission(LearningResourceId learningResourceId, Permission permission) {
        return fetchLearningResourceInfo(learningResourceId)
                .then(learningResourceInfo -> Result.of(learningResourceInfo.authorId.equals(permission.userId) || permission.role.equals(Authenticator.Role.ADMIN)))
                .then(success -> success ? Result.of(null) : Fail.of(ErrorCode.PermissionDenied));
    }

    private static Procedure<ErrorCode, Void> ensurePermission(CommentId commentId, Permission permission) {
        return AppComment.fetchCommentInfo(commentId)
                .then(commentInfo -> Result.of(commentInfo.commenter.equals(permission.userId) || permission.role.equals(Authenticator.Role.ADMIN)))
                .then(ok -> ok ? Result.of(null) : Fail.of(ErrorCode.PermissionDenied));
    }

    protected static LearningResourceInfo fetchLearningResourceInfoAndUnwrap(LearningResourceId id, Consumer<ErrorCode> nilCallback) {
        return fetchLearningResourceInfo(id).reduce(e -> {
            nilCallback.accept(e);
            return null;
        }, i -> i);
    }

    protected static Procedure<ErrorCode, LearningResourceInfo> fetchLearningResourceInfo(LearningResourceId id) {
        return Procedure.fromOptional(componentFactory.learningResource.postInfo(id), ErrorCode.LearningResourceNonExist);
    }

    private static Procedure<ErrorCode, CourseCode> parseCourse(String courseStr) {
        return Procedure.fromOptional(Arrays.stream(Course.values())
                .filter(course -> course.toString().equals(courseStr))
                .findFirst(), ErrorCode.ParseCourseError
        ).then(course -> Result.of(componentFactory.course.generateCourseCode(course)));
    }

    private static Content fromIdToContent(ContentId contentId) {
        return AppContent.fromContentId(contentId).reduce(AppLearningResource::warnNil, i -> i);
    }

    private static <T, E> T warnNil(E error) {
        log.warn("null warning: {}", error);
        return null;
    }

    private static <T, R> R nilOrTr(T obj, Function<T, R> transformer) {
        if (obj == null) return null;
        else return transformer.apply(obj);
    }
}
