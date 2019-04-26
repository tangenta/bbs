package com.gaufoo.bbs.application;

import static com.gaufoo.bbs.application.types.Comment.*;
import com.gaufoo.bbs.application.types.Content;
import static com.gaufoo.bbs.application.types.PersonalInformation.*;
import com.gaufoo.bbs.components.commentGroup.CommentGroup;
import com.gaufoo.bbs.components.commentGroup.comment.common.CommentId;
import com.gaufoo.bbs.components.commentGroup.comment.reply.common.ReplyId;
import com.gaufoo.bbs.components.commentGroup.common.CommentGroupId;
import com.gaufoo.bbs.components.content.common.ContentId;
import com.gaufoo.bbs.components.user.common.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;
import static com.gaufoo.bbs.application.AppContent.*;
import static com.gaufoo.bbs.application.Commons.*;

public class AppComment {
    public static Logger log = LoggerFactory.getLogger(AppComment.class);

    public static AllComments consAllComments(CommentGroupId commentGroupId, Long skip, Long first) {
        final CommentGroup cg = componentFactory.commentGroup;
        return new AllComments() {
            public Long              getTotalCount() { return cg.getCommentsCount(commentGroupId); }
            public List<CommentInfo> getComments()   {
                return cg.allComments(commentGroupId).map(AppComment::consCommentInfo)
                        .filter(Objects::nonNull).skip(skip == null ? 0L : skip)
                        .limit(first == null ? Long.MAX_VALUE : first)
                        .collect(Collectors.toList());
            }
        };
    }

    public static CommentInfo consCommentInfo(CommentId cid) {
        final CommentGroup cg = componentFactory.commentGroup;
        return cg.commentInfo(cid).map(cinfo -> new CommentInfo() {
            public String       getId()      { return cid.value; }
            public Content      getContent() { return fromContentId(ContentId.of(cinfo.contentId)).reduce(AppComment::warnNil, i -> i); }
            public PersonalInfo getAuthor()  { return fetchPersonalInfo(UserId.of(cinfo.commenter)).reduce(AppComment::warnNil, i -> i); }
            public AllReplies   getAllReplies(Long skip, Long first) { return consAllReplies(cid, skip, first); }
        }).orElse(null);
    }

    public static AllReplies consAllReplies(CommentId cid, Long skip, Long first) {
        final CommentGroup cg = componentFactory.commentGroup;
        return new AllReplies() {
            public Long getTotalCount() { return cg.getRepliesCount(cid); }
            public List<ReplyInfo> getReplies() {
                return cg.allReplies(cid).map(AppComment::consReplyInfo)
                        .filter(Objects::nonNull).skip(skip == null ? 0L : skip)
                        .limit(first == null ? Long.MAX_VALUE : first)
                        .collect(Collectors.toList());
            }
        };
    }

    public static ReplyInfo consReplyInfo(ReplyId rid) {
        final CommentGroup cg = componentFactory.commentGroup;
        return cg.replyInfo(rid).map(rinfo ->
            new ReplyInfo() {
                public String       getId()      { return rid.value; }
                public Content      getContent() { return fromContentId(ContentId.of(rinfo.contentId)).reduce(AppComment::warnNil, i -> i); }
                public PersonalInfo getAuthor()  { return fetchPersonalInfo(UserId.of(rinfo.replier)).reduce(AppComment::warnNil, i -> i); }
                public PersonalInfo getReplyTo() { return Optional.ofNullable(rinfo.replyTo).map(rpt -> fetchPersonalInfo(UserId.of(rpt)).reduce(AppComment::warnNil, i -> i)).orElse(null); }
            }).orElse(null);
    }

    private static <T, E> T warnNil(E error) {
        log.warn("null warning: {}", error);
        return null;
    }
}