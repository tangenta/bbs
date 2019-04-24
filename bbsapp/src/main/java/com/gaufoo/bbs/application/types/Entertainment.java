package com.gaufoo.bbs.application.types;

import java.util.List;

public interface Entertainment {

    interface AllEntertainmentsResult {}
    interface EntertainmentInfoResult {}
    interface EntertainmentsOfAuthorResult {}
    interface SearchEntertainmentsResult {}
    interface CreateEntertainmentResult {}
    interface DeleteEntertainmentResult {}
    interface CreateEntertainmentCommentResult {}
    interface DeleteEntertainmentCommentResult {}
    interface CreateEntertainmentCommentReplyResult {}
    interface DeleteEntertainmentCommentReplyResult {}

    interface EntertainmentInfo extends
            EntertainmentInfoResult,
            CreateEntertainmentResult,
            DeleteEntertainmentResult,
            Hot.HotItem,
            Latest.LatestItem
    {
        String getId();
        String getTitle();
        Content getContent();
        PersonalInformation.PersonalInfo getAuthor();
        PersonalInformation.PersonalInfo getLatestCommenter();
        Long getLatestActiveTime();
        Long getCreateTime();
        Integer getHeat();
        Comment.AllComments getAllComments(Long skip, Long first);
    }

    interface MultiEntertainments extends
            AllEntertainmentsResult,
            SearchEntertainmentsResult,
            EntertainmentsOfAuthorResult
    {
        Long getTotalCount();
        List<EntertainmentInfo> getEntertainments();
    }

    class EntertainmentInput {
        public String title;
        public Content content;
    }

    class EntertainmentCommentInput {
        public String postIdCommenting;
        public Content content;
    }

    class EntertainmentReplyInput {
        public String commentIdReplying;
        public Content content;
        public String replyTo;
    }
}