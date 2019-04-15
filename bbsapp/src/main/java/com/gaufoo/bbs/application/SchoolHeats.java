package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.util.Utils;
import com.gaufoo.bbs.components.authenticator.common.UserToken;
import com.gaufoo.bbs.components.authenticator.exceptions.AuthenticatorException;
import com.gaufoo.bbs.components.reply.common.ReplyId;
import com.gaufoo.bbs.components.reply.common.ReplyInfo;
import com.gaufoo.bbs.components.schoolHeat.common.PostComparators;
import com.gaufoo.bbs.components.schoolHeat.common.PostId;
import com.gaufoo.bbs.components.schoolHeat.common.PostInfo;
import com.gaufoo.bbs.components.user.common.UserId;
import com.gaufoo.bbs.util.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;

public class SchoolHeats {
    private static Logger logger = LoggerFactory.getLogger(SchoolHeats.class);

    public static CreatePostResult createPost(String userToken, PostInfoInput input) {
        logger.debug("createPost, userToken: {}, input: {}", userToken, input);
        try {
            ensurePostInputNonNull(input);

            UserId userId = fetchUserId(userToken);
            PostInfo itemInfo = buildPostItemInfo(userId, input);

            PostId result = publishPost(itemInfo);

            return CreatePostSuccess.of(result.value);

        } catch (AuthenticatorException | PostInputNullException | CreatePostException  e) {
            logger.debug("createPost - failed, error: {}, userToken: {}, input: {}", e.getMessage(), userToken, input);
            return SchoolHeatError.of(e.getMessage());
        }
    }

    private static void ensurePostInputNonNull(PostInfoInput input) {
        if (input.title == null || input.content == null ||
            input.title.isEmpty() || input.content.isEmpty()) {
            throw new PostInputNullException();
        }
    }

    private static UserId fetchUserId(String userToken) throws AuthenticatorException {
        String userIdStr = componentFactory.authenticator.getLoggedUser(UserToken.of(userToken)).userId;
        return UserId.of(userIdStr);
    }

    private static PostInfo buildPostItemInfo(UserId userId, PostInfoInput input) {
        return PostInfo.of(input.title, input.content, userId.value, null, 0, new LinkedList<>(),
                new LinkedList<>(), Instant.now(), Instant.now());
    }

    private static PostId publishPost(PostInfo postInfo) {
        return componentFactory.schoolHeat.publishPost(postInfo)
                .orElseThrow(() -> {
                    logger.debug("publishPost - failed, error: {}, postInfo: {}", "创建帖子失败",  postInfo);
                    return new CreatePostException();
                });
    }



    public static ModifyPostResult updatePost(String userToken, String postId, PostInfoInput input) {
        logger.debug("updatePost, userToken: {}, input: {}", userToken, input);
        try {
            UserId userId = fetchUserId(userToken);

            PostInfo oldPostInfo = fetchPostInfo(PostId.of(postId));
            checkPostPermission(oldPostInfo, userId);
            PostInfo newPostInfo = modOldPost(oldPostInfo, input);

            componentFactory.schoolHeat.updatePost(PostId.of(postId), newPostInfo);

            return SchoolHeatSuccess.build();

        } catch (AuthenticatorException | PostNonExistException e) {
            logger.debug("updatePost - failed, error: {}, userToken: {}, input: {}", e.getMessage(), userToken, input);
            return SchoolHeatError.of(e.getMessage());
        }
    }

    private static PostInfo fetchPostInfo(PostId postId) {
        return componentFactory.schoolHeat.postInfo(postId)
                .orElseThrow(() -> {
                    logger.debug("fetchPostInfo - failed, postId: {}", postId);
                    return new PostNonExistException();
                });
    }

    private static void checkPostPermission(PostInfo postInfo, UserId userId) {
        if (!postInfo.author.equals(userId.value)) {
            throw new PostPermissionException();
        }
    }

    private static PostInfo modOldPost(PostInfo postInfo, PostInfoInput input) {
        return postInfo.modTitle(input.title)
                .modContent(input.content)
                .modLatestActiveTime(Instant.now());
    }



