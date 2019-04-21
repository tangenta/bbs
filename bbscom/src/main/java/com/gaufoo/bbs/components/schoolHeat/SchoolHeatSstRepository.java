package com.gaufoo.bbs.components.schoolHeat;

import com.gaufoo.bbs.components.schoolHeat.common.SchoolHeatId;
import com.gaufoo.bbs.components.schoolHeat.common.SchoolHeatInfo;
import com.gaufoo.bbs.util.SstUtils;
import com.gaufoo.sst.SST;
import com.google.gson.Gson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class SchoolHeatSstRepository implements SchoolHeatRepository {
    private static final Gson gson = new Gson();
    private final SST idToInfo;
    private final SST authorIndex;

    private SchoolHeatSstRepository(Path storingPath) {
        this.idToInfo = SST.of("id-to-info", storingPath);
        this.authorIndex = SST.of("author-index", storingPath);
    }

    @Override
    public Stream<SchoolHeatId> getAllPostsAsc() {
        return SstUtils.waitFuture(idToInfo.allKeysAsc()).map(i -> i.map(SchoolHeatId::of)).orElse(Stream.empty());
    }

    @Override
    public Stream<SchoolHeatId> getAllPostsDes() {
        return SstUtils.waitFuture(idToInfo.allKeysDes()).map(i -> i.map(SchoolHeatId::of)).orElse(Stream.empty());

    }

    @Override
    public Stream<SchoolHeatId> getAllPostsByAuthorAsc(String authorId) {
        return SstUtils.waitFuture(authorIndex.rangeKeysAsc(authorId + "00000000", authorId + "99999999")
                .thenApply(keys -> keys.map(SchoolHeatSstRepository::retrieveId))).orElse(Stream.empty());
    }

    @Override
    public Stream<SchoolHeatId> getAllPostsByAuthorDes(String authorId) {
        return SstUtils.waitFuture(authorIndex.rangeKeysDes(authorId + "99999999", authorId + "00000000")
                .thenApply(keys -> keys.map(SchoolHeatSstRepository::retrieveId))).orElse(Stream.empty());

    }

    @Override
    public SchoolHeatInfo getPostInfo(SchoolHeatId postId) {
        return SstUtils.getEntry(idToInfo, postId.value, info -> gson.fromJson(info, SchoolHeatInfo.class));
    }

    @Override
    public boolean savePost(SchoolHeatId postId, SchoolHeatInfo postInfo) {
        if (SstUtils.contains(idToInfo, postId.value)) return false;
        List<CompletionStage<Boolean>> tasks = new ArrayList<>();
        tasks.add(SstUtils.setEntryAsync(idToInfo, postId.value, gson.toJson(postInfo)));
        tasks.add(SstUtils.setEntryAsync(authorIndex, concat(postId, postInfo.authorId), "GAUFOO"));
        return SstUtils.waitAllFutureParT(tasks, true, (a, b) -> a && b);
    }

    @Override
    public void deletePost(SchoolHeatId postId) {
        Optional.ofNullable(getPostInfo(postId)).ifPresent(info ->
                SstUtils.waitAllFuturesPar(
                        idToInfo.delete(postId.value),
                        authorIndex.delete(concat(postId, info.authorId))
                )
        );
    }

    @Override
    public void shutdown() {
        SstUtils.waitAllFuturesPar(idToInfo.shutdown(), authorIndex.shutdown());
    }

    private static String concat(SchoolHeatId postId, String authorId) {
        return authorId + postId.value;
    }

    private static SchoolHeatId retrieveId(String string) {
        return SchoolHeatId.of(string.substring(8));
    }

    public static SchoolHeatRepository get(Path storingPath) {
        return new SchoolHeatSstRepository(storingPath);
    }
}
