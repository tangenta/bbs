package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.error.Error;
import com.gaufoo.bbs.application.error.ErrorCode;
import com.gaufoo.bbs.application.types.Comment;
import com.gaufoo.bbs.application.types.Content;
import com.gaufoo.bbs.application.types.PersonalInformation;
import com.gaufoo.bbs.components.active.Active;
import com.gaufoo.bbs.components.active.common.ActiveInfo;
import com.gaufoo.bbs.components.authenticator.common.UserToken;
import com.gaufoo.bbs.components.commentGroup.CommentGroup;
import com.gaufoo.bbs.components.content.common.ContentInfo;
import com.gaufoo.bbs.components.heat.Heat;
import com.gaufoo.bbs.components.schoolHeat.common.SchoolHeatId;
import com.gaufoo.bbs.components.schoolHeat.common.SchoolHeatInfo;

import static com.gaufoo.bbs.util.TaskChain.Procedure.*;
import static com.gaufoo.bbs.util.TaskChain.*;

import com.gaufoo.bbs.components.user.common.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;

import com.gaufoo.bbs.application.types.SchoolHeat;

import java.time.Instant;

public class AppSchoolHeat {
    public static Logger log = LoggerFactory.getLogger(SchoolHeat.class);

//    public static SchoolHeat.AllSchoolHeatsResult allSchoolHeats(Long skip, Long first) {
//        final long fSkip = skip == null ? 0L : skip;
//        final long fFirst = first == null ? Long.MAX_VALUE : first;
//
//        return null;
//    }

    public static SchoolHeat.CreateSchoolHeatResult createSchoolHeat(SchoolHeat.SchoolHeatInput input, String loginToken) {
        CommentGroup commentGroup = componentFactory.commentGroup;
        Heat heat = componentFactory.heat;
        Active active = componentFactory.active;
        ErrorCode e = ErrorCode.CreatePostFailed;
        String schoolHeatGroup = Commons.getGroupId(Commons.PostType.SchoolHeat);
        String aTimeWindow = Commons.currentActiveTimeWindow(Instant.now());
        String hTimeWindow = Commons.currentHeatTimeWindow(Instant.now());

        return Commons.fetchUserId(UserToken.of(loginToken))
        /* 构造评论句柄     */ .then(userId -> fromOptional(commentGroup.cons(), e)
        /* 构造内容信息     */ .then(cgId   -> AppContent.consContent(input.content)
        /* 构造内容句柄     */ .then(ctInfo -> fromOptional(componentFactory.content.cons(ctInfo), e, () -> commentGroup.removeComments(cgId))
        /* 构造帖子        */ .then(ctId   -> Result.of(SchoolHeatInfo.of(input.title, ctId.value, userId.value, cgId.value), () -> componentFactory.content.remove(ctId)))
        /* 发表帖子        */ .then(ptInfo -> fromOptional(componentFactory.schoolHeat.publishPost(ptInfo), e)
        /* 构造热度        */ .then(ptId   -> fromOptional(heat.increase(schoolHeatGroup, ptId.value, 1), e, () -> componentFactory.schoolHeat.removePost(ptId))
        /* 构造最热        */ .then(ht     -> fromOptional(heat.increase(hTimeWindow, schoolHeatGroup + ptId.value, 1), e, () -> heat.remove(schoolHeatGroup, ptId.value))
        /* 构造活跃        */ .then(__     -> fromOptional(active.touch(schoolHeatGroup, ptId.value, userId.value), e, () -> heat.remove(hTimeWindow, schoolHeatGroup + ptId.value)))
        /* 构造最新        */ .then(at     -> fromOptional(active.touch(aTimeWindow, schoolHeatGroup + ptId.value, userId.value), e, () -> active.remove(schoolHeatGroup, ptId.value))
        /* 构造最终返回值   */ .then(__     -> Result.of(consSH(ptInfo, ptId, ht, at, ctInfo), () -> active.remove(aTimeWindow, schoolHeatGroup + ptId.value))))))))))
                            .reduce(Error::of, i -> i);
    }

    private static SchoolHeat.SchoolHeatInfo consSH(SchoolHeatInfo info, SchoolHeatId id, Long heat, ActiveInfo activeInfo, ContentInfo contentInfo) {
        return new SchoolHeat.SchoolHeatInfo() {
            public String                                            getId() { return id.value; }
            public String                                         getTitle() { return info.title; }
            public Content                                      getContent() { return AppContent.fromContentInfo(contentInfo); }
            public PersonalInformation.PersonalInfo              getAuthor() { return Commons.fetchPersonalInfo(UserId.of(info.authorId)).reduce(AppSchoolHeat::warnNil, i -> i); }
            public PersonalInformation.PersonalInfo     getLatestCommenter() { return Commons.fetchPersonalInfo(UserId.of(activeInfo.toucherId)).reduce(AppSchoolHeat::warnNil, i -> i); }
            public Long                                getLatestActiveTime() { return activeInfo.time.toEpochMilli(); }
            public Long                                      getCreateTime() { return info.createTime.toEpochMilli(); }
            public Long                                            getHeat() { return heat; }
            public Comment.AllComments getAllComments(Long skip, Long first) { return null; } // TODO
        };
    }

    private static <T, E> T warnNil(E error) {
        log.warn("null warning: {}", error);
        return null;
    }
}