    public static ModifyPostResult deletePost(String userToken, String postId) {
        logger.debug("deletePost, userToken: {}, postId: {}", userToken, postId);
        try {
            UserId userId = fetchUserId(userToken);

            PostInfo postToDel = fetchPostInfo(PostId.of(postId));
            checkPostPermission(postToDel, userId);

            removePostAndReplies(postId, postToDel);

            return SchoolHeatSuccess.build();

        } catch (AuthenticatorException | PostNonExistException e) {
            logger.debug("deletePost - failed, error: {}, userToken: {}, postId: {}", e.getMessage(), userToken, postId);
            return SchoolHeatError.of(e.getMessage());
        }
    }

    private static void removePostAndReplies(String postId, PostInfo postInfo) {
        componentFactory.schoolHeat.removePost(PostId.of(postId));
        postInfo.replyIdentifiers.forEach(replyIdentifier ->
                componentFactory.reply.removeReply(ReplyId.of(replyIdentifier))
        );
    }


    public enum SortedBy {
        TimeAsc,
        TimeDes,
        HeatAsc,
        HeatDes,
    }
    public static List<PostItemInfo> allPosts(int skip, int first, SortedBy sortedBy) {
        Comparator<PostInfo> comparator = null;
        switch (sortedBy) {
            case TimeAsc: comparator = PostComparators.comparingTime; break;
            case TimeDes: comparator = PostComparators.comparingTimeReversed; break;
            case HeatAsc: comparator = PostComparators.comparingHeat; break;
            case HeatDes: comparator = PostComparators.comparingHeatReversed; break;
        }
        Stream<PostId> postIds = componentFactory.schoolHeat.allPosts(comparator);

        return convertIdsToItemInfo(postIds)
                .skip(skip).limit(first)
                .collect(Collectors.toList());
    }

    private static Stream<PostItemInfo> convertIdsToItemInfo(Stream<PostId> postIdStream) {
        return postIdStream.map(postId -> Tuple.of(postId, componentFactory.schoolHeat.postInfo(postId)))
                .filter(idInfoTup -> idInfoTup.right.isPresent())
                .map(idInfoTup -> Tuple.of(idInfoTup.left, idInfoTup.right.get()))
                .map(idPostInfoTuple -> constructPostItemInfo(idPostInfoTuple.left, idPostInfoTuple.right));
    }

    private static PostItemInfo constructPostItemInfo(PostId postId, PostInfo postInfo) {
        return new PostItemInfo() {
            @Override
            public String getPostId() {
                return postId.value;
            }
            @Override
            public String getTitle() {
                return Utils.nilStrToEmpty(postInfo.title);
            }
            @Override
            public String getContent() {
                return Utils.nilStrToEmpty(postInfo.content);
            }
            @Override
            public PersonalInformation.PersonalInfo getAuthor() {
                return PersonalInformation.personalInfo(UserId.of(postInfo.author))
                        .orElse(null);
            }
            @Override
            public PersonalInformation.PersonalInfo getLatestReplier() {
                if (postInfo.latestReplier == null) return null;
                return PersonalInformation.personalInfo(UserId.of(postInfo.latestReplier))
                        .orElse(null);
            }
            @Override
            public Long getLatestActiveTime() {
                return postInfo.latestActiveTime.toEpochMilli();
            }
            @Override
            public Long getCreateTime() {
                return postInfo.createTime.toEpochMilli();
            }
            @Override
            public Integer getHeat() {
                return postInfo.heat;
            }
            @Override
            public List<ReplyItemInfo> getAllReplies() {
                return postInfo.replyIdentifiers.stream().map(replyIdVal -> {
                    ReplyId replyId = ReplyId.of(replyIdVal);
                    Optional<ReplyInfo> oReplyInfo = componentFactory.reply.replyInfo(replyId);
                    return oReplyInfo.map(replyInfo -> constructReplyItemInfo(replyId, replyInfo))
                            .orElse(null);
                }).filter(Objects::nonNull).collect(Collectors.toList());
            }
        };
    }

    private static ReplyItemInfo constructReplyItemInfo(ReplyId replyId, ReplyInfo replyInfo) {
        return new ReplyItemInfo() {
            @Override
            public String getReplyId() {
                return replyId.value;
            }
            @Override
            public String getPostIdReplying() {
                return replyInfo.subject;
            }
            @Override
            public String getContent() {
                return Utils.nilStrToEmpty(replyInfo.content);
            }
            @Override
            public PersonalInformation.PersonalInfo getAuthor() {
                return PersonalInformation.personalInfo(UserId.of(replyInfo.replier))
                        .orElse(null);
            }
            @Override
            public List<CommentItemInfo> getAllComments() {
                return replyInfo.comments.stream()
                        .map(SchoolHeats::constructCommentItemInfo)
                        .collect(Collectors.toList());
            }
        };
    }

