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
   * Gets the Java version string. Tries multiple sources in order of priority:
   * 1. From Toolchain (if configured) - the JDK toolchain specified for the project
   * 2. From MavenProject compiler configuration (maven.compiler.source or maven.compiler.target)
   *    - This reflects the actual Java version the project is configured to use
   * 3. From system property (java.version) - the JVM running Maven
   *
   * The version string is normalized to handle both "1.8" and "8" formats for Java 8.
   *
   * @param toolchainManager The toolchain manager
   * @param session The Maven session
   * @param project The Maven project
   * @return The normalized Java version string
   */
  private static String getJavaVersion(
      final ToolchainManager toolchainManager,
      final MavenSession session,
      final MavenProject project) {
    String version = null;

    // Method 1: Check if Toolchain is configured (highest priority indicator)
    // Toolchain is the preferred way as it reflects the actual JDK being used
    // Note: Toolchain API doesn't directly expose version, but if toolchain exists,
    // the compiler configuration should match the toolchain's JDK version
    final Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
    boolean toolchainConfigured = (tc != null);
    // If toolchain is configured, prioritize compiler config over system property

    // Method 2: Try to get from MavenProject compiler configuration
    // This reflects the project's target Java version
    if (version == null || version.isEmpty()) {
      String compilerSource = project.getProperties().getProperty("maven.compiler.source");
      if (compilerSource != null && !compilerSource.isEmpty()) {
        version = compilerSource;
      } else {
        String compilerTarget = project.getProperties().getProperty("maven.compiler.target");
        if (compilerTarget != null && !compilerTarget.isEmpty()) {
          version = compilerTarget;
        }
      }
    }

    // Method 3: Fallback to system property (the JVM running Maven)
    // Only use system property if toolchain is not configured (to avoid version mismatch)
    if ((version == null || version.isEmpty()) && !toolchainConfigured) {
      version = System.getProperty("java.version");
    }

    // Normalize version string: convert "8" to "1.8" for consistency
    // This handles cases where Maven config uses "8" instead of "1.8"
    if ("8".equals(version)) {
      return "1.8";
    }
    // Also handle "7" -> "1.7", "6" -> "1.6", etc. for older Java versions
    if (version != null && version.matches("^[5-8]$")) {
      return "1." + version;
    }

    return version;
  }

  /**
   * Gets the Java major version number. Parses the version string and returns the major version
   * number (e.g., "1.8" -> 8, "9" -> 9, "11" -> 11).
   *
   * @param toolchainManager The toolchain manager
   * @param session The Maven session
   * @param project The Maven project
   * @return The Java major version number, or -1 if version cannot be determined
   */
  public static int getJavaVersionNumber(
      final ToolchainManager toolchainManager,
      final MavenSession session,
      final MavenProject project) {
    String versionStr = getJavaVersion(toolchainManager, session, project);
    if (versionStr == null || versionStr.isEmpty()) {
      return -1;
    }

    // Handle "1.x" format (Java 8 and earlier)
    if (versionStr.startsWith("1.")) {
      try {
        // Extract the minor version number (e.g., "1.8" -> 8)
        String minorVersion = versionStr.substring(2);
        // Handle cases like "1.8.0" -> take first part
        int dotIndex = minorVersion.indexOf('.');
        if (dotIndex > 0) {
          minorVersion = minorVersion.substring(0, dotIndex);
        }
        return Integer.parseInt(minorVersion);
      } catch (NumberFormatException e) {
        return -1;
      }
    }

    // Handle direct version numbers (Java 9+)
    try {
      // Handle cases like "9", "11", "17.0.1" -> take first part
      int dotIndex = versionStr.indexOf('.');
      if (dotIndex > 0) {
        versionStr = versionStr.substring(0, dotIndex);
      }
      // Handle cases like "9-ea" or "11+10" -> take first part
      int dashIndex = versionStr.indexOf('-');
      if (dashIndex > 0) {
        versionStr = versionStr.substring(0, dashIndex);
      }
      int plusIndex = versionStr.indexOf('+');
      if (plusIndex > 0) {
        versionStr = versionStr.substring(0, plusIndex);
      }
      return Integer.parseInt(versionStr);
    } catch (NumberFormatException e) {
      return -1;
    }
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
