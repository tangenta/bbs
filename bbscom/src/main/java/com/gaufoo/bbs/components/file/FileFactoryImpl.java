package com.gaufoo.bbs.components.file;

import com.gaufoo.bbs.components.file.common.FileId;
import com.gaufoo.bbs.components.idGenerator.IdGenerator;

import java.util.Optional;

public class FileFactoryImpl implements FileFactory {
    private final String componentName;
    private final FileFactoryRepository repository;
    private final IdGenerator idGenerator;

    FileFactoryImpl(String componentName, FileFactoryRepository repository, IdGenerator idGenerator) {
        this.componentName = componentName;
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    @Override
    public Optional<FileId> createFile(byte[] file, String filename) {
        FileId id = FileId.of(idGenerator.generateId());
        if (repository.saveFile(id, file, filename)) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> filename(FileId id) {
        return Optional.ofNullable(repository.getFilename(id));
    }

    @Override
    public Optional<String> fileURI(FileId id) {
        return Optional.ofNullable(repository.getURI(id));
    }

    @Override
    public void Remove(FileId id) {
        repository.delete(id);
    }

    @Override
    public String getName() {
        return this.componentName;
    }

    @Override
    public void shutdown() {
        repository.shutdown();
    }
}