    private static CommentItemInfo constructCommentItemInfo(ReplyInfo.Comment comment) {
        return new CommentItemInfo() {
            @Override
            public String getContent() {
                return Utils.nilStrToEmpty(comment.content);
            }
            @Override
            public PersonalInformation.PersonalInfo getCommentTo() {
                if (comment.commentTo == null) return null;
                return PersonalInformation.personalInfo(UserId.of(comment.commentTo))
                        .orElse(null);
            }
            @Override
            public PersonalInformation.PersonalInfo getAuthor() {
                return PersonalInformation.personalInfo(UserId.of(comment.commentator))
                        .orElse(null);
            }
        };
    }


    private interface UndoFunction {
        void undo();
    }

    public static CreateReplyResult createReply(String userToken, ReplyInfoInput input) {
        logger.debug("createReply, input: {}", input);
        List<UndoFunction> undoFunctions = new LinkedList<>();
        try {
            UserId userId = fetchUserId(userToken);

            ReplyInfo replyInfo = buildReplyInfo(userId, input);
            Optional<ReplyId> replyId = componentFactory.reply.reply(replyInfo);
            if (!replyId.isPresent()) {
                logger.debug("createReply - failed, error: {}, input: {}", "添加回复失败", input);
                return SchoolHeatError.of("添加回复失败");
            }
            undoFunctions.add(() -> componentFactory.reply.removeReply(replyId.get()));

            PostId postId = PostId.of(input.postIdToReply);
            componentFactory.schoolHeat.addReply(postId, replyId.get().value);
            undoFunctions.add(() -> componentFactory.schoolHeat.removeReply(postId, replyId.get().value));

            PostItemInfo postItemInfo = constructPostItemInfo(postId, fetchPostInfo(postId));
            return CreateReplySuccess.of(postItemInfo);

        } catch (AuthenticatorException e) {
            logger.debug("createReply - failed, error: {}, input: {}", e.getMessage(), input);
            undoFunctions.forEach(UndoFunction::undo);
            return SchoolHeatError.of(e.getMessage());
        }
    }

    private static ReplyInfo buildReplyInfo(UserId userId, ReplyInfoInput input) {
        return ReplyInfo.of(input.postIdToReply, input.content, userId.value);
    }



    public static CreateCommentResult createComment(String userToken, CommentInfoInput input) {
        logger.debug("createComment, input: {}", input);
        try {
            UserId userId = fetchUserId(userToken);

            ReplyInfo.Comment comment = ReplyInfo.Comment.of(userId.value, input.content, input.replyIdToComment);
            boolean success = componentFactory.reply.comment(ReplyId.of(input.replyIdToComment), comment);
            if (!success) return SchoolHeatError.of("添加评论失败");

            ReplyInfo replyInfo = fetchReplyInfo(ReplyId.of(input.replyIdToComment));
            PostId postId = PostId.of(replyInfo.subject);
            PostInfo postInfo = fetchPostInfo(postId);

            PostItemInfo postItemInfo = constructPostItemInfo(postId, postInfo);

            return CreateCommentSuccess.of(postItemInfo);

        } catch (AuthenticatorException | ReplyNonExistException e) {
            logger.debug("createComment - failed, error: {}, input: {}", e.getMessage(), input);
            return SchoolHeatError.of(e.getMessage());
        }
    }

    private static ReplyInfo fetchReplyInfo(ReplyId replyId) {
        return componentFactory.reply.replyInfo(replyId)
                .orElseThrow(() -> {
                    logger.debug("fetchReplyInfo - failed, replyId: {}", replyId);
                    return new ReplyNonExistException();
                });
    }



    // for test
    public static void reset() {
        componentFactory.schoolHeat.allPosts(PostComparators.comparingTime).forEach(postId -> {
            PostInfo postInfo = componentFactory.schoolHeat.postInfo(postId).orElse(null);
            componentFactory.schoolHeat.removePost(postId);

            if (postInfo == null) return;
            postInfo.replyIdentifiers.forEach(replyId ->
                    componentFactory.reply.removeReply(ReplyId.of(replyId))
            );
        });

    }

