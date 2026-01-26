package org.checkerframework.maven.plugin;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
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
   * Gets the Java version string. Tries multiple sources in order of priority: 1. From Toolchain
   * (if configured) - the JDK toolchain specified for the project 2. From MavenProject compiler
   * configuration (maven.compiler.source or maven.compiler.target) - This reflects the actual Java
   * version the project is configured to use 3. From system property (java.version) - the JVM
   * running Maven
   *
   * <p>The version string is normalized to handle both "1.8" and "8" formats for Java 8.
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
    // Only use system property if toolchain is not configured (to avoid version
    // mismatch)
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
   * Parses a Java version string and returns the major version number. Handles both "1.x" format
   * (Java 8 and earlier) and direct version numbers (Java 9+). Examples: "1.8" -> 8, "9" -> 9, "11"
   * -> 11, "17.0.1" -> 17
   *
   * @param versionStr The version string to parse
   * @return The Java major version number, or -1 if version cannot be determined
   */
  private static int parseVersionString(String versionStr) {
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
   * Gets the Java source version number from pom.xml configuration. Reads from
   * maven.compiler.source or maven.compiler.target properties.
   *
   * @param project The Maven project
   * @return The Java source major version number, or -1 if version cannot be determined
   */
  public static int getJavaSourceVersionNumber(final MavenProject project) {
    String versionStr = null;

    // Try to get from maven.compiler.source first
    String compilerSource = project.getProperties().getProperty("maven.compiler.source");
    if (compilerSource != null && !compilerSource.isEmpty()) {
      versionStr = compilerSource;
    } else {
      // Fallback to maven.compiler.target
      String compilerTarget = project.getProperties().getProperty("maven.compiler.target");
      if (compilerTarget != null && !compilerTarget.isEmpty()) {
        versionStr = compilerTarget;
      }
    }

    return parseVersionString(versionStr);
  }

  /**
   * Gets the actual JVM version number that is running the Maven build. Priority: 1. From Toolchain
   * (if configured) 2. From system property (java.version)
   *
   * @param toolchainManager The toolchain manager
   * @param session The Maven session
   * @return The JVM major version number, or -1 if version cannot be determined
   */
  public static int getJvmVersionNumber(
      final ToolchainManager toolchainManager, final MavenSession session) {
    String versionStr = null;

    // Method 1: Try to get from Toolchain
    final Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
    if (tc != null) {
      // Try to get version from toolchain using reflection
      // Toolchain API may have getVersion() method or version in model
      try {
        // First try: direct getVersion() method
        try {
          Object version = tc.getClass().getMethod("getVersion").invoke(tc);
          if (version != null) {
            versionStr = version.toString();
          }
        } catch (NoSuchMethodException e) {
          // Try to get from toolchain model
          try {
            Object model = tc.getClass().getMethod("getModel").invoke(tc);
            if (model != null) {
              try {
                Object version = model.getClass().getMethod("getVersion").invoke(model);
                if (version != null) {
                  versionStr = version.toString();
                }
              } catch (Exception e2) {
                // Version method not available in model
              }
            }
          } catch (Exception e2) {
            // Model method not available
          }
        }
      } catch (Exception e) {
        // Reflection failed, continue to fallback
      }
    }

    // Method 2: Fallback to system property (the JVM running Maven)
    if (versionStr == null || versionStr.isEmpty()) {
      versionStr = System.getProperty("java.version");
    }

    return parseVersionString(versionStr);
  }

  /**
   * Gets the Java major version number. Parses the version string and returns the major version
   * number (e.g., "1.8" -> 8, "9" -> 9, "11" -> 11).
   *
   * @param toolchainManager The toolchain manager
   * @param session The Maven session
   * @param project The Maven project
   * @return The Java major version number, or -1 if version cannot be determined
   * @deprecated Use {@link #getJavaSourceVersionNumber(MavenProject)} or {@link
   *     #getJvmVersionNumber(ToolchainManager, MavenSession)} instead
   */
  @Deprecated
  public static int getJavaVersionNumber(
      final ToolchainManager toolchainManager,
      final MavenSession session,
      final MavenProject project) {
    String versionStr = getJavaVersion(toolchainManager, session, project);
    return parseVersionString(versionStr);
  }

  /**
   * Gets the Checker Framework version from project dependencies. Looks for "checker" or
   * "checker-qual" artifacts in the project dependencies.
   *
   * @param project The Maven project*
   * @return The version string if found, or null otherwise
   */
  public static String getCheckerFrameworkVersionFromDependencies(final MavenProject project) {
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
   * Resolves an artifact JAR file using multiple methods in order of priority: 1. Find from project
   * dependencies 2. Check local repository 3. Resolve and download from remote repositories
   *
   * @param groupId The artifact group ID
   * @param artifactId The artifact ID
   * @param version The artifact version
   * @param project The Maven project
   * @param repositorySystem The repository system for resolving artifacts
   * @param localRepository The local repository
   * @param session The Maven session for accessing remote repositories
   * @param log The logger
   * @return The JAR file, or null if not found
   */
  private static File resolveArtifact(
      final String groupId,
      final String artifactId,
      final String version,
      final MavenProject project,
      final RepositorySystem repositorySystem,
      final ArtifactRepository localRepository,
      final MavenSession session,
      final Log log) {
    // Step 1: Find from project dependencies
    Optional<Artifact> artifact =
        project.getArtifacts().stream()
            .filter(a -> groupId.equals(a.getGroupId()) && artifactId.equals(a.getArtifactId()))
            .findFirst();
    if (artifact.isPresent()) {
      File artifactFile = artifact.get().getFile();
      if (artifactFile != null && artifactFile.exists()) {
        return artifactFile;
      }
    }

    // Step 2: Check local repository
    try {
      Artifact resolvedArtifact =
          repositorySystem.createArtifact(groupId, artifactId, version, null, "jar");

      File artifactFile =
          new File(localRepository.getBasedir(), localRepository.pathOf(resolvedArtifact));

      if (artifactFile.exists()) {
        return artifactFile;
      }
    } catch (Exception e) {
      log.warn("Error checking local repository for " + artifactId + ": " + e.getMessage());
    }

    // Step 3: Resolve and download from remote repositories
    try {

      Artifact artifactToResolve =
          repositorySystem.createArtifact(groupId, artifactId, version, null, "jar");

      // Get remote repositories from the project and session
      List<ArtifactRepository> remoteRepositories = session.getRequest().getRemoteRepositories();
      if (remoteRepositories == null || remoteRepositories.isEmpty()) {
        // Fallback to project repositories if session repositories are empty
        remoteRepositories = project.getRemoteArtifactRepositories();
      }
      if (remoteRepositories == null) {
        remoteRepositories = Collections.emptyList();
      }

      // Create resolution request
      ArtifactResolutionRequest request = new ArtifactResolutionRequest();
      request.setArtifact(artifactToResolve);
      request.setLocalRepository(localRepository);
      request.setRemoteRepositories(remoteRepositories);

      // Resolve the artifact (this will download it if needed)
      ArtifactResolutionResult result = repositorySystem.resolve(request);
      if (!result.isSuccess()) {
        return null;
      }

      if (result.getArtifacts() != null && !result.getArtifacts().isEmpty()) {
        Artifact resolvedArtifact = result.getArtifacts().iterator().next();
        File artifactFile = resolvedArtifact.getFile();
        if (artifactFile != null && artifactFile.exists()) {
          log.info(
              "Successfully downloaded " + artifactId + " to: " + artifactFile.getAbsolutePath());
          return artifactFile;
        }
      }
    } catch (Exception e) {
      log.warn("Error downloading " + artifactId + " from remote repositories: " + e.getMessage());
    }

    return null;
  }

  /**
   * Gets the Checker Framework JAR file for the given artifact ID (e.g., "checker" or
   * "checker-qual"). First tries to find it from project dependencies, then from local repository,
   * and finally downloads it from remote repositories if needed.
   *
   * @param artifactId The artifact ID (e.g., "checker" or "checker-qual")
   * @param project The Maven project
   * @param repositorySystem The repository system for resolving artifacts
   * @param localRepository The local repository
   * @param session The Maven session for accessing remote repositories
   * @param checkerFrameworkVersion The Checker Framework version
   * @param log The logger
   * @return The JAR file, or null if not found
   */
  public static File getFrameworkJar(
      final String artifactId,
      final MavenProject project,
      final RepositorySystem repositorySystem,
      final ArtifactRepository localRepository,
      final MavenSession session,
      final String checkerFrameworkVersion,
      final Log log) {
    return resolveArtifact(
        CHECKER_FRAMEWORK_GROUPD_ID,
        artifactId,
        checkerFrameworkVersion,
        project,
        repositorySystem,
        localRepository,
        session,
        log);
  }

  /**
   * Gets the Error Prone javac JAR file. First tries to find it from project dependencies, then
   * from local repository, and finally downloads it from remote repositories if needed.
   *
   * @param project The Maven project
   * @param repositorySystem The repository system for resolving artifacts
   * @param localRepository The local repository
   * @param session The Maven session for accessing remote repositories
   * @param log The logger
   * @return The JAR file, or null if not found
   */
  public static File getErrorProneJavacJar(
      final MavenProject project,
      final RepositorySystem repositorySystem,
      final ArtifactRepository localRepository,
      final MavenSession session,
      final Log log) {
    final String errorProneJavacGroupId = "com.google.errorprone";
    final String errorProneJavacArtifactId = "javac";
    final String errorProneJavacVersion = "9+181-r4173-1";

    return resolveArtifact(
        errorProneJavacGroupId,
        errorProneJavacArtifactId,
        errorProneJavacVersion,
        project,
        repositorySystem,
        localRepository,
        session,
        log);
  }

  /**
   * Gets the annotated JDK JAR file (jdk8) for Checker Framework. This is required for certain
   * Checker Framework versions when running on Java 8. First tries to find it from project
   * dependencies, then from local repository, and finally downloads it from remote repositories if
   * needed.
   *
   * @param project The Maven project
   * @param repositorySystem The repository system for resolving artifacts
   * @param localRepository The local repository
   * @param session The Maven session for accessing remote repositories
   * @param checkerFrameworkVersion The Checker Framework version
   * @param log The logger
   * @return The JAR file, or null if not found
   */
  public static File getAnnotatedJdkJar(
      final MavenProject project,
      final RepositorySystem repositorySystem,
      final ArtifactRepository localRepository,
      final MavenSession session,
      final String checkerFrameworkVersion,
      final Log log) {
    return getFrameworkJar(
        "jdk8", project, repositorySystem, localRepository, session, checkerFrameworkVersion, log);
  }
}
