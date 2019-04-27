package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.error.Error;
import com.gaufoo.bbs.application.error.ErrorCode;
import com.gaufoo.bbs.application.error.Ok;
import com.gaufoo.bbs.application.types.Comment;
import com.gaufoo.bbs.application.types.Content;
import com.gaufoo.bbs.application.types.LearningResource;
import com.gaufoo.bbs.application.types.PersonalInformation;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;
import static com.gaufoo.bbs.util.TaskChain.*;

public class AppLearningResource {
    private static Logger log = LoggerFactory.getLogger(AppLearningResource.class);

    public static LearningResource.CreateLearningResourceResult createLearningResource(LearningResource.LearningResourceInput learningResourceInput, String userToken) {
        class Ctx {
            UserId userId;                            Void put(UserId userId)            { this.userId = userId;          return null; }
            LearningResourceInfo contructedLearnInfo; Void put(LearningResourceInfo lri) { this.contructedLearnInfo = lri; return null; }
            LearningResourceId learningResourceId;    Void put(LearningResourceId lri)   { this.learningResourceId = lri;  return null; }
        }
        Ctx ctx = new Ctx();

        return Commons.fetchUserId(UserToken.of(userToken)).mapR(ctx::put)
                .then(__ -> consLearningResourceInfo(ctx.userId, learningResourceInput)).mapR(ctx::put)
                .then(__ -> publishLearningResource(ctx.contructedLearnInfo)).mapR(ctx::put)
                .then(__ -> consActiveAndHeat(ctx.learningResourceId, ctx.userId))
                .reduce(Error::of, __ -> consLearningResourceInfoRet(ctx.userId, ctx.learningResourceId, learningResourceInput, ctx.contructedLearnInfo));
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
            CommentId commentId;            Void put(CommentId id)              { this.commentId = id;     return null; }
        }
        Ctx ctx = new Ctx();

        LearningResourceId learnId = LearningResourceId.of(input.postIdCommenting);
        return Commons.fetchUserId(UserToken.of(userToken)).mapR(ctx::put)
                .then(__ -> fetchLearningResourceInfo(learnId)).mapR(ctx::put)
                .then(__ -> AppContent.storeContentInput(input.content)).mapR(ctx::put)
                .then(__ -> Result.of(CommentInfo.of(ctx.contentId.value, ctx.userId.value)))
                .then(__ -> AppComment.postComment(CommentGroupId.of(ctx.learnInfo.commentGroupId), input.content, ctx.userId)).mapR(ctx::put)
                .reduce(Error::of, __ -> consCommentInfo(ctx.commentId, ctx.contentId, ctx.userId));
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
                .then(__ -> Result.of(LearningResourceInfo.of(userId.value, ctx.contentId.value, ctx.courseCode.value, ctx.attachedFileId.value, ctx.commentGroupId.value)));
    }

    private static Procedure<ErrorCode, Void> consActiveAndHeat(LearningResourceId learningResourceId, UserId userId) {
        String currentActiveTimeWin = Commons.currentActiveTimeWindow(Instant.now());
        String learnResourceGroupId = Commons.getGroupId(Commons.PostType.LearningResource);

        Optional<ActiveInfo> activeInfo = componentFactory.active.touch(Commons.getGroupId(Commons.PostType.LearningResource), learningResourceId.value, userId.value);
        Optional<ActiveInfo> mostActiveInfo = componentFactory.active.touch(currentActiveTimeWin, learnResourceGroupId + learningResourceId.value, userId.value);
        Optional<Long> heat = componentFactory.heat.increase(learnResourceGroupId, learningResourceId.value, 1);
        Optional<Long> hottest = componentFactory.heat.increase(currentActiveTimeWin, learnResourceGroupId + learningResourceId.value, 1);

        boolean success = activeInfo.isPresent() && mostActiveInfo.isPresent() && heat.isPresent() && hottest.isPresent();
        return success ? Result.of(null, () -> clearActiveAndHeat(learningResourceId)) : Fail.of(ErrorCode.CreateActiveAndHeatFailed);
    }

    private static Procedure<ErrorCode, Void> clearActiveAndHeat(LearningResourceId learningResourceId) {
        String currentActiveTimeWin = Commons.currentActiveTimeWindow(Instant.now());
        String lastActiveTimeWin = Commons.lastActiveTimeWindow(Instant.now());
        String groupId = Commons.getGroupId(Commons.PostType.LearningResource);

        boolean rmActive = componentFactory.active.remove(groupId, learningResourceId.value);
        boolean rmMostActive = componentFactory.active.remove(currentActiveTimeWin, groupId + learningResourceId.value);
        boolean rmLastMostActive = componentFactory.active.remove(lastActiveTimeWin, groupId + learningResourceId.value);
        boolean rmHeat = componentFactory.heat.remove(groupId, learningResourceId.value);
        boolean rmHottest = componentFactory.heat.remove(currentActiveTimeWin, groupId + learningResourceId.value);
        boolean rmLastHottest = componentFactory.heat.remove(lastActiveTimeWin, groupId + learningResourceId.value);

        boolean success = rmActive && (rmMostActive || rmLastMostActive) && rmHeat && (rmHottest || rmLastHottest);
        return success ? Result.of(null) : Fail.of(ErrorCode.ClearActiveAndHeatFailed);
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

    private static Comment.CommentInfo consCommentInfo(CommentId commentId, ContentId contentId, UserId authorId) {
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
        };
    }

    private static LearningResource.LearningResourceInfo consLearningResourceInfoRet(UserId userId, LearningResourceId resourceId, LearningResource.LearningResourceInput input, LearningResourceInfo commonInfo) {
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

    private static Procedure<ErrorCode, LearningResourceInfo> fetchLearningResourceInfo(LearningResourceId id) {
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

}
