package com.gaufoo.bbs.application;

import com.gaufoo.bbs.application.error.Error;
import com.gaufoo.bbs.application.error.ErrorCode;
import com.gaufoo.bbs.application.error.Ok;
import com.gaufoo.bbs.application.types.Found;
import com.gaufoo.bbs.application.types.PersonalInformation;
import com.gaufoo.bbs.application.util.LazyVal;
import com.gaufoo.bbs.application.util.StaticResourceConfig;
import com.gaufoo.bbs.components.authenticator.common.UserToken;
import com.gaufoo.bbs.components.file.common.FileId;
import com.gaufoo.bbs.components.found.common.FoundId;
import com.gaufoo.bbs.components.found.common.FoundInfo;
import com.gaufoo.bbs.components.user.common.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.gaufoo.bbs.application.ComponentFactory.componentFactory;
import static com.gaufoo.bbs.util.TaskChain.*;


public class AppFound {
    private static Logger log = LoggerFactory.getLogger(AppFound.class);

    public static Found.AllFoundsResult allFounds(Long skip, Long first) {
        final long fSkip = skip == null ? 0L : skip;
        final long fFirst = first == null ? Long.MAX_VALUE : first;

        Supplier<List<Found.FoundInfo>> fs = () -> componentFactory.found.allPosts()
                .map(foundId -> consFoundInfo(foundId, LazyVal.of(() -> componentFactory.found.postInfo(foundId).orElse(null))))
                .skip(fSkip).limit(fFirst)
                .collect(Collectors.toList());

        return consMultiFoundsInfo(fs);
    }

    public static Found.FoundInfoResult foundInfo(String foundIdStr) {
        FoundId foundId = FoundId.of(foundIdStr);
        return componentFactory.found.postInfo(foundId)
                .map(foundInfo -> (Found.FoundInfoResult)consFoundInfo(foundId, LazyVal.with(foundInfo)))
                .orElse(Error.of(ErrorCode.FoundPostNonExist));
    }

    public static Found.CreateFoundResult createFound(Found.FoundInput input, String userToken) {
        return Commons.fetchUserId(UserToken.of(userToken))
                .then(userId -> addPictureIfNecessary(input.pictureBase64)
                        .mapR(oFileId -> oFileId.orElse(null))
                        .then(fileId -> publishFoundPost(input, userId, fileId)
                                .mapR(fndId -> consFoundInfo(input, fndId, userId, fileId))))
                .reduce(Error::of, i -> i);
    }

    public static Found.DeleteFoundResult deleteFound(String foundId, String userToken) {
        return Commons.fetchUserId(UserToken.of(userToken))
                .then(userId -> ensureOwnPost(userId, FoundId.of(foundId)))
                .then(ig -> Result.of(componentFactory.found.removePost(FoundId.of(foundId))))
                .mapR(success -> success ? Ok.build() : Error.of(ErrorCode.DeleteFoundFailed))
                .reduce(Error::of, i -> i);
    }

    public static Found.ClaimFoundResult claimFound(String foundId, String userToken) {
        return Commons.fetchUserId(UserToken.of(userToken))
                .then(userId -> Result.of(componentFactory.found.claim(FoundId.of(foundId), userId.value)))
                .reduce(Error::of, op -> op.isPresent() ? Ok.build() : Error.of(ErrorCode.ClaimFoundFailed));
    }

    public static Found.CancelClaimFoundResult cancelClaimFound(String foundId, String userToken) {
        Procedure<ErrorCode, UserId> userIdProc = Commons.fetchUserId(UserToken.of(userToken));
        Supplier<Procedure<ErrorCode, FoundInfo>> foundInfoProcSup = () -> Procedure.fromOptional(
                componentFactory.found.postInfo(FoundId.of(foundId)),
                ErrorCode.FoundPostNonExist);

        return userIdProc.then(userId -> foundInfoProcSup.get()
                .then(foundInfo -> Result.of(Objects.equals(foundInfo.losterId, userId.value)))
                .then(eq -> eq ? Result.of(null) : Fail.of(ErrorCode.PermissionDenied))
                .then(continued -> Procedure.fromOptional(componentFactory.found.removeClaim(FoundId.of(foundId)), ErrorCode.CancelClaimFailed))
        ).reduce(Error::of, foundInfo -> Ok.build());
    }