    public interface CommentItemInfo {
        String getContent();
        PersonalInformation.PersonalInfo getCommentTo();
        PersonalInformation.PersonalInfo getAuthor();
    }

    public interface ReplyItemInfo {
        String getReplyId();
        String getPostIdReplying();
        String getContent();
        PersonalInformation.PersonalInfo getAuthor();
        List<CommentItemInfo> getAllComments();
    }

    public interface PostItemInfo {
        String getPostId();
        String getTitle();
        String getContent();
        PersonalInformation.PersonalInfo getAuthor();
        PersonalInformation.PersonalInfo getLatestReplier();
        Long getLatestActiveTime();
        Long getCreateTime();
        Integer getHeat();
        List<ReplyItemInfo> getAllReplies();
    }

    public static class PostInfoInput {
        private String title;
        private String content;

        public PostInfoInput() {
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public void setContent(String content) {
            this.content = content;
        }

        @Override
        public String toString() {
            return "title: " + title + ", content: " + content;
        }
    }

    public static class ReplyInfoInput {
        private String postIdToReply;
        private String content;

        public ReplyInfoInput() {
        }

        public void setPostIdToReply(String postIdToReply) {
            this.postIdToReply = postIdToReply;
        }

        public void setContent(String content) {
            this.content = content;
        }

        @Override
        public String toString() {
            return "postIdToReply: " + postIdToReply + ", content: " + content;
        }
    }

    public static class CommentInfoInput {
        private String replyIdToComment;
        private String content;

        public CommentInfoInput() {
        }

        public void setReplyIdToComment(String replyIdToComment) {
            this.replyIdToComment = replyIdToComment;
        }

        public void setContent(String content) {
            this.content = content;
        }

        @Override
        public String toString() {
            return "replyIdToComment: " + replyIdToComment + ", content: " + content;
        }
    }

    public static class SchoolHeatError implements CreatePostResult, ModifyPostResult,
            CreateReplyResult, CreateCommentResult {
        private String error;

        public SchoolHeatError(String error) {
            this.error = error;
        }

        public static SchoolHeatError of(String error) {
            return new SchoolHeatError(error);
        }

        public String getError() {
            return error;
        }
    }

    public static class CreatePostSuccess implements CreatePostResult {
        private String postId;

        public CreatePostSuccess(String postId) {
            this.postId = postId;
        }

        public static CreatePostSuccess of(String postId) {
            return new CreatePostSuccess(postId);
        }

        public String getPostId() {
            return postId;
        }
    }


    public interface CreatePostResult {
    }

    public static class SchoolHeatSuccess implements ModifyPostResult {
        private Boolean ok;

        public SchoolHeatSuccess() {
            this.ok = true;
        }

        public static SchoolHeatSuccess build() {
            return new SchoolHeatSuccess();
        }

        public Boolean getOk() {
            return ok;
        }
    }

    public interface ModifyPostResult {
    }

    public static class CreateReplySuccess implements CreateReplyResult {
        private PostItemInfo postInfo;

        public CreateReplySuccess(PostItemInfo postInfo) {
            this.postInfo = postInfo;
        }

        public static CreateReplySuccess of(PostItemInfo postInfo) {
            return new CreateReplySuccess(postInfo);
        }

        public PostItemInfo getPostInfo() {
            return postInfo;
        }
    }

    public interface CreateReplyResult {
    }

    public static class CreateCommentSuccess implements CreateCommentResult {
        private PostItemInfo postInfo;

        public CreateCommentSuccess(PostItemInfo postInfo) {
            this.postInfo = postInfo;
        }

        public static CreateCommentSuccess of(PostItemInfo postInfo) {
            return new CreateCommentSuccess(postInfo);
        }

        public PostItemInfo getPostInfo() {
            return postInfo;
        }
    }

    public interface CreateCommentResult {
    }

    private static class CreatePostException extends RuntimeException {
        CreatePostException() {
            super("创建帖子失败");
        }
    }

    private static class PostInputNullException extends RuntimeException {
        PostInputNullException() {
            super("标题或内容为空");
        }
    }

    private static class PostNonExistException extends RuntimeException {
        PostNonExistException() {
            super("帖子不存在");
        }
    }

    private static class ReplyNonExistException extends RuntimeException {
        ReplyNonExistException() {
            super("回复不存在");
        }
    }

    private static class PostPermissionException extends RuntimeException {
        PostPermissionException() {
            super("无操作权限");
        }
    }

}