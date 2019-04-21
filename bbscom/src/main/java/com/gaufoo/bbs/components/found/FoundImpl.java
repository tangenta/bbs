package com.gaufoo.bbs.components.found;

import com.gaufoo.bbs.components.found.common.FoundId;
import com.gaufoo.bbs.components.found.common.FoundInfo;
import com.gaufoo.bbs.components.idGenerator.IdGenerator;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class FoundImpl implements Found {
    private final FoundRepository repository;
    private final IdGenerator idGenerator;
    private final AtomicLong count;

    FoundImpl(FoundRepository repository, IdGenerator idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.count = new AtomicLong(repository.getAllPostsAsc().count());
    }

    @Override
    public Stream<FoundId> allPosts(boolean descending) {
        if (descending) return repository.getAllPostsDes();
        else return repository.getAllPostsAsc();
    }

    @Override
    public Optional<FoundInfo> postInfo(FoundId foundId) {
        return Optional.ofNullable(repository.getPostInfo(foundId));
    }

    @Override
    public Optional<FoundId> publishPost(FoundInfo foundInfo) {
        FoundId id = FoundId.of(idGenerator.generateId());
        if (repository.savePost(id, foundInfo)) {
            this.count.incrementAndGet();
            return Optional.of(id);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<FoundInfo> claim(FoundId foundId, String losterId) {
        return postInfo(foundId).flatMap(info -> {
            if (info.losterId == null) {
                FoundInfo newInfo = info.modLosterId(losterId);
                if (repository.updatePost(foundId, newInfo)) {
                    return Optional.ofNullable(newInfo);
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        });
    }

    @Override
    public void removePost(FoundId foundId) {
        postInfo(foundId).ifPresent(i -> {
            repository.deletePost(foundId);
            this.count.decrementAndGet();
        });
    }

    @Override
    public Long allPostsCount() {
        return this.count.get();
    }
}