    public static void reset() {
        componentFactory.found.allPosts().forEach(id ->
                componentFactory.found.removePost(id)
        );
    }

    private static Procedure<ErrorCode, FoundId> publishFoundPost(Found.FoundInput input, UserId publisher, FileId nullablePictureId) {
        return Procedure.fromOptional(componentFactory.found.publishPost(
                FoundInfo.of(input.itemName, publisher.value, input.description, input.position, nilOrTr(nullablePictureId, x -> x.value),
                        input.contact, nilOrTr(input.foundTime, Instant::ofEpochMilli))
        ), ErrorCode.PublishFoundFailed);
    }

    private static Found.FoundInfo consFoundInfo(FoundId foundId, LazyVal<FoundInfo> foundInfo) {
        return new Found.FoundInfo() {
            public String getId()           { return foundId.value; }
            public String getName()         { return nilOrTr(foundInfo.get(), x -> x.name); }
            public String getDescription()  { return nilOrTr(foundInfo.get(), x -> x.description); }
            public String getPosition()     { return nilOrTr(foundInfo.get(), x -> x.position); }
            public String getPictureURL()   { return factorOutPictureUrl(FileId.of(foundInfo.get().pictureId)); }
            public String getContact()      { return nilOrTr(foundInfo.get(), x -> x.contact); }
            public Long getCreateTime()     { return nilOrTr(foundInfo.get(), x -> x.createTime.toEpochMilli()); }
            public Long getFoundTime()      { return nilOrTr(foundInfo.get(), x -> nilOrTr(x.foundTime, Instant::toEpochMilli)); }
            public PersonalInformation.PersonalInfo getPublisher() {
                return Commons.fetchPersonalInfo(UserId.of(foundInfo.get().publisherId)).reduce(AppFound::warnNil, r -> r);
            }
            public PersonalInformation.PersonalInfo getClaimer() {
                if (foundInfo.get().losterId == null) return null;
                return Commons.fetchPersonalInfo(UserId.of(foundInfo.get().losterId)).reduce(AppFound::warnNil, r -> r);
            }
        };
    }

    private static Found.FoundInfo consFoundInfo(Found.FoundInput input, FoundId id, UserId publisherId, FileId nullableFileId) {
        return new Found.FoundInfo() {
            public String getId() { return id.value; }
            public PersonalInformation.PersonalInfo getPublisher() {
                return Commons.fetchPersonalInfo(publisherId).reduce(AppFound::warnNil, i -> i);
            }
            public String getName() { return input.itemName; }
            public String getDescription() { return input.description; }
            public String getPosition() { return input.position; }
            public String getPictureURL() {
                if (nullableFileId == null) return null;
                return factorOutPictureUrl(nullableFileId);
            }
            public String getContact() { return input.contact; }
            public Long getCreateTime() { return Instant.now().toEpochMilli(); }
            public Long getFoundTime() { return input.foundTime; }
            public PersonalInformation.PersonalInfo getClaimer() { return null; }
        };
    }

    private static Found.MultiFoundInfos consMultiFoundsInfo(Supplier<List<Found.FoundInfo>> foundInfos) {
        return new Found.MultiFoundInfos() {
            public Long getTotalCount() { return componentFactory.found.allPostsCount(); }
            public List<Found.FoundInfo> getFounds() { return foundInfos.get(); }
        };
    }

    private static Procedure<ErrorCode, Optional<FileId>> addPictureIfNecessary(String nullablePictureBase64) {
        if (nullablePictureBase64 == null) return Result.of(Optional.empty());
        return Commons.storeBase64File(componentFactory.lostFoundImages, nullablePictureBase64)
                .then(fileId -> Result.of(Optional.of(fileId), () -> componentFactory.lostFoundImages.remove(fileId)));
    }

    private static String factorOutPictureUrl(FileId fileId) {
        return Commons.fetchFileUrl(componentFactory.lostFoundImages, StaticResourceConfig.FileType.LostFoundImage, fileId)
                .reduce(e -> null, i -> i);
    }

    private static Procedure<ErrorCode, ?> ensureOwnPost(UserId userId, FoundId foundId) {
        return Procedure.fromOptional(componentFactory.found.postInfo(foundId), ErrorCode.FoundPostNonExist)
                .then(foundInfo -> foundInfo.publisherId.equals(userId.value) ? Result.of(null) : Fail.of(ErrorCode.PermissionDenied));
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
