package com.gaufoo.bbs.components.file;

import com.gaufoo.bbs.components.file.common.FileId;
import com.gaufoo.bbs.components.idGenerator.IdGenerator;

import java.util.Optional;

public interface FileFactory {
    Optional<FileId> createFile(byte[] file, String suffix);

    Optional<FileId> createFile(byte[] file);

    Optional<String> filename(FileId id);

    Optional<String> fileURI(FileId id);

    boolean remove(FileId id);

    static FileFactory defau1t(FileFactoryRepository repository, IdGenerator idGenerator) {
        return new FileFactoryImpl(repository, idGenerator);
    }
}
