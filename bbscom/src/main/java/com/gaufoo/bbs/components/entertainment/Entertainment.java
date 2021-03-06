package com.gaufoo.bbs.components.entertainment;

import com.gaufoo.bbs.components.entertainment.common.EntertainmentId;
import com.gaufoo.bbs.components.entertainment.common.EntertainmentInfo;
import com.gaufoo.bbs.components.idGenerator.IdGenerator;
import com.gaufoo.bbs.components.user.common.UserId;

import java.util.Optional;
import java.util.stream.Stream;

public interface Entertainment {
    Stream<EntertainmentId> allPosts(boolean descending);

    default Stream<EntertainmentId> allPosts() {
        return allPosts(false);
    }

    Stream<EntertainmentId> allPostsByAuthor(String authorId, boolean descending);

    default Stream<EntertainmentId> allPostsByAuthor(String authorId) {
        return allPostsByAuthor(authorId, true);
    }

    Optional<EntertainmentInfo> postInfo(EntertainmentId entertainmentId);

    Optional<EntertainmentId> publishPost(EntertainmentInfo entertainmentInfo);

    boolean removePost(EntertainmentId entertainmentId);

    Long allPostsCount();

    Long allPostsCountByAuthor(UserId authorId);

    static Entertainment defau1t(EntertainmentRepository repository, IdGenerator idGenerator) {
        return new EntertainmentImpl(repository, idGenerator);
    }
}
