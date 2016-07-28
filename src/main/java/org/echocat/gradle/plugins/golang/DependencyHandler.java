package org.echocat.gradle.plugins.golang;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.echocat.gradle.plugins.golang.model.*;
import org.echocat.gradle.plugins.golang.model.GolangDependency.Type;
import org.echocat.gradle.plugins.golang.utils.Executor;
import org.echocat.gradle.plugins.golang.vcs.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.io.File.separatorChar;
import static java.lang.Boolean.TRUE;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.walkFileTree;
import static java.util.Collections.emptyList;
import static org.echocat.gradle.plugins.golang.DependencyHandler.GetResult.downloaded;
import static org.echocat.gradle.plugins.golang.DependencyHandler.GetResult.alreadyExists;
import static org.echocat.gradle.plugins.golang.DependencyHandler.DependencyDirType.*;

public class DependencyHandler {

    private static final Pattern IS_EXTERNAL_DEPENDENCY_PATTERN = Pattern.compile("^([a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-.]+/[a-zA-Z0-9\\-_.$]+[^ ]*)");
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyHandler.class);

    @Nonnull
    private final VcsRepositoryProvider _vcsRepositoryProvider = new CombinedVcsRepositoryProvider();

    @Nonnull
    private final Settings _settings;

    public DependencyHandler(@Nonnull Settings settings) {
        _settings = settings;
    }

    @Nonnull
    public Map<GolangDependency, GetResult> get(@Nullable String configuration) throws Exception {
        final DependenciesSettings dependencies = _settings.getDependencies();
        final Map<GolangDependency, GetResult> handledDependencies = new LinkedHashMap<>();
        final Queue<GolangDependency> toHandle = new LinkedList<>();
        toHandle.addAll(dependencies(configuration));

        GolangDependency dependency;
        while ((dependency = toHandle.poll()) != null) {
            if (!handledDependencies.containsKey(dependency)) {
                final RawVcsReference reference = dependency.toRawVcsReference();
                final VcsRepository repository = _vcsRepositoryProvider.tryProvideFor(reference);
                if (repository == null) {
                    throw new RuntimeException("Could not download dependency: " + reference);
                }
                LOGGER.debug("Update dependency {} (if required)...", reference);
                if (TRUE.equals(dependencies.getForceUpdate())) {
                    repository.forceUpdate(selectTargetDirectoryFor(configuration));
                    LOGGER.info("Dependency {} updated.", reference);
                } else {
                    final VcsFullReference fullReference = repository.updateIfRequired(selectTargetDirectoryFor(configuration));
                    if (fullReference != null) {
                        LOGGER.info("Dependency {} updated.", reference);
                        handledDependencies.put(dependency, downloaded);
                    } else {
                        LOGGER.debug("No update required for dependency {}.", reference);
                        handledDependencies.put(dependency, alreadyExists);
                    }
                }
                toHandle.addAll(resolveDependenciesOf(dependency));
            }
        }
        return handledDependencies;
    }

    @Nonnull
    protected Collection<GolangDependency> resolveDependenciesOf(@Nonnull GolangDependency dependency) throws Exception {
        final Executor executor = Executor.executor()
            .executable(_settings.getToolchain().toolchainBinary("importsExtractor"))
            .arguments(dependency.getGroup())
            .execute()
        ;
        final String plainDependencies = executor.getStdoutAsString().trim();
        final Collection<GolangDependency> result = new ArrayList<>();
        final String[] plainDependencyParts = StringUtils.split(plainDependencies, '\n');
        for (final String plainDependency : plainDependencyParts) {
            final Matcher matcher = IS_EXTERNAL_DEPENDENCY_PATTERN.matcher(plainDependency);
            if (matcher.matches()) {
                LOGGER.warn("''{}''", matcher.group(1));
                result.add(new GolangDependency()
                    .setGroup(matcher.group(1))
                    .setType(Type.implicit)
                );
            }
        }
        return result;
    }

    @Nonnull
    protected File selectTargetDirectoryFor(@Nullable String configuration) throws Exception {
        if ("tool".equals(configuration)) {
            return new File(_settings.getBuild().getGopath(), "src");
        }
        return _settings.getDependencies().getDependencyCache();
    }

    @Nonnull
    public Collection<Path> deleteUnknownDependenciesIfRequired() throws Exception {
        final DependenciesSettings dependencies = _settings.getDependencies();
        final File dependencyCacheDirectory = dependencies.getDependencyCache();
        final Set<String> knownDependencyIds = new HashSet<>();
        for (final GolangDependency dependency : dependencies(null)) {
            knownDependencyIds.add(dependency.getGroup());
        }
        return doDeleteUnknownDependenciesIfRequired(dependencyCacheDirectory.toPath(), knownDependencyIds);
    }

    @Nonnull
    protected Collection<GolangDependency> dependencies(@Nullable String configurationName) {
        final List<GolangDependency> result = new ArrayList<>();
        final Project project = _settings.getProject();
        final ConfigurationContainer configurations = project.getConfigurations();
        if (configurationName != null) {
            final Configuration configuration = configurations.getByName(configurationName);
            for (final Dependency dependency : configuration.getDependencies()) {
                if (dependency instanceof GolangDependency) {
                    result.add((GolangDependency) dependency);
                } else {
                    result.add(new GolangDependency(dependency));
                }
            }
        } else {
            for (final Configuration configuration : project.getConfigurations()) {
                for (final Dependency dependency : configuration.getDependencies()) {
                    if (dependency instanceof GolangDependency) {
                        result.add((GolangDependency) dependency);
                    } else {
                        result.add(new GolangDependency(dependency));
                    }
                }
            }
        }
        return result;
    }

    @Nonnull
    protected Collection<Path> doDeleteUnknownDependenciesIfRequired(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        if (!TRUE.equals(_settings.getDependencies().getDeleteUnknownDependencies())) {
            return emptyList();
        }
        return doDeleteUnknownDependencies(root, knownDependencyIds);
    }

    @Nonnull
    protected Collection<Path> doDeleteUnknownDependencies(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        final Collection<Path> paths = collectUnknownDependencyDirectories(root, knownDependencyIds);
        for (final Path path : paths) {
            LOGGER.debug("Deleting unknown dependency in {}...", path);
            try {
                FileUtils.forceDelete(path.toFile());
            } catch (final IOException e) {
                throw new IOException("Could not remove " + path + ".", e);
            }
            LOGGER.info("Unknown dependency in {} deleted.", path);
        }
        return paths;
    }

    @Nonnull
    protected Collection<Path> collectUnknownDependencyDirectories(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) {
        final Set<Path> result = new TreeSet<>(Collections.<Path>reverseOrder());
        final Map<Path, DependencyDirType> candidates = collectDirectoriesToKnownDependencyOf(root, knownDependencyIds);
        for (final Entry<Path, DependencyDirType> candidate : candidates.entrySet()) {
            if (candidate.getValue() == unknown) {
                result.add(candidate.getKey());
            }
        }
        return result;
    }

    @Nonnull
    protected Map<Path, DependencyDirType> collectDirectoriesToKnownDependencyOf(@Nonnull final Path root, @Nonnull final Set<String> knownDependencyIds) {
        final Map<Path, DependencyDirType> directories = new TreeMap<>();
        try {
            walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(root)) {
                        return CONTINUE;
                    }

                    if (isKnownDependencyDirectory(dir, root, knownDependencyIds)) {
                        directories.put(dir, containsInfoFile);
                        Path parent = dir.getParent();
                        while (parent != null && !parent.equals(root)) {
                            final DependencyDirType type = directories.get(parent);
                            if (type == null || type == unknown) {
                                directories.put(parent, parentOfContainsInfoFile);
                            }
                            parent = parent.getParent();
                        }
                        return CONTINUE;
                    }

                    if (isChildOfWithInfoFile(dir, root, directories)) {
                        directories.put(dir, hasContainsInfoFileParent);
                        return CONTINUE;
                    }

                    if (!directories.containsKey(dir)) {
                        directories.put(dir, unknown);
                    }
                    return CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return directories;
    }

    protected boolean isChildOfWithInfoFile(@Nonnull Path directory, @Nonnull Path root, @Nonnull Map<Path, DependencyDirType> directories) {
        Path parent = directory.getParent();
        while (parent != null && !parent.equals(root)) {
            if (directories.get(parent) == containsInfoFile) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    protected boolean isKnownDependencyDirectory(@Nonnull Path directory, @Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        final int dirCount = directory.getNameCount();
        final int rootCount = root.getNameCount();
        final Path subPath = directory.subpath(rootCount, dirCount);
        final String idToTest = subPath.toString().replace(separatorChar, '/');
        return knownDependencyIds.contains(idToTest);
    }

    protected enum DependencyDirType {
        unknown,
        containsInfoFile,
        hasContainsInfoFileParent,
        parentOfContainsInfoFile
    }

    public enum GetResult {
        downloaded,
        alreadyExists
    }
}