package org.checkerframework.maven.plugin;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.DirectoryScanner;
import org.eclipse.aether.impl.ArtifactResolver;

/**
 * A set of utility methods to find the necessary JSR 308 jars and to resolve any sources/classes
 * needed for compilation and checking
 *
 * @author Adam Warski (adam at warski dot org)
 */
public class PathUtils {
  private static final String DEFAULT_INCLUSION_PATTERN = "**/*.java";
  private static final String CHECKER_FRAMEWORK_GROUPD_ID = "org.checkerframework";

  /**
   * Scans the given compile source roots for sources, taking into account the given includes and
   * excludes.
   *
   * @param compileSourceRoots A list of source roots.
   * @param sourceIncludes Includes specification. Defaults to DEFAULT_INCLUSION_PATTERN if no
   *     sourceIncludes are specified
   * @param sourceExcludes Excludes specification.
   * @return A list of included sources from the given source roots.
   */
  public static List<String> scanForSources(
      final List<String> compileSourceRoots,
      final Set<String> sourceIncludes,
      final Set<String> sourceExcludes) {
    if (sourceIncludes.isEmpty()) {
      sourceIncludes.add(DEFAULT_INCLUSION_PATTERN);
    }

    final List<String> sources = new ArrayList<>();

    for (String compileSourceRoot : compileSourceRoots) {
      final File compileSourceRootFile = new File(compileSourceRoot);
      final String[] sourcesFromSourceRoot =
          scanForSources(compileSourceRootFile, sourceIncludes, sourceExcludes);

      for (final String sourceFromSourceRoot : sourcesFromSourceRoot) {
        sources.add(new File(compileSourceRootFile, sourceFromSourceRoot).getAbsolutePath());
      }
    }

    return sources;
  }

  /**
   * Scans a single source dir for sources and includes only the files whose name match the patterns
   * in sourceIncludes and excludes all files whose names match the patterns in sourceExcludes
   *
   * @param sourceDir The directory to scan
   * @param sourceIncludes Only include a file if its name matches a pattern in sourceIncludes
   * @param sourceExcludes Exclude a file if its name matches a pattern in sourceExcludes
   * @return A set of filepath strings
   */
  private static String[] scanForSources(
      final File sourceDir, final Set<String> sourceIncludes, final Set<String> sourceExcludes) {
    final DirectoryScanner ds = new DirectoryScanner();
    ds.setFollowSymlinks(true);
    ds.setBasedir(sourceDir);

    ds.setIncludes(sourceIncludes.toArray(new String[0]));
    ds.setExcludes(sourceExcludes.toArray(new String[0]));

    ds.addDefaultExcludes();

    try {
      ds.scan();
    } catch (IllegalStateException e) {
      // the source directory (java/) does not exist
      return new String[0];
    }

    return ds.getIncludedFiles();
  }

  /**
   * Gets the path to the given executable, looking it up in the toolchain.
   *
   * @param executable Name of the executable.
   * @param toolchainManager Dependency.
   * @param session Dependency.
   * @return Path to the executable.
   */
  public static String getExecutablePath(
      String executable, final ToolchainManager toolchainManager, final MavenSession session) {
    final File execFile = new File(executable);
    if (execFile.exists()) {
      return execFile.getAbsolutePath();
    } else {
      final Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
      if (tc != null) {
        return tc.findTool(executable);
      }
    }
    return executable;
  }

  /**
   * Gets the Checker Framework version from project dependencies. Looks for "checker" or
   * "checker-qual" artifacts in the project dependencies.
   *
   * @param project The Maven project
   * @param log The logger
   * @return The version string if found, or null otherwise
   */
  public static String getCheckerFrameworkVersionFromDependencies(
      final MavenProject project, final Log log) {
    // Look for checker in project dependencies
    Optional<Artifact> artifact =
        project.getArtifacts().stream()
            .filter(
                a ->
                    "org.checkerframework".equals(a.getGroupId())
                        && ("checker-qual".equals(a.getArtifactId())))
            .findFirst();

    if (artifact.isPresent()) {
      return artifact.get().getVersion();
    }
    return null;
  }

  /**
   * Gets the Checker Framework JAR file for the given artifact ID (e.g., "checker" or
   * "checker-qual"). First tries to find it from project dependencies, then from local repository,
   * and finally from the classloader as a fallback.
   *
   * @param artifactId The artifact ID (e.g., "checker" or "checker-qual")
   * @param project The Maven project
   * @param repositorySystem The repository system for resolving artifacts
   * @param localRepository The local repository
   * @param checkerFrameworkVersion The Checker Framework version
   * @param log The logger
   * @return The JAR file, or null if not found
   */
  public static File getFrameworkJar(
      final String artifactId,
      final MavenProject project,
      final RepositorySystem repositorySystem,
      final ArtifactRepository localRepository,
      final String checkerFrameworkVersion,
      final Log log) {
    // Method 1: Find from project dependencies
    Optional<Artifact> artifact =
        project.getArtifacts().stream()
            .filter(
                a ->
                    "org.checkerframework".equals(a.getGroupId())
                        && artifactId.equals(a.getArtifactId()))
            .findFirst();
    if (artifact.isPresent()) {
      File artifactFile = artifact.get().getFile();
      if (artifactFile != null && artifactFile.exists()) {
        return artifactFile;
      }
    }

    // Method 2: Resolve from local repository
    try {
      Artifact resolvedArtifact =
          repositorySystem.createArtifact(
              "org.checkerframework", artifactId, checkerFrameworkVersion, null, "jar");

      File artifactFile =
          new File(localRepository.getBasedir(), localRepository.pathOf(resolvedArtifact));

      if (artifactFile.exists()) {
        return artifactFile;
      }
    } catch (Exception e) {
      // ignore
    }
    return null;
  }
}
