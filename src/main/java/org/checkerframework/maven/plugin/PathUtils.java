package org.checkerframework.maven.plugin;

import java.io.File;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

/**
 * A set of utility methods to find the necessary Checker Framework jars and to resolve any
 * sources/classes needed for compilation and checking
 */
public class PathUtils {
  private static final String CHECKER_FRAMEWORK_GROUPD_ID = "org.checkerframework";

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
   * @param project The Maven project*
   * @return The version string if found, or null otherwise
   */
  public static String getCheckerFrameworkVersionFromDependencies(
      final MavenProject project) {
    // Look for checker in project dependencies
    Optional<Artifact> artifact =
        project.getArtifacts().stream()
            .filter(
                a ->
                    CHECKER_FRAMEWORK_GROUPD_ID.equals(a.getGroupId())
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
                    CHECKER_FRAMEWORK_GROUPD_ID.equals(a.getGroupId())
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
              CHECKER_FRAMEWORK_GROUPD_ID, artifactId, checkerFrameworkVersion, null, "jar");

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
